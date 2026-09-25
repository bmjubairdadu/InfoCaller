package com.infocaller.app.ui.theme

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
fun Modifier.glassy(
    radius: Dp = 16.dp,
    borderWidth: Dp = 1.dp,
    blur: Dp = 0.dp
): Modifier {
    val schemeBg = androidx.compose.material3.MaterialTheme.colorScheme.background
    val dark = schemeBg.red * 0.299f + schemeBg.green * 0.587f + schemeBg.blue * 0.114f < 0.5f
    val tint = if (dark) Color.White else Color.Black
    return this
        .clip(RoundedCornerShape(radius))
        .then(if (blur > 0.dp && android.os.Build.VERSION.SDK_INT >= 31) Modifier.blur(blur) else Modifier)
        .background(
            Brush.verticalGradient(
                colors = listOf(
                    tint.copy(alpha = if (dark) 0.12f else 0.05f),
                    tint.copy(alpha = if (dark) 0.06f else 0.02f)
                )
            )
        )
        .border(
            width = borderWidth,
            brush = Brush.verticalGradient(
                colors = listOf(
                    tint.copy(alpha = if (dark) 0.25f else 0.14f),
                    tint.copy(alpha = if (dark) 0.05f else 0.06f)
                )
            ),
            shape = RoundedCornerShape(radius)
        )
}

@Composable
fun GlassyBackground(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(androidx.compose.material3.MaterialTheme.colorScheme.background)
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(Primary.copy(alpha = 0.12f), Color.Transparent),
                    center = Offset(size.width * 0.85f, size.height * 0.15f),
                    radius = size.width * 0.7f
                )
            )
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(Secondary.copy(alpha = 0.08f), Color.Transparent),
                    center = Offset(size.width * 0.15f, size.height * 0.85f),
                    radius = size.width * 0.6f
                )
            )
        }

        content()
    }
}

fun Modifier.brandGradient(
    radius: Dp = 16.dp
) = this
    .clip(RoundedCornerShape(radius))
    .background(
        Brush.horizontalGradient(
            colors = listOf(
                Primary,
                PrimaryVariant
            )
        )
    )

fun Modifier.blueGradient(
    radius: Dp = 16.dp
) = this
    .clip(RoundedCornerShape(radius))
    .background(
        Brush.horizontalGradient(
            colors = listOf(
                Color(0xFF007BFF),
                Color(0xFF00C6FF)
            )
        )
    )
