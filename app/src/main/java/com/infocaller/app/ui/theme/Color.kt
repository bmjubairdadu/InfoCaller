package com.infocaller.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color

val Primary = Color(0xFFFBBF24)
val PrimaryVariant = Color(0xFFD97706)
val TruecallerBlue = Color(0xFF0087FF)
val Secondary = Color(0xFF10B981)
val Tertiary = Color(0xFF6366F1)

val Error = Color(0xFFEF4444)
val Success = Color(0xFF10B981)
val Warning = Color(0xFFFBBF24)

val GlassBackground = Color(0x1AFFFFFF)
val GlassBorder = Color(0x0DFFFFFF)

val GradientStart = Color(0xFFFBBF24)
val GradientEnd = Color(0xFFD97706)

val OnPrimary = Color(0xFF000000)
val OnSurface = Color(0xFFFFFFFF)

val Background: Color
    @Composable @ReadOnlyComposable
    get() = MaterialTheme.colorScheme.background
val Surface: Color
    @Composable @ReadOnlyComposable
    get() = MaterialTheme.colorScheme.surface
val CardBackground: Color
    @Composable @ReadOnlyComposable
    get() = MaterialTheme.colorScheme.surfaceVariant
val TextPrimary: Color
    @Composable @ReadOnlyComposable
    get() = MaterialTheme.colorScheme.onBackground
val TextSecondary: Color
    @Composable @ReadOnlyComposable
    get() = MaterialTheme.colorScheme.onSurfaceVariant

private fun isDarkScheme(scheme: androidx.compose.material3.ColorScheme): Boolean =
    scheme.background.red * 0.299f + scheme.background.green * 0.587f + scheme.background.blue * 0.114f < 0.5f

val contentPrimary: Color
    @Composable @ReadOnlyComposable
    get() = MaterialTheme.colorScheme.onBackground

@Composable
@ReadOnlyComposable
fun contentSecondary(alpha: Float = 0.7f): Color =
    MaterialTheme.colorScheme.onBackground.copy(alpha = alpha)

@Composable
@ReadOnlyComposable
fun faintTint(alpha: Float): Color {
    val dark = isDarkScheme(MaterialTheme.colorScheme)
    return (if (dark) Color.White else Color.Black).copy(alpha = alpha)
}

@Composable
@ReadOnlyComposable
fun topBarScrim(alpha: Float = 0.3f): Color {
    val dark = isDarkScheme(MaterialTheme.colorScheme)
    return (if (dark) Color.Black else Color.White).copy(alpha = alpha)
}
