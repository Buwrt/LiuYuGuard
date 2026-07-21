package com.liuyuguard.executor

import com.liuyuguard.model.*

/**
 * Netfilter/Iptables数据包过滤底层执行抽象类（小节6完整开发）
 *
 * 职责：精准执行放行/DROP指令
 * 禁止：直接与UI层通信
 */
abstract class IptablesExecutor {

    /**
     * 执行Iptables指令
     * @param command 完整的iptables命令字符串
     * @return 执行结果
     */
    abstract suspend fun execute(command: String): ShellResult

    /**
     * 为指定UID添加DROP规则
     * @param uid 应用UID
     * @param iface 网络接口名（wlan0/rmnet_data0等）
     * @param direction 流量方向（INGRESS入/EGRESS出）
     * @return 执行结果
     */
    abstract suspend fun addDropRule(uid: Int, iface: String, direction: Direction): ShellResult

    /**
     * 为指定UID删除DROP规则
     */
    abstract suspend fun removeDropRule(uid: Int, iface: String, direction: Direction): ShellResult

    /**
     * 查询指定UID的现有规则
     * @return 匹配的规则行列表
     */
    abstract suspend fun queryRules(uid: Int): List<String>

    /**
     * 初始化Iptables自定义链
     */
    abstract suspend fun initCustomChain(): ShellResult

    /**
     * 清理自定义链（应用卸载时调用）
     */
    abstract suspend fun cleanupCustomChain(): ShellResult
}

/**
 * 网卡硬件原始流量字节采集抽象类（小节6完整开发）
 *
 * 职责：对接xt_qtaguid内核模块读取流量数据
 * 数据源：/proc/net/xt_qtaguid/stats
 */
abstract class TrafficStatsReader {

    /**
     * 从/proc/net/xt_qtaguid/stats读取原始流量数据
     * @return 按UID和接口分类的流量字节数据 Map<UID, Map<InterfaceName, InterfaceTraffic>>
     */
    abstract suspend fun readQtaguidStats(): Map<Int, Map<String, InterfaceTraffic>>

    /**
     * 读取指定网卡的接口级流量统计
     * @param iface 网络接口名
     */
    abstract suspend fun readInterfaceStats(iface: String): InterfaceTraffic

    /**
     * 读取所有活跃网络接口列表
     */
    abstract suspend fun getActiveInterfaces(): List<String>
}

/**
 * 进程状态监听抽象类（小节6完整开发）
 *
 * 职责：内核态监听进程CPU占用、APP前台/后台生命周期状态
 * 数据源：/proc/[pid]/、/proc/net/
 */
abstract class ProcessMonitor {

    /**
     * 获取指定UID下所有进程的状态列表
     */
    abstract suspend fun getProcessStatesForUid(uid: Int): List<ProcessState>

    /**
     * 判断指定UID是否有前台进程
     */
    abstract suspend fun isUidForeground(uid: Int): Boolean

    /**
     * 获取指定PID的CPU占用率
     */
    abstract suspend fun getCpuUsage(pid: Int): Float

    /**
     * 获取指定UID关联的所有PID列表（主进程+后台子进程）
     */
    abstract suspend fun getPidsForUid(uid: Int): List<Int>
}