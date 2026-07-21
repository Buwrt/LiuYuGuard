package com.liuyuguard.manager.modules

import com.liuyuguard.model.*

// ============================================================================
// 子模块1: UID管理模块接口
// ============================================================================

/**
 * UID管理模块
 *
 * 职责：
 * - 通过包名获取应用UID
 * - 绑定主进程+所有后台子PID
 * - 查询指定UID的包名映射
 */
interface IUidManager {
    /** 通过包名获取UID */
    suspend fun getUidForPackage(packageName: String): Int?

    /** 通过UID获取包名 */
    suspend fun getPackageForUid(uid: Int): String?

    /** 获取指定UID关联的所有PID列表 */
    suspend fun getPidsForUid(uid: Int): List<Int>

    /** 获取所有已安装应用的UID列表 */
    suspend fun getAllAppUids(): Map<Int, String> /* uid -> packageName */
}

// ============================================================================
// 子模块2: Iptables防火墙模块接口
// ============================================================================

/**
 * Iptables防火墙模块
 *
 * 职责：
 * - 新增/删除/查询网卡区分规则(wlan0/rmnet_data0)
 * - 规则本地持久化（断电恢复）
 * - 自定义链管理(LYGUARD_INGRESS / LYGUARD_EGRESS)
 */
interface IIptablesManager {
    /** 初始化自定义链 */
    suspend fun initChains(): CommandResult

    /** 清理自定义链 */
    suspend fun cleanupChains(): CommandResult

    /** 添加DROP规则 */
    suspend fun addDropRule(uid: Int, iface: String, direction: Direction): CommandResult

    /** 删除DROP规则 */
    suspend fun removeDropRule(uid: Int, iface: String, direction: Direction): CommandResult

    /** 查询指定UID的所有规则 */
    suspend fun queryRules(uid: Int): List<String>

    /** 查询自定义链中所有规则 */
    suspend fun listAllRules(): List<String>

    /** 持久化所有规则到本地文件 */
    suspend fun persistRules(): CommandResult

    /** 从本地文件恢复规则 */
    suspend fun restoreRules(): CommandResult
}

// ============================================================================
// 子模块3: 内核文件读取模块接口
// ============================================================================

/**
 * 内核文件读取模块
 *
 * 职责：
 * - 只读 /proc/net/xt_qtaguid/stats 原始流量字节数据
 * - 解析内核格式为结构化数据
 * - 只读操作，不修改任何内核状态
 */
interface IKernelFileReader {
    /** 读取xt_qtaguid原始数据 */
    suspend fun readQtaguidStats(): String?

    /** 解析流量统计数据为结构化数据 */
    suspend fun parseTrafficStats(): Map<Int, Map<String, InterfaceTraffic>>

    /** 读取/proc/net/dev 接口级流量 */
    suspend fun readProcNetDev(): Map<String, InterfaceTraffic>

    /** 读取/proc/[pid]/stat 获取进程信息 */
    suspend fun readProcessStat(pid: Int): String?
}

// ============================================================================
// 子模块4: 进程管控模块接口
// ============================================================================

/**
 * 进程管控模块
 *
 * 职责：
 * - 冻结广播唤醒（冻结指定UID的后台进程）
 * - 拦截关联启动
 * - 限制后台自启
 * - 进程前台/后台状态判定
 */
interface IProcessController {
    /** 判断指定UID是否有前台进程 */
    suspend fun isUidForeground(uid: Int): Boolean

    /** 获取指定UID下所有进程的状态 */
    suspend fun getProcessStates(uid: Int): List<ProcessState>

    /** 冻结指定UID的后台进程 */
    suspend fun freezeUid(uid: Int): CommandResult

    /** 解冻指定UID的进程 */
    suspend fun unfreezeUid(uid: Int): CommandResult

    /** 拦截指定UID的关联启动 */
    suspend fun blockStartByUid(uid: Int, targetUid: Int): CommandResult

    /** 恢复指定UID的关联启动 */
    suspend fun unblockStartByUid(uid: Int, targetUid: Int): CommandResult

    /** 获取指定PID的CPU占用率 */
    suspend fun getCpuUsage(pid: Int): Float
}

// ============================================================================
// 子模块5: 双卡网卡区分模块接口
// ============================================================================

/**
 * 双卡网卡区分模块
 *
 * 职责：
 * - 识别双卡卡槽
 * - 分离两张SIM独立网卡接口
 * - 绑定subscriptionId与网卡接口的映射
 */
interface ISimCardManager {
    /** 获取双卡信息列表 */
    suspend fun getSimCardInfo(): List<SimCardInfo>

    /** 获取指定卡槽的网卡接口名 */
    suspend fun getInterfaceForSlot(slotIndex: Int): String?

    /** 获取指定网卡接口所属卡槽 */
    suspend fun getSlotForInterface(iface: String): Int?

    /** 监听双卡切换事件 */
    fun setOnSimChangeListener(listener: (List<SimCardInfo>) -> Unit)
}