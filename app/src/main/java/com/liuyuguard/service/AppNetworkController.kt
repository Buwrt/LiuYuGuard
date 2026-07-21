package com.liuyuguard.service

import com.liuyuguard.model.CommandResult
import com.liuyuguard.model.Direction
import com.liuyuguard.model.IRootCommandBridge
import com.liuyuguard.util.Constants
import timber.log.Timber

/**
 * 单应用精准断网控制器
 *
 * 实现单应用精准断网全链路：全网断网、蜂窝单独断网、WiFi单独断网、恢复网络。
 * 基于iptables的uid-owner匹配机制，对指定UID的所有活跃网卡接口添加/移除DROP规则。
 *
 * 设计原则：
 * - 所有网络管控操作通过IRootCommandBridge下发，不直接执行Shell命令
 * - INGRESS + EGRESS双向拦截，确保应用无法收发任何网络数据
 * - 自动探测当前活跃网卡接口（wlan0, rmnet_data*），避免对不活跃接口下发无效规则
 *
 * 使用约束：
 * - 仅限服务层内部调用，UI层禁止直接访问
 * - 所有方法均为suspend函数，需在协程中调用
 */
class AppNetworkController(
    /** Root指令桥接，用于下发iptables规则 */
    private val rootBridge: IRootCommandBridge
) {

    companion object {
        private const val TAG = "AppNetworkController"

        /** WiFi网卡接口名 */
        private const val IFACE_WIFI = Constants.INTERFACE_WIFI

        /** 蜂窝网卡接口前缀（匹配 rmnet_data0, rmnet_data1 等） */
        private const val IFACE_CELLULAR_PREFIX = Constants.INTERFACE_CELLULAR_PREFIX
    }

    // ========================================================================
    // 断网操作
    // ========================================================================

    /**
     * 全网断网（WiFi + 蜂窝都断）
     *
     * 执行流程：
     * 1. 获取UID所有PID（通过rootBridge获取进程状态）
     * 2. 查询当前活跃网卡（wlan0, rmnet_data*）
     * 3. 对所有活跃网卡添加DROP规则（INGRESS + EGRESS双向拦截）
     *
     * @param uid 目标应用的UID
     * @return CommandResult 操作结果（Success / Error / PartialSuccess）
     */
    suspend fun blockAll(uid: Int): CommandResult {
        Timber.tag(TAG).d("全网断网: uid=%d", uid)

        // 获取当前活跃网卡列表
        val activeInterfaces = getActiveNetworkInterfaces()
        if (activeInterfaces.isEmpty()) {
            Timber.tag(TAG).w("未检测到活跃网卡，跳过断网操作: uid=%d", uid)
            return CommandResult.Error("未检测到活跃网卡，无法执行断网操作")
        }

        Timber.tag(TAG).d("活跃网卡: %s, uid=%d", activeInterfaces.joinToString(), uid)

        return addBlockRulesForInterfaces(uid, activeInterfaces)
    }

    /**
     * WiFi单独断网
     *
     * 仅对wlan0接口添加DROP规则，蜂窝数据不受影响。
     * 适用于仅需限制WiFi访问的场景（如节省蜂窝流量配额）。
     *
     * @param uid 目标应用的UID
     * @return CommandResult 操作结果
     */
    suspend fun blockWifi(uid: Int): CommandResult {
        Timber.tag(TAG).d("WiFi断网: uid=%d", uid)

        return addBlockRulesForInterfaces(uid, listOf(IFACE_WIFI))
    }

    /**
     * 蜂窝单独断网
     *
     * 仅对rmnet_data*接口添加DROP规则，WiFi不受影响。
     * 适用于仅需限制蜂窝数据访问的场景（如防止后台偷跑流量）。
     *
     * @param uid 目标应用的UID
     * @return CommandResult 操作结果
     */
    suspend fun blockCellular(uid: Int): CommandResult {
        Timber.tag(TAG).d("蜂窝断网: uid=%d", uid)

        // 获取所有蜂窝网卡接口
        val cellularInterfaces = getActiveNetworkInterfaces()
            .filter { it.startsWith(IFACE_CELLULAR_PREFIX) }

        if (cellularInterfaces.isEmpty()) {
            Timber.tag(TAG).w("未检测到活跃蜂窝网卡，跳过蜂窝断网: uid=%d", uid)
            return CommandResult.Error("未检测到活跃蜂窝网卡，无法执行蜂窝断网")
        }

        return addBlockRulesForInterfaces(uid, cellularInterfaces)
    }

    // ========================================================================
    // 恢复操作
    // ========================================================================

    /**
     * 恢复WiFi联网
     *
     * 移除wlan0接口上针对指定UID的所有DROP规则。
     *
     * @param uid 目标应用的UID
     * @return CommandResult 操作结果
     */
    suspend fun unblockWifi(uid: Int): CommandResult {
        Timber.tag(TAG).d("恢复WiFi联网: uid=%d", uid)

        return removeBlockRulesForInterfaces(uid, listOf(IFACE_WIFI))
    }

    /**
     * 恢复蜂窝联网
     *
     * 移除所有rmnet_data*接口上针对指定UID的DROP规则。
     *
     * @param uid 目标应用的UID
     * @return CommandResult 操作结果
     */
    suspend fun unblockCellular(uid: Int): CommandResult {
        Timber.tag(TAG).d("恢复蜂窝联网: uid=%d", uid)

        // 需要移除所有可能的蜂窝接口规则
        val cellularInterfaces = getActiveNetworkInterfaces()
            .filter { it.startsWith(IFACE_CELLULAR_PREFIX) }

        // 即使当前没有活跃蜂窝接口，也尝试移除（可能在断网期间接口已变化）
        return removeBlockRulesForInterfaces(uid, cellularInterfaces)
    }

    /**
     * 全部恢复
     *
     * 移除指定UID在所有活跃网卡接口上的DROP规则。
     * 通常在用户手动解除断网、或闲置应用切回前台时调用。
     *
     * @param uid 目标应用的UID
     * @return CommandResult 操作结果
     */
    suspend fun unblockAll(uid: Int): CommandResult {
        Timber.tag(TAG).d("全部恢复联网: uid=%d", uid)

        val activeInterfaces = getActiveNetworkInterfaces()
        return removeBlockRulesForInterfaces(uid, activeInterfaces)
    }

    // ========================================================================
    // 状态查询
    // ========================================================================

    /**
     * 查询应用当前断网状态
     *
     * 通过rootBridge查询指定UID的iptables规则，判断WiFi和蜂窝是否分别被阻断。
     *
     * @param uid 目标应用的UID
     * @return BlockedState 包含WiFi和蜂窝的断网状态
     */
    suspend fun queryBlockState(uid: Int): BlockedState {
        Timber.tag(TAG).d("查询断网状态: uid=%d", uid)

        val rules = rootBridge.queryBlockRules(uid)

        // 判断WiFi是否被阻断：规则中包含wlan0接口的DROP规则
        val isBlockedWifi = rules.any { rule ->
            rule.contains(IFACE_WIFI) && rule.contains("DROP")
        }

        // 判断蜂窝是否被阻断：规则中包含rmnet_data*接口的DROP规则
        val isBlockedCellular = rules.any { rule ->
            rule.contains(IFACE_CELLULAR_PREFIX) && rule.contains("DROP")
        }

        Timber.tag(TAG).d(
            "断网状态: uid=%d, wifi=%b, cellular=%b",
            uid, isBlockedWifi, isBlockedCellular
        )

        return BlockedState(
            uid = uid,
            isBlockedWifi = isBlockedWifi,
            isBlockedCellular = isBlockedCellular
        )
    }

    // ========================================================================
    // 内部辅助方法
    // ========================================================================

    /**
     * 获取当前活跃的网络接口列表
     *
     * 通过rootBridge查询所有网卡信息，筛选出WiFi（wlan0）和蜂窝（rmnet_data*）接口。
     *
     * @return 活跃网络接口名列表，如 ["wlan0", "rmnet_data0"]
     */
    private suspend fun getActiveNetworkInterfaces(): List<String> {
        val interfaces = mutableListOf<String>()

        try {
            // 查询双卡网卡信息以获取蜂窝接口
            val simCards = rootBridge.getSimCardInterfaces()
            for (sim in simCards) {
                if (sim.interfaceName.isNotBlank()) {
                    interfaces.add(sim.interfaceName)
                }
            }

            // WiFi接口始终检测（即使当前未连接也需要在规则中记录）
            // 因为WiFi可能在断网后连接，规则需要提前就位
            interfaces.add(IFACE_WIFI)
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "获取活跃网络接口失败，使用默认列表")
            // 降级：返回默认接口列表
            interfaces.add(IFACE_WIFI)
            interfaces.add("${IFACE_CELLULAR_PREFIX}0")
        }

        return interfaces.distinct()
    }

    /**
     * 为指定UID在多个网卡接口上添加DROP规则
     *
     * 对每个接口同时添加INGRESS和EGRESS方向的DROP规则。
     * 如果部分规则添加失败，返回PartialSuccess；全部失败返回Error。
     *
     * @param uid 目标应用UID
     * @param interfaces 网卡接口列表
     * @return 汇总后的CommandResult
     */
    private suspend fun addBlockRulesForInterfaces(
        uid: Int,
        interfaces: List<String>
    ): CommandResult {
        var errorCount = 0
        var lastError: CommandResult.Error? = null
        val directions = Direction.entries

        for (iface in interfaces) {
            for (direction in directions) {
                val result = rootBridge.addBlockRule(
                    uid = uid,
                    iface = iface,
                    direction = direction
                )

                when (result) {
                    is CommandResult.Success -> {
                        Timber.tag(TAG).d(
                            "添加断网规则成功: uid=%d, iface=%s, dir=%s",
                            uid, iface, direction
                        )
                    }
                    is CommandResult.Error -> {
                        errorCount++
                        lastError = result
                        Timber.tag(TAG).w(
                            "添加断网规则失败: uid=%d, iface=%s, dir=%s, err=%s",
                            uid, iface, direction, result.message
                        )
                    }
                    is CommandResult.PartialSuccess -> {
                        // 部分成功也视为异常情况，记录日志
                        Timber.tag(TAG).w(
                            "添加断网规则部分成功: uid=%d, iface=%s, dir=%s, msg=%s",
                            uid, iface, direction, result.message
                        )
                    }
                }
            }
        }

        return when {
            errorCount == 0 -> CommandResult.Success
            errorCount < interfaces.size * directions.size -> {
                // 部分失败：成功一部分但不是全部
                CommandResult.PartialSuccess(
                    "成功添加 ${interfaces.size * directions.size - errorCount}/${interfaces.size * directions.size} 条规则，${errorCount}条失败"
                )
            }
            else -> lastError ?: CommandResult.Error("未知错误")
        }
    }

    /**
     * 为指定UID移除多个网卡接口上的DROP规则
     *
     * 对每个接口同时移除INGRESS和EGRESS方向的DROP规则。
     * 如果部分规则移除失败，返回PartialSuccess；全部失败返回Error。
     *
     * @param uid 目标应用UID
     * @param interfaces 网卡接口列表
     * @return 汇总后的CommandResult
     */
    private suspend fun removeBlockRulesForInterfaces(
        uid: Int,
        interfaces: List<String>
    ): CommandResult {
        if (interfaces.isEmpty()) {
            Timber.tag(TAG).d("无需移除规则：接口列表为空, uid=%d", uid)
            return CommandResult.Success
        }

        var errorCount = 0
        var lastError: CommandResult.Error? = null
        val directions = Direction.entries

        for (iface in interfaces) {
            for (direction in directions) {
                val result = rootBridge.removeBlockRule(
                    uid = uid,
                    iface = iface,
                    direction = direction
                )

                when (result) {
                    is CommandResult.Success -> {
                        Timber.tag(TAG).d(
                            "移除断网规则成功: uid=%d, iface=%s, dir=%s",
                            uid, iface, direction
                        )
                    }
                    is CommandResult.Error -> {
                        errorCount++
                        lastError = result
                        Timber.tag(TAG).w(
                            "移除断网规则失败: uid=%d, iface=%s, dir=%s, err=%s",
                            uid, iface, direction, result.message
                        )
                    }
                    is CommandResult.PartialSuccess -> {
                        Timber.tag(TAG).w(
                            "移除断网规则部分成功: uid=%d, iface=%s, dir=%s, msg=%s",
                            uid, iface, direction, result.message
                        )
                    }
                }
            }
        }

        return when {
            errorCount == 0 -> CommandResult.Success
            errorCount < interfaces.size * directions.size -> {
                CommandResult.PartialSuccess(
                    "成功移除 ${interfaces.size * directions.size - errorCount}/${interfaces.size * directions.size} 条规则，${errorCount}条失败"
                )
            }
            else -> lastError ?: CommandResult.Error("未知错误")
        }
    }
}

/**
 * 应用断网状态数据类
 *
 * 描述指定UID在WiFi和蜂窝两个维度的网络拦截状态。
 * 由AppNetworkController.queryBlockState()返回，供上层（如IdleAppDetector）判断。
 *
 * @property uid 目标应用的UID
 * @property isBlockedWifi WiFi网络是否已被阻断
 * @property isBlockedCellular 蜂窝网络是否已被阻断
 */
data class BlockedState(
    val uid: Int,
    val isBlockedWifi: Boolean,
    val isBlockedCellular: Boolean
) {
    /** 是否处于全网断网状态（WiFi + 蜂窝均被阻断） */
    val isFullyBlocked: Boolean get() = isBlockedWifi && isBlockedCellular

    /** 是否处于部分断网状态（仅阻断其中一个） */
    val isPartiallyBlocked: Boolean get() = isBlockedWifi xor isBlockedCellular

    /** 是否处于未断网状态 */
    val isNotBlocked: Boolean get() = !isBlockedWifi && !isBlockedCellular
}
