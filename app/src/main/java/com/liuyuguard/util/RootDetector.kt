package com.liuyuguard.util

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Root权限检测器
 * 通过多种方式判断设备是否已Root
 */
object RootDetector {

    /** 快速检测：尝试执行 id 命令 */
    suspend fun isRooted(): Boolean = withContext(Dispatchers.IO) {
        try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
            val output = process.inputStream.bufferedReader().readText().trim()
            val exitCode = process.waitFor()
            exitCode == 0 && (output.contains("uid=0") || output.contains("root"))
        } catch (_: Exception) {
            false
        }
    }

    /** 深度检测：多种Root特征检测 */
    suspend fun deepDetect(): Boolean = withContext(Dispatchers.IO) {
        // 方式1: 检查su命令
        val suPaths = listOf(
            "/system/bin/su",
            "/system/xbin/su",
            "/sbin/su",
            "/su/bin/su",
            "/magisk/.core/bin/su",
            "/data/adb/ksu/bin/su",
            "/data/adb/ap/bin/su"
        )
        val suExists = suPaths.any { File(it).exists() }

        // 方式2: 尝试执行su
        val suWorks = try {
            val p = Runtime.getRuntime().exec("su")
            val os = p.outputStream
            os.write("id\n".toByteArray())
            os.write("exit\n".toByteArray())
            os.flush()
            val result = p.inputStream.bufferedReader().readText()
            p.waitFor()
            os.close()
            result.contains("uid=0") || result.contains("root")
        } catch (_: Exception) {
            false
        }

        // 方式3: 检查Magisk/AP/KernelSU特征
        val magiskPaths = listOf(
            "/sbin/.magisk",
            "/data/adb/magisk",
            "/data/adb/ksu",
            "/data/adb/ap"
        )
        val magiskDetected = magiskPaths.any { File(it).exists() }

        suExists || suWorks || magiskDetected
    }
}