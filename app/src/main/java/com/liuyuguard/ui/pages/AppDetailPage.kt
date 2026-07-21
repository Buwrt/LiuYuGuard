package com.liuyuguard.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.liuyuguard.model.AppTrafficInfo
import com.liuyuguard.ui.animations.listItemClick
import com.liuyuguard.ui.theme.TrafficColors
import com.liuyuguard.util.TrafficFormatter

// ============================================================================
// 排序模式枚举
// ============================================================================

/** 列表排序方式 */
private enum class SortMode(val label: String) {
    /** 按下载量降序 */
    BY_DOWNLOAD("按下载量"),
    /** 按上传量降序 */
    BY_UPLOAD("按上传量"),
    /** 按总流量降序 */
    BY_TOTAL("按总流量")
}

// ============================================================================
// 模拟数据
// ============================================================================

/** 10个模拟应用数据 */
private val mockAppList = listOf(
    AppTrafficInfo(
        uid = 10001,
        packageName = "com.tencent.mm",
        appName = "微信",
        rxBytes = 523_456_789L,
        txBytes = 108_234_567L,
        isBlockedWifi = false,
        isBlockedCellular = false
    ),
    AppTrafficInfo(
        uid = 10002,
        packageName = "com.tencent.mobileqq",
        appName = "QQ",
        rxBytes = 312_678_432L,
        txBytes = 78_543_210L,
        isBlockedWifi = false,
        isBlockedCellular = false
    ),
    AppTrafficInfo(
        uid = 10003,
        packageName = "com.eg.android.AlipayGphone",
        appName = "支付宝",
        rxBytes = 198_765_432L,
        txBytes = 45_678_900L,
        isBlockedWifi = false,
        isBlockedCellular = true
    ),
    AppTrafficInfo(
        uid = 10004,
        packageName = "com.android.chrome",
        appName = "Chrome浏览器",
        rxBytes = 156_432_109L,
        txBytes = 23_456_789L,
        isBlockedWifi = false,
        isBlockedCellular = false
    ),
    AppTrafficInfo(
        uid = 10005,
        packageName = "tv.danmaku.bili",
        appName = "哔哩哔哩",
        rxBytes = 287_654_321L,
        txBytes = 12_345_678L,
        isBlockedWifi = true,
        isBlockedCellular = true
    ),
    AppTrafficInfo(
        uid = 10006,
        packageName = "com.ss.android.ugc.aweme",
        appName = "抖音",
        rxBytes = 456_789_012L,
        txBytes = 34_567_890L,
        isBlockedWifi = false,
        isBlockedCellular = false
    ),
    AppTrafficInfo(
        uid = 10007,
        packageName = "com.android.vending",
        appName = "应用商店",
        rxBytes = 67_890_123L,
        txBytes = 8_901_234L,
        isBlockedWifi = false,
        isBlockedCellular = false
    ),
    AppTrafficInfo(
        uid = 10008,
        packageName = "com.netease.cloudmusic",
        appName = "网易云音乐",
        rxBytes = 89_012_345L,
        txBytes = 5_678_901L,
        isBlockedWifi = false,
        isBlockedCellular = false
    ),
    AppTrafficInfo(
        uid = 10009,
        packageName = "com.sina.weibo",
        appName = "微博",
        rxBytes = 134_567_890L,
        txBytes = 18_765_432L,
        isBlockedWifi = false,
        isBlockedCellular = false
    ),
    AppTrafficInfo(
        uid = 10010,
        packageName = "com.jingdong.app.mall",
        appName = "京东",
        rxBytes = 78_901_234L,
        txBytes = 11_234_567L,
        isBlockedWifi = false,
        isBlockedCellular = false
    )
)

// ============================================================================
// 应用流量明细列表页
// ============================================================================

/**
 * 应用流量明细列表页
 *
 * 功能：
 * - LazyColumn 展示应用列表
 * - 顶部搜索栏（搜索应用名）
 * - 排序选项（按下载量/上传量/总流量）
 * - 每项包含：应用图标占位(首字母圆形)、应用名、上传/下载流量、断网状态指示器
 * - 点击项跳转到联网控制面板（传递UID）
 * - 列表项带点击缩放动效
 * - 列表项之间有分割线
 *
 * @param onNavigateToControl 点击应用项时跳转到联网控制面板，传递UID
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppDetailScreen(
    onNavigateToControl: (Int) -> Unit
) {
    // ---- 搜索关键词 ----
    var searchQuery by remember { mutableStateOf("") }

    // ---- 当前排序方式 ----
    var sortMode by remember { mutableStateOf(SortMode.BY_DOWNLOAD) }

    // ---- 根据搜索和排序过滤/排列数据 ----
    val filteredList = remember(mockAppList, searchQuery, sortMode) {
        mockAppList
            .filter { app ->
                // 搜索过滤：匹配应用名或包名
                searchQuery.isBlank() ||
                        app.appName.contains(searchQuery, ignoreCase = true) ||
                        app.packageName.contains(searchQuery, ignoreCase = true)
            }
            .sortedWith(
                when (sortMode) {
                    SortMode.BY_DOWNLOAD -> compareByDescending<AppTrafficInfo> { it.rxBytes }
                    SortMode.BY_UPLOAD -> compareByDescending { it.txBytes }
                    SortMode.BY_TOTAL -> compareByDescending { it.rxBytes + it.txBytes }
                }
            )
    }

    Column(
        modifier = Modifier.fillMaxSize()
    ) {
        // ==========================================
        // 顶部搜索栏
        // ==========================================
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            placeholder = {
                Text("搜索应用名称...")
            },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Default.Search,
                    contentDescription = "搜索"
                )
            },
            singleLine = true,
            shape = MaterialTheme.shapes.large,
            colors = OutlinedTextFieldDefaults.colors(
                unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                focusedBorderColor = MaterialTheme.colorScheme.primary
            )
        )

        // ==========================================
        // 排序选项（FilterChip横向排列）
        // ==========================================
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SortMode.entries.forEach { mode ->
                FilterChip(
                    selected = sortMode == mode,
                    onClick = { sortMode = mode },
                    label = {
                        Text(
                            text = mode.label,
                            style = MaterialTheme.typography.labelMedium
                        )
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                )
            }
        }

        // ==========================================
        // 应用列表
        // ==========================================
        if (filteredList.isEmpty()) {
            // 空状态
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "没有找到匹配的应用",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(vertical = 4.dp)
            ) {
                itemsIndexed(
                    items = filteredList,
                    key = { _, app -> app.uid }
                ) { index, app ->
                    // 列表项
                    AppTrafficItem(
                        app = app,
                        onClick = { onNavigateToControl(app.uid) }
                    )

                    // 列表项之间的分割线（最后一项不加）
                    if (index < filteredList.size - 1) {
                        HorizontalDivider(
                            modifier = Modifier.padding(horizontal = 72.dp),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                        )
                    }
                }
            }
        }
    }
}

// ============================================================================
// 单个应用流量列表项
// ============================================================================

/**
 * 应用流量列表项
 *
 * 布局：[首字母圆形图标] [应用名 + 流量信息] [断网指示器]
 * 带点击缩放动效
 *
 * @param app 应用流量数据
 * @param onClick 点击回调
 */
@Composable
private fun AppTrafficItem(
    app: AppTrafficInfo,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .listItemClick { onClick() }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // ---- 应用图标占位：首字母圆形 ----
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = app.appName.firstOrNull()?.toString() ?: "?",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                fontWeight = FontWeight.Bold
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        // ---- 应用名 + 流量信息 ----
        Column(
            modifier = Modifier.weight(1f)
        ) {
            // 应用名
            Text(
                text = app.appName,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(2.dp))

            // 上传/下载流量
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // 下载
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(TrafficColors.rxColor())
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "↓ ${TrafficFormatter.format(app.rxBytes)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // 上传
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(TrafficColors.txColor())
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "↑ ${TrafficFormatter.format(app.txBytes)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        Spacer(modifier = Modifier.width(8.dp))

        // ---- 断网状态指示器 ----
        if (app.isBlockedTotal) {
            // 全网断网：红色圆点
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(TrafficColors.blockedColor())
            )
        } else if (app.isBlockedWifi || app.isBlockedCellular) {
            // 部分断网：橙色圆点
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(TrafficColors.warningColor())
            )
        }
        // 未断网时不显示指示器
    }
}