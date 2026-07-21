package com.liuyuguard.model

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

// ============================================================================
// UI层 -> 业务服务层的指令接口
// ============================================================================

/**
 * UI层指令下发接口
 * UI层通过此接口向业务服务层发送指令，禁止直接调用底层权限/网络操作
 */
interface ICommandDispatcher {
    /** 下发指令到业务服务层 */
    suspend fun dispatch(command: UiCommand): CommandResult
}

/**
 * 指令执行结果
 */
sealed class CommandResult {
    data object Success : CommandResult()
    data class Error(val message: String, val code: Int = -1) : CommandResult()
    data class PartialSuccess(val message: String) : CommandResult()
}

// ============================================================================
// 业务服务层 -> UI层的数据回传接口
// ============================================================================

/**
 * UI层数据观察接口
 * UI层通过此接口从业务服务层获取实时数据，禁止主动拉取
 */
interface ITrafficDataObserver {
    /** 流量总览实时数据流 */
    val trafficOverview: StateFlow<TrafficOverview?>

    /** 应用流量列表数据流 */
    val appTrafficList: StateFlow<List<AppTrafficInfo>>

    /** 双卡SIM流量数据流 */
    val simCardData: StateFlow<List<SimCardInfo>>

    /** 图表分时数据流 */
    val chartData: SharedFlow<List<HourlyTraffic>>

    /** 运行模式数据流 */
    val runMode: StateFlow<RunMode>

    /** 服务是否正在运行 */
    val isServiceRunning: StateFlow<Boolean>

    /** 骆驼高精度模式是否开启 */
    val isHighPrecisionMode: StateFlow<Boolean>
}

// ============================================================================
// 业务服务层 -> Root管理层的指令接口
// ============================================================================

/**
 * 业务层向Root管理层下发指令的接口
 */
interface IRootCommandBridge {
    /** 为指定UID添加网络拦截规则 */
    suspend fun addBlockRule(uid: Int, iface: String, direction: Direction): CommandResult

    /** 为指定UID移除网络拦截规则 */
    suspend fun removeBlockRule(uid: Int, iface: String, direction: Direction): CommandResult

    /** 查询指定UID的所有规则 */
    suspend fun queryBlockRules(uid: Int): List<String>

    /** 读取内核流量统计 */
    suspend fun readTrafficStats(): Map<Int, InterfaceTraffic>

    /** 获取指定UID的进程状态 */
    suspend fun getProcessState(uid: Int): List<ProcessState>

    /** 冻结指定UID的进程 */
    suspend fun freezeProcess(uid: Int): CommandResult

    /** 解冻指定UID的进程 */
    suspend fun unfreezeProcess(uid: Int): CommandResult

    /** 获取双卡网卡信息 */
    suspend fun getSimCardInterfaces(): List<SimCardInfo>

    /** 提升服务OOM优先级 */
    suspend fun boostServicePriority(pid: Int): CommandResult

    /** 持久化当前所有规则 */
    suspend fun persistRules(): CommandResult

    /** 从缓存恢复规则 */
    suspend fun restoreRules(): CommandResult
}

/** 网络方向 */
enum class Direction { INGRESS, EGRESS }

/** 网卡接口流量数据（扩展） */
data class InterfaceTraffic(
    val rxBytes: Long = 0L,
    val txBytes: Long = 0L,
    val rxPackets: Long = 0L,
    val txPackets: Long = 0L
)

/** 进程状态数据（扩展） */
data class ProcessState(
    val pid: Int,
    val uid: Int,
    val packageName: String,
    val isForeground: Boolean,
    val cpuUsage: Float = 0f
)

// ============================================================================
// Root管理层 -> 内核执行层的指令接口
// ============================================================================

/**
 * RootShellManager向内核执行层下发指令的接口
 */
interface IKernelExecutor {
    /** 执行Shell命令 */
    suspend fun executeCommand(command: String): ShellResult

    /** 执行多条Shell命令（原子性） */
    suspend fun executeCommands(commands: List<String>): List<ShellResult>

    /** 读取文件内容 */
    suspend fun readFile(path: String): String?

    /** 判断文件是否存在 */
    suspend fun fileExists(path: String): Boolean

    /** 写入文件 */
    suspend fun writeFile(path: String, content: String): Boolean
}

/**
 * Shell命令执行结果
 */
data class ShellResult(
    val success: Boolean,
    val stdout: String = "",
    val stderr: String = "",
    val exitCode: Int = -1
)

// ============================================================================
// 守护接口
// ============================================================================

/**
 * 服务守护接口（业务服务层内部使用）
 */
interface IServiceGuardian {
    /** 检测服务是否存活 */
    fun isServiceAlive(serviceName: String): Boolean

    /** 重启指定服务 */
    fun restartService(serviceName: String)

    /** 获取服务PID */
    fun getServicePid(serviceName: String): Int
}