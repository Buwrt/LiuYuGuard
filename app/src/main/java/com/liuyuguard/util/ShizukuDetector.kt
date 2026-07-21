package com.liuyuguard.util

import android.content.Context
import rikka.shizuku.Shizuku

/**
 * Shizuku权限检测器
 * 检测Shizuku是否运行且已授权本应用
 */
object ShizukuDetector {

    /** 检测Shizuku是否正在运行 */
    fun isShizukuRunning(): Boolean {
        return try {
            Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED
        } catch (_: Exception) {
            false
        }
    }

    /** 检测Shizuku是否已安装但未授权 */
    fun isShizukuInstalledButDenied(): Boolean {
        return try {
            // Shizuku已安装但权限未授予
            !isShizukuRunning() && isShizukuAvailable()
        } catch (_: Exception) {
            false
        }
    }

    /** 检测Shizuku服务是否可用（不一定已授权） */
    fun isShizukuAvailable(): Boolean {
        return try {
            Shizuku.pingBinder()
        } catch (_: Exception) {
            false
        }
    }

    /** 请求Shizuku授权 */
    fun requestPermission(activity: Context, requestCode: Int) {
        try {
            if (Shizuku.checkSelfPermission() != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                if (Shizuku.shouldShowRequestPermissionRationale()) {
                    // 用户之前拒绝过，引导手动授权
                    Shizuku.requestPermission(requestCode)
                } else {
                    Shizuku.requestPermission(requestCode)
                }
            }
        } catch (_: Exception) {
            // Shizuku不可用
        }
    }
}
