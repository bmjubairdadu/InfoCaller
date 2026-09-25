package com.infocaller.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.infocaller.app.ui.theme.Elevations
import com.infocaller.app.ui.theme.Motion
import com.infocaller.app.ui.theme.Primary
import com.infocaller.app.ui.theme.Radii
import com.infocaller.app.ui.theme.SectionHeader
import com.infocaller.app.ui.theme.Space
import com.infocaller.app.ui.theme.contentSecondary
import com.infocaller.app.ui.theme.faintTint
import com.infocaller.app.ui.theme.pressable
import com.infocaller.app.ui.theme.rememberPressInteraction
import com.infocaller.app.ui.theme.strokeSoft
import com.infocaller.app.ui.theme.surfaceElevated

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(),
        style = SectionHeader,
        color = contentSecondary(0.55f),
        modifier = modifier
    )
}

@Composable
fun AppCard(
    modifier: Modifier = Modifier,
    radius: androidx.compose.ui.unit.Dp = Radii.lg,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit
) {
    val interaction = rememberPressInteraction()
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && onClick != null) 0.975f else 1f,
        animationSpec = Motion.pressSpring,
        label = "cardScale"
    )
    Surface(
        modifier = modifier
            .scale(scale)
            .clip(RoundedCornerShape(radius)),
        color = surfaceElevated,
        shape = RoundedCornerShape(radius),
        border = BorderStroke(1.dp, strokeSoft),
        tonalElevation = Elevations.low
    ) {
        Box(
            modifier = if (onClick != null) {
                Modifier
                    .pressable(interaction, pressedScale = 1f)
                    .clickable(
                        interactionSource = interaction,
                        indication = null,
                        onClick = onClick
                    )
            } else Modifier
        ) {
            content()
        }
    }
}

@Composable
fun AccentBar(modifier: Modifier = Modifier, height: androidx.compose.ui.unit.Dp = 3.dp) {
    Box(
        modifier = modifier
            .height(height)
            .clip(RoundedCornerShape(Radii.pill))
            .background(Brush.horizontalGradient(listOf(Primary, Primary.copy(alpha = 0.25f))))
    )
}

@Composable
fun IconBadge(
    icon: ImageVector,
    modifier: Modifier = Modifier,
    size: androidx.compose.ui.unit.Dp = 38.dp,
    tint: Color = Primary,
    background: Color = tint.copy(alpha = 0.14f)
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(background),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(size * 0.5f)
        )
    }
}

@Composable
fun InfoRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color = LocalContentColor.current
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = Space.sm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = contentSecondary(0.6f),
            modifier = Modifier.width(112.dp)
        )
        Spacer(Modifier.width(Space.sm))
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold),
            color = valueColor,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
fun ChevronLink(
    text: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val interaction = rememberPressInteraction()
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(Radii.sm))
            .pressable(interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = Space.sm, vertical = Space.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.xs)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = Primary
        )
        Icon(
            imageVector = Icons.Default.ChevronRight,
            contentDescription = null,
            tint = Primary,
            modifier = Modifier.size(16.dp)
        )
    }
}

@Composable
fun Pill(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Primary
) {
    val bg by animateColorAsState(color.copy(alpha = 0.14f), label = "pillBg")
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(Radii.pill))
            .background(bg)
            .border(1.dp, color.copy(alpha = 0.32f), RoundedCornerShape(Radii.pill))
            .padding(horizontal = Space.sm, vertical = 3.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = color
        )
    }
}

@Composable
fun SkeletonLine(
    modifier: Modifier = Modifier,
    height: androidx.compose.ui.unit.Dp = 14.dp
) {
    Box(
        modifier = modifier
            .height(height)
            .clip(RoundedCornerShape(Radii.pill))
            .background(faintTint(0.08f))
    )
}

@Composable
fun VerticalSpacer(height: androidx.compose.ui.unit.Dp) = Spacer(Modifier.height(height))
