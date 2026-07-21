package com.liuyuguard.executor

import android.app.ActivityManager
import android.content.Context
import android.os.Process
import com.liuyuguard.model.ProcessState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * 进程状态监听器实现
 *
 * 架构位置：层级4（Linux内核底层执行层）- 进程状态监控
 * 职责：
 * - 遍历 /proc/[pid]/status 获取UID关联的进程信息
 * - 通过 ActivityManager 判断UID的前台/后台状态
 * - 通过两次读取 /proc/[pid]/stat 计算进程CPU占用率
 *
 * 数据源：
 * - /proc/[pid]/status: 进程状态文件，包含Uid、Name、Pid等信息
 * - /proc/[pid]/stat: 进程CPU时间统计（utime、stime），用于计算CPU占用率
 * - ActivityManager: Android系统API，判断应用前台/后台状态
 *
 * 注意：
 * - /proc文件系统的读取需要Root权限（通过BaseKernelExecutor执行）
 * - ActivityManager的前台判断不需要Root权限（直接使用Context）
 * - CPU使用率计算采用两次采样间隔法，间隔100ms
 *
 * @param context Android上下文，用于获取ActivityManager判断前台状态
 * @param kernelExecutor 内核执行器实例，用于执行Root Shell命令读取/proc文件
 */
class ProcessMonitorImpl(
    private val context: Context,
    private val kernelExecutor: BaseKernelExecutor
) : ProcessMonitor() {

    companion object {
        /** /proc目录路径 */
        private const val PATH_PROC = "/proc"

        /** CPU使用率采样间隔（毫秒） */
        private const val CPU_SAMPLE_INTERVAL_MS = 100L

        /** /proc/stat中CPU行中jiffies字段的总数量（从cpu到cpuNice的偏移量） */
        private const val PROC_STAT_UTIME_INDEX = 13   // 用户态CPU时间（jiffies）
        private const val PROC_STAT_STIME_INDEX = 14   // 内核态CPU时间（jiffies）
        private const val PROC_STAT_STARTTIME_INDEX = 21 // 进程启动时间（jiffies）
    }

    // ========================================================================
    // 进程状态查询
    // ========================================================================

    /**
     * 获取指定UID下所有进程的状态列表
     *
     * 实现步骤：
     * 1. 列出 /proc/ 下所有数字目录（即所有PID）
     * 2. 读取每个PID的 /proc/[pid]/status 文件
     * 3. 解析 Uid: 行，匹配目标UID的进程
     * 4. 构建ProcessState列表，包含PID、UID、包名、前台状态等信息
     *
     * /proc/[pid]/status 中的关键字段：
     * - Name: 进程名
     * - Pid: 进程ID
     * - Uid: 真实UID / 有效UID / 保存UID / 文件系统UID
     *   格式：Uid:\t10086\t10086\t10086\t10086
     *
     * @param uid 目标应用UID
     * @return 该UID下的所有进程状态列表
     */
    override suspend fun getProcessStatesForUid(uid: Int): List<ProcessState> =
        withContext(Dispatchers.IO) {
            val pids = getPidsForUidInternal(uid)
            val isForeground = isUidForegroundInternal(uid)

            pids.mapNotNull { pid ->
                val name = getProcessNameByPid(pid)
                // 构建进程状态数据
                ProcessState(
                    pid = pid,
                    uid = uid,
                    packageName = name,
                    isForeground = isForeground,
                    cpuUsage = 0f // CPU使用率需要单独查询，避免在此处阻塞
                )
            }
        }

    /**
     * 判断指定UID是否有前台进程
     *
     * 通过Android ActivityManager API判断应用是否处于前台。
     * 使用 UsageStatsManager 或 ActivityManager.getRunningAppProcesses 的辅助方法。
     *
     * 优先级策略：
     * - 如果能获取到 RunningAppProcesses，检查任何进程是否在前台 importance
     * - 如果UID有重要性级别 <= IMPORTANCE_FOREGROUND，则判定为前台
     *
     * @param uid 目标应用UID
     * @return true表示该UID有前台进程
     */
    override suspend fun isUidForeground(uid: Int): Boolean = withContext(Dispatchers.IO) {
        isUidForegroundInternal(uid)
    }

    /**
     * 获取指定PID的CPU占用率
     *
     * 采用两次采样间隔法计算CPU使用率：
     * 1. 第一次读取 /proc/[pid]/stat 获取进程CPU时间（utime + stime）
     * 2. 同时读取 /proc/stat 获取系统总CPU时间
     * 3. 等待100ms
     * 4. 第二次读取同样的数据
     * 5. 计算公式：CPU% = ((进程CPU增量) / (系统CPU总量增量)) * 100%
     *
     * /proc/[pid]/stat 格式（关键字段）：
     *   pid (comm) state ppid pgrp ... utime(13) stime(14) ... starttime(21)
     *   时间单位：jiffies（通常1 jiffy = 10ms，取决于HZ配置）
     *
     * @param pid 目标进程PID
     * @return CPU占用率百分比（0.0 ~ 100.0+），读取失败返回0.0
     */
    override suspend fun getCpuUsage(pid: Int): Float = withContext(Dispatchers.IO) {
        try {
            // 第一次采样
            val (procCpuTime1, totalCpuTime1) = readCpuTimes(pid)
            if (procCpuTime1 < 0 || totalCpuTime1 <= 0) {
                return@withContext 0f
            }

            // 等待采样间隔
            delay(CPU_SAMPLE_INTERVAL_MS)

            // 第二次采样
            val (procCpuTime2, totalCpuTime2) = readCpuTimes(pid)
            if (procCpuTime2 < 0 || totalCpuTime2 <= 0) {
                return@withContext 0f
            }

            // 计算增量
            val procDelta = procCpuTime2 - procCpuTime1
            val totalDelta = totalCpuTime2 - totalCpuTime1

            if (totalDelta <= 0) {
                return@withContext 0f
            }

            // 计算CPU使用率百分比
            val cpuUsage = (procDelta.toDouble() / totalDelta.toDouble()) * 100.0

            cpuUsage.toFloat().coerceIn(0f, 100f)
        } catch (e: Exception) {
            // 采样过程中出现异常，返回0
            0f
        }
    }

    /**
     * 获取指定UID关联的所有PID列表（主进程+后台子进程）
     *
     * 遍历 /proc/ 下所有数字目录，读取 /proc/[pid]/status 文件，
     * 解析 Uid: 行判断该进程是否属于目标UID。
     *
     * @param uid 目标应用UID
     * @return 该UID下的所有PID列表
     */
    override suspend fun getPidsForUid(uid: Int): List<Int> = withContext(Dispatchers.IO) {
        getPidsForUidInternal(uid)
    }

    // ========================================================================
    // 内部辅助方法
    // ========================================================================

    /**
     * 内部实现：获取指定UID关联的所有PID
     *
     * @param uid 目标UID
     * @return PID列表
     */
    private suspend fun getPidsForUidInternal(uid: Int): List<Int> {
        // 列出 /proc/ 下所有数字目录名（即PID）
        val result = kernelExecutor.executeCommand("ls $PATH_PROC")
        if (!result.success) {
            return emptyList()
        }

        val pidList = mutableListOf<Int>()

        for (line in result.stdout.lines()) {
            val trimmed = line.trim()
            // 检查是否为纯数字（PID目录）
            if (trimmed.matches(Regex("\\d+"))) {
                val pid = trimmed.toInt()

                // 读取 /proc/[pid]/status 中的Uid行
                val uidResult = kernelExecutor.executeCommand(
                    "grep '^Uid:' $PATH_PROC/$pid/status"
                )

                if (uidResult.success) {
                    // Uid行格式：Uid:\t10086\t10086\t10086\t10086
                    // 取第一个数字（真实UID）
                    val uidLine = uidResult.stdout.trim()
                    val uidValue = parseUidLine(uidLine)

                    if (uidValue == uid) {
                        pidList.add(pid)
                    }
                }
            }
        }

        return pidList
    }

    /**
     * 解析Uid行中的真实UID值
     *
     * @param uidLine Uid行的内容，格式：Uid:\t10086\t10086\t10086\t10086
     * @return 真实UID值，解析失败返回-1
     */
    private fun parseUidLine(uidLine: String): Int {
        // 提取 "Uid:" 后面的部分
        val parts = uidLine.substringAfter("Uid:").trim().split(Regex("\\s+"))
        return if (parts.isNotEmpty()) {
            parts[0].toIntOrNull() ?: -1
        } else {
            -1
        }
    }

    /**
     * 根据PID获取进程名
     *
     * 从 /proc/[pid]/status 中的 Name: 行读取进程名称。
     *
     * @param pid 进程PID
     * @return 进程名称，读取失败返回空字符串
     */
    private suspend fun getProcessNameByPid(pid: Int): String {
        val result = kernelExecutor.executeCommand(
            "grep '^Name:' $PATH_PROC/$pid/status"
        )
        if (!result.success) {
            return ""
        }
        // Name行格式：Name:\tcom.example.app
        return result.stdout.trim().substringAfter("Name:").trim()
    }

    /**
     * 内部实现：判断UID是否有前台进程
     *
     * 通过 ActivityManager 获取正在运行的进程列表，
     * 检查是否有进程的UID匹配目标UID且importance级别为前台。
     *
     * @param uid 目标UID
     * @return true表示有前台进程
     */
    private fun isUidForegroundInternal(uid: Int): Boolean {
        return try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                ?: return false

            val processes = am.runningAppProcesses ?: return false

            // 检查是否有任何匹配UID的进程处于前台
            processes.any { processInfo ->
                // Process.uid() 返回的是Linux UID，直接与目标UID比较
                // 注意：某些Android版本中 uid 字段可能不可用，通过 pkgList 辅助判断
                val importance = processInfo.importance
                val isFg = importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND

                // 通过进程的uid字段判断（Android API >= 29）
                // 低版本通过包名关联PackageManager获取UID
                if (isFg && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                    try {
                        val processUid = Process.getUidForName(processInfo.processName)
                        processUid == uid
                    } catch (e: Exception) {
                        false
                    }
                } else {
                    false
                }
            }
        } catch (e: Exception) {
            // ActivityManager不可用或权限不足
            false
        }
    }

    /**
     * 读取进程和系统的CPU时间
     *
     * 同时读取：
     * - /proc/[pid]/stat: 获取进程的utime和stime
     * - /proc/stat: 获取系统总的CPU时间（所有CPU核心之和）
     *
     * /proc/[pid]/stat 字段说明（从1开始计数）：
     *   [1]pid [2]comm [3]state ... [14]utime [15]stime ... [22]starttime
     *
     * /proc/stat CPU行格式：
     *   cpu  user nice system idle iowait irq softirq steal guest guest_nice
     *   所有字段之和为系统总CPU时间
     *
     * @param pid 目标进程PID
     * @return Pair(进程CPU时间, 系统总CPU时间)，单位为jiffies
     */
    private suspend fun readCpuTimes(pid: Int): Pair<Long, Long> {
        // 读取进程CPU时间
        val procStatResult = kernelExecutor.executeCommand("cat $PATH_PROC/$pid/stat")
        if (!procStatResult.success) {
            return Pair(-1L, -1L)
        }

        val procFields = procStatResult.stdout.trim().split(Regex("\\s+"))
        if (procFields.size < 22) {
            return Pair(-1L, -1L)
        }

        // 进程CPU时间 = utime + stime
        val utime = procFields[PROC_STAT_UTIME_INDEX].toLongOrNull() ?: 0L
        val stime = procFields[PROC_STAT_STIME_INDEX].toLongOrNull() ?: 0L
        val procCpuTime = utime + stime

        // 读取系统总CPU时间
        val systemStatResult = kernelExecutor.executeCommand("grep '^cpu ' /proc/stat")
        if (!systemStatResult.success) {
            return Pair(procCpuTime, -1L)
        }

        // cpu行的字段：cpu user nice system idle iowait irq softirq steal guest guest_nice
        val cpuFields = systemStatResult.stdout.trim().split(Regex("\\s+"))
        if (cpuFields.size < 8) {
            return Pair(procCpuTime, -1L)
        }

        // 系统总CPU时间 = 所有字段之和（user+nice+system+idle+iowait+irq+softirq+steal）
        var totalCpuTime = 0L
        for (i in 1 until cpuFields.size) {
            totalCpuTime += cpuFields[i].toLongOrNull() ?: 0L
        }

        return Pair(procCpuTime, totalCpuTime)
    }
}
