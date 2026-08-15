package com.infocaller.app.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProgressIndicatorDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.infocaller.app.R
import com.infocaller.app.ui.theme.Background
import kotlinx.coroutines.delay

@Composable
fun SplashScreen(onSplashFinished: () -> Unit) {
    var startAnimation by remember { mutableStateOf(false) }
    
    val infiniteTransition = rememberInfiniteTransition(label = "Glow")
    val glowAlpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 0.7f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "GlowAlpha"
    )

    val scale by animateFloatAsState(
        targetValue = if (startAnimation) 1f else 0.4f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "LogoScale"
    )
    
    val alpha by animateFloatAsState(
        targetValue = if (startAnimation) 1f else 0f,
        animationSpec = tween(1200),
        label = "Alpha"
    )

    LaunchedEffect(Unit) {
        startAnimation = true
        delay(2500) 
        onSplashFinished()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Background),
        contentAlignment = Alignment.Center
    ) {
        // Background Glow Effect
        Box(
            modifier = Modifier
                .size(300.dp)
                .graphicsLayer {
                    this.alpha = glowAlpha
                    this.scaleX = scale * 1.2f
                    this.scaleY = scale * 1.2f
                }
                .background(
                    color = Color(0xFF818CF8).copy(alpha = 0.2f),
                    shape = CircleShape
                )
        )

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.graphicsLayer {
                this.scaleX = scale
                this.scaleY = scale
                this.alpha = alpha
            }
        ) {
            Image(
                painter = painterResource(id = R.drawable.app_logo),
                contentDescription = "App Logo",
                modifier = Modifier
                    .size(160.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.1f), CircleShape)
                    .padding(16.dp)
            )
            
            Spacer(modifier = Modifier.height(32.dp))
            
            Text(
                text = "InfoCaller",
                style = MaterialTheme.typography.displayLarge,
                color = Color.White,
                letterSpacing = 4.sp,
                textAlign = TextAlign.Center,
                fontWeight = FontWeight.ExtraBold
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            
            Text(
                text = "Identify Everyone",
                style = MaterialTheme.typography.bodyLarge,
                color = Color.White.copy(alpha = 0.6f),
                fontWeight = FontWeight.Light,
                textAlign = TextAlign.Center,
                letterSpacing = 2.sp
            )
        }
        
        // Loader at the bottom
        androidx.compose.material3.CircularProgressIndicator(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 64.dp)
                .size(32.dp),
            color = Color.White.copy(alpha = 0.3f),
            strokeWidth = 2.dp
        )
    }
}
