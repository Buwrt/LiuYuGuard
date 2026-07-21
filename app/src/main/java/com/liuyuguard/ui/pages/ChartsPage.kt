package com.liuyuguard.ui.pages

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.liuyuguard.ui.theme.TrafficColors
import com.liuyuguard.util.TrafficFormatter
import kotlin.random.Random

// ============================================================================
// 多周期流量图表页
// ============================================================================

/** 周期枚举 */
private enum class ChartPeriod(val label: String, val dataCount: Int) {
    DAY("日", 24),
    WEEK("周", 7),
    MONTH("月", 30)
}

/** 单根柱子的模拟数据 */
private data class BarData(
    val downloadBytes: Long, // 下载字节数（蓝色）
    val uploadBytes: Long    // 上传字节数（绿色）
)

/**
 * 流量图表页面
 *
 * 布局结构：
 * - 顶部：3个分段按钮（日/周/月）
 * - 中间：Canvas绘制的上下行双色柱状图
 * - 底部：图例 + 当前周期总流量统计
 *
 * 模拟数据每次页面进入时重新生成
 */
@Composable
fun ChartsScreen() {
    // ---- 当前选中的周期 ----
    var selectedPeriod by remember { mutableStateOf(ChartPeriod.DAY) }

    // ---- 为每个周期生成独立的模拟数据 ----
    val periodDataMap = remember {
        ChartPeriod.entries.associateWith { period ->
            List(period.dataCount) {
                BarData(
                    downloadBytes = Random.nextLong(50_000_000L, 500_000_000L),
                    uploadBytes = Random.nextLong(10_000_000L, 100_000_000L)
                )
            }
        }
    }

    // ---- 切换周期时重新入场动画 ----
    var chartKey by remember { mutableIntStateOf(0) }
    var isChartVisible by remember { mutableStateOf(true) }

    LaunchedEffect(selectedPeriod) {
        // 先隐藏图表
        isChartVisible = false
        kotlinx.coroutines.delay(150L)
        // 更新key让Canvas重建
        chartKey++
        // 重新显示图表
        isChartVisible = true
    }

    // ---- 颜色引用 ----
    val rxColor = TrafficColors.rxColor()
    val txColor = TrafficColors.txColor()

    // ---- 当前周期的数据 ----
    val currentData = periodDataMap[selectedPeriod] ?: emptyList()

    // ---- 计算当前周期总流量 ----
    val totalDownload = remember(currentData) { currentData.sumOf { it.downloadBytes } }
    val totalUpload = remember(currentData) { currentData.sumOf { it.uploadBytes } }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        // ==========================================
        // 顶部标题
        // ==========================================
        Text(
            text = "流量趋势",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(bottom = 12.dp)
        )

        // ==========================================
        // 周期选择：3个FilterChip分段按钮
        // ==========================================
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            ChartPeriod.entries.forEach { period ->
                val isSelected = selectedPeriod == period
                FilterChip(
                    selected = isSelected,
                    onClick = {
                        if (!isSelected) selectedPeriod = period
                    },
                    label = {
                        Text(
                            text = period.label,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                        )
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                    ),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.weight(1f)
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // ==========================================
        // 柱状图区域（Canvas绘制，带入场动画）
        // ==========================================
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .height(280.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            ),
            shape = MaterialTheme.shapes.medium
        ) {
            AnimatedVisibility(
                visible = isChartVisible,
                enter = fadeIn(
                    animationSpec = tween(400, easing = FastOutSlowInEasing)
                ),
                exit = fadeOut(
                    animationSpec = tween(150)
                )
            ) {
                // 使用key确保切换周期时重新触发柱子生长动画
                key(chartKey) {
                    TrafficBarChart(
                        data = currentData,
                        rxColor = rxColor,
                        txColor = txColor,
                        period = selectedPeriod,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(12.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // ==========================================
        // 图例
        // ==========================================
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 下载图例
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(rxColor)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "下载",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.width(24.dp))

            // 上传图例
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(txColor)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "上传",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // ==========================================
        // 当前周期总流量统计
        // ==========================================
        Card(
            modifier = Modifier.fillMaxWidth(),
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
                Text(
                    text = when (selectedPeriod) {
                        ChartPeriod.DAY -> "今日总流量"
                        ChartPeriod.WEEK -> "本周总流量"
                        ChartPeriod.MONTH -> "本月总流量"
                    },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(bottom = 12.dp)
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    // 总下载
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(rxColor)
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "总下载",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = TrafficFormatter.format(totalDownload),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = rxColor
                        )
                    }

                    // 总上传
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(txColor)
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "总上传",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = TrafficFormatter.format(totalUpload),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = txColor
                        )
                    }

                    // 总计
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(
                                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                )
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "总计",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = TrafficFormatter.format(totalDownload + totalUpload),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }
    }
}

// ============================================================================
// Canvas绘制的上下行双色柱状图
// ============================================================================

/**
 * 流量柱状图
 * 使用Canvas绘制，每根柱子分上下两段：上方蓝色=下载，下方绿色=上传
 * 带入场动画（柱子从中间向两侧生长）
 *
 * @param data 柱状图数据列表
 * @param rxColor 下载颜色（蓝色）
 * @param txColor 上传颜色（绿色）
 * @param period 当前周期（用于X轴标签）
 * @param modifier Modifier
 */
@Composable
private fun TrafficBarChart(
    data: List<BarData>,
    rxColor: androidx.compose.ui.graphics.Color,
    txColor: androidx.compose.ui.graphics.Color,
    period: ChartPeriod,
    modifier: Modifier = Modifier
) {
    // ---- 入场动画：柱子从0增长到目标高度 ----
    var animProgress by remember { mutableStateOf(0f) }
    val animatedProgress by animateFloatAsState(
        targetValue = animProgress,
        animationSpec = tween(800, easing = FastOutSlowInEasing),
        label = "bar_growth"
    )

    // 页面进入时触发增长动画
    LaunchedEffect(Unit) {
        animProgress = 1f
    }

    Canvas(modifier = modifier) {
        if (data.isEmpty()) return@Canvas

        val barCount = data.size
        val totalWidth = size.width
        val totalHeight = size.height - 30f // 底部留空给X轴标签
        val barSpacing = if (barCount > 15) 2f else 6f
        val barWidth = (totalWidth - barSpacing * (barCount + 1)) / barCount

        // 找出最大值用于归一化
        val maxRx = data.maxOf { it.downloadBytes }.toFloat()
        val maxTx = data.maxOf { it.uploadBytes }.toFloat()
        val maxVal = maxOf(maxRx, maxTx, 1f)

        // 柱子圆角半径
        val cornerRadius = if (barCount > 15) 1f else 3f

        data.forEachIndexed { index, barData ->
            val x = barSpacing + index * (barWidth + barSpacing)

            // 下载柱高度（从中间向上）
            val rxHeight = (barData.downloadBytes.toFloat() / maxVal) * (totalHeight * 0.45f) * animatedProgress
            // 上传柱高度（从中间向下）
            val txHeight = (barData.uploadBytes.toFloat() / maxVal) * (totalHeight * 0.45f) * animatedProgress

            val centerY = totalHeight / 2f

            // ---- 绘制下载柱（蓝色，向上） ----
            if (rxHeight > 0f) {
                drawRoundRect(
                    color = rxColor,
                    topLeft = Offset(x, centerY - rxHeight),
                    size = Size(barWidth, rxHeight),
                    cornerRadius = CornerRadius(cornerRadius)
                )
            }

            // ---- 绘制上传柱（绿色，向下） ----
            if (txHeight > 0f) {
                drawRoundRect(
                    color = txColor,
                    topLeft = Offset(x, centerY),
                    size = Size(barWidth, txHeight),
                    cornerRadius = CornerRadius(cornerRadius)
                )
            }
        }

        // ---- 绘制中间分隔线 ----
        drawLine(
            color = androidx.compose.ui.graphics.Color.Gray.copy(alpha = 0.3f),
            start = Offset(0f, totalHeight / 2f),
            end = Offset(totalWidth, totalHeight / 2f),
            strokeWidth = 1f
        )

        // ---- 绘制X轴标签（每隔几个显示一个，避免重叠） ----
        val labelInterval = when (barCount) {
            in 1..7 -> 1
            in 8..15 -> 2
            else -> 4
        }

        val paint = androidx.compose.ui.graphics.Paint().asFrameworkPaint()
        paint.color = android.graphics.Color.GRAY
        paint.textSize = 22f
        paint.textAlign = android.graphics.Paint.Align.CENTER

        data.forEachIndexed { index, _ ->
            if (index % labelInterval == 0) {
                val x = barSpacing + index * (barWidth + barSpacing) + barWidth / 2f
                val label = when (period) {
                    ChartPeriod.DAY -> "${index}h"
                    ChartPeriod.WEEK -> listOf("一", "二", "三", "四", "五", "六", "日")
                        .getOrElse(index) { "$index" }
                    ChartPeriod.MONTH -> "${index + 1}"
                }
                drawContext.canvas.nativeCanvas.drawText(
                    label,
                    x,
                    totalHeight + 20f,
                    paint
                )
            }
        }
    }
}