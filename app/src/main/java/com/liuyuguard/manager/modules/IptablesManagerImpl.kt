package com.liuyuguard.manager.modules

import com.liuyuguard.manager.RootShellManagerImpl
import com.liuyuguard.model.CommandResult
import com.liuyuguard.model.Direction
import com.liuyuguard.util.Constants
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Iptables防火墙模块实现
 *
 * 职责：
 * - 管理LYGUARD_INGRESS / LYGUARD_EGRESS自定义链
 * - 添加/删除/查询DROP规则（按UID + 网卡接口 + 方向）
 * - 规则本地持久化（JSON序列化写入/data/local/tmp/liuyuguard/）
 * - 断电恢复（从JSON文件读取并逐条执行）
 *
 * 所有Shell命令通过 RootShellManagerImpl.executeShell() 执行
 */
class IptablesManagerImpl : IIptablesManager {

    /** JSON序列化器，配置为宽松模式以兼容不同格式 */
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    /** 当前活跃的规则缓存（用于持久化） */
    private val activeRules = mutableListOf<PersistedRule>()

    // ========================================================================
    // 自定义链管理
    // ========================================================================

    /**
     * 初始化自定义链
     *
     * 创建 LYGUARD_INGRESS 和 LYGUARD_EGRESS 两条自定义链，
     * 并将它们分别绑定到 INPUT 和 OUTPUT 链的末尾。
     * 如果链已存在则跳过创建（不报错）。
     *
     * @return CommandResult 操作结果
     */
    override suspend fun initChains(): CommandResult {
        val commands = listOf(
            // 创建EGRESS自定义链（出站流量）
            "iptables -N ${Constants.IPTABLES_CHAIN_EGRESS} 2>/dev/null; true",
            // 将EGRESS链绑定到OUTPUT链
            "iptables -A OUTPUT -j ${Constants.IPTABLES_CHAIN_EGRESS} 2>/dev/null; true",
            // 创建INGRESS自定义链（入站流量）
            "iptables -N ${Constants.IPTABLES_CHAIN_INGRESS} 2>/dev/null; true",
            // 将INGRESS链绑定到INPUT链
            "iptables -A INPUT -j ${Constants.IPTABLES_CHAIN_INGRESS} 2>/dev/null; true"
        )

        val results = RootShellManagerImpl.executeShellBatch(commands)
        val allSuccess = results.all { it.success }

        return if (allSuccess) {
            CommandResult.Success
        } else {
            val errors = results.filter { !it.success }.joinToString("; ") { it.stderr }
            CommandResult.Error(errors)
        }
    }

    /**
     * 清理自定义链
     *
     * 先清空链内规则，再从INPUT/OUTPUT解绑，最后删除自定义链。
     *
     * @return CommandResult 操作结果
     */
    override suspend fun cleanupChains(): CommandResult {
        val commands = listOf(
            // 清空EGRESS链规则
            "iptables -F ${Constants.IPTABLES_CHAIN_EGRESS} 2>/dev/null; true",
            // 从OUTPUT链解绑EGRESS
            "iptables -D OUTPUT -j ${Constants.IPTABLES_CHAIN_EGRESS} 2>/dev/null; true",
            // 删除EGRESS链
            "iptables -X ${Constants.IPTABLES_CHAIN_EGRESS} 2>/dev/null; true",
            // 清空INGRESS链规则
            "iptables -F ${Constants.IPTABLES_CHAIN_INGRESS} 2>/dev/null; true",
            // 从INPUT链解绑INGRESS
            "iptables -D INPUT -j ${Constants.IPTABLES_CHAIN_INGRESS} 2>/dev/null; true",
            // 删除INGRESS链
            "iptables -X ${Constants.IPTABLES_CHAIN_INGRESS} 2>/dev/null; true"
        )

        val results = RootShellManagerImpl.executeShellBatch(commands)
        activeRules.clear()

        return if (results.all { it.success }) {
            CommandResult.Success
        } else {
            CommandResult.PartialSuccess("部分清理命令执行失败")
        }
    }

    // ========================================================================
    // 规则增删查
    // ========================================================================

    /**
     * 添加DROP规则
     *
     * 根据方向选择链：
     * - EGRESS: iptables -A LYGUARD_EGRESS -m owner --uid-owner {uid} -o {iface} -j DROP
     * - INGRESS: iptables -A LYGUARD_INGRESS -m owner --uid-owner {uid} -i {iface} -j DROP
     *
     * @param uid 目标应用UID
     * @param iface 网卡接口名（如 wlan0、rmnet_data0）
     * @param direction 流量方向（INGRESS入站 / EGRESS出站）
     * @return CommandResult 操作结果
     */
    override suspend fun addDropRule(uid: Int, iface: String, direction: Direction): CommandResult {
        // 根据方向构建iptables命令
        val chain = when (direction) {
            Direction.EGRESS -> Constants.IPTABLES_CHAIN_EGRESS
            Direction.INGRESS -> Constants.IPTABLES_CHAIN_INGRESS
        }
        // EGRESS用 -o（出站接口），INGRESS用 -i（入站接口）
        val ifaceFlag = when (direction) {
            Direction.EGRESS -> "-o"
            Direction.INGRESS -> "-i"
        }
        val command = "iptables -A $chain -m owner --uid-owner $uid $ifaceFlag $iface -j DROP"

        val result = RootShellManagerImpl.executeShell(command)
        if (result.success) {
            // 添加到缓存，用于后续持久化
            activeRules.add(PersistedRule(uid = uid, iface = iface, direction = direction.name))
        }

        return if (result.success) {
            CommandResult.Success
        } else {
            CommandResult.Error(result.stderr)
        }
    }

    /**
     * 删除DROP规则
     *
     * 与addDropRule对称，使用 -D 替代 -A
     *
     * @param uid 目标应用UID
     * @param iface 网卡接口名
     * @param direction 流量方向
     * @return CommandResult 操作结果
     */
    override suspend fun removeDropRule(uid: Int, iface: String, direction: Direction): CommandResult {
        val chain = when (direction) {
            Direction.EGRESS -> Constants.IPTABLES_CHAIN_EGRESS
            Direction.INGRESS -> Constants.IPTABLES_CHAIN_INGRESS
        }
        val ifaceFlag = when (direction) {
            Direction.EGRESS -> "-o"
            Direction.INGRESS -> "-i"
        }
        val command = "iptables -D $chain -m owner --uid-owner $uid $ifaceFlag $iface -j DROP"

        val result = RootShellManagerImpl.executeShell(command)
        if (result.success) {
            // 从缓存中移除
            activeRules.removeIf {
                it.uid == uid && it.iface == iface && it.direction == direction.name
            }
        }

        return if (result.success) {
            CommandResult.Success
        } else {
            CommandResult.Error(result.stderr)
        }
    }

    /**
     * 查询指定UID的所有规则
     *
     * 通过 iptables -L 列出自定义链中所有规则，过滤出包含目标UID的行
     *
     * @param uid 目标UID
     * @return 匹配的规则字符串列表
     */
    override suspend fun queryRules(uid: Int): List<String> {
        val result = RootShellManagerImpl.executeShell(
            "iptables -L ${Constants.IPTABLES_CHAIN_EGRESS} -n -v 2>/dev/null; " +
            "iptables -L ${Constants.IPTABLES_CHAIN_INGRESS} -n -v 2>/dev/null"
        )

        if (!result.success) return emptyList()

        // 过滤包含目标UID的规则行
        return result.stdout.lines()
            .filter { it.contains("uid $uid") || it.contains("uid-owner $uid") }
    }

    /**
     * 查询自定义链中所有规则
     *
     * @return 两条自定义链中的所有规则字符串
     */
    override suspend fun listAllRules(): List<String> {
        val result = RootShellManagerImpl.executeShell(
            "iptables -L ${Constants.IPTABLES_CHAIN_EGRESS} -n -v 2>/dev/null; " +
            "iptables -L ${Constants.IPTABLES_CHAIN_INGRESS} -n -v 2>/dev/null"
        )

        if (!result.success) return emptyList()

        return result.stdout.lines().filter { it.isNotBlank() }
    }

    // ========================================================================
    // 持久化与恢复
    // ========================================================================

    /**
     * 持久化所有规则到本地文件
     *
     * 将当前 activeRules 列表序列化为JSON，
     * 通过Root写入 /data/local/tmp/liuyuguard/iptables_rules.json
     *
     * @return CommandResult 操作结果
     */
    override suspend fun persistRules(): CommandResult {
        if (activeRules.isEmpty()) {
            return CommandResult.Success
        }

        return try {
            val rulesJson = json.encodeToString(activeRules)
            // 先创建目录，再写入文件
            val mkdirResult = RootShellManagerImpl.executeShell(
                "mkdir -p ${Constants.RULES_CACHE_DIR}"
            )
            if (!mkdirResult.success) {
                return CommandResult.Error("创建持久化目录失败: ${mkdirResult.stderr}")
            }

            // 通过echo写入文件（避免特殊字符问题，使用base64编码）
            val encoded = android.util.Base64.encodeToString(
                rulesJson.toByteArray(Charsets.UTF_8),
                android.util.Base64.NO_WRAP
            )
            val writeResult = RootShellManagerImpl.executeShell(
                "echo '$encoded' | base64 -d > ${Constants.RULES_CACHE_DIR}/${Constants.RULES_CACHE_FILE}"
            )

            if (writeResult.success) {
                CommandResult.Success
            } else {
                CommandResult.Error("写入持久化文件失败: ${writeResult.stderr}")
            }
        } catch (e: Exception) {
            CommandResult.Error("持久化序列化失败: ${e.message}")
        }
    }

    /**
     * 从本地文件恢复规则
     *
     * 从 /data/local/tmp/liuyuguard/iptables_rules.json 读取JSON，
     * 反序列化后逐条执行iptables命令恢复规则
     *
     * @return CommandResult 操作结果
     */
    override suspend fun restoreRules(): CommandResult {
        return try {
            // 读取持久化文件内容（使用base64解码避免特殊字符问题）
            val readResult = RootShellManagerImpl.executeShell(
                "base64 ${Constants.RULES_CACHE_DIR}/${Constants.RULES_CACHE_FILE} 2>/dev/null"
            )

            if (!readResult.success || readResult.stdout.isBlank()) {
                // 无持久化文件或读取失败，视为首次运行
                return CommandResult.Success
            }

            // Base64解码
            val decodedBytes = android.util.Base64.decode(
                readResult.stdout.trim(),
                android.util.Base64.DEFAULT
            )
            val rulesJson = String(decodedBytes, Charsets.UTF_8)

            // 反序列化
            val rules: List<PersistedRule> = json.decodeFromString(rulesJson)

            if (rules.isEmpty()) {
                return CommandResult.Success
            }

            // 逐条恢复规则
            var failCount = 0
            for (rule in rules) {
                val direction = try {
                    Direction.valueOf(rule.direction)
                } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                    // 方向值非法，跳过该规则
                    failCount++
                    continue
                }

                val addResult = addDropRule(rule.uid, rule.iface, direction)
                if (addResult !is CommandResult.Success) {
                    failCount++
                }
            }

            if (failCount == 0) {
                CommandResult.Success
            } else {
                CommandResult.PartialSuccess("共${rules.size}条规则，${failCount}条恢复失败")
            }
        } catch (e: Exception) {
            // 反序列化失败或其他异常，不阻塞初始化
            CommandResult.PartialSuccess("规则恢复异常: ${e.message}")
        }
    }

    // ========================================================================
    // 内部数据类
    // ========================================================================

    /**
     * 持久化规则的数据模型
     * 用于JSON序列化/反序列化
     */
    @Serializable
    data class PersistedRule(
        /** 目标UID */
        val uid: Int,
        /** 网卡接口名 */
        val iface: String,
        /** 流量方向（"INGRESS" 或 "EGRESS"） */
        val direction: String
    )
}