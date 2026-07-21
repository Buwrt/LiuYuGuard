package com.liuyuguard.model

/**
 * UI层与业务服务层的指令通信协议
 * UI仅下发这些指令，不执行任何底层操作
 */
sealed class UiCommand {

    /** 服务控制 */
    data object StartTrafficService : UiCommand()
    data object StopTrafficService : UiCommand()

    /** 断网控制 */
    data class BlockAppNetwork(
        val uid: Int,
        val blockWifi: Boolean = true,
        val blockCellular: Boolean = true
    ) : UiCommand()

    data class UnblockAppNetwork(
        val uid: Int,
        val unblockWifi: Boolean = true,
        val unblockCellular: Boolean = true
    ) : UiCommand()

    /** 闲置应用控制 */
    data class SetIdleBlockEnabled(val enabled: Boolean, val timeoutMinutes: Int) : UiCommand()

    /** 骆驼高精度模式 */
    data class SetHighPrecisionMode(val enabled: Boolean) : UiCommand()

    /** 流量统计周期切换 */
    data class SwitchChartPeriod(val period: ChartPeriod) : UiCommand()

    /** 设置变更 */
    data class UpdateSettings(val key: String, val value: Any) : UiCommand()
}

enum class ChartPeriod { DAILY, WEEKLY, MONTHLY }