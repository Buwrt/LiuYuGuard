package com.liuyuguard.manager

import com.liuyuguard.manager.modules.*
import com.liuyuguard.model.*

/**
 * RootShellManager全局单例调度管理器抽象父类
 *
 * 架构位置：层级3（Root指令管理层）
 * 职责：
 * 1. 统一收口所有Shell指令
 * 2. 指令排队、异常重试、权限校验
 * 3. 规则持久化缓存
 * 4. 隔离UI层和内核层的直接交互
 *
 * 包含5个子模块（通过接口解耦）：
 * - IUidManager: UID管理
 * - IIptablesManager: Iptables防火墙
 * - IKernelFileReader: 内核文件读取
 * - IProcessController: 进程管控
 * - ISimCardManager: 双卡网卡区分
 *
 * 禁止：
 * - 直接向UI层发送数据（通过层级2桥接）
 * - 在非Root/Shizuku环境下执行命令
 */
abstract class BaseRootShellManager : IRootCommandBridge {

    /** 子模块1：UID管理 */
    abstract val uidManager: IUidManager

    /** 子模块2：Iptables防火墙 */
    abstract val iptablesManager: IIptablesManager

    /** 子模块3：内核文件读取 */
    abstract val kernelReader: IKernelFileReader

    /** 子模块4：进程管控 */
    abstract val processController: IProcessController

    /** 子模块5：双卡网卡区分 */
    abstract val simCardManager: ISimCardManager

    // ========================================================================
    // 生命周期
    // ========================================================================

    /**
     * 初始化RootShellManager
     * @return 是否初始化成功
     */
    abstract suspend fun initialize(): Boolean

    /**
     * 释放所有资源
     */
    abstract fun destroy()

    /**
     * 检查是否已初始化
     */
    abstract fun isInitialized(): Boolean

    // ========================================================================
    // 指令执行核心（带排队、重试、权限校验）
    // ========================================================================

    /**
     * 执行Shell命令（带重试和排队）
     * @param command Shell命令
     * @param retryCount 重试次数（默认2次）
     * @return 执行结果
     */
    abstract suspend fun executeShell(command: String, retryCount: Int = 2): ShellResult

    /**
     * 批量执行Shell命令（原子性）
     * @param commands Shell命令列表
     * @return 每条命令的执行结果
     */
    abstract suspend fun executeShellBatch(commands: List<String>): List<ShellResult>

    // ========================================================================
    // IRootCommandBridge 实现（层级2调用入口）
    // ========================================================================

    override suspend fun addBlockRule(uid: Int, iface: String, direction: Direction): CommandResult {
        return iptablesManager.addDropRule(uid, iface, direction)
    }

    override suspend fun removeBlockRule(uid: Int, iface: String, direction: Direction): CommandResult {
        return iptablesManager.removeDropRule(uid, iface, direction)
    }

    override suspend fun queryBlockRules(uid: Int): List<String> {
        return iptablesManager.queryRules(uid)
    }

    override suspend fun readTrafficStats(): Map<Int, InterfaceTraffic> {
        return kernelReader.parseTrafficStats().mapValues { (_, interfaces) ->
            // 汇总一个UID下所有接口的流量
            interfaces.values.fold(InterfaceTraffic()) { acc, traffic ->
                InterfaceTraffic(
                    rxBytes = acc.rxBytes + traffic.rxBytes,
                    txBytes = acc.txBytes + traffic.txBytes,
                    rxPackets = acc.rxPackets + traffic.rxPackets,
                    txPackets = acc.txPackets + traffic.txPackets
                )
            }
        }
    }

    override suspend fun getProcessState(uid: Int): List<ProcessState> {
        return processController.getProcessStates(uid)
    }

    override suspend fun freezeProcess(uid: Int): CommandResult {
        return processController.freezeUid(uid)
    }

    override suspend fun unfreezeProcess(uid: Int): CommandResult {
        return processController.unfreezeUid(uid)
    }

    override suspend fun getSimCardInterfaces(): List<SimCardInfo> {
        return simCardManager.getSimCardInfo()
    }

    override suspend fun boostServicePriority(pid: Int): CommandResult {
        // 通过Shell修改OOM优先级
        return executeShell("echo -${com.liuyuguard.util.Constants.OOM_ADJ_FOREGROUND_SERVICE} > /proc/$pid/oom_score_adj")
            .let {
                if (it.success) CommandResult.Success else CommandResult.Error(it.stderr)
            }
    }

    override suspend fun persistRules(): CommandResult {
        return iptablesManager.persistRules()
    }

    override suspend fun restoreRules(): CommandResult {
        return iptablesManager.restoreRules()
    }
}