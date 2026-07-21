package com.liuyuguard.ui.animations

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ripple
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp

// ============================================================================
// 全局动效时间规范
// ============================================================================

object LiuYuMotion {
    /** 标准动画时长 */
    const val StandardDurationMs = 300

    /** 快速动画时长 */
    const val QuickDurationMs = 150

    /** 慢速动画时长 */
    const val SlowDurationMs = 500

    /** 页面切换动画时长 */
    const val PageTransitionMs = 350

    /** 标准缓动 */
    val StandardEasing = FastOutSlowInEasing

    /** 弹出缓动 */
    val EmphasizedEasing = EmphasizedEasing

    /** 弹性缓动 */
    val BounceEasing = EaseOutBounce

    /** 标准弹簧 */
    fun standardSpring() = spring<Float>(
        stiffness = Spring.StiffnessMedium,
        dampingRatio = Spring.DampingRatioMediumBouncy
    )

    /** 柔和弹簧 */
    fun gentleSpring() = spring<Float>(
        stiffness = Spring.StiffnessMediumLow,
        dampingRatio = Spring.DampingRatioNoBouncy
    )
}

// ============================================================================
// 按钮点击缩放动效
// ============================================================================

/**
 * 按钮点击缩放动效Modifier
 * 按下时缩小到0.95，松开时弹回1.0
 */
fun Modifier.clickableScale(
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
    enabled: Boolean = true,
    onClick: () -> Unit
): Modifier = composed {
    var isPressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (isPressed && enabled) 0.95f else 1f,
        animationSpec = LiuYuMotion.standardSpring(),
        label = "clickable_scale"
    )

    this
        .scale(scale)
        .clickable(
            interactionSource = interactionSource,
            indication = ripple(bounded = true),
            enabled = enabled,
            onClick = {
                isPressed = true
                onClick()
            }
        )
}

/**
 * 列表项点击缩放 + 轻微位移效果
 */
fun Modifier.listItemClick(
    enabled: Boolean = true,
    onClick: () -> Unit
): Modifier = composed {
    var isPressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (isPressed && enabled) 0.98f else 1f,
        animationSpec = LiuYuMotion.gentleSpring(),
        label = "list_item_scale"
    )
    val offset by animateFloatAsState(
        targetValue = if (isPressed && enabled) 4f else 0f,
        animationSpec = tween(LiuYuMotion.QuickDurationMs),
        label = "list_item_offset"
    )

    this
        .scale(scale)
        .graphicsLayer { translationX = offset }
        .clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = ripple(bounded = true),
            enabled = enabled,
            onClick = onClick
        )
}

// ============================================================================
// 页面切换动画
// ============================================================================

/** 页面进入动画（从右滑入 + 淡入） */
internal val EnterTransition = slideInHorizontally(
    initialOffsetX = { it / 3 },
    animationSpec = tween(LiuYuMotion.PageTransitionMs, easing = LiuYuMotion.StandardEasing)
) + fadeIn(animationSpec = tween(LiuYuMotion.PageTransitionMs))

/** 页面退出动画（向左滑出 + 淡出） */
internal val ExitTransition = slideOutHorizontally(
    targetOffsetX = { -it / 3 },
    animationSpec = tween(LiuYuMotion.PageTransitionMs, easing = LiuYuMotion.StandardEasing)
) + fadeOut(animationSpec = tween(LiuYuMotion.PageTransitionMs))

/** 页面返回时进入（从左滑入） */
internal val PopEnterTransition = slideInHorizontally(
    initialOffsetX = { -it / 3 },
    animationSpec = tween(LiuYuMotion.PageTransitionMs, easing = LiuYuMotion.StandardEasing)
) + fadeIn(animationSpec = tween(LiuYuMotion.PageTransitionMs))

/** 页面返回时退出（向右滑出） */
internal val PopExitTransition = slideOutHorizontally(
    targetOffsetX = { it / 3 },
    animationSpec = tween(LiuYuMotion.PageTransitionMs, easing = LiuYuMotion.StandardEasing)
) + fadeOut(animationSpec = tween(LiuYuMotion.PageTransitionMs))

// ============================================================================
// 通用显示/隐藏动画
// ============================================================================

/** 元素出现/消失动画（缩放+透明度） */
fun Modifier.animateVisibility(visible: Boolean): Modifier = composed {
    val scale by animateFloatAsState(
        targetValue = if (visible) 1f else 0.8f,
        animationSpec = tween(LiuYuMotion.StandardDurationMs, easing = LiuYuMotion.StandardEasing),
        label = "visibility_scale"
    )
    val alpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(LiuYuMotion.StandardDurationMs),
        label = "visibility_alpha"
    )
    this
        .scale(scale)
        .graphicsLayer { this.alpha = alpha }
}

/** 开关状态翻转动画 */
val ToggleTransition: EnterTransition = scaleIn(
    animationSpec = spring(
        stiffness = Spring.StiffnessMediumLow,
        dampingRatio = Spring.DampingRatioMediumBouncy
    ),
    initialScale = 0.8f
) + fadeIn(animationSpec = tween(100))

val ToggleExitTransition: ExitTransition = scaleOut(
    animationSpec = tween(100),
    targetScale = 0.8f
) + fadeOut(animationSpec = tween(100))