package com.liuyuguard.service

import android.content.Context
import android.content.pm.PackageManager
import android.net.TrafficStats
import com.liuyuguard.model.CommandResult
import com.liuyuguard.model.InterfaceTraffic
import rikka.shizuku.Shizuku
import timber.log.Timber

/**
 * Shizuku无Root兼容网络管控器
 *
 * 在设备无Root权限时，通过Shizuku提供的ADB Shell权限实现基础网络管控。
 * Shizuku的shell权限级别低于完整Root权限，因此部分操作可能失败或受限。
 *
 * 功能边界：
 * - 网络拦截：通过Shizuku RemoteProcess执行iptables命令（ADB权限级别）
 * - 流量统计：使用Android TrafficStats API获取UID级流量（弱精度）
 * - 权限检测：检测Shizuku服务是否可用且已授权
 *
 * 已知限制：
 * - Shizuku的shell权限不等同于Root权限，部分iptables操作可能被拒绝
 * - Android TrafficStats API无法区分WiFi/蜂窝流量，仅提供UID总流量
 * - TrafficStats在部分定制ROM上可能返回-1（不支持）
 * - 无法读取/proc/net/xt_qtaguid/stats（需要Root权限）
 *
 * 使用约束：
 * - 仅在RunMode.SHIZUKU模式下使用
 * - 操作失败时应降级到LOCKED模式或提示用户
 */
class ShizukuNetworkController(
    /** Android Context，用于获取PackageManager等系统服务 */
    private val context: Context
) {
    companion object {
        private const val TAG = "ShizukuNetController"

        /** iptables命令路径 */
        private const val IPTABLES_PATH = "/system/bin/iptables"

        /** Shizuku RemoteProcess执行超时时间（毫秒） */
        private const val COMMAND_TIMEOUT_MS = 5_000L
    }

    // ========================================================================
    // 网络拦截规则管理
    // ========================================================================

    /**
     * Shizuku模式下添加网络拦截规则
     *
     * 通过Shizuku RemoteProcess执行iptables命令，为指定UID在指定网卡接口上
     * 添加DROP规则（INGRESS + EGRESS双向拦截）。
     *
     * 实现方式：
     * 1. 构建iptables命令字符串
     * 2. 通过Shizuku.newProcess()创建RemoteProcess执行命令
     * 3. 读取命令输出和退出码，判断执行结果
     *
     * 注意：
     * - Shizuku的shell权限受限，部分操作可能失败（权限不足）
     * - 不支持自定义iptables链，直接操作默认链（INPUT/OUTPUT）
     * - 需要先检查Shizuku是否可用，否则直接返回Error
     *
     * @param uid 目标应用的UID
     * @param iface 目标网卡接口名（如 wlan0, rmnet_data0）
     * @return CommandResult 操作结果（Success / Error / PartialSuccess）
     */
    suspend fun addBlockRule(uid: Int, iface: String): CommandResult {
        Timber.tag(TAG).d("Shizuku添加拦截规则: uid=%d, iface=%s", uid, iface)

        // 检查Shizuku是否可用
        if (!isAvailable()) {
            Timber.tag(TAG).w("Shizuku不可用，无法添加拦截规则")
            return CommandResult.Error("Shizuku服务不可用或未授权")
        }

        val directions = listOf(
            "INPUT" to "INPUT",  // INGRESS：拦截流入
            "OUTPUT" to "OUTPUT" // EGRESS：拦截流出
        )

        var errorCount = 0
        var lastError: String? = null

        for ((chain, _) in directions) {
            // 构建iptables命令：在指定链上为UID添加DROP规则
            val command = buildIptablesAddCommand(uid, iface, chain)

            val result = executeShizukuCommand(command)

            if (result.success) {
                Timber.tag(TAG).d(
                    "Shizuku添加规则成功: uid=%d, iface=%s, chain=%s",
                    uid, iface, chain
                )
            } else {
                errorCount++
                lastError = result.stderr
                Timber.tag(TAG).w(
                    "Shizuku添加规则失败: uid=%d, iface=%s, chain=%s, err=%s",
                    uid, iface, chain, result.stderr
                )
            }
        }

        return when {
            errorCount == 0 -> CommandResult.Success
            errorCount < directions.size -> CommandResult.PartialSuccess(
                "成功添加 ${directions.size - errorCount}/${directions.size} 条规则，${errorCount}条失败"
            )
            else -> CommandResult.Error(
                lastError ?: "添加拦截规则失败，Shizuku权限可能不足",
                -2 // Shizuku权限不足错误码
            )
        }
    }

    /**
     * Shizuku模式下移除网络拦截规则
     *
     * 通过Shizuku RemoteProcess执行iptables -D命令，移除之前添加的DROP规则。
     *
     * @param uid 目标应用的UID
     * @param iface 目标网卡接口名
     * @return CommandResult 操作结果
     */
    suspend fun removeBlockRule(uid: Int, iface: String): CommandResult {
        Timber.tag(TAG).d("Shizuku移除拦截规则: uid=%d, iface=%s", uid, iface)

        // 检查Shizuku是否可用
        if (!isAvailable()) {
            Timber.tag(TAG).w("Shizuku不可用，无法移除拦截规则")
            return CommandResult.Error("Shizuku服务不可用或未授权")
        }

        val directions = listOf("INPUT", "OUTPUT")

        var errorCount = 0
        var lastError: String? = null

        for (chain in directions) {
            // 构建iptables删除命令
            val command = buildIptablesRemoveCommand(uid, iface, chain)

            val result = executeShizukuCommand(command)

            if (result.success) {
                Timber.tag(TAG).d(
                    "Shizuku移除规则成功: uid=%d, iface=%s, chain=%s",
                    uid, iface, chain
                )
            } else {
                // 移除规则时，如果规则不存在（exitCode非0），不算严重错误
                if (result.stderr.contains("No chain/target/match")) {
                    Timber.tag(TAG).d(
                        "规则不存在，无需移除: uid=%d, iface=%s, chain=%s",
                        uid, iface, chain
                    )
                } else {
                    errorCount++
                    lastError = result.stderr
                    Timber.tag(TAG).w(
                        "Shizuku移除规则失败: uid=%d, iface=%s, chain=%s, err=%s",
                        uid, iface, chain, result.stderr
                    )
                }
            }
        }

        return when {
            errorCount == 0 -> CommandResult.Success
            errorCount < directions.size -> CommandResult.PartialSuccess(
                "成功移除 ${directions.size - errorCount}/${directions.size} 条规则"
            )
            else -> CommandResult.Error(
                lastError ?: "移除拦截规则失败",
                -2
            )
        }
    }

    // ========================================================================
    // 流量统计（弱精度）
    // ========================================================================

    /**
     * 读取流量统计（弱精度，使用TrafficStats API代替内核读取）
     *
     * 通过Android系统的TrafficStats API获取每个UID的累计流量。
     *
     * 已知限制：
     * - TrafficStats.getUidRxBytes(uid) 只能获取每个UID的总接收字节数
     * - 无法区分WiFi和蜂窝流量（API限制）
     * - 无法获取报文级统计（rxPackets/txPackets）
     * - 部分定制ROM可能返回TrafficStats.UNSUPPORTED（-1）
     *
     * @return Map<UID, InterfaceTraffic> 各UID的流量统计（rxBytes/txBytes）
     */
    suspend fun readTrafficStats(): Map<Int, InterfaceTraffic> {
        Timber.tag(TAG).d("Shizuku模式读取流量统计（弱精度）")

        val result = mutableMapOf<Int, InterfaceTraffic>()

        try {
            // 获取所有已安装应用的UID列表
            val packages = context.packageManager.getInstalledPackages(0)
            val uidSet = packages
                .mapNotNull { it.applicationInfo?.uid }
                .toSet()

            for (uid in uidSet) {
                val rxBytes = TrafficStats.getUidRxBytes(uid)
                val txBytes = TrafficStats.getUidTxBytes(uid)

                // 跳过不支持的UID（TrafficStats返回-1表示不支持）
                if (rxBytes == TrafficStats.UNSUPPORTED || txBytes == TrafficStats.UNSUPPORTED) {
                    continue
                }

                // 跳过流量为0的UID（可能是未产生流量的系统应用）
                if (rxBytes == 0L && txBytes == 0L) {
                    continue
                }

                result[uid] = InterfaceTraffic(
                    rxBytes = rxBytes,
                    txBytes = txBytes
                    // 注意：Shizuku模式下无法获取报文级统计
                    // rxPackets 和 txPackets 留为默认值 0
                )
            }

            Timber.tag(TAG).d(
                "流量统计读取完成：共%d个活跃UID",
                result.size
            )

        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Shizuku模式读取流量统计异常")
        }

        return result
    }

    // ========================================================================
    // Shizuku可用性检测
    // ========================================================================

    /**
     * 检测Shizuku是否可用
     *
     * 判断条件：
     * 1. Shizuku服务是否正在运行（pingBinder成功）
     * 2. 应用是否已获得Shizuku权限（checkSelfPermission == PERMISSION_GRANTED）
     *
     * @return true表示Shizuku可用且已授权，false表示不可用
     */
    fun isAvailable(): Boolean {
        return try {
            Shizuku.pingBinder() &&
                    Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "检测Shizuku可用性异常")
            false
        }
    }

    // ========================================================================
    // 内部辅助方法
    // ========================================================================

    /**
     * 构建iptables添加规则命令
     *
     * 生成iptables命令字符串，在指定链上为指定UID和网卡接口添加DROP规则。
     * 使用 -m owner --uid-owner 匹配UID，使用 -i/-o 匹配网卡接口。
     *
     * @param uid 目标UID
     * @param iface 目标网卡接口
     * @param chain iptables链名（INPUT/OUTPUT）
     * @return 完整的iptables命令字符串
     */
    private fun buildIptablesAddCommand(uid: Int, iface: String, chain: String): String {
        return when (chain) {
            "INPUT" -> {
                // INGRESS规则：在INPUT链上匹配流入数据包
                // -i 指定流入接口，-m owner --uid-owner 匹配UID
                "$IPTABLES_PATH -I INPUT -i $iface -m owner --uid-owner $uid -j DROP"
            }
            "OUTPUT" -> {
                // EGRESS规则：在OUTPUT链上匹配流出数据包
                // -o 指定流出接口，-m owner --uid-owner 匹配UID
                "$IPTABLES_PATH -I OUTPUT -o $iface -m owner --uid-owner $uid -j DROP"
            }
            else -> ""
        }
    }

    /**
     * 构建iptables移除规则命令
     *
     * @param uid 目标UID
     * @param iface 目标网卡接口
     * @param chain iptables链名（INPUT/OUTPUT）
     * @return 完整的iptables删除命令字符串
     */
    private fun buildIptablesRemoveCommand(uid: Int, iface: String, chain: String): String {
        return when (chain) {
            "INPUT" -> {
                "$IPTABLES_PATH -D INPUT -i $iface -m owner --uid-owner $uid -j DROP"
            }
            "OUTPUT" -> {
                "$IPTABLES_PATH -D OUTPUT -o $iface -m owner --uid-owner $uid -j DROP"
            }
            else -> ""
        }
    }

    /**
     * 通过Shizuku RemoteProcess执行Shell命令
     *
     * 使用Shizuku.newProcess()创建一个ADB级别的Shell进程来执行命令。
     * 相比直接Runtime.exec()，Shizuku提供的进程具有更高的权限（ADB级别）。
     *
     * @param command 要执行的Shell命令
     * @return ShellCommandResult 包含执行状态、标准输出和标准错误
     */
    private suspend fun executeShizukuCommand(command: String): ShellCommandResult {
        return try {
            // 通过Shizuku创建RemoteProcess（ADB权限级别的Shell进程）
            val process = Shizuku.newProcess(arrayOf("sh", "-c", command), null, null)

            // 读取标准输出
            val stdout = process.inputStream.bufferedReader().use { reader ->
                reader.readText().trim()
            }

            // 读取标准错误
            val stderr = process.errorStream.bufferedReader().use { reader ->
                reader.readText().trim()
            }

            // 等待命令执行完成，设置超时
            val finished = process.waitFor()
            val exitCode = process.exitValue()

            ShellCommandResult(
                success = exitCode == 0,
                stdout = stdout,
                stderr = stderr,
                exitCode = exitCode
            )
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Shizuku命令执行异常: %s", command)
            ShellCommandResult(
                success = false,
                stdout = "",
                stderr = e.message ?: "未知异常",
                exitCode = -1
            )
        }
    }

    /**
     * Shell命令执行结果（Shizuku内部使用）
     *
     * @property success 命令是否执行成功（exitCode == 0）
     * @property stdout 标准输出内容
     * @property stderr 标准错误内容
     * @property exitCode 进程退出码
     */
    private data class ShellCommandResult(
        val success: Boolean,
        val stdout: String = "",
        val stderr: String = "",
        val exitCode: Int = -1
    )
}
