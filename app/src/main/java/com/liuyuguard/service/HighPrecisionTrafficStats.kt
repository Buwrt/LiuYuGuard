package com.liuyuguard.service

import com.liuyuguard.model.HourlyTraffic
import com.liuyuguard.model.IRootCommandBridge
import com.liuyuguard.model.InterfaceTraffic
import com.liuyuguard.util.Constants
import kotlinx.coroutines.*
import timber.log.Timber
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * 骆驼高精度分时流量统计系统
 *
 * 实现四级流量统计聚合链路：
 * 1. 10秒内核采样 - 通过IRootCommandBridge读取/proc/net/xt_qtaguid/stats获取原始内核数据
 * 2. 分钟聚合 - 每6次采样（60秒）进行一次分钟级结算
 * 3. 小时滚动汇总 - 每小时归档一次，生成24小时趋势数据
 * 4. 24小时日报 - 保留最近24个小时的归档数据，用于UI图表展示
 *
 * 设计原则：
 * - 所有采样操作通过IRootCommandBridge下发，不直接读取内核文件
 * - 使用AtomicLong/AtomicInteger保证线程安全，支持并发采样
 * - 通过TrafficDataRepository向UI层发射图表数据，遵循单向数据流架构
 *
 * 使用约束：
 * - 仅限Root模式下启用（Shizuku模式精度不足，不应使用此组件）
 * - 生命周期由BaseTaskScheduler统一调度
 */
class HighPrecisionTrafficStats(
    /** Root指令桥接，用于读取内核流量统计 */
    private val rootBridge: IRootCommandBridge,
    /** 流量数据仓库，用于向UI层发射图表数据 */
    private val repository: TrafficDataRepository
) {
    companion object {
        private const val TAG = "HighPrecisionTraffic"

        /** 10秒采样周期（毫秒） */
        private const val SAMPLING_INTERVAL_MS = Constants.SAMPLING_INTERVAL_MS

        /** 分钟结算周期（毫秒） */
        private const val MINUTE_SETTLE_INTERVAL_MS = Constants.MINUTE_SETTLE_MS

        /** 小时归档周期（毫秒） */
        private const val HOUR_ARCHIVE_INTERVAL_MS = Constants.HOUR_ARCHIVE_MS

        /** 24小时日报保留条数上限 */
        private const val DAILY_REPORT_MAX_HOURS = 24
    }

    // ========================================================================
    // 协程作用域与任务管理
    // ========================================================================

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** 采样任务Job */
    private var samplingJob: Job? = null

    /** 分钟结算任务Job */
    private var minuteSettleJob: Job? = null

    /** 小时归档任务Job */
    private var hourArchiveJob: Job? = null

    // ========================================================================
    // 分钟聚合器（一级聚合：10秒采样 -> 分钟数据）
    // ========================================================================

    /** 分钟级接收字节数累加器 */
    private val minuteRxBytes = AtomicLong(0L)

    /** 分钟级发送字节数累加器 */
    private val minuteTxBytes = AtomicLong(0L)

    /** 分钟级采样次数计数器 */
    private val minuteSampleCount = AtomicInteger(0)

    // ========================================================================
    // 小时聚合器（二级聚合：分钟数据 -> 小时数据）
    // ========================================================================

    /** 小时聚合器映射表：小时（0~23）-> 对应的聚合器 */
    private val hourlyAccumulator = mutableMapOf<Int, HourlyAccumulator>()

    // ========================================================================
    // 日报数据（三级存储：24小时滚动归档）
    // ========================================================================

    /** 24小时日报数据列表，按小时升序排列 */
    private val dailyReport = mutableListOf<HourlyTraffic>()

    // ========================================================================
    // 上次采样缓存（用于计算增量）
    // ========================================================================

    /** 上一次采样的原始流量数据（UID -> InterfaceTraffic） */
    private var lastSampleData: Map<Int, InterfaceTraffic> = emptyMap()

    /** 是否已完成首次采样（首次采样不计算增量，仅记录基准值） */
    private var isFirstSample: Boolean = true

    // ========================================================================
    // 核心采样逻辑
    // ========================================================================

    /**
     * 执行10秒内核采样
     *
     * 采样流程：
     * 1. 通过rootBridge.readTrafficStats()从内核获取原始流量数据
     * 2. 与上次采样数据做差值，计算各UID在各接口上的增量流量
     * 3. 累加增量到分钟聚合器（minuteRxBytes / minuteTxBytes）
     * 4. 更新lastSampleData缓存
     *
     * 注意事项：
     * - 首次采样不计算增量，仅记录基准值
     * - 内核计数器在设备重启后会归零，需要处理溢出/重置检测
     * - 采样失败不影响聚合器状态，仅记录日志
     */
    suspend fun performSampling() {
        Timber.tag(TAG).d("开始执行10秒内核采样")

        try {
            // 1. 从内核读取原始流量统计
            val currentData = rootBridge.readTrafficStats()

            if (currentData.isEmpty()) {
                Timber.tag(TAG).w("内核采样返回空数据，跳过本次采样")
                return
            }

            // 2. 首次采样：仅记录基准值，不计算增量
            if (isFirstSample) {
                Timber.tag(TAG).d("首次采样，记录基准值，共%d个UID", currentData.size)
                lastSampleData = currentData
                isFirstSample = false
                return
            }

            // 3. 计算增量并累加到分钟聚合器
            var totalDeltaRx = 0L
            var totalDeltaTx = 0L

            for ((uid, currentTraffic) in currentData) {
                val lastTraffic = lastSampleData[uid]

                if (lastTraffic != null) {
                    // 处理内核计数器重置的情况：当前值 < 上次值说明发生了重置
                    val deltaRx = if (currentTraffic.rxBytes >= lastTraffic.rxBytes) {
                        currentTraffic.rxBytes - lastTraffic.rxBytes
                    } else {
                        // 内核计数器重置，使用当前值作为增量
                        currentTraffic.rxBytes
                    }

                    val deltaTx = if (currentTraffic.txBytes >= lastTraffic.txBytes) {
                        currentTraffic.txBytes - lastTraffic.txBytes
                    } else {
                        // 内核计数器重置，使用当前值作为增量
                        currentTraffic.txBytes
                    }

                    totalDeltaRx += deltaRx
                    totalDeltaTx += deltaTx
                } else {
                    // 新出现的UID，使用当前值作为增量
                    totalDeltaRx += currentTraffic.rxBytes
                    totalDeltaTx += currentTraffic.txBytes
                }
            }

            // 4. 累加到分钟聚合器
            minuteRxBytes.addAndGet(totalDeltaRx)
            minuteTxBytes.addAndGet(totalDeltaTx)
            minuteSampleCount.incrementAndGet()

            Timber.tag(TAG).d(
                "采样完成：增量RX=%d字节, TX=%d字节, 本分钟累计采样%d次",
                totalDeltaRx, totalDeltaTx, minuteSampleCount.get()
            )

            // 5. 更新上次采样缓存
            lastSampleData = currentData

        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "内核采样异常")
        }
    }

    // ========================================================================
    // 分钟结算逻辑
    // ========================================================================

    /**
     * 分钟结算
     *
     * 结算流程：
     * 1. 读取分钟聚合器中的累计数据（RX/TX字节数和采样次数）
     * 2. 将分钟数据写入当前小时的聚合器
     * 3. 重置分钟聚合器（归零计数器）
     * 4. 更新Repository数据（如果需要实时展示分钟级数据）
     *
     * 注意事项：
     * - 结算操作是原子的：先读取后重置，避免数据丢失
     * - 如果本分钟内无采样数据（sampleCount=0），跳过结算
     */
    suspend fun performMinuteSettle() {
        Timber.tag(TAG).d("开始执行分钟结算")

        try {
            // 1. 原子读取并重置分钟聚合器
            val rxBytes = minuteRxBytes.getAndSet(0L)
            val txBytes = minuteTxBytes.getAndSet(0L)
            val sampleCount = minuteSampleCount.getAndSet(0)

            // 2. 无采样数据则跳过
            if (sampleCount == 0L || (rxBytes == 0L && txBytes == 0L)) {
                Timber.tag(TAG).d("本分钟无采样数据，跳过结算")
                return
            }

            // 3. 获取当前小时，写入小时聚合器
            val currentHour = getCurrentHour()
            val accumulator = hourlyAccumulator.getOrPut(currentHour) {
                HourlyAccumulator()
            }

            accumulator.rxBytes.addAndGet(rxBytes)
            accumulator.txBytes.addAndGet(txBytes)
            accumulator.samples.addAndGet(sampleCount)

            Timber.tag(TAG).d(
                "分钟结算完成：写入小时%d, RX=%d字节, TX=%d字节, 采样%d次",
                currentHour, rxBytes, txBytes, sampleCount
            )

        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "分钟结算异常")
        }
    }

    // ========================================================================
    // 小时归档逻辑
    // ========================================================================

    /**
     * 小时归档
     *
     * 归档流程：
     * 1. 获取当前小时的小时聚合器数据
     * 2. 将聚合数据转化为HourlyTraffic并归档到日报列表
     * 3. 清理过期的小时聚合器（超过24小时的数据）
     * 4. 通过repository.emitChartData()向UI层发射图表数据
     *
     * 注意事项：
     * - 日报列表保持最多24条记录（滚动窗口）
     * - 归档后保留当前小时的聚合器（因为新一个小时的数据即将开始）
     * - 如果跨天归档，会清除前一天的小时聚合器
     */
    suspend fun performHourArchive() {
        Timber.tag(TAG).d("开始执行小时归档")

        try {
            val currentHour = getCurrentHour()

            // 1. 获取上一个小时的聚合器数据（即需要归档的小时）
            val previousHour = if (currentHour == 0) 23 else currentHour - 1
            val accumulator = hourlyAccumulator[previousHour]

            if (accumulator == null) {
                Timber.tag(TAG).d("上个小时%d无聚合数据，跳过归档", previousHour)
                // 即使无数据也需要发射图表（可能UI在等待更新）
                repository.emitChartData(getDailyReport())
                return
            }

            // 2. 构建小时流量数据并归档到日报
            val hourlyTraffic = HourlyTraffic(
                hour = previousHour,
                rxBytes = accumulator.rxBytes.get(),
                txBytes = accumulator.txBytes.get()
            )

            // 3. 更新日报列表（如果该小时已有记录则更新，否则新增）
            val existingIndex = dailyReport.indexOfFirst { it.hour == previousHour }
            if (existingIndex >= 0) {
                dailyReport[existingIndex] = hourlyTraffic
            } else {
                dailyReport.add(hourlyTraffic)
            }

            // 按小时排序
            dailyReport.sortBy { it.hour }

            Timber.tag(TAG).d(
                "小时归档完成：小时=%d, RX=%d字节, TX=%d字节, 日报共%d条",
                previousHour, hourlyTraffic.rxBytes, hourlyTraffic.txBytes, dailyReport.size
            )

            // 4. 清理过期的小时聚合器（保留当前和上一个小时的，防止归档数据丢失）
            val staleHours = hourlyAccumulator.keys.filter { hour ->
                hour != currentHour && hour != previousHour
            }
            staleHours.forEach { hour ->
                hourlyAccumulator.remove(hour)
                Timber.tag(TAG).d("清理过期小时聚合器: 小时%d", hour)
            }

            // 5. 发射图表数据到UI层
            repository.emitChartData(getDailyReport())

        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "小时归档异常")
        }
    }

    // ========================================================================
    // 数据查询
    // ========================================================================

    /**
     * 获取24小时日报数据
     *
     * 返回当前日报列表的快照，包含每个小时的RX/TX流量数据。
     * 列表按小时升序排列，最多24条记录。
     *
     * @return 24小时流量日报列表
     */
    fun getDailyReport(): List<HourlyTraffic> {
        return dailyReport.toList()
    }

    // ========================================================================
    // 生命周期管理
    // ========================================================================

    /**
     * 启动高精度流量统计系统
     *
     * 启动三个独立的定时任务：
     * - 10秒内核采样：每SAMPLING_INTERVAL_MS执行一次
     * - 分钟结算：每MINUTE_SETTLE_INTERVAL_MS执行一次
     * - 小时归档：每HOUR_ARCHIVE_INTERVAL_MS执行一次
     *
     * 每个任务独立运行在各自的协程中，互不阻塞。
     * 如果已有任务在运行，会先停止再重新启动。
     */
    fun start() {
        Timber.tag(TAG).i("启动骆驼高精度流量统计系统")

        stop() // 先确保已有任务已停止

        // 启动10秒内核采样任务
        samplingJob = scope.launch {
            while (isActive) {
                try {
                    performSampling()
                } catch (_: CancellationException) {
                    break
                } catch (e: Exception) {
                    Timber.tag(TAG).e(e, "采样任务异常")
                }
                delay(SAMPLING_INTERVAL_MS)
            }
        }

        // 启动分钟结算任务
        minuteSettleJob = scope.launch {
            while (isActive) {
                try {
                    performMinuteSettle()
                } catch (_: CancellationException) {
                    break
                } catch (e: Exception) {
                    Timber.tag(TAG).e(e, "分钟结算任务异常")
                }
                delay(MINUTE_SETTLE_INTERVAL_MS)
            }
        }

        // 启动小时归档任务
        hourArchiveJob = scope.launch {
            while (isActive) {
                try {
                    performHourArchive()
                } catch (_: CancellationException) {
                    break
                } catch (e: Exception) {
                    Timber.tag(TAG).e(e, "小时归档任务异常")
                }
                delay(HOUR_ARCHIVE_INTERVAL_MS)
            }
        }

        Timber.tag(TAG).i("骆驼高精度流量统计系统已启动")
    }

    /**
     * 停止高精度流量统计系统
     *
     * 取消所有定时任务，但保留已采集的日报数据。
     * 调用start()可重新启动。
     */
    fun stop() {
        Timber.tag(TAG).i("停止骆驼高精度流量统计系统")

        samplingJob?.cancel()
        samplingJob = null

        minuteSettleJob?.cancel()
        minuteSettleJob = null

        hourArchiveJob?.cancel()
        hourArchiveJob = null

        Timber.tag(TAG).i("骆驼高精度流量统计系统已停止")
    }

    // ========================================================================
    // 内部辅助方法
    // ========================================================================

    /**
     * 获取当前小时（0~23）
     */
    private fun getCurrentHour(): Int {
        return java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
    }

    /**
     * 小时聚合器数据类
     *
     * 用于累加同一小时内所有分钟结算的流量数据。
     * 使用AtomicLong保证线程安全，支持并发写入（多个采样任务同时写入）。
     *
     * @property rxBytes 接收字节数累加器
     * @property txBytes 发送字节数累加器
     * @property samples 采样次数累加器
     */
    data class HourlyAccumulator(
        val rxBytes: AtomicLong = AtomicLong(0),
        val txBytes: AtomicLong = AtomicLong(0),
        val samples: AtomicInteger = AtomicInteger(0)
    )
}
