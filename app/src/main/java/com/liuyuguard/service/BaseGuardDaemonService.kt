package com.liuyuguard.service

import android.app.Notification
import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.liuyuguard.model.IServiceGuardian
import com.liuyuguard.util.Constants

/**
 * 双向守护服务抽象父类
 *
 * 职责边界：
 * - 双向互唤醒机制：主服务 <-> 守护服务
 * - 检测主服务存活状态
 * - 主服务异常时自动重启
 * - Root环境下自动提升OOM进程优先级
 *
 * 禁止：
 * - 处理任何业务逻辑
 * - 直接操作iptables/Shell
 */
abstract class BaseGuardDaemonService : Service(), IServiceGuardian {

    /** 守护心跳间隔 */
    protected open val heartbeatIntervalMs: Long = Constants.GUARD_HEARTBEAT_MS

    /** 主服务重启延迟 */
    protected open val restartDelayMs: Long = Constants.GUARD_RESTART_DELAY_MS

    /**
     * 检测主服务是否存活（子类实现）
     */
    override abstract fun isServiceAlive(serviceName: String): Boolean

    /**
     * 重启指定服务（子类实现）
     */
    override abstract fun restartService(serviceName: String)

    /**
     * 获取服务PID（子类实现）
     */
    override abstract fun getServicePid(serviceName: String): Int

    /**
     * 构建前台通知（子类实现）
     */
    protected abstract fun buildGuardNotification(): Notification

    /**
     * 提升服务OOM优先级（Root模式下）
     */
    protected abstract fun boostOOMPriority(pid: Int)

    /**
     * 启动心跳检测循环
     */
    protected abstract fun startHeartbeat()

    /**
     * 停止心跳检测循环
     */
    protected abstract fun stopHeartbeat()

    // ========================================================================
    // Service 生命周期
    // ========================================================================

    override fun onCreate() {
        super.onCreate()
        startHeartbeat()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(Constants.NOTIFICATION_ID_GUARD, buildGuardNotification())
        return START_STICKY
    }

    override fun onDestroy() {
        stopHeartbeat()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}