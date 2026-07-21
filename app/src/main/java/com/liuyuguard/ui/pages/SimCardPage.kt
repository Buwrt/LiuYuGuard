package com.liuyuguard.ui.pages

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SignalCellular4Bar
import androidx.compose.material.icons.filled.SimCard
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.liuyuguard.ui.theme.TrafficColors
import com.liuyuguard.util.TrafficFormatter

// ============================================================================
// 双卡SIM独立流量统计页
// ============================================================================

/** 单张SIM卡的模拟数据 */
private data class SimCardData(
    val simLabel: String,        // SIM卡标签（SIM1/SIM2）
    val carrierName: String,     // 运营商名称
    val signalStrength: Int,     // 信号强度（0-4格）
    val todayRxBytes: Long,      // 今日下载
    val todayTxBytes: Long,      // 今日上传
    val monthRxBytes: Long,      // 月度累计下载
    val monthTxBytes: Long,      // 月度累计上传
    val monthlyQuotaBytes: Long  // 月套餐总量
) {
    /** 月度已用百分比 */
    val monthUsagePercent: Float
        get() = ((monthRxBytes + monthTxBytes).toFloat() / monthlyQuotaBytes)
            .coerceIn(0f, 1f)
}

/**
 * 双卡SIM流量统计页面
 *
 * 布局结构：
 * - 顶部：双卡状态指示（SIM1/SIM2运营商和信号）
 * - 中间：每张SIM卡流量详情卡片
 * - 底部：合计流量统计卡片
 * - 所有卡片带左右交替滑入动画
 */
@Composable
fun SimCardScreen() {
    // ---- 模拟数据（每次进入重新生成） ----
    val sim1Data = remember {
        SimCardData(
            simLabel = "SIM 1",
            carrierName = "中国移动",
            signalStrength = 4,
            todayRxBytes = 326_500_000L,    // ~311 MB
            todayTxBytes = 85_200_000L,     // ~81 MB
            monthRxBytes = 652_300_000L,    // ~622 MB
            monthTxBytes = 173_800_000L,    // ~166 MB
            monthlyQuotaBytes = 1_073_741_824L // 1GB
        )
    }

    val sim2Data = remember {
        SimCardData(
            simLabel = "SIM 2",
            carrierName = "中国联通",
            signalStrength = 3,
            todayRxBytes = 156_800_000L,    // ~149 MB
            todayTxBytes = 42_100_000L,     // ~40 MB
            monthRxBytes = 389_600_000L,    // ~371 MB
            monthTxBytes = 98_500_000L,     // ~94 MB
            monthlyQuotaBytes = 1_073_741_824L // 1GB
        )
    }

    // ---- 卡片入场动画状态 ----
    val cardStates = remember {
        List(5) { mutableStateOf(false) }
    }

    LaunchedEffect(Unit) {
        cardStates.forEachIndexed { index, state ->
            kotlinx.coroutines.delay(100L * (index + 1))
            state.value = true
        }
    }

    // ---- 颜色引用 ----
    val rxColor = TrafficColors.rxColor()
    val txColor = TrafficColors.txColor()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        // ==========================================
        // 顶部标题
        // ==========================================
        Text(
            text = "双卡流量统计",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(bottom = 12.dp)
        )

        // ==========================================
        // 双卡状态指示
        // ==========================================
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // SIM1状态卡
            AnimatedVisibility(
                visible = cardStates[0].value,
                enter = slideInHorizontally(
                    initialOffsetX = { -it / 3 },
                    animationSpec = tween(400, easing = FastOutSlowInEasing)
                ) + fadeIn(tween(400))
            ) {
                SimStatusCard(
                    data = sim1Data,
                    modifier = Modifier.weight(1f)
                )
            }

            // SIM2状态卡
            AnimatedVisibility(
                visible = cardStates[1].value,
                enter = slideInHorizontally(
                    initialOffsetX = { it / 3 },
                    animationSpec = tween(400, easing = FastOutSlowInEasing)
                ) + fadeIn(tween(400))
            ) {
                SimStatusCard(
                    data = sim2Data,
                    modifier = Modifier.weight(1f)
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // ==========================================
        // SIM1流量详情卡片
        // ==========================================
        AnimatedVisibility(
            visible = cardStates[2].value,
            enter = slideInHorizontally(
                initialOffsetX = { -it / 3 },
                animationSpec = tween(400, easing = FastOutSlowInEasing)
            ) + fadeIn(tween(400))
        ) {
            SimTrafficDetailCard(
                data = sim1Data,
                rxColor = rxColor,
                txColor = txColor,
                modifier = Modifier.fillMaxWidth()
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        // ==========================================
        // SIM2流量详情卡片
        // ==========================================
        AnimatedVisibility(
            visible = cardStates[3].value,
            enter = slideInHorizontally(
                initialOffsetX = { it / 3 },
                animationSpec = tween(400, easing = FastOutSlowInEasing)
            ) + fadeIn(tween(400))
        ) {
            SimTrafficDetailCard(
                data = sim2Data,
                rxColor = rxColor,
                txColor = txColor,
                modifier = Modifier.fillMaxWidth()
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        // ==========================================
        // 合计流量统计卡片
        // ==========================================
        AnimatedVisibility(
            visible = cardStates[4].value,
            enter = fadeIn(
                animationSpec = tween(500, easing = FastOutSlowInEasing)
            )
        ) {
            TotalTrafficCard(
                sim1 = sim1Data,
                sim2 = sim2Data,
                rxColor = rxColor,
                txColor = txColor,
                modifier = Modifier.fillMaxWidth()
            )
        }

        // 底部留白
        Spacer(modifier = Modifier.height(16.dp))
    }
}

// ============================================================================
// SIM卡状态指示卡片
// ============================================================================

/**
 * SIM卡状态小卡片
 * 显示运营商名称和信号强度
 *
 * @param data SIM卡数据
 * @param modifier Modifier
 */
@Composable
private fun SimStatusCard(
    data: SimCardData,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        ),
        shape = MaterialTheme.shapes.medium
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // SIM卡图标
            Icon(
                imageVector = Icons.Default.SimCard,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(28.dp)
            )

            Spacer(modifier = Modifier.height(4.dp))

            // SIM标签
            Text(
                text = data.simLabel,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )

            Spacer(modifier = Modifier.height(2.dp))

            // 运营商名称
            Text(
                text = data.carrierName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )

            Spacer(modifier = Modifier.height(4.dp))

            // 信号强度指示（用小圆点表示）
            Row(
                horizontalArrangement = Arrangement.spacedBy(3.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                repeat(4) { index ->
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(
                                if (index < data.signalStrength)
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                else
                                    MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.25f)
                            )
                    )
                }
            }
        }
    }
}

// ============================================================================
// SIM卡流量详情卡片
// ============================================================================

/**
 * SIM卡流量详情大卡片
 * 显示卡号/运营商、今日上下行、月度上下行、用量进度条
 *
 * @param data SIM卡数据
 * @param rxColor 下载颜色
 * @param txColor 上传颜色
 * @param modifier Modifier
 */
@Composable
private fun SimTrafficDetailCard(
    data: SimCardData,
    rxColor: androidx.compose.ui.graphics.Color,
    txColor: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        shape = MaterialTheme.shapes.medium
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            // ---- 卡头：SIM标签 + 运营商名 ----
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.SimCard,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(20.dp)
                    )
                }

                Spacer(modifier = Modifier.width(10.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = data.simLabel,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = data.carrierName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // 信号图标
                Icon(
                    imageVector = Icons.Default.SignalCellular4Bar,
                    contentDescription = "信号强度",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // ---- 今日流量 ----
            Text(
                text = "今日流量",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                // 今日下载
                TrafficValueItem(
                    label = "下载",
                    value = TrafficFormatter.format(data.todayRxBytes),
                    color = rxColor
                )
                // 今日上传
                TrafficValueItem(
                    label = "上传",
                    value = TrafficFormatter.format(data.todayTxBytes),
                    color = txColor
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // ---- 月度累计 ----
            Text(
                text = "月度累计",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                // 月度下载
                TrafficValueItem(
                    label = "下载",
                    value = TrafficFormatter.format(data.monthRxBytes),
                    color = rxColor
                )
                // 月度上传
                TrafficValueItem(
                    label = "上传",
                    value = TrafficFormatter.format(data.monthTxBytes),
                    color = txColor
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // ---- 用量进度条 ----
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "月套餐用量",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "${(data.monthUsagePercent * 100).toInt()}%",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = if (data.monthUsagePercent > 0.8f)
                        MaterialTheme.colorScheme.error
                    else
                        MaterialTheme.colorScheme.primary
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            LinearProgressIndicator(
                progress = { data.monthUsagePercent },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp)),
                color = if (data.monthUsagePercent > 0.8f)
                    MaterialTheme.colorScheme.error
                else
                    MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
                strokeCap = androidx.compose.ui.graphics.StrokeCap.Round
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = "已用 ${TrafficFormatter.format(data.monthRxBytes + data.monthTxBytes)} / " +
                        TrafficFormatter.format(data.monthlyQuotaBytes),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// ============================================================================
// 流量数值项（带上下行双色圆点）
// ============================================================================

/**
 * 流量数值展示项
 * 带有颜色圆点指示
 *
 * @param label 标签（下载/上传）
 * @param value 格式化后的流量值
 * @param color 指示颜色
 */
@Composable
private fun TrafficValueItem(
    label: String,
    value: String,
    color: androidx.compose.ui.graphics.Color
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        // 双色圆点
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(color)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Column {
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = value,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

// ============================================================================
// 合计流量统计卡片
// ============================================================================

/**
 * 双卡合计流量统计
 *
 * @param sim1 SIM1数据
 * @param sim2 SIM2数据
 * @param rxColor 下载颜色
 * @param txColor 上传颜色
 * @param modifier Modifier
 */
@Composable
private fun TotalTrafficCard(
    sim1: SimCardData,
    sim2: SimCardData,
    rxColor: androidx.compose.ui.graphics.Color,
    txColor: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier
) {
    // 合计数据
    val totalTodayRx = sim1.todayRxBytes + sim2.todayRxBytes
    val totalTodayTx = sim1.todayTxBytes + sim2.todayTxBytes
    val totalMonthRx = sim1.monthRxBytes + sim2.monthRxBytes
    val totalMonthTx = sim1.monthTxBytes + sim2.monthTxBytes

    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        ),
        shape = MaterialTheme.shapes.medium
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Text(
                text = "双卡合计",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )

            Spacer(modifier = Modifier.height(12.dp))

            // 今日合计
            Text(
                text = "今日",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f)
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                TrafficValueItem(
                    label = "下载",
                    value = TrafficFormatter.format(totalTodayRx),
                    color = rxColor
                )
                TrafficValueItem(
                    label = "上传",
                    value = TrafficFormatter.format(totalTodayTx),
                    color = txColor
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // 月度合计
            Text(
                text = "月度",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f)
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                TrafficValueItem(
                    label = "下载",
                    value = TrafficFormatter.format(totalMonthRx),
                    color = rxColor
                )
                TrafficValueItem(
                    label = "上传",
                    value = TrafficFormatter.format(totalMonthTx),
                    color = txColor
                )
            }
        }
    }
}