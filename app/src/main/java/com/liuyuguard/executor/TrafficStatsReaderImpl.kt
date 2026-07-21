package com.liuyuguard.executor

import com.liuyuguard.model.InterfaceTraffic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 流量统计读取器实现
 *
 * 架构位置：层级4（Linux内核底层执行层）- 网卡硬件原始流量字节采集
 * 职责：
 * - 通过Root Shell读取 /proc/net/xt_qtaguid/stats 获取按UID分接口的流量数据
 * - 通过Root Shell读取 /proc/net/dev 获取网卡接口级原始流量
 * - 通过Root Shell读取 /sys/class/net/ 获取活跃网络接口列表
 *
 * 数据源：
 * - xt_qtaguid: Android内核模块，提供基于UID和网卡接口的精细流量统计
 * - /proc/net/dev: 网络设备级统计（rx_bytes/tx_bytes）
 * - /sys/class/net/: 系统网络接口目录
 *
 * 注意：需要Root权限才能读取xt_qtaguid统计数据
 *
 * @param kernelExecutor 内核执行器实例，用于执行Root Shell命令
 */
class TrafficStatsReaderImpl(
    private val kernelExecutor: BaseKernelExecutor
) : TrafficStatsReader() {

    companion object {
        /** xt_qtaguid统计文件路径 */
        private const val PATH_QTAGUID_STATS = "/proc/net/xt_qtaguid/stats"

        /** 网络设备统计文件路径 */
        private const val PATH_PROC_NET_DEV = "/proc/net/dev"

        /** 系统网络接口目录路径 */
        private const val PATH_SYS_CLASS_NET = "/sys/class/net"

        /** 回环接口名称，始终排除 */
        private const val INTERFACE_LO = "lo"

        /** xt_qtaguid/stats 文件中的数据行字段索引 */
        private const val IDX_IFACE = 1       // 网络接口名
        private const val IDX_UID = 3          // 应用UID
        private const val IDX_RX_BYTES = 5     // 接收字节数
        private const val IDX_TX_BYTES = 7     // 发送字节数
        private const val IDX_RX_PACKETS = 6  // 接收包数
        private const val IDX_TX_PACKETS = 8  // 发送包数
    }

    // ========================================================================
    // xt_qtaguid流量数据读取
    // ========================================================================

    /**
     * 从 /proc/net/xt_qtaguid/stats 读取原始流量数据
     *
     * 文件格式（每行一条记录）：
     * idx iface acct_tag_hex uid_int ... rx_bytes rx_packets tx_bytes tx_packets ...
     *
     * 解析规则：
     * - 跳过以 "idx" 开头的表头行
     * - 使用空白字符分割每行
     * - 字段位置：[1]=iface, [3]=uid, [5]=rx_bytes, [6]=rx_packets, [7]=tx_bytes, [8]=tx_packets
     * - 跳过UID为0的记录（系统/内核流量，不属于任何APP）
     *
     * 返回结构：Map<UID, Map<InterfaceName, InterfaceTraffic>>
     * 外层Map按UID分类，内层Map按网卡接口分类，支持双卡场景下分别统计。
     *
     * @return 按UID和接口分类的流量字节数据
     */
    override suspend fun readQtaguidStats(): Map<Int, Map<String, InterfaceTraffic>> =
        withContext(Dispatchers.IO) {
            val result = kernelExecutor.executeCommand("cat $PATH_QTAGUID_STATS")
            if (!result.success) {
                return@withContext emptyMap()
            }

            // 按UID分组，内层按接口名分组
            val statsMap = mutableMapOf<Int, MutableMap<String, InterfaceTraffic>>()

            for (line in result.stdout.lines()) {
                // 跳过表头行和空行
                if (line.isBlank() || line.startsWith("idx") || line.startsWith("No")) {
                    continue
                }

                val fields = line.split(Regex("\\s+"))
                if (fields.size < 9) {
                    continue // 字段不足，跳过此行
                }

                try {
                    val iface = fields[IDX_IFACE]
                    val uid = fields[IDX_UID].toInt()
                    val rxBytes = fields[IDX_RX_BYTES].toLong()
                    val rxPackets = fields[IDX_RX_PACKETS].toLong()
                    val txBytes = fields[IDX_TX_BYTES].toLong()
                    val txPackets = fields[IDX_TX_PACKETS].toLong()

                    // 跳过UID为0的系统流量
                    if (uid == 0) continue

                    // 获取或创建UID对应的接口Map
                    val ifaceMap = statsMap.getOrPut(uid) { mutableMapOf() }

                    // 同一UID同一接口可能有多行记录（不同tag），累加流量
                    val existing = ifaceMap[iface]
                    if (existing != null) {
                        ifaceMap[iface] = InterfaceTraffic(
                            rxBytes = existing.rxBytes + rxBytes,
                            txBytes = existing.txBytes + txBytes,
                            rxPackets = existing.rxPackets + rxPackets,
                            txPackets = existing.txPackets + txPackets
                        )
                    } else {
                        ifaceMap[iface] = InterfaceTraffic(
                            rxBytes = rxBytes,
                            txBytes = txBytes,
                            rxPackets = rxPackets,
                            txPackets = txPackets
                        )
                    }
                } catch (e: NumberFormatException) {
                    // 解析异常时跳过该行
                    continue
                }
            }

            // 转为不可变Map返回
            statsMap.mapValues { it.value.toMap() }
        }

    /**
     * 读取指定网卡的接口级流量统计
     *
     * 从 /proc/net/dev 读取指定接口的数据。
     * 文件格式示例：
     *   Inter-|   Receive                   |  Transmit
     *    face |bytes packets errs drop ... |bytes packets errs drop ...
     *   wlan0: 1234567  8901   0    0 ...  2345678  5678   0    0 ...
     *
     * @param iface 网络接口名（如 wlan0, rmnet_data0）
     * @return 该接口的流量数据（rx接收/tx发送），读取失败返回全零数据
     */
    override suspend fun readInterfaceStats(iface: String): InterfaceTraffic =
        withContext(Dispatchers.IO) {
            val result = kernelExecutor.executeCommand("cat $PATH_PROC_NET_DEV")
            if (!result.success) {
                return@withContext InterfaceTraffic()
            }

            // 在输出中查找目标接口行（格式：接口名: 后跟数据）
            for (line in result.stdout.lines()) {
                val trimmedLine = line.trim()
                // 匹配接口行，格式为 "wlan0: ..."
                if (trimmedLine.startsWith("$iface:") || trimmedLine.startsWith("$iface ")) {
                    // 提取冒号后面的数据部分
                    val dataPart = trimmedLine.substringAfter(":")
                    val fields = dataPart.trim().split(Regex("\\s+"))

                    // 字段顺序：rx_bytes[1] rx_packets[2] rx_errs[3] rx_drop[4] rx_fifo[5] rx_frame[6] rx_compressed[7] rx_multicast[8]
                    //           tx_bytes[9] tx_packets[10] ...
                    if (fields.size >= 10) {
                        try {
                            return@withContext InterfaceTraffic(
                                rxBytes = fields[0].toLongOrNull() ?: 0L,
                                txBytes = fields[8].toLongOrNull() ?: 0L,
                                rxPackets = fields[1].toLongOrNull() ?: 0L,
                                txPackets = fields[9].toLongOrNull() ?: 0L
                            )
                        } catch (e: NumberFormatException) {
                            break // 解析失败返回默认值
                        }
                    }
                }
            }

            // 未找到目标接口数据
            InterfaceTraffic()
        }

    /**
     * 读取所有活跃网络接口列表
     *
     * 通过列出 /sys/class/net/ 目录下的所有子目录来获取网络接口列表。
     * 同时排除以下接口：
     * - lo: 回环接口
     * - 以 "ifb" 开头的虚拟接口（ingress过滤桥接）
     * - 以 "sit" 开头的隧道接口
     *
     * @return 活跃网络接口名称列表（如 [wlan0, rmnet_data0, rmnet_data1]）
     */
    override suspend fun getActiveInterfaces(): List<String> = withContext(Dispatchers.IO) {
        // 列出 /sys/class/net/ 目录下所有子目录
        val result = kernelExecutor.executeCommand("ls $PATH_SYS_CLASS_NET")
        if (!result.success) {
            return@withContext emptyList()
        }

        result.stdout.lines()
            .map { it.trim() }
            .filter { iface ->
                iface.isNotBlank()
                    && iface != INTERFACE_LO                    // 排除回环接口
                    && !iface.startsWith("ifb")                 // 排除虚拟过滤桥接接口
                    && !iface.startsWith("sit")                 // 排除SIT隧道接口
                    && !iface.startsWith("ip6tnl")              // 排除IPv6隧道接口
            }
            .sorted()
    }
}
