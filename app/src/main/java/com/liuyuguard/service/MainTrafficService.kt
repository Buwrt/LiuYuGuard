package com.liuyuguard.service

import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
import com.liuyuguard.R
import com.liuyuguard.model.CommandResult
import com.liuyuguard.model.UiCommand
import com.liuyuguard.ui.MainActivity
import com.liuyuguard.util.Constants
import kotlinx.coroutines.runBlocking

/**
 * 主流量管控前台服务
 *
 * 继承BaseTrafficService，实现所有abstract方法。
 * 作为业务服务层的核心，协调Repository、CommandDispatcher、TaskScheduler三大组件。
 *
 * 职责边界：
 * - 初始化并持有Repository和CommandDispatcher
 * - 通过CommandDispatcher接收并处理UI层指令
 * - 管理定时任务调度（采样、结算、归档、闲置轮询）
 * - 向UI层回传实时数据（通过Repository）
 *
 * 禁止：
 * - 直接执行Shell/iptables命令（交由RootBridge）
 * - 直接读取/proc文件（交由RootBridge）
 */
class MainTrafficService : BaseTrafficService() {

    companion object {
        /** 前台通知 - 点击动作：打开主界面 */
        private const val ACTION_OPEN_MAIN = "com.liuyuguard.action.OPEN_MAIN"

        /** 前台通知 - 停止服务动作 */
        private const val ACTION_STOP_SERVICE = "com.liuyuguard.action.STOP_SERVICE"
    }

    // ========================================================================
    // 核心组件
    // ========================================================================

    /** 数据仓库：服务层与UI层之间的数据中转站 */
    lateinit var repository: TrafficDataRepository
        private set

    /** 指令分发器：接收UI层指令并路由 */
    private lateinit var commandDispatcher: CommandDispatcherImpl

    /** 定时任务调度器 */
    private var taskScheduler: BaseTaskScheduler? = null

    // ========================================================================
    // BaseTrafficService 抽象方法实现
    // ========================================================================

    /**
     * 服务初始化
     * 创建Repository、CommandDispatcher，标记服务运行状态
     */
    override fun onServiceInit() {
        // 初始化数据仓库
        repository = TrafficDataRepository()
        // 初始化指令分发器（传入自身引用以便获取RootBridge和Repository）
        commandDispatcher = CommandDispatcherImpl(this)
        // 标记服务为运行中
        repository.setServiceRunning(true)
    }

    /**
     * 处理业务指令
     * 委托给CommandDispatcher进行解析和路由
     */
    override fun handleBusinessCommand(command: UiCommand): CommandResult {
        // 注意：dispatch是suspend函数，此处通过runBlocking或协程调用
        // 实际调用在BaseTrafficService.dispatch()中以suspend方式触发
        return runCatching {
            // 在服务层协程中同步执行，返回最终结果
            runBlocking {
                commandDispatcher.dispatch(command)
            }
        }.getOrElse { e ->
            CommandResult.Error("指令处理异常: ${e.message}")
        }
    }

    /**
     * 启动定时任务调度
     * 创建BaseTaskScheduler实现，注册4类定时任务：
     * - kernel_sampling: 10秒内核流量采样
     * - minute_settle: 1分钟结算
     * - hour_archive: 1小时归档
     * - idle_polling: 30秒闲置轮询
     */
    override fun startScheduledTasks() {
        if (taskScheduler != null) return // 避免重复创建

        taskScheduler = object : BaseTaskScheduler() {
            override fun defineTasks(): Map<String, TaskDefinition> {
                return mapOf(
                    // 10秒内核流量采样
                    TaskNames.KERNEL_SAMPLING to TaskDefinition(
                        intervalMs = Constants.SAMPLING_INTERVAL_MS
                    ) {
                        performKernelSampling()
                    },
                    // 1分钟结算
                    TaskNames.MINUTE_SETTLE to TaskDefinition(
                        intervalMs = Constants.MINUTE_SETTLE_MS
                    ) {
                        performMinuteSettle()
                    },
                    // 1小时归档
                    TaskNames.HOUR_ARCHIVE to TaskDefinition(
                        intervalMs = Constants.HOUR_ARCHIVE_MS
                    ) {
                        performHourArchive()
                    },
                    // 30秒闲置轮询
                    TaskNames.IDLE_POLLING to TaskDefinition(
                        intervalMs = Constants.IDLE_CHECK_INTERVAL_MS
                    ) {
                        performIdlePolling()
                    }
                )
            }
        }

        taskScheduler?.startAll()
    }

    /**
     * 停止定时任务调度
     */
    override fun stopScheduledTasks() {
        taskScheduler?.destroy()
        taskScheduler = null
    }

    /**
     * 构建带动作按钮的前台通知
     * 包含"打开主界面"和"停止服务"两个动作
     */
    override fun buildForegroundNotification(): Notification {
        // 点击通知打开主界面
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPendingIntent = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // 停止服务按钮
        val stopIntent = Intent(this, MainTrafficService::class.java).apply {
            action = ACTION_STOP_SERVICE
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return Notification.Builder(this, Constants.CHANNEL_TRAFFIC)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(openPendingIntent)
            // 添加停止服务动作按钮
            .addAction(
                android.R.drawable.ic_delete,
                "停止服务",
                stopPendingIntent
            )
            .setOngoing(true)
            .build()
    }

    // ========================================================================
    // Service 生命周期扩展
    // ========================================================================

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 处理通知栏停止服务动作
        if (intent?.action == ACTION_STOP_SERVICE) {
            stopSelf()
            return START_NOT_STICKY
        }

        // 调用父类标准流程（前台通知 + Intent解析）
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onDestroy() {
        // 清理资源：停止调度器、标记服务停止
        stopScheduledTasks()
        repository.setServiceRunning(false)
        super.onDestroy()
    }

    // ========================================================================
    // 定时任务具体逻辑（骨架实现，待小节4完整填充）
    // ========================================================================

    /**
     * 10秒内核流量采样
     * 通过RootBridge读取/proc/net/xt_qtaguid/stats，更新Repository
     */
    private suspend fun performKernelSampling() {
        val bridge = rootBridge ?: return
        try {
            // 读取内核流量统计
            val stats = bridge.readTrafficStats()
            // TODO: 将原始统计数据转换为TrafficOverview，更新Repository
            // TODO: 转换为AppTrafficInfo列表，更新Repository
            // TODO: 根据高精度模式调整采样间隔
        } catch (_: Exception) {
            // 采样失败不中断调度，等待下一周期
        }
    }

    /**
     * 1分钟结算
     * 汇总分钟级流量数据，更新图表数据
     */
    private suspend fun performMinuteSettle() {
        try {
            // TODO: 汇总当前分钟的流量增量
            // TODO: 发射图表数据到Repository
        } catch (_: Exception) {
            // 结算失败不中断调度
        }
    }

    /**
     * 1小时归档
     * 将小时级数据持久化到DataStore/数据库
     */
    private suspend fun performHourArchive() {
        try {
            // TODO: 汇总当前小时的总流量
            // TODO: 将归档数据持久化
            // TODO: 发射HourlyTraffic数据
        } catch (_: Exception) {
            // 归档失败不中断调度
        }
    }

    /**
     * 30秒闲置轮询
     * 检测后台闲置应用，根据策略自动断网
     */
    private suspend fun performIdlePolling() {
        val bridge = rootBridge ?: return
        try {
            // TODO: 遍历已安装应用，检测进程状态
            // TODO: 判断是否超时未使用
            // TODO: 对超时应用通过bridge.addBlockRule下发断网规则
        } catch (_: Exception) {
            // 闲置检测失败不中断调度
        }
    }
}
