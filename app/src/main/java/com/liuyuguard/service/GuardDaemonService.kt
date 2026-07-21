package com.liuyuguard.service

import android.app.Notification
import android.content.Context
import android.content.Intent
import com.liuyuguard.R
import com.liuyuguard.util.Constants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 双向守护服务
 *
 * 继承BaseGuardDaemonService，实现守护逻辑骨架。
 * 负责持续检测主服务（MainTrafficService）存活状态，
 * 当主服务异常退出时自动重启，并通过Root环境提升OOM进程优先级防止系统回收。
 *
 * 职责边界：
 * - 双向互唤醒：主服务 <-> 守护服务互相拉起
 * - 检测主服务存活状态（通过ActivityManager）
 * - 主服务异常时自动重启
 * - Root环境下自动提升OOM进程优先级
 *
 * 禁止：
 * - 处理任何业务逻辑（流量采样、断网等）
 * - 直接操作iptables/Shell
 */
class GuardDaemonService : BaseGuardDaemonService() {

    companion object {
        /** 主流量服务类名 */
        private const val MAIN_SERVICE_CLASS = "com.liuyuguard.service.MainTrafficService"

        /** 连续检测失败最大次数，超过后触发重启 */
        private const val MAX_FAIL_COUNT = 3

        /** 连续成功次数，重置失败计数器 */
        private const val RESET_THRESHOLD = 2
    }

    // ========================================================================
    // 心跳状态
    // ========================================================================

    /** 心跳协程作用域 */
    private val heartbeatScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** 心跳协程Job */
    private var heartbeatJob: kotlinx.coroutines.Job? = null

    /** 连续检测失败计数 */
    private var failCount = 0

    /** 连续检测成功计数 */
    private var successCount = 0

    // ========================================================================
    // BaseGuardDaemonService 抽象方法实现
    // ========================================================================

    /**
     * 检测主服务是否存活
     * 通过ActivityManager查询运行中的服务列表判断
     */
    override fun isServiceAlive(serviceName: String): Boolean {
        return try {
            val manager = getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
            manager?.getRunningServices(Int.MAX_VALUE)
                ?.any { it.service.className == serviceName }
                ?: false
        } catch (_: Exception) {
            false
        }
    }

    /**
     * 重启指定服务
     * 通过startForegroundService拉起目标服务
     */
    override fun restartService(serviceName: String) {
        try {
            val intent = Intent().apply {
                component = android.content.ComponentName(packageName, serviceName)
                // 传递守护服务PID，避免重复检测冲突
                putExtra("guard_pid", android.os.Process.myPid())
            }
            startForegroundService(intent)
        } catch (_: Exception) {
            // 重启失败，等待下一心跳周期重试
        }
    }

    /**
     * 获取服务PID
     * 通过ActivityManager查询服务进程ID
     */
    override fun getServicePid(serviceName: String): Int {
        return try {
            val manager = getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
            manager?.getRunningServices(Int.MAX_VALUE)
                ?.firstOrNull { it.service.className == serviceName }
                ?.pid ?: -1
        } catch (_: Exception) {
            -1
        }
    }

    /**
     * 构建守护服务前台通知
     */
    override fun buildGuardNotification(): Notification {
        return Notification.Builder(this, Constants.CHANNEL_GUARD)
            .setContentTitle("流御守护")
            .setContentText("守护运行中")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .build()
    }

    /**
     * 提升服务OOM优先级（Root模式下）
     * 通过RootBridge写入/proc/<pid>/oom_adj实现
     */
    override fun boostOOMPriority(pid: Int) {
        try {
            // TODO: 通过RootShellManager执行命令修改OOM优先级
            // val cmd = "echo ${Constants.OOM_ADJ_FOREGROUND_SERVICE} > /proc/$pid/oom_adj"
            // RootShellManager.executeCommand(cmd)
        } catch (_: Exception) {
            // 提升优先级失败，不影响守护循环
        }
    }

    /**
     * 启动心跳检测循环
     * 以固定间隔检测主服务状态，异常时自动重启
     */
    override fun startHeartbeat() {
        heartbeatJob = heartbeatScope.launch {
            while (isActive) {
                try {
                    checkMainService()
                } catch (_: kotlinx.coroutines.CancellationException) {
                    break
                } catch (_: Exception) {
                    // 心跳异常不中断循环
                }
                delay(heartbeatIntervalMs)
            }
        }
    }

    /**
     * 停止心跳检测循环
     */
    override fun stopHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = null
    }

    // ========================================================================
    // 守护核心逻辑
    // ========================================================================

    /**
     * 检测主服务状态
     * 连续失败超过MAX_FAIL_COUNT次则判定服务死亡并触发重启
     */
    private fun checkMainService() {
        val alive = isServiceAlive(MAIN_SERVICE_CLASS)

        if (alive) {
            successCount++
            if (successCount >= RESET_THRESHOLD) {
                // 连续成功，重置失败计数器
                failCount = 0
                successCount = 0
            }

            // Root模式下，尝试提升主服务OOM优先级
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                val pid = getServicePid(MAIN_SERVICE_CLASS)
                if (pid > 0) {
                    boostOOMPriority(pid)
                }
            }
        } else {
            successCount = 0
            failCount++

            if (failCount >= MAX_FAIL_COUNT) {
                // 连续检测失败，判定服务已死亡，执行重启
                failCount = 0
                restartService(MAIN_SERVICE_CLASS)
            }
        }
    }

    // ========================================================================
    // 生命周期扩展
    // ========================================================================

    override fun onDestroy() {
        stopHeartbeat()
        heartbeatScope.cancel()
        super.onDestroy()
    }
}
