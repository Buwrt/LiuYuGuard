package com.liuyuguard.service

import android.app.Notification
import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.liuyuguard.model.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 主流量管控前台服务抽象父类
 *
 * 职责边界：
 * - 接收UI下发的全部业务指令（通过ICommandDispatcher）
 * - 流量阈值判断
 * - 闲置应用判定
 * - 统计周期调度
 * - 心跳保活逻辑
 * - 向UI层回传实时数据（通过ITrafficDataObserver，由子类或注入的Repository提供）
 *
 * 禁止：
 * - 直接执行Shell/iptables命令（交由层级3 RootShellManager）
 * - 直接读取/proc文件（交由层级3 RootShellManager）
 */
abstract class BaseTrafficService : Service(), ICommandDispatcher {

    /** Root命令桥接（由层级3注入） */
    protected var rootBridge: IRootCommandBridge? = null

    /** 数据观察者（由子类注入TrafficDataRepository） */
    protected var dataObserver: ITrafficDataObserver? = null

    /**
     * 服务初始化（子类实现）
     */
    protected abstract fun onServiceInit()

    /**
     * 处理业务指令（子类实现）
     */
    protected abstract fun handleBusinessCommand(command: UiCommand): CommandResult

    /**
     * 启动定时任务调度
     */
    protected abstract fun startScheduledTasks()

    /**
     * 停止定时任务调度
     */
    protected abstract fun stopScheduledTasks()

    /**
     * 构建前台通知（子类实现）
     */
    protected abstract fun buildForegroundNotification(): Notification

    /**
     * 注入Root命令桥接（层级3在初始化后调用）
     */
    fun injectRootBridge(bridge: IRootCommandBridge) {
        this.rootBridge = bridge
    }

    // ========================================================================
    // Service 生命周期
    // ========================================================================

    override fun onCreate() {
        super.onCreate()
        onServiceInit()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(com.liuyuguard.util.Constants.NOTIFICATION_ID_TRAFFIC, buildForegroundNotification())

        // 解析Intent中的指令
        intent?.let { handleIntent(it) }

        return START_STICKY
    }

    override fun onDestroy() {
        stopScheduledTasks()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * 解析Intent中的业务指令
     */
    private fun handleIntent(intent: Intent) {
        // 小节4实现：从Intent extras中解析UiCommand
    }

    // ========================================================================
    // ICommandDispatcher 实现（UI层指令入口）
    // ========================================================================

    override suspend fun dispatch(command: UiCommand): CommandResult {
        return handleBusinessCommand(command)
    }
}