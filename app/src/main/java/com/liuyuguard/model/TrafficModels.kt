package com.liuyuguard.model

/**
 * 服务层向UI层回传的状态数据
 */
data class TrafficOverview(
    val totalRxBytes: Long = 0L,
    val totalTxBytes: Long = 0L,
    val todayRxBytes: Long = 0L,
    val todayTxBytes: Long = 0L,
    val wifiRxBytes: Long = 0L,
    val wifiTxBytes: Long = 0L,
    val cellularRxBytes: Long = 0L,
    val cellularTxBytes: Long = 0L,
    val blockedAppCount: Int = 0,
    val isServiceRunning: Boolean = false,
    val isHighPrecision: Boolean = false
)

data class AppTrafficInfo(
    val uid: Int,
    val packageName: String,
    val appName: String,
    val icon: String? = null,
    val rxBytes: Long = 0L,
    val txBytes: Long = 0L,
    val isBlockedWifi: Boolean = false,
    val isBlockedCellular: Boolean = false,
    val isBlockedTotal: Boolean get() = isBlockedWifi && isBlockedCellular,
    val isIdle: Boolean = false,
    val isForeground: Boolean = true,
    val simSlot: Int = -1
)

data class SimCardInfo(
    val slotIndex: Int,
    val carrierName: String = "",
    val displayName: String = "",
    val subscriptionId: Int = -1,
    val interfaceName: String = "",
    val rxBytes: Long = 0L,
    val txBytes: Long = 0L
)

data class HourlyTraffic(
    val hour: Int,
    val rxBytes: Long,
    val txBytes: Long
)

data class DailyTraffic(
    val dayOfWeek: Int,
    val rxBytes: Long,
    val txBytes: Long
)