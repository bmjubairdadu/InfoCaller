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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.infocaller.app.R
import com.infocaller.app.ui.theme.Primary
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.milliseconds


@Composable
fun InfoCallerLauncherScreen(
    onLauncherComplete: () -> Unit
) {
    var startAnimation by remember { mutableStateOf(false) }
    
    val logoAlpha by animateFloatAsState(
        targetValue = if (startAnimation) 1f else 0f,
        animationSpec = tween(durationMillis = 1200, easing = FastOutSlowInEasing),
        label = "LogoAlpha"
    )
    
    val logoScale by animateFloatAsState(
        targetValue = if (startAnimation) 1f else 0.7f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioLowBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "LogoScale"
    )

    val textAlpha by animateFloatAsState(
        targetValue = if (startAnimation) 1f else 0f,
        animationSpec = tween(durationMillis = 1000, delayMillis = 700),
        label = "TextAlpha"
    )

    val textOffsetY by animateDpAsState(
        targetValue = if (startAnimation) 0.dp else 40.dp,
        animationSpec = tween(durationMillis = 1000, delayMillis = 700),
        label = "TextOffset"
    )

    LaunchedEffect(Unit) {
        startAnimation = true
        delay(2200.milliseconds) 
        onLauncherComplete()
    }

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
                    .size(160.dp)
                    .scale(logoScale)
                    .alpha(logoAlpha)
            )
            
            Spacer(modifier = Modifier.height(24.dp))
            
            Text(
                text = "InfoCaller",
                style = MaterialTheme.typography.displayMedium.copy(
                    fontWeight = FontWeight.ExtraBold,
                    color = Primary,
                    letterSpacing = 4.sp
                ),
                modifier = Modifier
                    .alpha(textAlpha)
                    .offset(y = textOffsetY)
            )
        }
    }
}
