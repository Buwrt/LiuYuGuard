package com.liuyuguard.service

import com.liuyuguard.model.IRootCommandBridge
import com.liuyuguard.model.ProcessState
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap
import kotlin.collections.set

/**
 * 后台闲置应用断网检测器
 *
 * 实现后台闲置超时自动断网逻辑。
 * 周期性轮询所有应用的进程前后台状态，对超过设定时间未回到前台的后台应用
 * 自动下发全网断网规则 + 进程唤醒拦截，防止应用在后台偷跑流量。
 * 当应用重新回到前台时，自动撤销断网规则恢复正常网络。
 *
 * 设计原则：
 * - 闲置判定基于进程的前后台状态，而非流量阈值（流量阈值由UI层的AppDetailPage展示）
 * - 闲置计时器在应用切回前台时自动重置
 * - 仅对真正处于后台（isForeground=false）的应用计时
 * - 用户手动断网的应用（通过UI层指令）不会被闲置检测器自动恢复
 *
 * 使用约束：
 * - 仅限服务层内部调用，由MainTrafficService的闲置轮询任务驱动
 * - performIdleCheck()为suspend函数，需在协程中调用
 */
class IdleAppDetector(
    /** Root指令桥接，用于获取进程状态和下发网络拦截规则 */
    private val rootBridge: IRootCommandBridge,
    /** 网络控制器，用于执行断网/恢复操作 */
    private val networkController: AppNetworkController,
    /** 流量数据仓库，用于更新UI层的应用闲置状态 */
    private val repository: TrafficDataRepository
) {

    companion object {
        private const val TAG = "IdleAppDetector"

        /** 默认闲置超时：5分钟 */
        private const val DEFAULT_TIMEOUT_MS = 5 * 60_000L

        /** 需要排除的系统UID范围起始（系统进程不进行闲置检测） */
        private const val SYSTEM_UID_MIN = 1000

        /** 本应用自身UID（不对自己断网） */
        private const val SELF_UID_THRESHOLD = 10000
    }

    // ========================================================================
    // 状态管理
    // ========================================================================

    /**
     * 闲置计时器映射表
     * key: 应用UID
     * value: 该应用首次进入后台的时间戳（System.currentTimeMillis()）
     * 使用ConcurrentHashMap保证线程安全（闲置轮询和UI操作可能并发）
     */
    private val idleTimers = ConcurrentHashMap<Int, Long>()

    /**
     * 已被闲置检测器断网的应用集合
     * 用于区分"用户手动断网"和"闲置自动断网"，避免恢复时误操作用户手动设置
     */
    private val idleBlockedUids = ConcurrentHashMap<Int, Boolean>()

    /**
     * 闲置检测开关
     * 为false时performIdleCheck()直接返回，不执行任何检测逻辑
     */
    @Volatile
    private var enabled = false

    /**
     * 闲置超时时间（毫秒）
     * 默认5分钟，可通过setTimeoutMinutes()动态调整
     */
    @Volatile
    private var timeoutMs = DEFAULT_TIMEOUT_MS

    /**
     * 用户手动断网的UID集合（WiFi维度）
     * 闲置检测器不会自动恢复这些UID的WiFi网络
     * 由外部（CommandDispatcher）在用户手动操作时同步
     */
    val manuallyBlockedWifiUids = mutableSetOf<Int>()

    /**
     * 用户手动断网的UID集合（蜂窝维度）
     * 闲置检测器不会自动恢复这些UID的蜂窝网络
     * 由外部（CommandDispatcher）在用户手动操作时同步
     */
    val manuallyBlockedCellularUids = mutableSetOf<Int>()

    // ========================================================================
    // 核心检测逻辑
    // ========================================================================

    /**
     * 执行一次闲置检测轮询
     *
     * 完整流程：
     * 1. 获取所有应用的前后台状态（通过rootBridge获取进程列表）
     * 2. 遍历每个应用：
     *    a. 如果应用在前台 -> 重置其闲置计时器；如果之前被闲置断网则自动恢复
     *    b. 如果应用在后台 -> 检查是否超时；超时则下发全网断网规则+进程唤醒拦截
     * 3. 更新Repository中的应用闲置状态，供UI层展示
     *
     * 注意：
     * - 系统进程（UID < SYSTEM_UID_MIN）跳过
     * - 本应用自身跳过
     * - 用户手动断网的应用不会被闲置检测器恢复
     */
    suspend fun performIdleCheck() {
        // 未启用时直接返回
        if (!enabled) return

        Timber.tag(TAG).v("开始闲置检测轮询, 超时阈值=%dms", timeoutMs)

        try {
            // 步骤1: 收集所有运行中应用的进程状态
            val allProcessStates = collectAllProcessStates()
            if (allProcessStates.isEmpty()) {
                Timber.tag(TAG).v("未获取到任何进程状态，跳过本轮检测")
                return
            }

            // 按UID分组，判断每个UID是否有前台进程
            val uidForegroundMap = mutableMapOf<Int, Boolean>()
            for (process in allProcessStates) {
                // 跳过系统UID和自身
                if (process.uid < SYSTEM_UID_MIN) continue
                // 只处理应用UID
                if (process.uid < SELF_UID_THRESHOLD) continue

                // 如果该UID有任意一个前台进程，则标记为前台
                val currentForeground = uidForegroundMap[process.uid] ?: false
                if (process.isForeground && !currentForeground) {
                    uidForegroundMap[process.uid] = true
                } else if (!uidForegroundMap.containsKey(process.uid)) {
                    uidForegroundMap[process.uid] = false
                }
            }

            val now = System.currentTimeMillis()

            // 步骤2: 遍历所有已知UID，执行闲置判断
            for ((uid, isForeground) in uidForegroundMap) {
                if (isForeground) {
                    // 2a. 应用在前台：重置闲置计时器
                    handleForegroundApp(uid, now)
                } else {
                    // 2b. 应用在后台：检查是否超时
                    handleBackgroundApp(uid, now)
                }
            }

            // 步骤3: 清理已不再运行的应用的计时器
            cleanupStaleTimers(uidForegroundMap.keys)

            // 步骤4: 更新Repository中的应用闲置状态
            updateRepositoryIdleStates(uidForegroundMap, now)

            Timber.tag(TAG).v("闲置检测轮询完成, 计时器数量=%d, 闲置断网数量=%d",
                idleTimers.size, idleBlockedUids.size)

        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "闲置检测轮询异常")
        }
    }

    // ========================================================================
    // 公开配置方法
    // ========================================================================

    /**
     * 设置闲置检测开关
     * @param enabled true启用闲置检测，false停止检测
     */
    fun setEnabled(enabled: Boolean) {
        this.enabled = enabled
        Timber.tag(TAG).i("闲置检测开关: %b", enabled)

        if (!enabled) {
            // 关闭时清空所有闲置状态（不自动恢复，保持当前规则）
            Timber.tag(TAG).i("闲置检测已关闭，保留当前断网规则不清除")
        }
    }

    /**
     * 设置闲置超时时间
     * @param minutes 超时分钟数，最小1分钟，最大60分钟
     */
    fun setTimeoutMinutes(minutes: Int) {
        val clamped = minutes.coerceIn(1, 60)
        this.timeoutMs = clamped * 60_000L
        Timber.tag(TAG).i("闲置超时设置: %d分钟", clamped)
    }

    /**
     * 标记某个UID为用户手动断网
     * 闲置检测器在应用回到前台时不会自动恢复手动断网的网络
     *
     * @param uid 应用UID
     * @param blockWifi 是否手动断WiFi
     * @param blockCellular 是否手动断蜂窝
     */
    fun markManualBlock(uid: Int, blockWifi: Boolean, blockCellular: Boolean) {
        if (blockWifi) manuallyBlockedWifiUids.add(uid)
        if (blockCellular) manuallyBlockedCellularUids.add(uid)
    }

    /**
     * 取消某个UID的手动断网标记
     *
     * @param uid 应用UID
     * @param unblockWifi 是否取消手动WiFi断网
     * @param unblockCellular 是否取消手动蜂窝断网
     */
    fun unmarkManualBlock(uid: Int, unblockWifi: Boolean, unblockCellular: Boolean) {
        if (unblockWifi) manuallyBlockedWifiUids.remove(uid)
        if (unblockCellular) manuallyBlockedCellularUids.remove(uid)
    }

    // ========================================================================
    // 内部处理方法
    // ========================================================================

    /**
     * 处理前台应用
     *
     * 1. 清除该UID的闲置计时器
     * 2. 如果该UID之前被闲置检测器自动断网，则恢复网络
     *
     * @param uid 应用UID
     * @param now 当前时间戳
     */
    private suspend fun handleForegroundApp(uid: Int, now: Long) {
        val wasIdleBlocked = idleBlockedUids.remove(uid) == true

        // 清除闲置计时器
        idleTimers.remove(uid)

        if (wasIdleBlocked) {
            // 应用从闲置状态回到前台，自动恢复网络
            Timber.tag(TAG).i("应用回到前台，恢复网络: uid=%d", uid)

            // 仅恢复非手动断网的维度
            val unblockWifi = !manuallyBlockedWifiUids.contains(uid)
            val unblockCellular = !manuallyBlockedCellularUids.contains(uid)

            when {
                unblockWifi && unblockCellular -> {
                    networkController.unblockAll(uid)
                }
                unblockWifi -> {
                    networkController.unblockWifi(uid)
                }
                unblockCellular -> {
                    networkController.unblockCellular(uid)
                }
                else -> {
                    // 全部都是手动断网，不自动恢复
                    Timber.tag(TAG).d("应用回到前台，但所有维度均为手动断网，不自动恢复: uid=%d", uid)
                }
            }
        }
    }

    /**
     * 处理后台应用
     *
     * 1. 如果该UID没有闲置计时器，创建一个
     * 2. 检查是否超过超时阈值
     * 3. 超时则下发全网断网规则 + 冻结进程（如果尚未断网）
     *
     * @param uid 应用UID
     * @param now 当前时间戳
     */
    private suspend fun handleBackgroundApp(uid: Int, now: Long) {
        // 跳过已被用户手动全网断网的应用（避免重复操作）
        if (manuallyBlockedWifiUids.contains(uid) && manuallyBlockedCellularUids.contains(uid)) {
            return
        }

        // 记录或获取首次进入后台的时间戳
        val firstIdleTimestamp = idleTimers.getOrPut(uid) { now }
        val idleDuration = now - firstIdleTimestamp

        if (idleDuration >= timeoutMs) {
            // 已超时，检查是否已被闲置断网
            if (idleBlockedUids.contains(uid)) {
                // 已经被闲置断网，无需重复操作
                Timber.tag(TAG).v("应用已处于闲置断网状态，跳过: uid=%d", uid)
                return
            }

            // 下发闲置断网规则
            Timber.tag(TAG).i(
                "应用闲置超时，下发断网规则: uid=%d, 闲置时长=%dms, 阈值=%dms",
                uid, idleDuration, timeoutMs
            )

            // 对非手动断网的维度执行断网
            val blockWifi = !manuallyBlockedWifiUids.contains(uid)
            val blockCellular = !manuallyBlockedCellularUids.contains(uid)

            when {
                blockWifi && blockCellular -> {
                    // 全网断网
                    networkController.blockAll(uid)
                }
                blockWifi -> {
                    // 仅WiFi断网
                    networkController.blockWifi(uid)
                }
                blockCellular -> {
                    // 仅蜂窝断网
                    networkController.blockCellular(uid)
                }
                else -> {
                    // 已被手动全网断网，跳过
                    return
                }
            }

            // 标记为闲置断网
            idleBlockedUids[uid] = true

            // 可选：冻结进程以进一步省电（防止应用通过wakeLock唤醒CPU）
            try {
                rootBridge.freezeProcess(uid)
                Timber.tag(TAG).d("已冻结闲置应用进程: uid=%d", uid)
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "冻结进程失败，仅断网: uid=%d", uid)
                // 进程冻结失败不阻断断网逻辑
            }
        }
    }

    /**
     * 清理已不再运行的进程的计时器
     *
     * 移除idleTimers中不属于当前活跃进程UID的条目，
     * 避免计时器映射表无限膨胀。
     *
     * @param activeUids 当前活跃的UID集合
     */
    private fun cleanupStaleTimers(activeUids: Set<Int>) {
        val staleUids = idleTimers.keys.filter { it !in activeUids }
        for (uid in staleUids) {
            idleTimers.remove(uid)
            // 同时清理闲置断网标记（进程已退出，规则由iptables管理）
            idleBlockedUids.remove(uid)
        }
        if (staleUids.isNotEmpty()) {
            Timber.tag(TAG).v("清理过期计时器: %s", staleUids.joinToString())
        }
    }

    /**
     * 更新Repository中的应用闲置状态
     *
     * 将当前闲置检测的结果同步到TrafficDataRepository，
     * UI层通过AppTrafficInfo.isIdle字段展示应用闲置标记。
     *
     * @param uidForegroundMap UID -> 是否前台 的映射
     * @param now 当前时间戳
     */
    private fun updateRepositoryIdleStates(
        uidForegroundMap: Map<Int, Boolean>,
        now: Long
    ) {
        val currentList = repository.appTrafficList.value.toMutableList()
        var updated = false

        for (i in currentList.indices) {
            val app = currentList[i]
            val isForeground = uidForegroundMap[app.uid] ?: true // 默认视为前台
            val firstIdleTs = idleTimers[app.uid]
            val idleRemaining = if (!isForeground && firstIdleTs != null) {
                val remaining = timeoutMs - (now - firstIdleTs)
                if (remaining > 0) remaining else 0L
            } else {
                0L
            }

            val isIdle = idleBlockedUids.contains(app.uid)

            if (app.isIdle != isIdle || app.isForeground != isForeground) {
                currentList[i] = app.copy(
                    isIdle = isIdle,
                    isForeground = isForeground
                )
                updated = true
            }
        }

        if (updated) {
            repository.updateAppTrafficList(currentList)
        }
    }

    /**
     * 收集所有运行中应用的进程状态
     *
     * 遍历当前已知应用UID，通过rootBridge获取每个UID的进程列表。
     * 返回去重后的ProcessState列表。
     *
     * @return 所有活跃进程的状态列表
     */
    private suspend fun collectAllProcessStates(): List<ProcessState> {
        val allStates = mutableListOf<ProcessState>()

        // 从当前Repository中的应用列表获取已知UID
        val knownApps = repository.appTrafficList.value
        for (app in knownApps) {
            try {
                val states = rootBridge.getProcessState(app.uid)
                allStates.addAll(states)
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "获取进程状态失败: uid=%d", app.uid)
            }
        }

        return allStates
    }
}
