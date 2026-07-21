package com.liuyuguard.manager.modules

import com.liuyuguard.manager.RootShellManagerImpl
import com.liuyuguard.model.InterfaceTraffic
import com.liuyuguard.util.Constants

/**
 * 内核文件读取模块实现
 *
 * 职责：
 * - 只读 /proc/net/xt_qtaguid/stats 原始流量字节数据
 * - 解析 xt_qtaguid 格式为结构化数据（UID + 接口 -> 流量）
 * - 读取 /proc/net/dev 获取接口级流量汇总
 * - 读取 /proc/[pid]/stat 获取进程信息
 *
 * 所有文件读取通过 RootShellManagerImpl.executeShell("cat ...") 执行，
 * 因为 /proc/net/xt_qtaguid/stats 等文件需要Root权限才能完整读取。
 *
 * 注意：本模块仅执行只读操作，不修改任何内核状态
 */
class KernelFileReaderImpl : IKernelFileReader {

    // ========================================================================
    // IKernelFileReader 接口实现
    // ========================================================================

    /**
     * 读取xt_qtaguid原始数据
     *
     * 通过Root执行 cat /proc/net/xt_qtaguid/stats 获取原始内容
     *
     * @return 原始文件内容字符串，读取失败返回null
     */
    override suspend fun readQtaguidStats(): String? {
        val result = RootShellManagerImpl.executeShell("cat ${Constants.PROC_QTAGUID_STATS}")
        return if (result.success && result.stdout.isNotBlank()) {
            result.stdout
        } else {
            null
        }
    }

    /**
     * 解析流量统计数据为结构化数据
     *
     * 解析 /proc/net/xt_qtaguid/stats 的格式。
     * 文件头部有一行或两行标签行（以 "idx" 开头），后续每行是一条记录。
     *
     * 每行格式（空格分隔）：
     * idx iface acct_tag_hex uid_int acct_tag_uid ... rx_bytes rx_packets ... tx_bytes tx_packets ...
     *
     * 字段索引（以标准Android内核为准）：
     * - [0] idx: 索引号
     * - [1] iface: 网卡接口名（如 wlan0, rmnet_data0）
     * - [2] acct_tag_hex: 计费标签（十六进制，"0x0"表示无标签）
     * - [3] uid_int: UID（十进制整数）
     * - [5] rx_bytes: 接收字节数
     * - [6] rx_packets: 接收包数
     * - [7] tx_bytes: 发送字节数（某些内核版本字段位置可能不同）
     * - [8] tx_packets: 发送包数
     *
     * 注意：不同内核版本字段顺序可能略有差异，
     * 这里按主流Android内核（4.14+）的字段顺序解析
     *
     * @return Map<UID, Map<接口名, InterfaceTraffic>>
     */
    override suspend fun parseTrafficStats(): Map<Int, Map<String, InterfaceTraffic>> {
        val raw = readQtaguidStats() ?: return emptyMap()
        val result = mutableMapOf<Int, MutableMap<String, InterfaceTraffic>>()

        val lines = raw.lines()
        for (line in lines) {
            // 跳过标签行和空行
            if (line.isBlank() || line.startsWith("idx") || line.startsWith("Iface")) {
                continue
            }

            val fields = line.trim().split("\\s+".toRegex())
            if (fields.size < 9) continue

            // 跳过UID为0的记录（内核/系统进程）
            val uid = fields[3].toIntOrNull() ?: continue
            if (uid <= 0) continue

            // 跳过无标签的计费记录以外的标签记录
            // acct_tag_hex 在 [2]，"0x0" 表示无标签（正常的per-UID流量统计）
            val acctTag = fields[2]
            if (acctTag != "0x0") continue

            val iface = fields[1]
            // 跳过空接口名
            if (iface.isBlank()) continue

            // 解析流量数据
            // rx_bytes在 [5], rx_packets在 [6], tx_bytes在 [7], tx_packets在 [8]
            val rxBytes = fields[5].toLongOrNull() ?: 0L
            val rxPackets = fields[6].toLongOrNull() ?: 0L
            val txBytes = fields[7].toLongOrNull() ?: 0L
            val txPackets = fields[8].toLongOrNull() ?: 0L

            // 跳过全零记录
            if (rxBytes == 0L && txBytes == 0L) continue

            // 构建流量数据
            val traffic = InterfaceTraffic(
                rxBytes = rxBytes,
                txBytes = txBytes,
                rxPackets = rxPackets,
                txPackets = txPackets
            )

            // 合并到结果中（同一UID+接口可能有多个计数器条目，累加）
            val ifaceMap = result.getOrPut(uid) { mutableMapOf() }
            val existing = ifaceMap[iface]
            if (existing != null) {
                ifaceMap[iface] = InterfaceTraffic(
                    rxBytes = existing.rxBytes + traffic.rxBytes,
                    txBytes = existing.txBytes + traffic.txBytes,
                    rxPackets = existing.rxPackets + traffic.rxPackets,
                    txPackets = existing.txPackets + traffic.txPackets
                )
            } else {
                ifaceMap[iface] = traffic
            }
        }

        return result
    }

    /**
     * 读取/proc/net/dev接口级流量
     *
     * /proc/net/dev 文件格式：
     * - 前两行是表头
     * - 每行格式：  iface: rx_bytes rx_packets rx_errs rx_drop rx_fifo rx_frame rx_compressed rx_multicast tx_bytes tx_packets ...
     *
     * 例如：
     *   wlan0:  12345678  1000  0  0  0  0  0  100  8765432  800  0  0  0  0  0  0
     *
     * @return Map<接口名, InterfaceTraffic>
     */
    override suspend fun readProcNetDev(): Map<String, InterfaceTraffic> {
        val result = RootShellManagerImpl.executeShell("cat /proc/net/dev")
        if (!result.success || result.stdout.isBlank()) {
            return emptyMap()
        }

        val trafficMap = mutableMapOf<String, InterfaceTraffic>()
        val lines = result.stdout.lines()

        for (line in lines) {
            // 跳过表头行（前两行）和空行
            if (line.isBlank() || !line.contains(":")) continue
            if (line.contains("Inter-") || line.contains("face") || line.contains("Receive")) continue

            // 分割接口名和数据部分
            val colonIndex = line.indexOf(':')
            val iface = line.substring(0, colonIndex).trim()
            val dataPart = line.substring(colonIndex + 1).trim()

            // 数据部分以空格分隔
            val fields = dataPart.split("\\s+".toRegex())
            if (fields.size < 10) continue

            // 字段顺序（标准Linux /proc/net/dev格式）：
            // [0] rx_bytes, [1] rx_packets, [2] rx_errs, [3] rx_drop,
            // [4] rx_fifo, [5] rx_frame, [6] rx_compressed, [7] rx_multicast,
            // [8] tx_bytes, [9] tx_packets, ...
            val rxBytes = fields[0].toLongOrNull() ?: 0L
            val rxPackets = fields[1].toLongOrNull() ?: 0L
            val txBytes = fields[8].toLongOrNull() ?: 0L
            val txPackets = fields[9].toLongOrNull() ?: 0L

            trafficMap[iface] = InterfaceTraffic(
                rxBytes = rxBytes,
                txBytes = txBytes,
                rxPackets = rxPackets,
                txPackets = txPackets
            )
        }

        return trafficMap
    }

    /**
     * 读取/proc/[pid]/stat获取进程信息
     *
     * /proc/[pid]/stat 文件包含进程的状态信息，
     * 字段以空格分隔，第二个字段是进程名（括号包裹）。
     *
     * @param pid 进程ID
     * @return 文件原始内容字符串，读取失败返回null
     */
    override suspend fun readProcessStat(pid: Int): String? {
        val result = RootShellManagerImpl.executeShell("cat /proc/$pid/stat 2>/dev/null")
        return if (result.success && result.stdout.isNotBlank()) {
            result.stdout
        } else {
            null
        }
    }
}