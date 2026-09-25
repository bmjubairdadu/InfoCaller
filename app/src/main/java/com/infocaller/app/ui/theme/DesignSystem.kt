package com.infocaller.app.ui.theme

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

object Space {
    val xxs: Dp = 2.dp
    val xs: Dp = 4.dp
    val sm: Dp = 8.dp
    val md: Dp = 12.dp
    val lg: Dp = 16.dp
    val xl: Dp = 20.dp
    val xxl: Dp = 24.dp
    val xxxl: Dp = 32.dp
    val huge: Dp = 40.dp
    val giant: Dp = 56.dp
    val screen: Dp = 20.dp
    val section: Dp = 28.dp
}

object Radii {
    val xs: Dp = 8.dp
    val sm: Dp = 12.dp
    val md: Dp = 16.dp
    val lg: Dp = 22.dp
    val xl: Dp = 28.dp
    val pill: Dp = 999.dp
}

object Elevations {
    val flat: Dp = 0.dp
    val low: Dp = 2.dp
    val mid: Dp = 6.dp
    val high: Dp = 12.dp
    val glow: Dp = 20.dp
}

object Motion {
    val emphasized: Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    val standard: Easing = CubicBezierEasing(0.2f, 0f, 0.2f, 1f)
    val decelerate: Easing = CubicBezierEasing(0f, 0f, 0.2f, 1f)
    val accelerate: Easing = CubicBezierEasing(0.4f, 0f, 1f, 1f)

    const val INSTANT = 90
    const val FAST = 160
    const val NORMAL = 240
    const val SLOW = 360
    const val SCREEN = 420

    fun <T> fast(): FiniteAnimationSpec<T> = tween(FAST, easing = standard)
    fun <T> normal(): FiniteAnimationSpec<T> = tween(NORMAL, easing = emphasized)
    fun <T> slow(): FiniteAnimationSpec<T> = tween(SLOW, easing = emphasized)

    val pressSpring: AnimationSpec<Float> =
        spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMediumLow)
}

fun <T> tweenFast(): FiniteAnimationSpec<T> = tween(Motion.FAST, easing = Motion.standard)
fun <T> tweenNormal(): FiniteAnimationSpec<T> = tween(Motion.NORMAL, easing = Motion.emphasized)
fun <T> tweenSlow(): FiniteAnimationSpec<T> = tween(Motion.SLOW, easing = Motion.emphasized)

@Composable
fun Modifier.pressable(
    interactionSource: MutableInteractionSource,
    enabled: Boolean = true,
    pressedScale: Float = 0.96f
): Modifier {
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && enabled) pressedScale else 1f,
        animationSpec = Motion.pressSpring,
        label = "pressScale"
    )
    return this.scale(scale)
}

fun Modifier.shimmerColors(): List<Color> = listOf(
    Color.White.copy(alpha = 0.00f),
    Color.White.copy(alpha = 0.09f),
    Color.White.copy(alpha = 0.00f)
)

@Composable
fun Modifier.brandBrush(): Brush = Brush.horizontalGradient(listOf(Primary, PrimaryVariant))

@Composable
fun Modifier.subtleSurfaceBrush(dark: Boolean): Brush = Brush.verticalGradient(
    if (dark) {
        listOf(Color.White.copy(alpha = 0.09f), Color.White.copy(alpha = 0.03f))
    } else {
        listOf(Color.White.copy(alpha = 0.95f), Color.White.copy(alpha = 0.72f))
    }
)

fun riseInAnimation(delayMs: Int = 0): EnterTransition =
    fadeIn(tween(Motion.SLOW, delayMs, easing = Motion.decelerate)) +
        expandVertically(tween(Motion.SLOW, delayMs, easing = Motion.emphasized)) { it / 6 } +
        scaleIn(tween(Motion.SLOW, delayMs, easing = Motion.emphasized), initialScale = 0.96f)

fun fadeThrough(delayMs: Int = 0): EnterTransition =
    fadeIn(tween(Motion.NORMAL, delayMs, easing = Motion.decelerate))

fun settleIn(delayMs: Int = 0): EnterTransition =
    fadeIn(tween(Motion.NORMAL, delayMs, easing = Motion.decelerate)) +
        scaleIn(tween(Motion.NORMAL, delayMs, easing = Motion.emphasized), initialScale = 0.94f)

val ExitCollapse: ExitTransition = fadeOut(tween(Motion.FAST, easing = Motion.accelerate)) +
    shrinkVertically(tween(Motion.FAST, easing = Motion.accelerate))

@Composable
fun rememberPressInteraction(): MutableInteractionSource = remember { MutableInteractionSource() }

fun Modifier.fadeAlpha(value: Float): Modifier = this.alpha(value.coerceIn(0f, 1f))

fun cardShape(radius: Dp = Radii.lg): Shape = androidx.compose.foundation.shape.RoundedCornerShape(radius)
