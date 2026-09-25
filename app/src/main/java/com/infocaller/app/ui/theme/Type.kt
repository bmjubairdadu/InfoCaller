package com.infocaller.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.sp

private val Sans = FontFamily.SansSerif

private val Trim = LineHeightStyle(
    alignment = LineHeightStyle.Alignment.Center,
    trim = LineHeightStyle.Trim.None
)

private fun style(
    size: Int,
    line: Int,
    weight: FontWeight,
    tracking: Double = 0.0,
) = TextStyle(
    fontFamily = Sans,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = line.sp,
    letterSpacing = tracking.sp,
    lineHeightStyle = Trim
)

val AppTypography = Typography(
    displayLarge = style(40, 48, FontWeight.Black, -1.0),
    displayMedium = style(34, 42, FontWeight.ExtraBold, -0.6),
    displaySmall = style(30, 38, FontWeight.ExtraBold, -0.4),

    headlineLarge = style(28, 36, FontWeight.Bold, -0.3),
    headlineMedium = style(24, 32, FontWeight.Bold, -0.2),
    headlineSmall = style(21, 28, FontWeight.Bold, 0.0),

    titleLarge = style(19, 26, FontWeight.Bold, 0.0),
    titleMedium = style(16, 23, FontWeight.SemiBold, 0.1),
    titleSmall = style(14, 20, FontWeight.SemiBold, 0.1),

    bodyLarge = style(16, 25, FontWeight.Normal, 0.15),
    bodyMedium = style(14, 21, FontWeight.Normal, 0.2),
    bodySmall = style(12, 18, FontWeight.Normal, 0.25),

    labelLarge = style(14, 20, FontWeight.SemiBold, 0.4),
    labelMedium = style(12, 16, FontWeight.SemiBold, 0.5),
    labelSmall = style(11, 15, FontWeight.Medium, 0.6)
)

val DisplayHero = style(44, 52, FontWeight.Black, -1.2)
val DisplayNumber = style(32, 38, FontWeight.Black, -0.8)
val SectionHeader = style(13, 18, FontWeight.Bold, 0.9)
val NumericBadge = style(22, 26, FontWeight.Black, -0.3)
val Mono = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontWeight = FontWeight.Medium,
    fontSize = 15.sp,
    lineHeight = 21.sp,
    letterSpacing = 0.4.sp
)

val Typography = AppTypography
