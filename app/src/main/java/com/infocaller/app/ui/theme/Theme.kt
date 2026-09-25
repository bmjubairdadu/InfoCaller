package com.infocaller.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.infocaller.app.util.findActivity

private val DarkColorScheme = darkColorScheme(
    primary = Primary,
    onPrimary = Color.Black,
    primaryContainer = PrimaryVariant,
    onPrimaryContainer = Color.Black,
    secondary = Secondary,
    onSecondary = Color.Black,
    secondaryContainer = Secondary.copy(alpha = 0.22f),
    onSecondaryContainer = Color(0xFF9BF3D0),
    tertiary = Tertiary,
    onTertiary = Color.White,
    tertiaryContainer = Tertiary.copy(alpha = 0.24f),
    onTertiaryContainer = Color(0xFFC3C4FF),
    background = Color(0xFF000000),
    onBackground = Color(0xFFFFFFFF),
    surface = Color(0xFF0B0B0E),
    onSurface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFF16171C),
    onSurfaceVariant = Color(0xFFB4B4BE),
    surfaceContainerLowest = Color(0xFF000000),
    surfaceContainerLow = Color(0xFF0D0D10),
    surfaceContainer = Color(0xFF131318),
    surfaceContainerHigh = Color(0xFF1B1C22),
    surfaceContainerHighest = Color(0xFF23242B),
    outline = Color(0xFF3A3B44),
    outlineVariant = Color(0xFF26272E),
    error = Error,
    onError = Color.White,
    errorContainer = Error.copy(alpha = 0.2f),
    onErrorContainer = Color(0xFFFFB4A9),
    scrim = Color.Black
)

private val LightColorScheme = lightColorScheme(
    primary = PrimaryVariant,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFEF3C7),
    onPrimaryContainer = Color(0xFF451A03),
    secondary = Color(0xFF047857),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD1FAE5),
    onSecondaryContainer = Color(0xFF022C22),
    tertiary = Color(0xFF4F46E5),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFE0E7FF),
    onTertiaryContainer = Color(0xFF1E1B4B),
    background = Color(0xFFF7F7F9),
    onBackground = Color(0xFF0B0B0E),
    surface = Color.White,
    onSurface = Color(0xFF0B0B0E),
    surfaceVariant = Color(0xFFF1F2F5),
    onSurfaceVariant = Color(0xFF52525B),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFFAFAFC),
    surfaceContainer = Color(0xFFF4F4F7),
    surfaceContainerHigh = Color(0xFFEDEDF1),
    surfaceContainerHighest = Color(0xFFE6E6EB),
    outline = Color(0xFFC9CAD1),
    outlineVariant = Color(0xFFE4E4E9),
    error = Color(0xFFDC2626),
    onError = Color.White,
    errorContainer = Color(0xFFFEE2E2),
    onErrorContainer = Color(0xFF7F1D1D),
    scrim = Color.Black
)

private val AppShapes = androidx.compose.material3.Shapes(
    extraSmall = androidx.compose.foundation.shape.RoundedCornerShape(Radii.xs),
    small = androidx.compose.foundation.shape.RoundedCornerShape(Radii.sm),
    medium = androidx.compose.foundation.shape.RoundedCornerShape(Radii.md),
    large = androidx.compose.foundation.shape.RoundedCornerShape(Radii.lg),
    extraLarge = androidx.compose.foundation.shape.RoundedCornerShape(Radii.xl)
)

@Composable
fun InfoCallerTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    val view = LocalView.current

    if (!view.isInEditMode) {
        (view.context.findActivity())?.window?.let { window ->
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                window.isNavigationBarContrastEnforced = false
            }
        }
    }

    androidx.compose.runtime.CompositionLocalProvider(
        LocalIsDarkTheme provides darkTheme
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = AppTypography,
            shapes = AppShapes,
            content = content
        )
    }
}

val LocalIsDarkTheme = androidx.compose.runtime.staticCompositionLocalOf { false }

@Composable
fun isDarkTheme(): Boolean = LocalIsDarkTheme.current
