package com.liuyuguard.ui.pages

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.SimCard
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.liuyuguard.ui.theme.TrafficColors
import com.liuyuguard.model.RunMode
import com.liuyuguard.model.TrafficOverview
import com.liuyuguard.ui.animations.clickableScale
import com.liuyuguard.ui.components.RxTxIndicator
import com.liuyuguard.ui.components.TrafficDataCard
import com.liuyuguard.util.TrafficFormatter

// ============================================================================
// 流量总看板页
// ============================================================================

/**
 * 流量总看板
 *
 * 布局结构：
 * - 顶部：运行模式标签(Root/Shizuku)、高精度模式开关、服务状态
 * - 中间：4张数据卡片（今日上传、今日下载、WiFi用量、蜂窝用量）
 * - 中下：RxTxIndicator 显示上下行
 * - 底部：快速跳转按钮组
 *
 * @param runMode 当前运行模式
 * @param onNavigateToDetail 跳转到应用明细页
 * @param onNavigateToCharts 跳转到图表页
 * @param onNavigateToCamel 跳转到骆驼模式页
 * @param onNavigateToSimCard 跳转到双卡统计页
 */
@Composable
fun DashboardScreen(
    runMode: RunMode = RunMode.ROOT,
    onNavigateToDetail: () -> Unit = {},
    onNavigateToCharts: () -> Unit = {},
    onNavigateToCamel: () -> Unit = {},
    onNavigateToSimCard: () -> Unit = {}
) {
    // ---- 模拟数据（后续接入真实ViewModel） ----
    val mockOverview = remember {
        TrafficOverview(
            todayRxBytes = 1_536_000_000L,   // 1.53 GB 今日下载
            todayTxBytes = 256_000_000L,     // 256 MB 今日上传
            wifiRxBytes = 1_200_000_000L,    // 1.2 GB WiFi下载
            wifiTxBytes = 200_000_000L,      // 200 MB WiFi上传
            cellularRxBytes = 336_000_000L,  // 336 MB 蜂窝下载
            cellularTxBytes = 56_000_000L,   // 56 MB 蜂窝上传
            isServiceRunning = true,
            isHighPrecision = true
        )
    }

    var highPrecisionEnabled by remember { mutableStateOf(mockOverview.isHighPrecision) }

    // ---- 卡片入场动画状态（4张卡片依次入场） ----
    val cardVisibleStates = remember {
        List(4) { index -> mutableStateOf(false) }
    }
    LaunchedEffect(Unit) {
        cardVisibleStates.forEachIndexed { index, state ->
            kotlinx.coroutines.delay(80L * (index + 1))
            state.value = true
        }
    }

    // ---- 滚动容器 ----
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        // ==========================================
        // 顶部信息栏：运行模式 + 高精度开关 + 服务状态
        // ==========================================
        TopInfoBar(
            runMode = runMode,
            isHighPrecision = highPrecisionEnabled,
            isServiceRunning = mockOverview.isServiceRunning,
            onHighPrecisionChanged = { highPrecisionEnabled = it }
        )

        Spacer(modifier = Modifier.height(16.dp))

        // ==========================================
        // 4张数据卡片（带入场动画）
        // ==========================================
        AnimatedVisibility(
            visible = cardVisibleStates[0].value,
            enter = fadeIn(tween(300)) + slideInVertically(
                initialOffsetY = { it / 3 },
                animationSpec = tween(300)
            )
        ) {
            TrafficDataCard(
                title = "今日下载",
                value = TrafficFormatter.format(mockOverview.todayRxBytes),
                icon = Icons.Default.ArrowDownward,
                color = TrafficColors.rxColor()
            )
        }

        AnimatedVisibility(
            visible = cardVisibleStates[1].value,
            enter = fadeIn(tween(300)) + slideInVertically(
                initialOffsetY = { it / 3 },
                animationSpec = tween(300)
            )
        ) {
            TrafficDataCard(
                title = "今日上传",
                value = TrafficFormatter.format(mockOverview.todayTxBytes),
                icon = Icons.Default.ArrowUpward,
                color = TrafficColors.txColor()
            )
        }

        AnimatedVisibility(
            visible = cardVisibleStates[2].value,
            enter = fadeIn(tween(300)) + slideInVertically(
                initialOffsetY = { it / 3 },
                animationSpec = tween(300)
            )
        ) {
            TrafficDataCard(
                title = "WiFi用量",
                value = TrafficFormatter.format(
                    mockOverview.wifiRxBytes + mockOverview.wifiTxBytes
                ),
                icon = Icons.Default.Wifi,
                color = MaterialTheme.colorScheme.tertiary
            )
        }

        AnimatedVisibility(
            visible = cardVisibleStates[3].value,
            enter = fadeIn(tween(300)) + slideInVertically(
                initialOffsetY = { it / 3 },
                animationSpec = tween(300)
            )
        ) {
            TrafficDataCard(
                title = "蜂窝用量",
                value = TrafficFormatter.format(
                    mockOverview.cellularRxBytes + mockOverview.cellularTxBytes
                ),
                icon = Icons.Default.SimCard,
                color = MaterialTheme.colorScheme.secondary
            )
        }

        Spacer(modifier = Modifier.height(20.dp))

        // ==========================================
        // 上下行实时指示器
        // ==========================================
        RxTxIndicator(
            rxValue = TrafficFormatter.format(mockOverview.todayRxBytes),
            txValue = TrafficFormatter.format(mockOverview.todayTxBytes)
        )

        Spacer(modifier = Modifier.height(24.dp))

        // ==========================================
        // 快速跳转按钮组（2x2网格）
        // ==========================================
        QuickJumpButtons(
            onNavigateToDetail = onNavigateToDetail,
            onNavigateToCharts = onNavigateToCharts,
            onNavigateToCamel = onNavigateToCamel,
            onNavigateToSimCard = onNavigateToSimCard
        )

        // 底部留白，避免被底部导航栏遮挡
        Spacer(modifier = Modifier.height(16.dp))
    }
}

// ============================================================================
// 顶部信息栏
// ============================================================================

/**
 * 顶部信息栏组件
 *
 * @param runMode 运行模式
 * @param isHighPrecision 高精度模式是否开启
 * @param isServiceRunning 服务是否运行中
 * @param onHighPrecisionChanged 高精度模式切换回调
 */
@Composable
private fun TopInfoBar(
    runMode: RunMode,
    isHighPrecision: Boolean,
    isServiceRunning: Boolean,
    onHighPrecisionChanged: (Boolean) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        shape = MaterialTheme.shapes.medium
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            // 第一行：运行模式标签 + 服务状态
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // 运行模式标签
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = when (runMode) {
                            RunMode.ROOT -> MaterialTheme.colorScheme.primaryContainer
                            RunMode.SHIZUKU -> MaterialTheme.colorScheme.tertiaryContainer
                            RunMode.LOCKED -> MaterialTheme.colorScheme.errorContainer
                        }
                    },
                    shape = MaterialTheme.shapes.small
                ) {
                    Text(
                        text = when (runMode) {
                            RunMode.ROOT -> "Root 模式"
                            RunMode.SHIZUKU -> "Shizuku 模式"
                            RunMode.LOCKED -> "权限锁定"
                        },
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelMedium,
                        color = when (runMode) {
                            RunMode.ROOT -> MaterialTheme.colorScheme.onPrimaryContainer
                            RunMode.SHIZUKU -> MaterialTheme.colorScheme.onTertiaryContainer
                            RunMode.LOCKED -> MaterialTheme.colorScheme.onErrorContainer
                        },
                        fontWeight = FontWeight.Bold
                    )
                }

                // 服务运行状态
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 状态指示圆点
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(
                                if (isServiceRunning)
                                    TrafficColors.txColor()
                                else
                                    TrafficColors.blockedColor()
                            )
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (isServiceRunning) "守护运行中" else "服务未启动",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // 第二行：高精度模式开关
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        text = "高精度模式",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = if (isHighPrecision) "内核级精确统计" else "基础统计模式",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = isHighPrecision,
                    onCheckedChange = onHighPrecisionChanged,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                        checkedTrackColor = MaterialTheme.colorScheme.primary
                    )
                )
            }
        }
    }
}

// ============================================================================
// 快速跳转按钮组
// ============================================================================

/**
 * 底部快速跳转按钮组（2行2列网格布局）
 *
 * @param onNavigateToDetail 跳转应用明细
 * @param onNavigateToCharts 跳转图表
 * @param onNavigateToCamel 跳转骆驼模式
 * @param onNavigateToSimCard 跳转双卡统计
 */
@Composable
private fun QuickJumpButtons(
    onNavigateToDetail: () -> Unit,
    onNavigateToCharts: () -> Unit,
    onNavigateToCamel: () -> Unit,
    onNavigateToSimCard: () -> Unit
) {
    Text(
        text = "快捷入口",
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(bottom = 12.dp)
    )

    // 第一行：应用明细 + 图表
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        QuickJumpButton(
            label = "应用明细",
            icon = Icons.Default.List,
            onClick = onNavigateToDetail,
            modifier = Modifier.weight(1f)
        )
        QuickJumpButton(
            label = "图表",
            icon = Icons.Default.BarChart,
            onClick = onNavigateToCharts,
            modifier = Modifier.weight(1f)
        )
    }

    Spacer(modifier = Modifier.height(8.dp))

    // 第二行：骆驼模式 + 双卡统计
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        QuickJumpButton(
            label = "骆驼模式",
            icon = Icons.Default.Bolt,
            onClick = onNavigateToCamel,
            modifier = Modifier.weight(1f)
        )
        QuickJumpButton(
            label = "双卡统计",
            icon = Icons.Default.SimCard,
            onClick = onNavigateToSimCard,
            modifier = Modifier.weight(1f)
        )
    }
}

// ============================================================================
// 单个快速跳转按钮
// ============================================================================

/**
 * 快速跳转按钮
 * 使用FilledTonalButton + 点击缩放动效
 *
 * @param label 按钮文字
 * @param icon 按钮图标
 * @param onClick 点击回调
 * @param modifier Modifier
 */
@Composable
private fun QuickJumpButton(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    FilledTonalButton(
        onClick = onClick,
        modifier = modifier
            .height(48.dp)
            .clickableScale { onClick() }
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            modifier = Modifier.size(18.dp)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium
        )
    }
}