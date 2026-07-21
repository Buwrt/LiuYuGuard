package com.liuyuguard.manager.modules

import android.content.pm.PackageManager
import com.liuyuguard.base.LiuYuApplication

/**
 * UID管理模块实现
 *
 * 职责：
 * - 通过PackageManager获取应用UID和包名映射
 * - 通过/proc文件系统获取指定UID关联的PID列表
 * - 提供UID <-> 包名的双向查询
 *
 * 所有PackageManager操作通过LiuYuApplication.instance获取Context
 */
class UidManagerImpl : IUidManager {

    /** 获取PackageManager实例 */
    private val packageManager: PackageManager
        get() = LiuYuApplication.instance.packageManager

    // ========================================================================
    // IUidManager 接口实现
    // ========================================================================

    /**
     * 通过包名获取应用UID
     *
     * @param packageName 应用包名（如 "com.example.app"）
     * @return 应用的Linux UID，找不到返回null
     */
    override suspend fun getUidForPackage(packageName: String): Int? {
        return try {
            val applicationInfo = packageManager.getApplicationInfo(packageName, 0)
            applicationInfo.uid
        } catch (e: PackageManager.NameNotFoundException) {
            // 包名不存在
            null
        }
    }

    /**
     * 通过UID获取包名
     *
     * @param uid Linux UID
     * @return 对应的包名，找不到返回null。如果多个包共享同一UID，返回第一个
     */
    override suspend fun getPackageForUid(uid: Int): String? {
        return try {
            val packages = packageManager.getPackagesForUid(uid)
            // 多个包可能共享同一UID（如同一开发者的应用），返回第一个
            packages?.firstOrNull()
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 获取指定UID关联的所有PID列表
     *
     * 通过读取 /proc 目录下所有进程的 status 文件，
     * 匹配 Uid 字段来找到属于目标UID的所有进程PID
     *
     * @param uid 目标UID
     * @return 属于该UID的所有PID列表
     */
    override suspend fun getPidsForUid(uid: Int): List<Int> {
        return try {
            val pids = mutableListOf<Int>()
            val procDir = java.io.File("/proc")

            // 遍历 /proc 下所有数字目录（每个数字目录代表一个进程）
            val pidDirs = procDir.listFiles { file ->
                file.isDirectory && file.name.matches(Regex("\\d+"))
            } ?: return emptyList()

            for (pidDir in pidDirs) {
                try {
                    val statusFile = java.io.File(pidDir, "status")
                    if (!statusFile.exists()) continue

                    // 读取status文件，查找Uid行
                    // 格式示例：Uid:	10086	10086	10086	10086
                    statusFile.forEachLine { line ->
                        if (line.startsWith("Uid:")) {
                            val parts = line.substringAfter("Uid:").trim().split("\\s+".toRegex())
                            // 第一个值是真实UID
                            val realUid = parts.getOrNull(0)?.toIntOrNull() ?: -1
                            if (realUid == uid) {
                                pids.add(pidDir.name.toInt())
                            }
                            return@forEachLine // 找到Uid行后不需要继续读
                        }
                    }
                } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                    // 进程可能在遍历过程中已退出，忽略
                    continue
                }
            }

            pids
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * 获取所有已安装应用的UID列表
     *
     * 通过PackageManager获取所有已安装应用，建立 UID -> packageName 的映射
     *
     * @return Map<UID, 包名>，多个应用共享同一UID时后者覆盖前者
     */
    override suspend fun getAllAppUids(): Map<Int, String> {
        return try {
            val result = mutableMapOf<Int, String>()

            // 获取所有已安装应用
            val packages = packageManager.getInstalledApplications(PackageManager.GET_META_DATA)

            for (appInfo in packages) {
                // 跳过系统包（UID < 10000 通常是系统进程）
                // 但保留所有应用以便上层过滤
                result[appInfo.uid] = appInfo.packageName
            }

            result
        } catch (e: Exception) {
            emptyMap()
        }
    }
}