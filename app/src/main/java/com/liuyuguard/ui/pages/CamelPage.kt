package com.liuyuguard.ui.pages

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DeviceHub
import androidx.compose.material.icons.filled.FilterAlt
import androidx.compose.material.icons.filled.PrecisionManufacturing
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

// ============================================================================
// 骆驼高精度模式开关页
// ============================================================================

/**
 * 骆驼高精度模式页面
 *
 * 布局结构：
 * - 顶部：TopAppBar + 返回按钮
 * - 顶部大图标区：盾牌图标 + 呼吸灯动画
 * - 中间大卡片：高精度模式总开关 + 弹跳动画
 * - 开关下方：模式说明文字
 * - 底部：4个FeatureItem特性列表
 *
 * @param onBack 返回上一页回调
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CamelScreen(onBack: () -> Unit) {
    // ---- 高精度模式开关状态 ----
    var camelEnabled by remember { mutableStateOf(false) }

    // ---- FeatureItem可见性状态（4个特性） ----
    val featureVisibleStates = remember {
        List(4) { mutableStateOf(false) }
    }

    // ---- 开关切换时控制FeatureItem的入场 ----
    LaunchedEffect(camelEnabled) {
        if (camelEnabled) {
            // 开启时：依次显示，间隔100ms
            featureVisibleStates.forEachIndexed { index, state ->
                delay(100L)
                state.value = true
            }
        } else {
            // 关闭时：同时隐藏
            featureVisibleStates.forEach { state ->
                state.value = false
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxSize()
    ) {
        // ==========================================
        // 顶部导航栏
        // ==========================================
        TopAppBar(
            title = {
                Text(
                    text = "骆驼高精度模式",
                    fontWeight = FontWeight.Bold
                )
            },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "返回"
                    )
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.surface
            )
        )

        // ==========================================
        // 可滚动内容区
        // ==========================================
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // ==========================================
            // 顶部大图标区：盾牌 + 呼吸灯动画
            // ==========================================
            BreathingShieldIcon(enabled = camelEnabled)

            Spacer(modifier = Modifier.height(24.dp))

            // ==========================================
            // 中间大卡片：总开关
            // ==========================================
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                ),
                shape = MaterialTheme.shapes.large
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // 大圆形开关按钮（使用弹跳动画的Switch）
                    val switchScale by animateSwitchBounce(camelEnabled)

                    Box(
                        modifier = Modifier
                            .size(80.dp)
                            .scale(switchScale)
                            .clip(CircleShape)
                            .background(
                                color = if (camelEnabled)
                                    MaterialTheme.colorScheme.primary
                                else
                                    MaterialTheme.colorScheme.surfaceVariant
                            )
                            .then(
                                if (!camelEnabled) {
                                    Modifier.border(
                                        width = 2.dp,
                                        color = MaterialTheme.colorScheme.outline,
                                        shape = CircleShape
                                    )
                                } else {
                                    Modifier
                                }
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Shield,
                            contentDescription = null,
                            tint = if (camelEnabled)
                                MaterialTheme.colorScheme.onPrimary
                            else
                                MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(40.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Text(
                        text = if (camelEnabled) "高精度模式已开启" else "高精度模式已关闭",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (camelEnabled)
                            MaterialTheme.colorScheme.primary
                        else
                            MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    // Switch组件（实际控制开关）
                    Switch(
                        checked = camelEnabled,
                        onCheckedChange = { camelEnabled = it },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                            checkedTrackColor = MaterialTheme.colorScheme.primary
                        )
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    // 模式说明文字
                    Text(
                        text = if (camelEnabled) {
                            "高精度模式已激活\n" +
                                    "系统将以10秒间隔采集内核级流量数据，\n" +
                                    "按分钟粒度聚合统计，\n" +
                                    "与运营商计费周期精准对齐。"
                        } else {
                            "开启高精度模式后，\n" +
                                    "将以10秒间隔进行内核采样，\n" +
                                    "提供分钟级精准流量统计，\n" +
                                    "帮助您精确掌握每一点流量消耗。"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        lineHeight = MaterialTheme.typography.bodyMedium.lineHeight
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // ==========================================
            // 底部特性列表
            // ==========================================
            Text(
                text = "核心特性",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp)
            )

            // 特性1：10秒内核采样
            CamelFeatureItem(
                visible = featureVisibleStates[0].value,
                icon = Icons.Default.PrecisionManufacturing,
                title = "10秒内核采样",
                description = "直接读取/proc/net/dev内核数据，10秒级精度采集每个网络接口的实时流量",
                enabled = camelEnabled
            )

            Spacer(modifier = Modifier.height(8.dp))

            // 特性2：分钟级数据聚合
            CamelFeatureItem(
                visible = featureVisibleStates[1].value,
                icon = Icons.Default.BarChart,
                title = "分钟级数据聚合",
                description = "将高频采样数据按分钟窗口聚合，生成平滑且精准的分钟级流量趋势",
                enabled = camelEnabled
            )

            Spacer(modifier = Modifier.height(8.dp))

            // 特性3：前台/后台流量区分
            CamelFeatureItem(
                visible = featureVisibleStates[2].value,
                icon = Icons.Default.FilterAlt,
                title = "前台/后台流量区分",
                description = "通过UID追踪每个应用的前台与后台流量消耗，精准定位流量异常应用",
                enabled = camelEnabled
            )

            Spacer(modifier = Modifier.height(8.dp))

            // 特性4：运营商计费对齐
            CamelFeatureItem(
                visible = featureVisibleStates[3].value,
                icon = Icons.Default.DeviceHub,
                title = "运营商计费对齐",
                description = "统计周期与运营商计费周期对齐，确保应用内流量数据与运营商账单一致",
                enabled = camelEnabled
            )

            // 底部留白
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

// ============================================================================
// 呼吸灯盾牌图标
// ============================================================================

/**
 * 带呼吸灯效果的盾牌图标
 * 开启时alpha脉冲动画，关闭时保持低透明度
 *
 * @param enabled 是否开启
 */
@Composable
private fun BreathingShieldIcon(enabled: Boolean) {
    // 呼吸灯动画：alpha在0.4~1.0之间脉冲
    val infiniteTransition = rememberInfiniteTransition(label = "breathing")
    val breathingAlpha by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "breathing_alpha"
    )

    // 关闭时使用固定低透明度
    val alpha = if (enabled) breathingAlpha else 0.3f

    Box(
        modifier = Modifier
            .size(100.dp)
            .alpha(alpha),
        contentAlignment = Alignment.Center
    ) {
        // 外层光圈
        Box(
            modifier = Modifier
                .size(90.dp)
                .clip(CircleShape)
                .background(
                    if (enabled)
                        MaterialTheme.colorScheme.primaryContainer
                    else
                        MaterialTheme.colorScheme.surfaceVariant
                ),
            contentAlignment = Alignment.Center
        ) {
            // 内层图标区
            Box(
                modifier = Modifier
                    .size(70.dp)
                    .clip(CircleShape)
                    .background(
                        if (enabled)
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
                        else
                            MaterialTheme.colorScheme.surfaceVariant
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Shield,
                    contentDescription = null,
                    tint = if (enabled)
                        MaterialTheme.colorScheme.primary
                    else
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.size(36.dp)
                )
            }
        }
    }
}

// ============================================================================
// 开关弹跳动画
// ============================================================================

/**
 * Switch切换时的弹跳缩放动画
 *
 * @param enabled 开关状态
 * @return 缩放值
 */
@Composable
private fun animateSwitchBounce(enabled: Boolean): Float {
    var targetScale by remember { mutableStateOf(1f) }
    var isAnimating by remember { mutableStateOf(false) }

    LaunchedEffect(enabled) {
        if (!isAnimating) {
            isAnimating = true
            targetScale = 0.85f
            kotlinx.coroutines.delay(100L)
            targetScale = 1.1f
            kotlinx.coroutines.delay(100L)
            targetScale = 0.95f
            kotlinx.coroutines.delay(80L)
            targetScale = 1f
            isAnimating = false
        }
    }

    val scale by androidx.compose.animation.core.animateFloatAsState(
        targetValue = targetScale,
        animationSpec = spring(
            stiffness = androidx.compose.animation.core.Spring.StiffnessMedium,
            dampingRatio = androidx.compose.animation.core.Spring.DampingRatioMediumBouncy
        ),
        label = "switch_bounce"
    )

    return scale
}

// ============================================================================
// 骆驼模式特性项
// ============================================================================

/**
 * 骆驼模式特性项
 * 开启时依次入场，关闭时整体变灰
 *
 * @param visible 是否可见
 * @param icon 图标
 * @param title 标题
 * @param description 描述
 * @param enabled 是否启用（控制alpha）
 */
@Composable
private fun CamelFeatureItem(
    visible: Boolean,
    icon: ImageVector,
    title: String,
    description: String,
    enabled: Boolean
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(
            animationSpec = tween(300, easing = FastOutSlowInEasing)
        ) + slideInVertically(
            initialOffsetY = { it / 4 },
            animationSpec = tween(300, easing = FastOutSlowInEasing)
        )
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            ),
            shape = MaterialTheme.shapes.medium
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.Top
            ) {
                // 图标
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(
                            if (enabled)
                                MaterialTheme.colorScheme.primaryContainer
                            else
                                MaterialTheme.colorScheme.surfaceVariant
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = if (enabled)
                            MaterialTheme.colorScheme.primary
                        else
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f),
                        modifier = Modifier.size(20.dp)
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                // 标题和描述
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .alpha(if (enabled) 1f else 0.5f)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Bold,
                            color = if (enabled)
                                MaterialTheme.colorScheme.onSurface
                            else
                                MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        if (enabled) {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = "已启用",
                                tint = Color(0xFF4CAF50),
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        lineHeight = MaterialTheme.typography.bodySmall.lineHeight
                    )
                }
            }
        }
    }
}