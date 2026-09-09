package com.infocaller.app.ui.screens

import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.infocaller.app.R
import com.infocaller.app.ui.theme.Primary
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

val LauncherFirstFrameDrawn = androidx.compose.runtime.mutableStateOf(false)

@Composable
fun InfoCallerLauncherScreen(
    onLauncherComplete: () -> Unit) {
    val logoAlpha = remember { androidx.compose.animation.core.Animatable(0f) }
    val logoScale = remember { androidx.compose.animation.core.Animatable(0.6f) }
    val pulse = remember { androidx.compose.animation.core.Animatable(0f) }
    val taglineAlpha = remember { androidx.compose.animation.core.Animatable(0f) }
    val letterProgress = remember { androidx.compose.animation.core.Animatable(0f) }
    val brand = "Infocaller"
    LaunchedEffect(Unit) {
        try { LauncherFirstFrameDrawn.value = true } catch (_:Exception) { }
        try {
            val scope = this
            scope.launch {
                logoAlpha.animateTo(
                    1f,
                    animationSpec = tween(durationMillis = 500, easing = FastOutSlowInEasing)
                )
            }
            scope.launch {
                logoScale.animateTo(
                    1f,
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioMediumBouncy,
                        stiffness = Spring.StiffnessLow
                    )
                )
                pulse.animateTo(
                    1f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(1200, easing = FastOutSlowInEasing),
                        repeatMode = RepeatMode.Reverse,
                    ),
                )
            }
            scope.launch {
                delay(350)
                letterProgress.animateTo(
                    1f,
                    animationSpec = tween(durationMillis = 900, easing = LinearOutSlowInEasing)
                )
            }
            scope.launch {
                delay(1100)
                taglineAlpha.animateTo(
                    1f,
                    animationSpec = tween(durationMillis = 600, easing = FastOutSlowInEasing)
                )
            }
        } catch (_: Exception) { }
        delay(2200.milliseconds)
        onLauncherComplete()
    }

    val gradient = Brush.linearGradient(
        colors = listOf(Primary, Color(0xFF7C6CFF), Primary),
    )
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .navigationBarsPadding(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Image(
                painter = painterResource(id = R.drawable.app_logo),
                contentDescription = "InfoCaller Logo",
                modifier = Modifier
                    .size(112.dp)
                    .scale(logoScale.value * (1f + 0.06f * pulse.value))
                    .alpha(logoAlpha.value)
            )

            Spacer(modifier = Modifier.height(24.dp))

            Row(horizontalArrangement = Arrangement.Center) {
                val visible = (letterProgress.value * brand.length).toInt().coerceIn(0, brand.length)
                brand.forEachIndexed { i, ch ->
                    val on = i < visible
                    val a: Float by animateFloatAsState(
                        targetValue = if (on) 1f else 0f,
                        animationSpec = tween(250, easing = FastOutSlowInEasing),
                        label = "letterAlpha",
                    )
                    val dy: Float by animateFloatAsState(
                        targetValue = if (on) 0f else 18f,
                        animationSpec = tween(250, easing = FastOutSlowInEasing),
                        label = "letterDy",
                    )
                    Text(
                        text = ch.toString(),
                        style = MaterialTheme.typography.displayMedium.copy(
                            fontWeight = FontWeight.ExtraBold,
                            brush = gradient,
                            letterSpacing = 3.sp,
                        ),
                        modifier = Modifier
                            .alpha(a)
                            .offset(y = dy.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = "Know who's calling",
                style = MaterialTheme.typography.bodyMedium.copy(
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                    letterSpacing = 1.5.sp,
                ),
                textAlign = TextAlign.Center,
                modifier = Modifier.alpha(taglineAlpha.value)
            )
        }
    }
}
