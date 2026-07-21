package com.liuyuguard.util

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings

/**
 * 系统权限检测工具类
 */
object PermissionChecker {

    /** 检查是否有前台服务权限 (Android 14+) */
    fun hasForegroundServicePermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            context.checkSelfPermission(android.Manifest.permission.FOREGROUND_SERVICE_SPECIAL_USE) ==
                    PackageManager.PERMISSION_GRANTED
        } else {
            context.checkSelfPermission(android.Manifest.permission.FOREGROUND_SERVICE) ==
                    PackageManager.PERMISSION_GRANTED
        }
    }

    /** 检查是否有通知权限 (Android 13+) */
    fun hasNotificationPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
                    PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    /** 检查是否有读取手机状态权限（SIM卡信息） */
    fun hasPhoneStatePermission(context: Context): Boolean {
        return context.checkSelfPermission(android.Manifest.permission.READ_PHONE_STATE) ==
                PackageManager.PERMISSION_GRANTED
    }

    /** 检查是否可以请求忽略电池优化 */
    fun isBatteryOptimizationIgnored(context: Context): Boolean {
        val pm = context.packageManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            intent.data = android.net.Uri.parse("package:${context.packageName}")
            intent.resolveActivity(pm) != null
        } else {
            true
        }
    }

    /** 检查是否拥有所有必要的基础权限 */
    fun hasAllBasicPermissions(context: Context): Boolean {
        return hasForegroundServicePermission(context) &&
                hasNotificationPermission(context) &&
                hasPhoneStatePermission(context)
    }

    /** 获取缺失权限列表（用于动态请求） */
    fun getMissingPermissions(context: Context): List<String> {
        val missing = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            if (!hasForegroundServicePermission(context)) {
                missing.add(android.Manifest.permission.FOREGROUND_SERVICE_SPECIAL_USE)
            }
        } else {
            if (context.checkSelfPermission(android.Manifest.permission.FOREGROUND_SERVICE) !=
                PackageManager.PERMISSION_GRANTED) {
                missing.add(android.Manifest.permission.FOREGROUND_SERVICE)
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !hasNotificationPermission(context)) {
            missing.add(android.Manifest.permission.POST_NOTIFICATIONS)
        }
        if (!hasPhoneStatePermission(context)) {
            missing.add(android.Manifest.permission.READ_PHONE_STATE)
        }
        return missing
    }
}