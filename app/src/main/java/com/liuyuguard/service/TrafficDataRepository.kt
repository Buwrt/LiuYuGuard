package com.liuyuguard.service

import com.liuyuguard.base.LiuYuApplication
import com.liuyuguard.model.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 流量数据仓库 - ITrafficDataObserver的实现
 *
 * 作为业务服务层与UI层之间的数据中转站，持有所有StateFlow数据。
 * UI层（ViewModel）通过此仓库收集实时数据，业务服务层通过更新方法推送最新状态。
 *
 * 设计原则：
 * - 单向数据流：服务层写入 -> UI层读取
 * - 所有数据变更通过更新方法进行，保证数据一致性
 * - 运行模式从Application全局状态直接获取，确保唯一真相源
 */
class TrafficDataRepository : ITrafficDataObserver {

    // ========================================================================
    // 流量总览
    // ========================================================================

    private val _trafficOverview = MutableStateFlow<TrafficOverview?>(null)

    /** 流量总览实时数据流，首次可能为null，UI层需做空值判断 */
    override val trafficOverview: StateFlow<TrafficOverview?> = _trafficOverview.asStateFlow()

    // ========================================================================
    // 应用流量列表
    // ========================================================================

    private val _appTrafficList = MutableStateFlow<List<AppTrafficInfo>>(emptyList())

    /** 按流量排序的应用流量列表数据流 */
    override val appTrafficList: StateFlow<List<AppTrafficInfo>> = _appTrafficList

    // ========================================================================
    // 双卡SIM流量数据
    // ========================================================================

    private val _simCardData = MutableStateFlow<List<SimCardInfo>>(emptyList())

    /** 双卡SIM流量数据流 */
    override val simCardData: StateFlow<List<SimCardInfo>> = _simCardData

    // ========================================================================
    // 图表分时数据（SharedFlow：每次发射仅对当前收集者可见，不缓存历史）
    // ========================================================================

    private val _chartData = MutableSharedFlow<List<HourlyTraffic>>(extraBufferCapacity = 1)

    /** 图表分时数据流，新收集者不会收到历史数据 */
    override val chartData: SharedFlow<List<HourlyTraffic>> = _chartData.asSharedFlow()

    // ========================================================================
    // 运行模式（从Application全局状态获取，确保唯一真相源）
    // ========================================================================

    /** 运行模式数据流，代理Application中的全局运行模式 */
    override val runMode: StateFlow<RunMode>
        get() = LiuYuApplication.instance.runMode

    // ========================================================================
    // 服务运行状态
    // ========================================================================

    private val _isServiceRunning = MutableStateFlow(false)

    /** 主流量服务是否正在运行 */
    override val isServiceRunning: StateFlow<Boolean> = _isServiceRunning.asStateFlow()

    // ========================================================================
    // 骆驼高精度模式
    // ========================================================================

    private val _isHighPrecisionMode = MutableStateFlow(false)

    /** 骆驼高精度模式是否开启 */
    override val isHighPrecisionMode: StateFlow<Boolean> =
        _isHighPrecisionMode.asStateFlow()

    // ========================================================================
    // 数据更新方法（供业务服务层调用）
    // ========================================================================

    /**
     * 更新流量总览数据
     * @param overview 最新的流量总览信息，传入null表示清空
     */
    fun updateTrafficOverview(overview: TrafficOverview?) {
        _trafficOverview.value = overview
    }

    /**
     * 更新应用流量列表
     * @param list 最新的按流量排序的应用流量信息列表
     */
    fun updateAppTrafficList(list: List<AppTrafficInfo>) {
        _appTrafficList.value = list
    }

    /**
     * 更新双卡SIM流量数据
     * @param data 最新的SIM卡流量信息列表
     */
    fun updateSimCardData(data: List<SimCardInfo>) {
        _simCardData.value = data
    }

    /**
     * 发射图表分时数据（一次性事件，新收集者不回放）
     * @param hourlyList 每小时流量数据列表
     */
    fun emitChartData(hourlyList: List<HourlyTraffic>) {
        _chartData.tryEmit(hourlyList)
    }

    /**
     * 设置服务运行状态
     * @param running true表示服务正在运行，false表示已停止
     */
    fun setServiceRunning(running: Boolean) {
        _isServiceRunning.value = running
    }

    /**
     * 设置骆驼高精度模式开关
     * @param enabled true表示开启高精度模式，false表示关闭
     */
    fun setHighPrecisionMode(enabled: Boolean) {
        _isHighPrecisionMode.value = enabled
    }
}
