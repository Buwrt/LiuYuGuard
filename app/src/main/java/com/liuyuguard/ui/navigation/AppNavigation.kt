package com.liuyuguard.ui.navigation

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SimCard
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.liuyuguard.model.RunMode
import com.liuyuguard.ui.animations.LiuYuMotion
import com.liuyuguard.ui.base.Routes
import com.liuyuguard.ui.pages.AppDetailScreen
import com.liuyuguard.ui.pages.CamelScreen
import com.liuyuguard.ui.pages.ChartsScreen
import com.liuyuguard.ui.pages.ControlPanelScreen
import com.liuyuguard.ui.pages.DashboardScreen
import com.liuyuguard.ui.pages.SettingsScreen
import com.liuyuguard.ui.pages.SimCardScreen

// ============================================================================
// 底部导航项数据
// ============================================================================

/** 底部Tab导航项 */
private data class BottomNavItem(
    val route: String,
    val label: String,
    val icon: ImageVector
)

/** 5个底部Tab：看板、明细、图表、双卡、设置 */
private val bottomNavItems = listOf(
    BottomNavItem(Routes.DASHBOARD, "看板", Icons.Default.Dashboard),
    BottomNavItem(Routes.APP_DETAIL, "明细", Icons.Default.List),
    BottomNavItem(Routes.CHARTS, "图表", Icons.Default.BarChart),
    BottomNavItem(Routes.SIM_CARD, "双卡", Icons.Default.SimCard),
    BottomNavItem(Routes.SETTINGS, "设置", Icons.Default.Settings)
)

/** 所有底部Tab的路由集合，用于判断是否显示底部栏 */
private val bottomTabRoutes = bottomNavItems.map { it.route }.toSet()

// ============================================================================
// 页面切换动画
// ============================================================================

/** Tab间切换动画时长 */
private const val TAB_TRANSITION_MS = 250

/**
 * Tab间切换时的进入动画 —— 平滑淡入
 */
private fun tabEnterTransition(): EnterTransition =
    fadeIn(animationSpec = tween(TAB_TRANSITION_MS, easing = FastOutSlowInEasing))

/**
 * Tab间切换时的退出动画 —— 平滑淡出
 */
private fun tabExitTransition(): ExitTransition =
    fadeOut(animationSpec = tween(TAB_TRANSITION_MS, easing = FastOutSlowInEasing))

/**
 * 二级页面推入时的进入动画 —— 从右侧滑入 + 淡入
 */
private fun pushEnterTransition(): EnterTransition =
    slideInHorizontally(
        initialOffsetX = { it / 3 },
        animationSpec = tween(LiuYuMotion.PageTransitionMs, easing = LiuYuMotion.StandardEasing)
    ) + fadeIn(animationSpec = tween(LiuYuMotion.PageTransitionMs))

/**
 * 当前页面被二级页面覆盖时的退出动画 —— 轻微淡出
 */
private fun pushExitTransition(): ExitTransition =
    fadeOut(animationSpec = tween(LiuYuMotion.PageTransitionMs / 2))

/**
 * 二级页面弹出时的进入动画 —— 从左侧滑入 + 淡入
 */
private fun popEnterTransition(): EnterTransition =
    slideInHorizontally(
        initialOffsetX = { -it / 3 },
        animationSpec = tween(LiuYuMotion.PageTransitionMs, easing = LiuYuMotion.StandardEasing)
    ) + fadeIn(animationSpec = tween(LiuYuMotion.PageTransitionMs))

/**
 * 二级页面退出时的动画 —— 向右侧滑出 + 淡出
 */
private fun popExitTransition(): ExitTransition =
    slideOutHorizontally(
        targetOffsetX = { it / 3 },
        animationSpec = tween(LiuYuMotion.PageTransitionMs, easing = LiuYuMotion.StandardEasing)
    ) + fadeOut(animationSpec = tween(LiuYuMotion.PageTransitionMs))

// ============================================================================
// 导航框架主入口
// ============================================================================

/**
 * 应用导航框架
 *
 * 使用NavHost + rememberNavController搭建完整路由体系
 * - 5个底部Tab页面：看板、明细、图表、双卡、设置
 * - 2个二级页面：骆驼模式、联网控制面板
 * - Tab间切换使用淡入淡出动画
 * - 推入/弹出二级页面使用滑动动画
 *
 * @param runMode 当前运行模式（Root/Shizuku），传递给各页面
 */
@Composable
fun AppNavigation(runMode: RunMode) {
    val navController = rememberNavController()

    // 监听当前路由，决定是否显示底部导航栏
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route
    val showBottomBar = currentRoute in bottomTabRoutes

    Scaffold(
        bottomBar = {
            // 仅在底部Tab页面显示导航栏
            if (showBottomBar) {
                NavigationBar {
                    bottomNavItems.forEach { item ->
                        NavigationBarItem(
                            icon = {
                                Icon(
                                    imageVector = item.icon,
                                    contentDescription = item.label
                                )
                            },
                            label = { Text(item.label) },
                            selected = currentRoute == item.route,
                            onClick = {
                                // 使用navigate + popUpTo实现Tab间平滑切换
                                navController.navigate(item.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            colors = NavigationBarItemDefaults.colors(
                                indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                                selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                selectedTextColor = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        )
                    }
                }
            }
        }
    ) { paddingValues ->
        NavHost(
            navController = navController,
            startDestination = Routes.DASHBOARD,
            modifier = Modifier.padding(paddingValues),
            // ---- 进入动画 ----
            enterTransition = {
                // Tab间切换：淡入；推入二级页面：右侧滑入
                if (initialState.destination.route in bottomTabRoutes
                    && targetState.destination.route in bottomTabRoutes
                ) {
                    tabEnterTransition()
                } else {
                    pushEnterTransition()
                }
            },
            // ---- 退出动画 ----
            exitTransition = {
                if (targetState.destination.route in bottomTabRoutes
                    && initialState.destination.route in bottomTabRoutes
                ) {
                    tabExitTransition()
                } else {
                    pushExitTransition()
                }
            },
            // ---- 弹回进入动画 ----
            popEnterTransition = {
                if (initialState.destination.route in bottomTabRoutes
                    && targetState.destination.route in bottomTabRoutes
                ) {
                    tabEnterTransition()
                } else {
                    popEnterTransition()
                }
            },
            // ---- 弹回退出动画 ----
            popExitTransition = {
                if (initialState.destination.route in bottomTabRoutes
                    && targetState.destination.route in bottomTabRoutes
                ) {
                    tabExitTransition()
                } else {
                    popExitTransition()
                }
            }
        ) {
            // ==================== 底部Tab页面 ====================

            // 看板页
            composable(route = Routes.DASHBOARD) {
                DashboardScreen(
                    runMode = runMode,
                    onNavigateToDetail = {
                        navController.navigate(Routes.APP_DETAIL) {
                            popUpTo(navController.graph.findStartDestination().id) {
                                saveState = true
                            }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                    onNavigateToCharts = {
                        navController.navigate(Routes.CHARTS) {
                            popUpTo(navController.graph.findStartDestination().id) {
                                saveState = true
                            }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                    onNavigateToCamel = {
                        navController.navigate(Routes.CAMEL)
                    },
                    onNavigateToSimCard = {
                        navController.navigate(Routes.SIM_CARD) {
                            popUpTo(navController.graph.findStartDestination().id) {
                                saveState = true
                            }
                            launchSingleTop = true
                            restoreState = true
                        }
                    }
                )
            }

            // 应用流量明细页
            composable(route = Routes.APP_DETAIL) {
                AppDetailScreen(
                    onNavigateToControl = { uid ->
                        navController.navigate("${Routes.CONTROL_PANEL}/$uid")
                    }
                )
            }

            // 图表页
            composable(route = Routes.CHARTS) {
                ChartsScreen()
            }

            // 双卡流量统计页
            composable(route = Routes.SIM_CARD) {
                SimCardScreen()
            }

            // 设置页
            composable(route = Routes.SETTINGS) {
                SettingsScreen(
                    onNavigateToCamel = {
                        navController.navigate(Routes.CAMEL)
                    }
                )
            }

            // ==================== 二级页面（非底部Tab） ====================

            // 联网控制面板 —— 携带UID参数
            composable(
                route = "${Routes.CONTROL_PANEL}/{uid}",
                arguments = listOf(
                    navArgument("uid") { type = NavType.IntType }
                )
            ) { backStackEntry ->
                val uid = backStackEntry.arguments?.getInt("uid") ?: 0
                ControlPanelScreen(
                    uid = uid,
                    onBack = { navController.popBackStack() }
                )
            }

            // 骆驼模式页
            composable(route = Routes.CAMEL) {
                CamelScreen(
                    onBack = { navController.popBackStack() }
                )
            }
        }
    }
}