package com.infocaller.app.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val DarkColorScheme = darkColorScheme(
    primary = Primary,
    onPrimary = Color.Black,
    primaryContainer = PrimaryVariant,
    onPrimaryContainer = Color.White,
    secondary = Secondary,
    onSecondary = Color.Black,
    tertiary = Tertiary,
    background = Background,
    surface = Surface,
    onBackground = TextPrimary,
    onSurface = TextPrimary,
    surfaceVariant = CardBackground,
    onSurfaceVariant = TextSecondary,
    error = Error,
    onError = Color.White
)

private val LightColorScheme = lightColorScheme(
    primary = Primary,
    onPrimary = Color.Black,
    primaryContainer = PrimaryVariant,
    onPrimaryContainer = Color.White,
    secondary = Secondary,
    onSecondary = Color.White,
    tertiary = Tertiary,
    background = Color(0xFFFAFAFA),
    surface = Color.White,
    onBackground = Color.Black,
    onSurface = Color.Black,
    surfaceVariant = Color(0xFFF3F4F6),
    onSurfaceVariant = Color(0xFF4B5563),
    error = Error,
    onError = Color.White
)

@Composable
fun InfoCallerTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    val view = LocalView.current
    
    if (!view.isInEditMode) {
        val window = (view.context as Activity).window
        WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
