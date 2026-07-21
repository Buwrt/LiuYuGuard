package com.liuyuguard.ui.pages

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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Blocked
import androidx.compose.material.icons.filled.NetworkCell
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.liuyuguard.ui.theme.TrafficColors
import com.liuyuguard.util.TrafficFormatter
import kotlinx.coroutines.launch

// ============================================================================
// 模拟数据
// ============================================================================

/** 根据UID查找模拟应用信息 */
private fun getMockAppInfo(uid: Int): MockAppInfo {
    // 根据UID匹配模拟数据，未匹配则返回默认
    return when (uid) {
        10001 -> MockAppInfo("微信", 523_456_789L, 108_234_567L, false, false)
        10002 -> MockAppInfo("QQ", 312_678_432L, 78_543_210L, false, false)
        10003 -> MockAppInfo("支付宝", 198_765_432L, 45_678_900L, false, true)
        10004 -> MockAppInfo("Chrome浏览器", 156_432_109L, 23_456_789L, false, false)
        10005 -> MockAppInfo("哔哩哔哩", 287_654_321L, 12_345_678L, true, true)
        10006 -> MockAppInfo("抖音", 456_789_012L, 34_567_890L, false, false)
        10007 -> MockAppInfo("应用商店", 67_890_123L, 8_901_234L, false, false)
        10008 -> MockAppInfo("网易云音乐", 89_012_345L, 5_678_901L, false, false)
        10009 -> MockAppInfo("微博", 134_567_890L, 18_765_432L, false, false)
        10010 -> MockAppInfo("京东", 78_901_234L, 11_234_567L, false, false)
        else -> MockAppInfo("应用 (UID:$uid)", 0L, 0L, false, false)
    }
}

/** 模拟应用信息（用于控制面板展示） */
private data class MockAppInfo(
    val appName: String,
    val rxBytes: Long,
    val txBytes: Long,
    val isBlockedWifi: Boolean,
    val isBlockedCellular: Boolean
)

// ============================================================================
// 单应用联网控制面板
// ============================================================================

/**
 * 单应用联网控制面板
 *
 * 功能：
 * - TopAppBar 带返回按钮，显示应用名
 * - 3个大开关卡片（竖排）：
 *   1. 全网断网（WiFi+蜂窝都断）
 *   2. WiFi单独断网
 *   3. 蜂窝单独断网
 * - 每个开关使用 Switch 组件，带状态动画
 * - 底部显示该应用当前流量统计
 * - 开关操作时显示 Snackbar 提示
 *
 * @param uid 应用UID
 * @param onBack 返回按钮回调
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ControlPanelScreen(
    uid: Int,
    onBack: () -> Unit
) {
    // ---- Snackbar 状态 ----
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // ---- 获取模拟应用信息 ----
    val appInfo = remember(uid) { getMockAppInfo(uid) }

    // ---- 断网开关状态 ----
    var blockTotal by remember { mutableStateOf(appInfo.isBlockedWifi && appInfo.isBlockedCellular) }
    var blockWifi by remember { mutableStateOf(appInfo.isBlockedWifi) }
    var blockCellular by remember { mutableStateOf(appInfo.isBlockedCellular) }

    // ---- 显示Snackbar的辅助函数 ----
    fun showMessage(message: String) {
        scope.launch {
            snackbarHostState.showSnackbar(message)
        }
    }

    // ---- 开关联动逻辑 ----
    // 全网断网开关变化 -> 联动WiFi和蜂窝
    fun onTotalBlockChanged(value: Boolean) {
        blockTotal = value
        blockWifi = value
        blockCellular = value
        showMessage(
            if (value) "已开启全网断网：${appInfo.appName}"
            else "已关闭全网断网：${appInfo.appName}"
        )
    }

    // WiFi单独断网变化 -> 更新全网状态
    fun onWifiBlockChanged(value: Boolean) {
        blockWifi = value
        blockTotal = blockWifi && blockCellular
        showMessage(
            if (value) "已断开 ${appInfo.appName} 的WiFi网络"
            else "已恢复 ${appInfo.appName} 的WiFi网络"
        )
    }

    // 蜂窝单独断网变化 -> 更新全网状态
    fun onCellularBlockChanged(value: Boolean) {
        blockCellular = value
        blockTotal = blockWifi && blockCellular
        showMessage(
            if (value) "已断开 ${appInfo.appName} 的蜂窝网络"
            else "已恢复 ${appInfo.appName} 的蜂窝网络"
        )
    }

    // ---- 页面进入时，如果处于全网断网状态则提示 ----
    LaunchedEffect(uid) {
        if (appInfo.isBlockedWifi && appInfo.isBlockedCellular) {
            snackbarHostState.showSnackbar("${appInfo.appName} 当前处于全网断网状态")
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = appInfo.appName,
                        maxLines = 1
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.Default.ArrowBack,
                            contentDescription = "返回"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // ==========================================
            // 开关1：全网断网
            // ==========================================
            NetworkSwitchCard(
                title = "全网断网",
                description = "同时断开WiFi和蜂窝网络连接",
                icon = Icons.Default.Blocked,
                isBlocked = blockTotal,
                onCheckedChanged = ::onTotalBlockChanged,
                accentColor = TrafficColors.blockedColor()
            )

            // ==========================================
            // 开关2：WiFi单独断网
            // ==========================================
            NetworkSwitchCard(
                title = "WiFi断网",
                description = "仅断开WiFi网络，蜂窝正常",
                icon = Icons.Default.WifiOff,
                isBlocked = blockWifi,
                onCheckedChanged = ::onWifiBlockChanged,
                accentColor = MaterialTheme.colorScheme.tertiary
            )

            // ==========================================
            // 开关3：蜂窝单独断网
            // ==========================================
            NetworkSwitchCard(
                title = "蜂窝断网",
                description = "仅断开蜂窝网络，WiFi正常",
                icon = Icons.Default.NetworkCell,
                isBlocked = blockCellular,
                onCheckedChanged = ::onCellularBlockChanged,
                accentColor = MaterialTheme.colorScheme.secondary
            )

            Spacer(modifier = Modifier.height(8.dp))

            // ==========================================
            // 底部：当前流量统计
            // ==========================================
            TrafficStatsCard(
                appName = appInfo.appName,
                rxBytes = appInfo.rxBytes,
                txBytes = appInfo.txBytes
            )

            // 底部留白
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

// ============================================================================
// 网络控制开关卡片
// ============================================================================

/**
 * 网络控制开关卡片
 *
 * 包含标题、描述文字、图标和Switch开关
 * 断网时卡片边框/背景变为红色调，开启时恢复正常
 *
 * @param title 开关标题
 * @param description 功能描述
 * @param icon 左侧图标
 * @param isBlocked 是否已断网
 * @param onCheckedChanged 开关切换回调
 * @param accentColor 强调色
 */
@Composable
private fun NetworkSwitchCard(
    title: String,
    description: String,
    icon: ImageVector,
    isBlocked: Boolean,
    onCheckedChanged: (Boolean) -> Unit,
    accentColor: Color
) {
    val containerColor = if (isBlocked) {
        MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = containerColor
        ),
        shape = MaterialTheme.shapes.medium
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 左侧：图标
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(
                        if (isBlocked) {
                            TrafficColors.blockedColor().copy(alpha = 0.15f)
                        } else {
                            accentColor.copy(alpha = 0.12f)
                        }
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = title,
                    tint = if (isBlocked) {
                        TrafficColors.blockedColor()
                    } else {
                        accentColor
                    },
                    modifier = Modifier.size(24.dp)
                )
            }

            Spacer(modifier = Modifier.width(14.dp))

            // 中间：标题 + 描述
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            // 右侧：Switch开关
            Switch(
                checked = isBlocked,
                onCheckedChange = onCheckedChanged,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = MaterialTheme.colorScheme.onErrorContainer,
                    checkedTrackColor = TrafficColors.blockedColor(),
                    uncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant
                )
            )
        }
    }
}

// ============================================================================
// 流量统计卡片
// ============================================================================

/**
 * 应用流量统计卡片
 * 显示应用当前的上下行流量数据
 *
 * @param appName 应用名称
 * @param rxBytes 下载字节数
 * @param txBytes 上传字节数
 */
@Composable
private fun TrafficStatsCard(
    appName: String,
    rxBytes: Long,
    txBytes: Long
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
            Text(
                text = "流量统计",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.Medium
            )

            Spacer(modifier = Modifier.height(12.dp))

            // 下载流量
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(TrafficColors.rxColor())
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "累计下载",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = TrafficFormatter.format(rxBytes),
                    style = MaterialTheme.typography.bodyMedium,
                    color = TrafficColors.rxColor(),
                    fontWeight = FontWeight.SemiBold
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            // 上传流量
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(TrafficColors.txColor())
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "累计上传",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = TrafficFormatter.format(txBytes),
                    style = MaterialTheme.typography.bodyMedium,
                    color = TrafficColors.txColor(),
                    fontWeight = FontWeight.SemiBold
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            // 总流量
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "总流量",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = TrafficFormatter.format(rxBytes + txBytes),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}