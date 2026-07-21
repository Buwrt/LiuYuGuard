package com.liuyuguard.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Brightness6
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.PermDeviceInformation
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

// ============================================================================
// 全局设置中心页
// ============================================================================

/**
 * 设置页面
 *
 * 布局结构（LazyColumn）：
 * - 服务控制组：主服务开关、守护服务开关
 * - 闲置管控组：闲置断网开关、闲置超时时间Slider
 * - 骆驼模式组：跳转到骆驼模式页面的入口
 * - 权限信息组：Root/Shizuku权限状态、运行模式
 * - 其他组：主题切换、关于信息
 *
 * @param onNavigateToCamel 跳转到骆驼模式页面
 */
@Composable
fun SettingsScreen(onNavigateToCamel: () -> Unit) {
    // ---- 服务控制状态 ----
    var mainServiceEnabled by remember { mutableStateOf(true) }
    var daemonServiceEnabled by remember { mutableStateOf(true) }

    // ---- 闲置管控状态 ----
    var idleDisconnectEnabled by remember { mutableStateOf(false) }
    var idleTimeoutMinutes by remember { mutableFloatStateOf(5f) }

    // ---- 主题选择（0=浅色, 1=深色, 2=跟随系统） ----
    var themeIndex by remember { mutableStateOf(2) }

    // ---- 权限信息（模拟数据） ----
    val hasRootPermission = remember { true }
    val hasShizukuPermission = remember { false }
    val currentRunMode = remember {
        if (hasRootPermission) "Root" else if (hasShizukuPermission) "Shizuku" else "未授权"
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize()
    ) {
        // ==========================================
        // 第一组：服务控制
        // ==========================================
        item {
            SectionTitle(text = "服务控制")
        }

        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                ),
                shape = MaterialTheme.shapes.medium
            ) {
                Column {
                    // 主服务开关
                    SettingSwitchItem(
                        icon = Icons.Default.NetworkCheck,
                        iconTint = MaterialTheme.colorScheme.primary,
                        title = "主服务",
                        description = "流量监控主服务，负责统计和拦截",
                        checked = mainServiceEnabled,
                        onCheckedChange = { mainServiceEnabled = it }
                    )

                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                        modifier = Modifier.padding(start = 56.dp)
                    )

                    // 守护服务开关
                    SettingSwitchItem(
                        icon = Icons.Default.Shield,
                        iconTint = MaterialTheme.colorScheme.tertiary,
                        title = "守护服务",
                        description = "后台常驻守护，防止服务被系统杀死",
                        checked = daemonServiceEnabled,
                        onCheckedChange = { daemonServiceEnabled = it }
                    )
                }
            }
        }

        // ==========================================
        // 第二组：闲置管控
        // ==========================================
        item {
            SectionTitle(text = "闲置管控")
        }

        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                ),
                shape = MaterialTheme.shapes.medium
            ) {
                Column {
                    // 闲置断网开关
                    SettingSwitchItem(
                        icon = Icons.Default.Timer,
                        iconTint = MaterialTheme.colorScheme.error,
                        title = "闲置断网",
                        description = "设备闲置一段时间后自动断开网络",
                        checked = idleDisconnectEnabled,
                        onCheckedChange = { idleDisconnectEnabled = it }
                    )

                    // 闲置超时时间Slider（仅开启时可用）
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                        modifier = Modifier.padding(start = 56.dp)
                    )

                    SettingSliderItem(
                        icon = Icons.Default.Speed,
                        iconTint = if (idleDisconnectEnabled)
                            MaterialTheme.colorScheme.secondary
                        else
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f),
                        title = "闲置超时时间",
                        value = idleTimeoutMinutes,
                        valueRange = 1f..30f,
                        enabled = idleDisconnectEnabled,
                        onValueChange = { idleTimeoutMinutes = it },
                        valueText = "${idleTimeoutMinutes.toInt()} 分钟"
                    )
                }
            }
        }

        // ==========================================
        // 第三组：骆驼模式
        // ==========================================
        item {
            SectionTitle(text = "骆驼模式")
        }

        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                ),
                shape = MaterialTheme.shapes.medium
            ) {
                ListItem(
                    headlineContent = {
                        Text(
                            text = "骆驼高精度模式",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    },
                    supportingContent = {
                        Text(
                            text = "10秒采样、分钟聚合、精准流量统计",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    },
                    leadingContent = {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.tertiaryContainer),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Bolt,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onTertiaryContainer,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    },
                    trailingContent = {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = "跳转",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    },
                    modifier = Modifier.clickable { onNavigateToCamel() }
                )
            }
        }

        // ==========================================
        // 第四组：权限信息
        // ==========================================
        item {
            SectionTitle(text = "权限信息")
        }

        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                ),
                shape = MaterialTheme.shapes.medium
            ) {
                Column {
                    // Root权限状态
                    SettingInfoItem(
                        icon = Icons.Default.Security,
                        iconTint = if (hasRootPermission)
                            MaterialTheme.colorScheme.primary
                        else
                            MaterialTheme.colorScheme.error,
                        title = "Root 权限",
                        description = if (hasRootPermission) "已获取" else "未获取",
                        descriptionColor = if (hasRootPermission)
                            Color(0xFF4CAF50)
                        else
                            MaterialTheme.colorScheme.error
                    )

                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                        modifier = Modifier.padding(start = 56.dp)
                    )

                    // Shizuku权限状态
                    SettingInfoItem(
                        icon = Icons.Default.PermDeviceInformation,
                        iconTint = if (hasShizukuPermission)
                            MaterialTheme.colorScheme.primary
                        else
                            MaterialTheme.colorScheme.error,
                        title = "Shizuku 权限",
                        description = if (hasShizukuPermission) "已连接" else "未连接",
                        descriptionColor = if (hasShizukuPermission)
                            Color(0xFF4CAF50)
                        else
                            MaterialTheme.colorScheme.error
                    )

                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                        modifier = Modifier.padding(start = 56.dp)
                    )

                    // 运行模式
                    SettingInfoItem(
                        icon = Icons.Default.Shield,
                        iconTint = MaterialTheme.colorScheme.secondary,
                        title = "当前运行模式",
                        description = currentRunMode,
                        descriptionColor = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // ==========================================
        // 第五组：其他
        // ==========================================
        item {
            SectionTitle(text = "其他")
        }

        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                ),
                shape = MaterialTheme.shapes.medium
            ) {
                Column {
                    // 主题切换
                    SettingThemeItem(
                        currentIndex = themeIndex,
                        onThemeSelected = { themeIndex = it }
                    )

                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                        modifier = Modifier.padding(start = 56.dp)
                    )

                    // 关于
                    SettingInfoItem(
                        icon = Icons.Default.Info,
                        iconTint = MaterialTheme.colorScheme.onSurfaceVariant,
                        title = "关于",
                        description = "LiuYu Guard v1.0.0",
                        descriptionColor = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // 底部留白
        item {
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

// ============================================================================
// 设置项组件
// ============================================================================

/**
 * 分组标题
 */
@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 6.dp)
    )
}

/**
 * 带Switch的设置项
 */
@Composable
private fun SettingSwitchItem(
    icon: ImageVector,
    iconTint: Color,
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    ListItem(
        headlineContent = {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
        },
        supportingContent = {
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        leadingContent = {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(iconTint.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = iconTint,
                    modifier = Modifier.size(22.dp)
                )
            }
        },
        trailingContent = {
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                    checkedTrackColor = MaterialTheme.colorScheme.primary
                )
            )
        }
    )
}

/**
 * 带Slider的设置项
 */
@Composable
private fun SettingSliderItem(
    icon: ImageVector,
    iconTint: Color,
    title: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    enabled: Boolean,
    onValueChange: (Float) -> Unit,
    valueText: String
) {
    ListItem(
        headlineContent = {
            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (enabled)
                        MaterialTheme.colorScheme.onSurface
                    else
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                )
                Spacer(modifier = Modifier.height(8.dp))
                // 超时数值显示
                Text(
                    text = valueText,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = if (enabled)
                        MaterialTheme.colorScheme.primary
                    else
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                )
            }
        },
        leadingContent = {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(iconTint.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = iconTint,
                    modifier = Modifier.size(22.dp)
                )
            }
        }
    )

    // Slider放在ListItem下方，独立占满宽度
    Slider(
        value = value,
        onValueChange = onValueChange,
        valueRange = valueRange,
        enabled = enabled,
        colors = SliderDefaults.colors(
            thumbColor = MaterialTheme.colorScheme.primary,
            activeTrackColor = MaterialTheme.colorScheme.primary,
            inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 0.dp)
    )
}

/**
 * 纯信息展示的设置项
 */
@Composable
private fun SettingInfoItem(
    icon: ImageVector,
    iconTint: Color,
    title: String,
    description: String,
    descriptionColor: Color
) {
    ListItem(
        headlineContent = {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
        },
        supportingContent = {
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = descriptionColor
            )
        },
        leadingContent = {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(iconTint.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = iconTint,
                    modifier = Modifier.size(22.dp)
                )
            }
        }
    )
}

/**
 * 主题切换设置项
 * 使用3个选项卡：浅色/深色/跟随系统
 */
@Composable
private fun SettingThemeItem(
    currentIndex: Int,
    onThemeSelected: (Int) -> Unit
) {
    val themeOptions = listOf(
        Triple(Icons.Default.LightMode, "浅色", 0),
        Triple(Icons.Default.DarkMode, "深色", 1),
        Triple(Icons.Default.Brightness6, "跟随系统", 2)
    )

    ListItem(
        headlineContent = {
            Text(
                text = "主题",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
        },
        leadingContent = {
            val currentIcon = themeOptions[currentIndex].first
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = currentIcon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(22.dp)
                )
            }
        },
        trailingContent = {
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                themeOptions.forEach { (_, label, index) ->
                    val isSelected = currentIndex == index
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = if (isSelected)
                                MaterialTheme.colorScheme.primary
                            else
                                MaterialTheme.colorScheme.surface
                        ),
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.clickable { onThemeSelected(index) }
                    ) {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isSelected)
                                MaterialTheme.colorScheme.onPrimary
                            else
                                MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                        )
                    }
                }
            }
        }
    )
}