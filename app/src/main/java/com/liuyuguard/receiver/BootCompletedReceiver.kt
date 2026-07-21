package com.liuyuguard.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.liuyuguard.service.MainTrafficService
import com.liuyuguard.service.GuardDaemonService

/**
 * 开机自启广播接收器（桩实现，小节4完整开发）
 *
 * 职责：
 * - 开机后自动启动主服务和守护服务
 */
class BootCompletedReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == "android.intent.action.QUICKBOOT_POWERON"
        ) {
            // 小节4实现：检查权限后启动双服务
            // val serviceIntent = Intent(context, MainTrafficService::class.java)
            // context.startForegroundService(serviceIntent)
        }
    }
}