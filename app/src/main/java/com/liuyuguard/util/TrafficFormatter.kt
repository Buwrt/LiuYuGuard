package com.liuyuguard.util

import java.text.DecimalFormat

/**
 * 流量字节格式化工具
 */
object TrafficFormatter {

    private val units = arrayOf("B", "KB", "MB", "GB", "TB")
    private val df = DecimalFormat("#,##0.##")

    /** 格式化字节数为可读字符串 */
    fun format(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        var value = bytes.toDouble()
        var unitIndex = 0
        while (value >= 1024 && unitIndex < units.size - 1) {
            value /= 1024
            unitIndex++
        }
        return "${df.format(value)} ${units[unitIndex]}"
    }

    /** 短格式（无小数位） */
    fun formatShort(bytes: Long): String {
        if (bytes <= 0) return "0"
        var value = bytes.toDouble()
        var unitIndex = 0
        while (value >= 1024 && unitIndex < units.size - 1) {
            value /= 1024
            unitIndex++
        }
        return "${df.format(value)}${units[unitIndex]}"
    }
}