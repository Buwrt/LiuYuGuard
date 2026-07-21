package com.liuyuguard.service

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import com.liuyuguard.model.IRootCommandBridge
import com.liuyuguard.util.Constants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * UI销毁后后台常驻控制器
 *
 * 确保流量管控服务在UI销毁（Activity被系统回收）后依然持续运行，
 * 不被系统的电量优化、任务清理等功能杀死。
 *
 * 保活策略（三级防御）：
 * 1. Root提升服务OOM优先级：将服务进程的oom_adj降低到-800，使其成为系统级高优先级进程
 * 2. iptables规则持久化：将当前所有断网规则序列化到/data/local/tmp/liuyuguard/，
 *    设备重启后由BootCompletedReceiver触发GuardDaemonService恢复规则
 * 3. 双守护保活：主服务(MainTrafficService)与守护服务(GuardDaemonService)互相检测、互相拉起
 *
 * 使用约束：
 * - 仅限服务层内部调用，UI层禁止直接访问
 */
class ServiceKeeper(
    /** Android上下文，用于通知管理和电池优化白名单申请 */
    private val context: Context,
    /** Root指令桥接（可能为null，Shizuku/无权限模式下部分功能不可用） */
    private val rootBridge: IRootCommandBridge?
) {

    companion object {
        private const val TAG = "ServiceKeeper"

        /** 电池优化白名单请求码 */
        private const val REQUEST_IGNORE_BATTERY_OPTIMIZATIONS = 2001

        /** 主流量服务类名 */
        private const val MAIN_SERVICE_CLASS = "com.liuyuguard.service.MainTrafficService"

        /** 守护服务类名 */
        private const val GUARD_SERVICE_CLASS = "com.liuyuguard.service.GuardDaemonService"
    }

    // ========================================================================
    // 持久化与保活
    // ========================================================================

    /**
     * 确保服务持续运行
     *
     * 综合执行三级保活策略：
     * 1. Root提升服务OOM优先级：降低oom_adj使系统不会优先回收本服务进程
     * 2. iptables规则持久化：将当前规则写入缓存文件，重启后可恢复
     * 3. 双守护保活：确保GuardDaemonService正在运行，实现主服务与守护服务互拉
     *
     * 建议调用时机：
     * - MainTrafficService.onServiceInit()中初始化时调用
     * - 定时任务中周期性调用（如每分钟一次）
     * - 收到BOOT_COMPLETED广播时调用
     */
    fun ensurePersistence() {
        Timber.tag(TAG).i("执行服务持久化保活策略")

        // 策略1: Root提升服务OOM优先级
        boostOOMPriority()

        // 策略2: iptables规则持久化
        persistIptablesRules()

        // 策略3: 确保守护服务运行
        ensureGuardServiceRunning()
    }

    /**
     * Root提升服务OOM优先级
     *
     * 通过rootBridge将当前服务进程的oom_adj设置为-800（Constants.OOM_ADJ_FOREGROUND_SERVICE），
     * 使其成为系统级高优先级进程，显著降低被LMK（Low Memory Killer）回收的概率。
     *
     * 降级策略：如果rootBridge为null（非Root模式），则跳过此步骤，
     * 依赖Android原生前台服务通知机制保活。
     */
    private fun boostOOMPriority() {
        val bridge = rootBridge ?: run {
            Timber.tag(TAG).w("RootBridge不可用，跳过OOM优先级提升")
            return
        }

        val pid = android.os.Process.myPid()
        Timber.tag(TAG).d("提升服务OOM优先级: pid=%d, target_adj=%d", pid, Constants.OOM_ADJ_FOREGROUND_SERVICE)

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val result = bridge.boostServicePriority(pid)
                when (result) {
                    is com.liuyuguard.model.CommandResult.Success -> {
                        Timber.tag(TAG).i("OOM优先级提升成功: pid=%d", pid)
                    }
                    is com.liuyuguard.model.CommandResult.Error -> {
                        Timber.tag(TAG).w("OOM优先级提升失败: pid=%d, err=%s", pid, result.message)
                    }
                    is com.liuyuguard.model.CommandResult.PartialSuccess -> {
                        Timber.tag(TAG).w("OOM优先级部分提升: pid=%d, msg=%s", pid, result.message)
                    }
                }
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "提升OOM优先级异常: pid=%d", pid)
            }
        }
    }

    /**
     * iptables规则持久化
     *
     * 通过rootBridge将当前所有已下发的iptables DROP规则序列化到缓存文件
     * (/data/local/tmp/liuyuguard/iptables_rules.json)。
     *
     * 设备重启后的恢复流程：
     * 1. BootCompletedReceiver收到开机广播
     * 2. 启动GuardDaemonService
     * 3. GuardDaemonService通过rootBridge.restoreRules()从缓存恢复规则
     *
     * 降级策略：如果rootBridge为null，则跳过持久化。
     */
    private fun persistIptablesRules() {
        val bridge = rootBridge ?: run {
            Timber.tag(TAG).w("RootBridge不可用，跳过规则持久化")
            return
        }

        Timber.tag(TAG).d("开始持久化iptables规则")

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val result = bridge.persistRules()
                when (result) {
                    is com.liuyuguard.model.CommandResult.Success -> {
                        Timber.tag(TAG).i("iptables规则持久化成功")
                    }
                    is com.liuyuguard.model.CommandResult.Error -> {
                        Timber.tag(TAG).w("iptables规则持久化失败: %s", result.message)
                    }
                    is com.liuyuguard.model.CommandResult.PartialSuccess -> {
                        Timber.tag(TAG).w("iptables规则部分持久化: %s", result.message)
                    }
                }
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "iptables规则持久化异常")
            }
        }
    }

    /**
     * 确保守护服务正在运行
     *
     * 检查GuardDaemonService是否存活，如果未运行则启动它。
     * 实现主服务与守护服务的双向互保：
     * - MainTrafficService启动时 -> 启动GuardDaemonService
     * - GuardDaemonService心跳检测 -> 如果MainTrafficService死了则重启
     *
     * 即使GuardDaemonService启动失败（权限问题等），主服务仍会通过
     * 前台通知 + START_STICKY机制保持存活。
     */
    private fun ensureGuardServiceRunning() {
        try {
            val guardIntent = Intent().apply {
                setClassName(context.packageName, GUARD_SERVICE_CLASS)
            }

            // 检查守护服务是否已运行
            val isRunning = isServiceRunning(GUARD_SERVICE_CLASS)
            if (!isRunning) {
                Timber.tag(TAG).i("守护服务未运行，尝试启动")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(guardIntent)
                } else {
                    context.startService(guardIntent)
                }
            } else {
                Timber.tag(TAG).v("守护服务已在运行")
            }
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "启动守护服务失败")
            // 守护服务启动失败不影响主服务运行
        }
    }

    // ========================================================================
    // 电池优化白名单
    // ========================================================================

    /**
     * 请求电池优化白名单
     *
     * 引导用户将本应用加入电池优化白名单（IGNORE_BATTERY_OPTIMIZATIONS），
     * 防止系统在省电模式下杀死后台服务。
     *
     * 需要在AndroidManifest.xml中声明REQUEST_IGNORE_BATTERY_OPTIMIZATIONS权限。
     * 此方法会发起一个系统弹窗请求，结果通过onActivityResult返回。
     *
     * 注意：此方法应在Activity上下文中调用（需要Activity作为.startActivityForResult的发起者）。
     * 如果在Service中调用，则使用FLAG_ACTIVITY_NEW_TASK标记。
     */
    fun requestBatteryOptimizationWhitelist() {
        Timber.tag(TAG).i("请求电池优化白名单")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val packageName = context.packageName
            val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager

            // 先检查是否已在白名单中
            if (pm?.isIgnoringBatteryOptimizations(packageName) == true) {
                Timber.tag(TAG).i("已在电池优化白名单中，无需重复请求")
                return
            }

            try {
                val intent = Intent().apply {
                    action = Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
                    data = Uri.parse("package:$packageName")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                Timber.tag(TAG).i("已发起电池优化白名单请求弹窗")
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "发起电池优化白名单请求失败，可能缺少权限或设备不支持")
                // 降级：尝试打开电池优化设置页面，让用户手动操作
                openBatteryOptimizationSettings()
            }
        } else {
            Timber.tag(TAG).w("Android版本低于M，不支持电池优化白名单API")
        }
    }

    /**
     * 打开电池优化设置页面（降级方案）
     *
     * 当直接请求白名单失败时，引导用户到系统设置页面手动添加。
     */
    private fun openBatteryOptimizationSettings() {
        try {
            val intent = Intent().apply {
                action = Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            Timber.tag(TAG).i("已打开电池优化设置页面")
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "打开电池优化设置页面失败")
        }
    }

    // ========================================================================
    // 前台通知管理
    // ========================================================================

    /**
     * 更新前台通知（保持服务前台状态）
     *
     * 更新MainTrafficService的前台通知内容，确保服务保持前台状态。
     * Android要求前台服务必须显示一个持久通知，通过定期更新通知内容，
     * 可以防止通知被系统静默移除（部分厂商ROM行为）。
     *
     * @param service 目标服务实例（MainTrafficService）
     * @param title 通知标题，如"流量管控运行中"
     * @param content 通知内容，如"已监控15个应用，3个已断网"
     */
    fun updateForegroundNotification(service: Service, title: String, content: String) {
        Timber.tag(TAG).d("更新前台通知: title=%s, content=%s", title, content)

        try {
            // 构建带动作按钮的更新通知
            val notification = buildServiceNotification(title, content)
            val notificationManager = context.getSystemService(
                Context.NOTIFICATION_SERVICE
            ) as? NotificationManager

            notificationManager?.notify(Constants.NOTIFICATION_ID_TRAFFIC, notification)

            // 同时更新Service的前台通知
            // Service.startForeground() 会替换之前的通知
            service.startForeground(Constants.NOTIFICATION_ID_TRAFFIC, notification)

            Timber.tag(TAG).v("前台通知更新成功")
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "更新前台通知失败")
        }
    }

    /**
     * 构建服务通知
     *
     * 创建一个包含应用图标、标题、内容和操作按钮的前台通知。
     * 使用CHANNEL_TRAFFIC通知渠道（需在Application或Service中提前创建）。
     *
     * @param title 通知标题
     * @param content 通知内容
     * @return 构建完成的Notification对象
     */
    private fun buildServiceNotification(title: String, content: String): Notification {
        // 点击通知打开主界面
        val openIntent = Intent().apply {
            setClassName(context.packageName, "com.liuyuguard.ui.MainActivity")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPendingIntent = PendingIntent.getActivity(
            context,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // 停止服务按钮
        val stopIntent = Intent().apply {
            setClassName(context.packageName, MAIN_SERVICE_CLASS)
            action = "com.liuyuguard.action.STOP_SERVICE"
        }
        val stopPendingIntent = PendingIntent.getService(
            context,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(context, Constants.CHANNEL_TRAFFIC)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(context)
                .setPriority(Notification.PRIORITY_LOW)
        }

        return builder
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(
                context.applicationInfo.icon
            )
            .setContentIntent(openPendingIntent)
            .addAction(
                android.R.drawable.ic_delete,
                "停止服务",
                stopPendingIntent
            )
            .setOngoing(true)  // 不允许用户滑动删除
            .build()
    }

    // ========================================================================
    // 辅助方法
    // ========================================================================

    /**
     * 检查指定服务是否正在运行
     *
     * @param serviceName 服务完整类名
     * @return true表示服务正在运行
     */
    private fun isServiceRunning(serviceName: String): Boolean {
        return try {
            val manager = context.getSystemService(Context.ACTIVITY_SERVICE)
                as? android.app.ActivityManager
            manager?.getRunningServices(Int.MAX_VALUE)
                ?.any { it.service.className == serviceName }
                ?: false
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "检查服务运行状态失败: %s", serviceName)
            false
        }
    }
}
