package com.infocaller.app.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

fun Modifier.glassy(
    radius: Dp = 16.dp,
    borderWidth: Dp = 1.dp,
    blur: Dp = 10.dp
) = this
    .clip(RoundedCornerShape(radius))
    .background(
        Brush.verticalGradient(
            colors = listOf(
                Color.White.copy(alpha = 0.1f),
                Color.White.copy(alpha = 0.05f)
            )
        )
    )
    .border(
        width = borderWidth,
        brush = Brush.verticalGradient(
            colors = listOf(
                Color.White.copy(alpha = 0.2f),
                Color.White.copy(alpha = 0.05f)
            )
        ),
        shape = RoundedCornerShape(radius)
    )