package com.liuyuguard.ui.base

import androidx.compose.runtime.Composable

// ============================================================================
// Composable 页面基类
// 所有Compose页面继承此接口约定
// ============================================================================

/**
 * Compose页面约定接口
 *
 * 规则：
 * 1. 页面仅做渲染和用户交互
 * 2. 禁止在@Composable中调用任何权限/网络/Shell操作
 * 3. 所有数据通过ViewModel获取
 * 4. 所有操作通过ViewModel.dispatchCommand下发
 */
interface IComposePage {
    /** 页面路由路径 */
    val route: String

    /** 页面标题 */
    val title: String
        @Composable get

    /** 页面内容（Compose） */
    @Composable
    fun Content()
}

// ============================================================================
// 页面路由常量
// ============================================================================

object Routes {
    const val DASHBOARD = "dashboard"
    const val APP_DETAIL = "app_detail"
    const val CONTROL_PANEL = "control_panel"
    const val CHARTS = "charts"
    const val SETTINGS = "settings"
    const val CAMEL = "camel"
    const val SIM_CARD = "sim_card"
}