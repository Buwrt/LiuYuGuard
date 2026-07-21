package com.liuyuguard.manager.modules

import android.app.ActivityManager
import android.app.usage.UsageStatsManager
import android.os.Build
import com.liuyuguard.base.LiuYuApplication
import com.liuyuguard.manager.RootShellManagerImpl
import com.liuyuguard.model.CommandResult
import com.liuyuguard.model.ProcessState
import kotlinx.coroutines.delay

/**
 * 进程管控模块实现
 *
 * 职责：
 * - 判断指定UID是否有前台进程（通过ActivityManager）
 * - 获取指定UID下所有进程的状态
 * - 冻结/解冻指定UID的进程（通过Root执行SIGSTOP/SIGCONT信号）
 * - 拦截/恢复关联启动
 * - 获取进程CPU占用率
 *
 * 前台判定策略：
 * 1. 通过ActivityManager.getRunningAppProcesses获取当前运行的进程列表
 * 2. 匹配RunningAppProcessInfo.importance判断前台状态
 * 3. 辅助通过UsageStatsManager获取最近使用的应用
 *
 * 冻结/解冻策略：
 * - 冻结：通过Root执行 kill -STOP 向目标UID的所有进程发送SIGSTOP信号
 * - 解冻：通过Root执行 kill -CONT 向目标UID的所有进程发送SIGCONT信号
 */
class ProcessControllerImpl : IProcessController {

    /** 获取ActivityManager实例 */
    private val activityManager: ActivityManager
        get() = LiuYuApplication.instance.getSystemService(
            android.content.Context.ACTIVITY_SERVICE
        ) as ActivityManager

    // ========================================================================
    // IProcessController 接口实现
    // ========================================================================

    /**
     * 判断指定UID是否有前台进程
     *
     * 通过ActivityManager.getRunningAppProcesses获取当前运行进程列表，
     * 遍历查找与目标UID匹配且importance <= IMPORTANCE_FOREGROUND_SERVICE的进程。
     * Android 6.0+ 同时使用UsageStatsManager辅助判断。
     *
     * @param uid 目标UID
     * @return true表示该UID有前台进程
     */
    override suspend fun isUidForeground(uid: Int): Boolean {
        return try {
            // 方式1：通过RunningAppProcesses判断
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                try {
                    val processes = activityManager.runningAppProcesses ?: return false
                    for (procInfo in processes) {
                        if (procInfo.uid == uid) {
                            // importance值越小越前台
                            // IMPORTANCE_FOREGROUND = 100, IMPORTANCE_VISIBLE = 200
                            // IMPORTANCE_FOREGROUND_SERVICE = 300
                            if (procInfo.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE) {
                                return true
                            }
                        }
                    }
                } catch (@Suppress("TooGenericExceptionCaught") e: SecurityException) {
                    // 某些ROM限制了getRunningAppProcesses的权限
                }
            }

            // 方式2：通过UsageStatsManager辅助判断（Android 6.0+）
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                try {
                    val usageStatsManager = LiuYuApplication.instance.getSystemService(
                        android.content.Context.USAGE_STATS_SERVICE
                    ) as UsageStatsManager

                    val endTime = System.currentTimeMillis()
                    val startTime = endTime - 30_000L // 查看最近30秒

                    val stats = usageStatsManager.queryUsageStats(
                        UsageStatsManager.INTERVAL_DAILY,
                        startTime,
                        endTime
                    )

                    // 获取目标UID的包名
                    val packageName = RootShellManagerImpl.uidManager.getPackageForUid(uid)
                    if (packageName != null) {
                        for (stat in stats) {
                            if (stat.packageName == packageName && stat.lastForegroundServiceForegroundTime > startTime) {
                                return true
                            }
                        }
                    }
                } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                    // UsageStatsManager查询失败，忽略
                }
            }

            false
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            false
        }
    }

    /**
     * 获取指定UID下所有进程的状态
     *
     * 流程：
     * 1. 通过uidManager.getPidsForUid获取该UID的所有PID
     * 2. 通过ActivityManager判断哪些在前台
     * 3. 读取/proc/[pid]/stat获取进程名等信息
     * 4. 构建ProcessState列表
     *
     * @param uid 目标UID
     * @return 该UID下所有进程的状态列表
     */
    override suspend fun getProcessStates(uid: Int): List<ProcessState> {
        return try {
            // 获取该UID关联的所有PID
            val pids = RootShellManagerImpl.uidManager.getPidsForUid(uid)
            if (pids.isEmpty()) return emptyList()

            // 获取包名
            val packageName = RootShellManagerImpl.uidManager.getPackageForUid(uid) ?: "unknown"

            // 获取前台进程PID列表
            val foregroundPids = mutableSetOf<Int>()
            try {
                val processes = activityManager.runningAppProcesses
                if (processes != null) {
                    for (procInfo in processes) {
                        if (procInfo.uid == uid &&
                            procInfo.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE
                        ) {
                            foregroundPids.add(procInfo.pid)
                        }
                    }
                }
            } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                // 忽略权限异常
            }

            // 为每个PID构建ProcessState
            pids.mapNotNull { pid ->
                try {
                    val isForeground = pid in foregroundPids
                    ProcessState(
                        pid = pid,
                        uid = uid,
                        packageName = packageName,
                        isForeground = isForeground
                    )
                } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                    null
                }
            }
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            emptyList()
        }
    }

    /**
     * 冻结指定UID的后台进程
     *
     * 通过Root执行 kill -STOP 向目标UID的所有进程发送SIGSTOP信号，
     * 使进程进入暂停状态（T状态），停止CPU时间片分配和所有网络IO。
     *
     * 注意：SIGSTOP会冻结该UID下的所有进程（包括可能的子进程），
     * 调用方应先通过isUidForeground判断，避免冻结前台进程。
     *
     * @param uid 目标UID
     * @return CommandResult 操作结果
     */
    override suspend fun freezeUid(uid: Int): CommandResult {
        // 获取该UID的所有PID
        val pids = RootShellManagerImpl.uidManager.getPidsForUid(uid)
        if (pids.isEmpty()) {
            return CommandResult.Error("未找到UID=$uid 的任何进程")
        }

        // 构建kill -STOP命令（一次性发送给所有PID）
        val pidList = pids.joinToString(" ")
        val command = "kill -STOP $pidList"

        val result = RootShellManagerImpl.executeShell(command)

        return if (result.success) {
            CommandResult.Success
        } else {
            CommandResult.Error("冻结UID=$uid 失败: ${result.stderr}")
        }
    }

    /**
     * 解冻指定UID的进程
     *
     * 通过Root执行 kill -CONT 向目标UID的所有进程发送SIGCONT信号，
     * 恢复进程的正常执行。
     *
     * @param uid 目标UID
     * @return CommandResult 操作结果
     */
    override suspend fun unfreezeUid(uid: Int): CommandResult {
        // 获取该UID的所有PID
        val pids = RootShellManagerImpl.uidManager.getPidsForUid(uid)
        if (pids.isEmpty()) {
            return CommandResult.Error("未找到UID=$uid 的任何进程")
        }

        // 构建kill -CONT命令
        val pidList = pids.joinToString(" ")
        val command = "kill -CONT $pidList"

        val result = RootShellManagerImpl.executeShell(command)

        return if (result.success) {
            CommandResult.Success
        } else {
            CommandResult.Error("解冻UID=$uid 失败: ${result.stderr}")
        }
    }

    /**
     * 拦截指定UID的关联启动
     *
     * 通过AppOpsManager的权限控制来阻止关联启动。
     * 使用Root修改 /data/system/appops.xml 中对应UID的OP_START_FOREGROUND权限。
     *
     * 备选方案：通过am命令设置appops权限限制
     *
     * @param uid 源UID（发起关联启动的应用）
     * @param targetUid 目标UID（被关联启动的应用）
     * @return CommandResult 操作结果
     */
    override suspend fun blockStartByUid(uid: Int, targetUid: Int): CommandResult {
        // 通过appops set命令限制目标UID的后台启动权限
        val targetPackage = RootShellManagerImpl.uidManager.getPackageForUid(targetUid) ?: return CommandResult.Error("无法找到UID=$targetUid 的包名")

        // 使用appops限制 RUN_IN_BACKGROUND 和 START_FOREGROUND 操作
        val command = "appops set $targetPackage RUN_IN_BACKGROUND ignore"
        val result = RootShellManagerImpl.executeShell(command)

        return if (result.success) {
            CommandResult.Success
        } else {
            CommandResult.Error("拦截关联启动失败: ${result.stderr}")
        }
    }

    /**
     * 恢复指定UID的关联启动
     *
     * 重置appops权限，允许正常关联启动
     *
     * @param uid 源UID
     * @param targetUid 目标UID
     * @return CommandResult 操作结果
     */
    override suspend fun unblockStartByUid(uid: Int, targetUid: Int): CommandResult {
        val targetPackage = RootShellManagerImpl.uidManager.getPackageForUid(targetUid) ?: return CommandResult.Error("无法找到UID=$targetUid 的包名")

        // 恢复RUN_IN_BACKGROUND权限为默认模式
        val command = "appops set $targetPackage RUN_IN_BACKGROUND allow"
        val result = RootShellManagerImpl.executeShell(command)

        return if (result.success) {
            CommandResult.Success
        } else {
            CommandResult.Error("恢复关联启动失败: ${result.stderr}")
        }
    }

    /**
     * 获取指定PID的CPU占用率
     *
     * 通过读取 /proc/[pid]/stat 两次（间隔100ms），
     * 计算CPU时间差占总CPU时间差的比例。
     *
     * /proc/[pid]/stat 字段说明（空格分隔，第2字段是进程名括号包裹）：
     * - [14] utime: 用户态CPU时间（jiffies）
     * - [15] stime: 内核态CPU时间（jiffies）
     *
     * CPU使用率 = (utime2 + stime2 - utime1 - stime1) / (totalCpu2 - totalCpu1) * 100%
     *
     * @param pid 进程ID
     * @return CPU占用率（0.0 ~ 100.0），获取失败返回0f
     */
    override suspend fun getCpuUsage(pid: Int): Float {
        return try {
            // 第一次读取
            val stat1 = RootShellManagerImpl.kernelReader.readProcessStat(pid) ?: return 0f
            val fields1 = parseProcStatFields(stat1) ?: return 0f
            val totalCpu1 = readTotalCpu()

            // 等待100ms
            delay(100L)

            // 第二次读取
            val stat2 = RootShellManagerImpl.kernelReader.readProcessStat(pid) ?: return 0f
            val fields2 = parseProcStatFields(stat2) ?: return 0f
            val totalCpu2 = readTotalCpu()

            // 计算进程CPU时间差
            val processDelta = (fields2.utime + fields2.stime) - (fields1.utime + fields1.stime)
            // 计算总CPU时间差
            val totalDelta = totalCpu2 - totalCpu1

            if (totalDelta <= 0 || processDelta <= 0) return 0f

            // 计算百分比
            val usage = (processDelta.toFloat() / totalDelta.toFloat()) * 100f
            usage.coerceIn(0f, 100f)
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            0f
        }
    }

    // ========================================================================
    // 内部方法
    // ========================================================================

    /**
     * 解析 /proc/[pid]/stat 的关键字段
     *
     * 注意：进程名可能包含空格和括号，因此需要从最后一个 ')' 开始解析
     *
     * @param statLine /proc/[pid]/stat 的一行内容
     * @return 解析出的字段，失败返回null
     */
    private fun parseProcStatFields(statLine: String): ProcStatFields? {
        return try {
            // 找到最后一个 ')' 来定位进程名结束位置
            val lastParen = statLine.lastIndexOf(')')
            if (lastParen < 0) return null

            // 从 ')' 之后的部分提取字段
            val afterName = statLine.substring(lastParen + 1).trim().split("\\s+".toRegex())

            // 字段索引（从 ')' 之后重新编号）：
            // [0]=state, [1]=ppid, ..., [12]=utime, [13]=stime
            if (afterName.size < 15) return null

            ProcStatFields(
                utime = afterName[12].toLongOrNull() ?: 0L,
                stime = afterName[13].toLongOrNull() ?: 0L
            )
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            null
        }
    }

    /**
     * 读取系统总CPU时间
     *
     * 从 /proc/stat 第一行解析：
     * cpu  user nice system idle iowait irq softirq steal guest guest_nice
     *
     * @return 所有CPU时间之和（jiffies）
     */
    private suspend fun readTotalCpu(): Long {
        val result = RootShellManagerImpl.executeShell("head -1 /proc/stat")
        if (!result.success) return 0L

        val parts = result.stdout.trim().split("\\s+".toRegex())
        if (parts.size < 8) return 0L

        // 累加 user + nice + system + idle + iowait + irq + softirq + steal
        var total = 0L
        for (i in 1 until minOf(parts.size, 9)) {
            total += parts[i].toLongOrNull() ?: 0L
        }
        return total
    }

    /**
     * /proc/[pid]/stat 解析结果
     */
    private data class ProcStatFields(
        val utime: Long,  // 用户态CPU时间
        val stime: Long   // 内核态CPU时间
    )
}