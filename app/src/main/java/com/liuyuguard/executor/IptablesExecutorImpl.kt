package com.liuyuguard.executor

import com.liuyuguard.model.Direction
import com.liuyuguard.model.ShellResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Iptables执行器实现
 *
 * 架构位置：层级4（Linux内核底层执行层）- Netfilter/Iptables规则管理
 * 职责：
 * - 执行iptables命令管理自定义链和规则
 * - 为指定UID添加/删除DROP规则（按网卡接口和流量方向）
 * - 初始化和清理LYGUARD_EGRESS / LYGUARD_INGRESS自定义链
 *
 * 设计要点：
 * - 通过BaseKernelExecutor（RootKernelExecutor）执行命令
 * - 自定义链LYGUARD_EGRESS绑定到OUTPUT链，LYGUARD_INGRESS绑定到INPUT链
 * - 所有规则使用 -m owner --uid-owner 精准匹配UID
 *
 * 自定义链结构：
 * - LYGUARD_EGRESS: 挂载于OUTPUT，管控出站流量（EGRESS）
 * - LYGUARD_INGRESS: 挂载于INPUT，管控入站流量（INGRESS）
 *
 * @param kernelExecutor 内核执行器实例（RootKernelExecutorImpl），用于执行Shell命令
 */
class IptablesExecutorImpl(
    private val kernelExecutor: BaseKernelExecutor
) : IptablesExecutor() {

    companion object {
        /** 出站流量自定义链名称 */
        private const val CHAIN_EGRESS = "LYGUARD_EGRESS"

        /** 入站流量自定义链名称 */
        private const val CHAIN_INGRESS = "LYGUARD_INGRESS"

        /** iptables可执行文件路径 */
        private const val IPTABLES = "iptables"
    }

    // ========================================================================
    // 核心规则操作
    // ========================================================================

    /**
     * 执行任意iptables命令字符串
     *
     * @param command 完整的iptables命令（例如 "iptables -L"）
     * @return ShellResult 命令执行结果
     */
    override suspend fun execute(command: String): ShellResult = withContext(Dispatchers.IO) {
        kernelExecutor.executeCommand(command)
    }

    /**
     * 为指定UID添加DROP规则
     *
     * 规则格式：
     * - EGRESS（出站）: iptables -A LYGUARD_EGRESS -m owner --uid-owner {uid} -o {iface} -j DROP
     * - INGRESS（入站）: iptables -A LYGUARD_INGRESS -m owner --uid-owner {uid} -i {iface} -j DROP
     *
     * 参数说明：
     * - -A: 追加规则到链末尾
     * - -m owner --uid-owner: 使用owner模块匹配UID
     * - -o/-i: 出站/入站网卡接口
     * - -j DROP: 动作为丢弃数据包
     *
     * @param uid 目标应用UID
     * @param iface 网络接口名（如 wlan0, rmnet_data0）
     * @param direction 流量方向 EGRESS/INGRESS
     * @return ShellResult 规则添加结果
     */
    override suspend fun addDropRule(
        uid: Int,
        iface: String,
        direction: Direction
    ): ShellResult = withContext(Dispatchers.IO) {
        val command = buildDropRuleCommand("-A", uid, iface, direction)
        kernelExecutor.executeCommand(command)
    }

    /**
     * 为指定UID删除DROP规则
     *
     * 与addDropRule相同格式，但使用 -D（删除）替代 -A（追加）
     *
     * @param uid 目标应用UID
     * @param iface 网络接口名
     * @param direction 流量方向
     * @return ShellResult 规则删除结果
     */
    override suspend fun removeDropRule(
        uid: Int,
        iface: String,
        direction: Direction
    ): ShellResult = withContext(Dispatchers.IO) {
        val command = buildDropRuleCommand("-D", uid, iface, direction)
        kernelExecutor.executeCommand(command)
    }

    /**
     * 查询指定UID在自定义链中的所有规则
     *
     * 遍历EGRESS和INGRESS两条自定义链，过滤出包含目标UID的规则行。
     *
     * @param uid 目标UID
     * @return 匹配的规则行列表
     */
    override suspend fun queryRules(uid: Int): List<String> = withContext(Dispatchers.IO) {
        val matchedRules = mutableListOf<String>()
        val chains = listOf(CHAIN_EGRESS, CHAIN_INGRESS)

        for (chain in chains) {
            // 使用 -n（数字格式）和 -v（详细）列出链中的规则
            val result = kernelExecutor.executeCommand(
                "$IPTABLES -n -v -L $chain"
            )
            if (result.success) {
                // 过滤包含目标UID的规则行，排除表头行
                result.stdout.lines()
                    .filter { line -> line.contains("owner") && line.contains("$uid") }
                    .mapTo(matchedRules) { it.trim() }
            }
        }

        matchedRules
    }

    // ========================================================================
    // 自定义链管理
    // ========================================================================

    /**
     * 初始化Iptables自定义链
     *
     * 流程：
     * 1. 创建LYGUARD_EGRESS链（如果不存在则创建，已存在则忽略错误）
     * 2. 创建LYGUARD_INGRESS链（同上）
     * 3. 将自定义链绑定到系统链：
     *    - LYGUARD_EGRESS -> OUTPUT链（从OUTPUT跳转到LYGUARD_EGRESS处理出站流量）
     *    - LYGUARD_INGRESS -> INPUT链（从INPUT跳转到LYGUARD_INGRESS处理入站流量）
     *
     * 绑定规则使用 RETURN 作为默认处理，即自定义链中未匹配的流量正常放行。
     *
     * @return ShellResult 初始化结果（返回最后一条命令的结果）
     */
    override suspend fun initCustomChain(): ShellResult = withContext(Dispatchers.IO) {
        val commands = listOf(
            // 创建出站自定义链（-N新建，失败说明已存在则忽略）
            "$IPTABLES -N $CHAIN_EGRESS 2>/dev/null; true",
            // 清空已有规则（防止重复初始化时规则叠加）
            "$IPTABLES -F $CHAIN_EGRESS",
            // 创建入站自定义链
            "$IPTABLES -N $CHAIN_INGRESS 2>/dev/null; true",
            // 清空已有规则
            "$IPTABLES -F $CHAIN_INGRESS",
            // 移除可能存在的旧绑定引用（防止重复绑定）
            "$IPTABLES -D OUTPUT -j $CHAIN_EGRESS 2>/dev/null; true",
            // 将出站自定义链绑定到OUTPUT链
            "$IPTABLES -A OUTPUT -j $CHAIN_EGRESS",
            // 移除可能存在的旧绑定引用
            "$IPTABLES -D INPUT -j $CHAIN_INGRESS 2>/dev/null; true",
            // 将入站自定义链绑定到INPUT链
            "$IPTABLES -A INPUT -j $CHAIN_INGRESS"
        )

        // 依次执行所有命令，返回最后一条命令的结果
        var lastResult: ShellResult = ShellResult(success = true)
        for (cmd in commands) {
            lastResult = kernelExecutor.executeCommand(cmd)
        }

        lastResult
    }

    /**
     * 清理自定义链
     *
     * 流程（必须按顺序执行，避免依赖错误）：
     * 1. 从INPUT链中移除对LYGUARD_INGRESS的引用（-D删除跳转规则）
     * 2. 从OUTPUT链中移除对LYGUARD_EGRESS的引用
     * 3. 清空LYGUARD_INGRESS链中的所有规则（-F flush）
     * 4. 清空LYGUARD_EGRESS链中的所有规则
     * 5. 删除LYGUARD_INGRESS链（-X删除空链）
     * 6. 删除LYGUARD_EGRESS链
     *
     * 注意：必须先解除系统链的引用，再清空和删除自定义链，否则会报错。
     *
     * @return ShellResult 清理结果
     */
    override suspend fun cleanupCustomChain(): ShellResult = withContext(Dispatchers.IO) {
        val commands = listOf(
            // 第一步：解除系统链的引用
            "$IPTABLES -D INPUT -j $CHAIN_INGRESS 2>/dev/null; true",
            "$IPTABLES -D OUTPUT -j $CHAIN_EGRESS 2>/dev/null; true",
            // 第二步：清空自定义链中的规则
            "$IPTABLES -F $CHAIN_INGRESS 2>/dev/null; true",
            "$IPTABLES -F $CHAIN_EGRESS 2>/dev/null; true",
            // 第三步：删除自定义链
            "$IPTABLES -X $CHAIN_INGRESS 2>/dev/null; true",
            "$IPTABLES -X $CHAIN_EGRESS 2>/dev/null; true"
        )

        var lastResult: ShellResult = ShellResult(success = true)
        for (cmd in commands) {
            lastResult = kernelExecutor.executeCommand(cmd)
        }

        lastResult
    }

    // ========================================================================
    // 内部辅助方法
    // ========================================================================

    /**
     * 构建iptables DROP规则命令
     *
     * @param action 规则操作："-A"追加 或 "-D"删除
     * @param uid 目标UID
     * @param iface 网络接口名
     * @param direction 流量方向
     * @return 完整的iptables命令字符串
     */
    private fun buildDropRuleCommand(
        action: String,
        uid: Int,
        iface: String,
        direction: Direction
    ): String {
        val chain = when (direction) {
            Direction.EGRESS -> CHAIN_EGRESS
            Direction.INGRESS -> CHAIN_INGRESS
        }
        // EGRESS使用 -o（出站接口），INGRESS使用 -i（入站接口）
        val ifaceFlag = when (direction) {
            Direction.EGRESS -> "-o"
            Direction.INGRESS -> "-i"
        }
        return "$IPTABLES $action $chain -m owner --uid-owner $uid $ifaceFlag $iface -j DROP"
    }
}
