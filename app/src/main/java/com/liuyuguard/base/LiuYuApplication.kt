package com.liuyuguard.base

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.liuyuguard.model.RunMode
import com.liuyuguard.util.Constants
import com.liuyuguard.util.RootDetector
import com.liuyuguard.util.ShizukuDetector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Application基类
 * 负责全局初始化：权限检测、通知渠道创建、主题状态管理
 */
class LiuYuApplication : Application() {

    // 全局协程作用域
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // 运行模式（全局共享状态）
    private val _runMode = MutableStateFlow(RunMode.LOCKED)
    val runMode: StateFlow<RunMode> = _runMode

    // 主题模式
    private val _isDarkTheme = MutableStateFlow(false)
    val isDarkTheme: StateFlow<Boolean> = _isDarkTheme

    // 权限检测状态
    private val _isPermissionChecking = MutableStateFlow(true)
    val isPermissionChecking: StateFlow<Boolean> = _isPermissionChecking

    override fun onCreate() {
        super.onCreate()
        instance = this

        // 创建通知渠道
        createNotificationChannels()

        // 检测运行模式（Root优先 -> Shizuku -> 锁定）
        detectRunMode()
    }

    /**
     * 创建前台服务通知渠道
     */
    private fun createNotificationChannels() {
        val manager = getSystemService(NotificationManager::class.java)

        val trafficChannel = NotificationChannel(
            Constants.CHANNEL_TRAFFIC,
            getString(com.liuyuguard.R.string.notification_channel_traffic),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(com.liuyuguard.R.string.notification_channel_desc_traffic)
            setShowBadge(false)
        }

        val guardChannel = NotificationChannel(
            Constants.CHANNEL_GUARD,
            getString(com.liuyuguard.R.string.notification_channel_guard),
            NotificationManager.IMPORTANCE_MIN
        ).apply {
            description = getString(com.liuyuguard.R.string.notification_channel_desc_guard)
            setShowBadge(false)
        }

        manager.createNotificationChannel(trafficChannel)
        manager.createNotificationChannel(guardChannel)
    }

    /**
     * 检测运行模式
     * 优先级：Root > Shizuku > 锁定
     */
    private fun detectRunMode() {
        applicationScope.launch {
            _isPermissionChecking.value = true

            // 1. 检测Root
            val hasRoot = RootDetector.deepDetect()
            if (hasRoot) {
                _runMode.value = RunMode.ROOT
                _isPermissionChecking.value = false
                return@launch
            }

            // 2. 检测Shizuku
            if (ShizukuDetector.isShizukuRunning()) {
                _runMode.value = RunMode.SHIZUKU
                _isPermissionChecking.value = false
                return@launch
            }

            // 3. 无权限，锁定
            _runMode.value = RunMode.LOCKED
            _isPermissionChecking.value = false
        }
    }

    /**
     * 重新检测权限（用户手动触发，如Shizuku授权后）
     */
    fun recheckRunMode() {
        detectRunMode()
    }

    /**
     * 更新主题模式
     */
    fun setDarkTheme(isDark: Boolean) {
        _isDarkTheme.value = isDark
    }

    companion object {
        lateinit var instance: LiuYuApplication
            private set
    }
}