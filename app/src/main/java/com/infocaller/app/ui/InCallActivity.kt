package com.infocaller.app.ui

import android.os.Bundle
import android.telecom.Call
import android.telecom.VideoProfile
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.rounded.*
import androidx.core.net.toUri
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.infocaller.app.data.local.CallManager
import com.infocaller.app.data.local.entity.QueuePriority
import com.infocaller.app.ui.theme.*
import com.infocaller.app.util.ContactUtils
import com.infocaller.app.util.LocationUtils
import com.infocaller.app.util.SocialUtils
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlin.math.abs
import kotlin.math.roundToInt

class InCallActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            val keyguardManager = getSystemService(android.app.KeyguardManager::class.java)
            keyguardManager?.requestDismissKeyguard(this, null)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                android.view.WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
                android.view.WindowManager.LayoutParams.FLAG_ALLOW_LOCK_WHILE_SCREEN_ON
            )
        }
        setContent {
            InfoCallerTheme {
                InCallScreen(onDismiss = { finish() })
            }
        }
    }
}

@Composable
fun InCallScreen(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as com.infocaller.app.InfoCallerApplication
    val enrichmentEngine = app.enrichmentEngine
    
    val call by CallManager.activeCall.collectAsState()
    val isMuted by CallManager.isMuted.collectAsState()
    val isSpeakerOn by CallManager.isSpeakerOn.collectAsState()
    val isHolding by CallManager.isHolding.collectAsState()
    val isRecording by CallManager.isRecording.collectAsState()
    
    var showDtmf by remember { mutableStateOf(false) }
    
    if (call == null) {
        onDismiss()
        return
    }

    val number = call?.details?.handle?.schemeSpecificPart ?: "Unknown"
    val normalizedNumber = remember(number) { com.infocaller.app.util.PhoneNumberUtils.normalize(number) }
    val enrichment by enrichmentEngine.getEnrichment(normalizedNumber).collectAsState(initial = null)
    
    var contactName by remember { mutableStateOf<String?>(null) }
    var contactPhotoUri by remember { mutableStateOf<String?>(null) }
    var isBlocked by remember { mutableStateOf(false) }
    
    @Suppress("DEPRECATION")
    var callState by remember { mutableStateOf(call?.state ?: Call.STATE_DISCONNECTED) }

    val infiniteTransition = rememberInfiniteTransition(label = "Pulse")

    DisposableEffect(Unit) {
        CallManager.init(context)
        onDispose {}
    }

    val callback = object : Call.Callback() {
        override fun onStateChanged(call: Call, state: Int) {
            callState = state
            if (state == Call.STATE_DISCONNECTED) onDismiss()
        }
    }

    DisposableEffect(call) {
        call?.registerCallback(callback)
        onDispose { call?.unregisterCallback(callback) }
    }

    LaunchedEffect(normalizedNumber) {
        if (normalizedNumber.isNotBlank()) {
            isBlocked = app.repository.isBlocked(normalizedNumber)
            contactName = com.infocaller.app.util.PhoneNumberUtils.getContactName(context, normalizedNumber)
            contactPhotoUri = com.infocaller.app.util.PhoneNumberUtils.getContactPhotoUri(context, normalizedNumber)
            enrichmentEngine.enqueue(normalizedNumber, priority = QueuePriority.HIGH)
        }
    }

    // Social Profiles for UI
    val socialProfiles = remember(enrichment?.socialProfilesJson) {
        SocialUtils.fromJson(enrichment?.socialProfilesJson)
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        val imageModel = contactPhotoUri ?: enrichment?.profileImageUrl
        // Clean professional incoming screen: true overlay when screen on(days), full-screen when pocketed.
        // No extra app notifications here - only InfoInCallService notification handles lock-screen.
        if (imageModel != null) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(imageModel)
                    .crossfade(true)
                    .build(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize().blur(60.dp).alpha(0.35f),
                contentScale = androidx.compose.ui.layout.ContentScale.Crop
            )
        }
        val themeColor = if (isBlocked) Error else Primary
        val bgGradient = Brush.verticalGradient(listOf(themeColor.copy(alpha = 0.25f), Color.Transparent, Color.Black.copy(alpha = 0.85f)))
        Box(modifier = Modifier.fillMaxSize().background(bgGradient))

        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 32.dp, vertical = 48.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // 2. Caller Info Section
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(top = 40.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    // Pulsing Rings
                    repeat(2) { i ->
                        val ringScale by infiniteTransition.animateFloat(
                            initialValue = 1f,
                            targetValue = 1.6f + (i * 0.2f),
                            animationSpec = infiniteRepeatable(
                                animation = tween(2000, delayMillis = i * 500, easing = LinearOutSlowInEasing),
                                repeatMode = RepeatMode.Restart
                            ),
                            label = "RingScale"
                        )
                        val ringAlpha by infiniteTransition.animateFloat(
                            initialValue = 0.4f,
                            targetValue = 0f,
                            animationSpec = infiniteRepeatable(
                                animation = tween(2000, delayMillis = i * 500, easing = LinearOutSlowInEasing),
                                repeatMode = RepeatMode.Restart
                            ),
                            label = "RingAlpha"
                        )
                        Box(
                            modifier = Modifier
                                .size(100.dp)
                                .scale(ringScale)
                                .alpha(ringAlpha)
                                .background(themeColor, CircleShape)
                        )
                    }

                    Surface(
                        modifier = Modifier.size(120.dp).shadow(24.dp, CircleShape),
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        border = androidx.compose.foundation.BorderStroke(3.dp, themeColor.copy(alpha = 0.5f))
                    ) {
                        if (imageModel != null) {
                            AsyncImage(
                                model = imageModel,
                                contentDescription = null,
                                modifier = Modifier.fillMaxSize(),
                                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                                error = rememberVectorPainter(Icons.Default.Person)
                            )
                        } else {
                            val initials = ContactUtils.getInitials(contactName ?: enrichment?.publicName)
                            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                                Text(text = initials, style = MaterialTheme.typography.displayMedium.copy(fontWeight = FontWeight.Bold), color = themeColor)
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(40.dp))

                AnimatedVisibility(
                    visible = true,
                    enter = fadeIn(tween(600)) + slideInVertically(tween(600)) { it / 2 }
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = contactName ?: enrichment?.publicName ?: "Unknown Caller",
                            style = MaterialTheme.typography.headlineLarge,
                            fontWeight = FontWeight.ExtraBold,
                            color = Color.White,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                        
                        Text(
                            text = com.infocaller.app.util.PhoneNumberUtils.formatAsYouType(number),
                            style = MaterialTheme.typography.titleLarge,
                            color = Color.White.copy(alpha = 0.7f),
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                }

                val location = LocationUtils.formatCallerLocation(enrichment?.city, enrichment?.region, enrichment?.country)
                if (location.isNotBlank()) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically, 
                        modifier = Modifier.padding(top = 16.dp).alpha(0.8f)
                    ) {
                        Icon(Icons.Default.Place, null, tint = themeColor, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(location, style = MaterialTheme.typography.bodyLarge, color = Color.White)
                    }
                }
                
                // Only used social - no generic placeholders
                val usedSocials = remember(socialProfiles) { com.infocaller.app.util.SocialUtils.filteredUsedProfiles(socialProfiles) }
                if (usedSocials.isNotEmpty()) {
                    Row(modifier = Modifier.padding(top = 24.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        usedSocials.forEach { profile -> SocialMiniIcon(profile) }
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))
                
                // Call State
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (callState == Call.STATE_RINGING) {
                        val infiniteIconTransition = rememberInfiniteTransition(label = "IconPulse")
                        val iconScale by infiniteIconTransition.animateFloat(
                            initialValue = 1f, targetValue = 1.2f,
                            animationSpec = infiniteRepeatable(tween(800), RepeatMode.Reverse), label = "IconScale"
                        )
                        Icon(
                            Icons.Rounded.PhoneInTalk, null, 
                            tint = Secondary, 
                            modifier = Modifier.size(24.dp).scale(iconScale)
                        )
                        Spacer(Modifier.width(12.dp))
                    }
                    Text(
                        text = getCallStateText(callState).uppercase(),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 2.sp,
                        color = if (callState == Call.STATE_RINGING) Secondary else Color.White.copy(alpha = 0.5f)
                    )
                }
            }

            // 3. Controls Section
            Box(modifier = Modifier.fillMaxWidth().padding(bottom = 20.dp)) {
                if (callState == Call.STATE_RINGING) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        val usedForConnect = remember(socialProfiles) { com.infocaller.app.util.SocialUtils.filteredUsedProfiles(socialProfiles) }
                        if (usedForConnect.isNotEmpty()) {
                            Text("QUICK CONNECT", color = Color.White.copy(alpha = 0.4f), style = MaterialTheme.typography.labelSmall, letterSpacing = 2.sp, modifier = Modifier.padding(bottom = 12.dp))
                            Row(modifier = Modifier.padding(bottom = 32.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                                usedForConnect.take(3).forEach { profile -> SocialActionCircle(profile) }
                            }
                        }

                        SwipeToAnswer(
                            onAccept = { call?.answer(VideoProfile.STATE_AUDIO_ONLY) },
                            onDecline = { call?.reject(false, null); onDismiss() }
                        )
                    }
                } else {
                    ActiveCallControls(
                        number = number,
                        isMuted = isMuted,
                        isSpeakerOn = isSpeakerOn,
                        isHolding = isHolding,
                        isRecording = isRecording,
                        onMute = { CallManager.mute(!isMuted) },
                        onSpeaker = { CallManager.setSpeaker(!isSpeakerOn) },
                        onHold = { CallManager.toggleHold() },
                        onEnd = { call?.disconnect(); onDismiss() },
                        onKeypad = { showDtmf = !showDtmf }
                    )
                }
            }
        }
        if (showDtmf) DtmfOverlay(onDigit = { CallManager.playDtmf(it) }, onDismiss = { showDtmf = false })
    }
}

@Composable
fun SocialActionCircle(profile: com.infocaller.app.domain.model.SocialProfile) {
    val context = LocalContext.current
    
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(
            onClick = { SocialUtils.openSocialProfile(context, profile) },
            modifier = Modifier.size(56.dp).shadow(8.dp, CircleShape),
            shape = CircleShape,
            color = Color.White.copy(alpha = 0.1f),
            border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.2f))
        ) {
            Box(contentAlignment = Alignment.Center) {
                AsyncImage(
                    model = SocialUtils.getLogoUrl(profile.platform),
                    contentDescription = profile.platform,
                    modifier = Modifier.size(32.dp).clip(CircleShape),
                    contentScale = androidx.compose.ui.layout.ContentScale.Fit
                )
            }
        }
        Text(
            profile.platform.uppercase(), 
            modifier = Modifier.padding(top = 8.dp), 
            fontSize = 9.sp, 
            color = Color.White.copy(alpha = 0.5f),
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
fun SwipeToAnswer(onAccept: () -> Unit, onDecline: () -> Unit) {
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current
    val screenWidth = LocalConfiguration.current.screenWidthDp.dp
    val sliderWidth = screenWidth - 64.dp
    val sliderWidthPx = with(density) { sliderWidth.toPx() }
    var offsetX by remember { mutableFloatStateOf(0f) }
    val handleSize = 80.dp
    val handleSizePx = with(density) { handleSize.toPx() }
    val maxOffset = (sliderWidthPx - handleSizePx) / 2

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(100.dp)
            .padding(horizontal = 16.dp)
            .glassy(radius = 50.dp, blur = 20.dp)
            .background(Color.White.copy(alpha = 0.05f), CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 40.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.alpha(0.6f)) {
                Icon(Icons.Default.Close, null, tint = Error, modifier = Modifier.size(20.dp))
                Text("DECLINE", color = Error, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.alpha(0.6f)) {
                Icon(Icons.Default.Check, null, tint = Success, modifier = Modifier.size(20.dp))
                Text("ANSWER", color = Success, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
            }
        }

        Box(
            modifier = Modifier
                .offset { IntOffset(offsetX.roundToInt(), 0) }
                .size(handleSize)
                .padding(4.dp)
                .shadow(16.dp, CircleShape)
                .background(
                    brush = Brush.linearGradient(
                        colors = when {
                            offsetX > 50f -> listOf(Success, Color(0xFF00C853))
                            offsetX < -50f -> listOf(Error, Color(0xFFD50000))
                            else -> listOf(Color.White, Color.White.copy(alpha = 0.8f))
                        }
                    ),
                    shape = CircleShape
                )
                .draggable(
                    orientation = Orientation.Horizontal,
                    state = rememberDraggableState { delta ->
                        offsetX = (offsetX + delta).coerceIn(-maxOffset, maxOffset)
                    },
                    onDragStarted = { haptic.performHapticFeedback(HapticFeedbackType.LongPress) },
                    onDragStopped = {
                        if (offsetX >= maxOffset * 0.8f) {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onAccept()
                        } else if (offsetX <= -maxOffset * 0.8f) {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onDecline()
                        }
                        offsetX = 0f
                    }
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = when {
                    offsetX > 50f -> Icons.Default.Call
                    offsetX < -50f -> Icons.Default.CallEnd
                    else -> Icons.Default.UnfoldMoreDouble
                },
                contentDescription = null,
                tint = if (abs(offsetX) < 50f) Color.Black else Color.White,
                modifier = Modifier.size(32.dp)
            )
        }
    }
}

@Composable
fun ActiveCallControls(number: String, isMuted: Boolean, isSpeakerOn: Boolean, isHolding: Boolean, isRecording: Boolean, onMute: () -> Unit, onSpeaker: () -> Unit, onHold: () -> Unit, onEnd: () -> Unit, onKeypad: () -> Unit) {
    val context = LocalContext.current
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            InCallButton(icon = if (isMuted) Icons.Rounded.MicOff else Icons.Rounded.Mic, label = "Mute", active = isMuted, onClick = onMute)
            InCallButton(icon = Icons.AutoMirrored.Filled.VolumeUp, label = "Speaker", active = isSpeakerOn, onClick = onSpeaker)
            InCallButton(
                icon = if (isRecording) Icons.Rounded.FiberManualRecord else Icons.Rounded.RadioButtonUnchecked, 
                label = if (isRecording) "Recording" else "Record", 
                active = isRecording, 
                onClick = { 
                    CallManager.toggleRecording(context as? android.app.Activity ?: return@InCallButton, number) 
                }
            )
            InCallButton(icon = if (isHolding) Icons.Rounded.PlayArrow else Icons.Rounded.Pause, label = if (isHolding) "Resume" else "Hold", active = isHolding, onClick = onHold)
            InCallButton(icon = Icons.Rounded.Dialpad, label = "Keypad", onClick = onKeypad)
        }
        Spacer(modifier = Modifier.height(48.dp))
        Surface(
            onClick = onEnd, 
            modifier = Modifier.size(80.dp).shadow(24.dp, CircleShape), 
            shape = CircleShape, 
            color = Error
        ) {
            Box(contentAlignment = Alignment.Center) { Icon(Icons.Rounded.CallEnd, null, tint = Color.White, modifier = Modifier.size(36.dp)) }
        }
    }
}

@Composable
fun InCallButton(icon: ImageVector, label: String, active: Boolean = false, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(onClick = onClick, modifier = Modifier.size(60.dp), shape = CircleShape, color = if (active) Primary else Color.White.copy(alpha = 0.1f)) {
            Box(contentAlignment = Alignment.Center) { Icon(icon, label, tint = if (active) Color.Black else Color.White) }
        }
        Text(label, modifier = Modifier.padding(top = 8.dp), color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp)
    }
}

@Composable
fun DtmfOverlay(onDigit: (Char) -> Unit, onDismiss: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.85f)).clickable { onDismiss() }, contentAlignment = Alignment.Center) {
        DtmfGrid(onDigit = onDigit)
    }
}

@Composable
fun DtmfGrid(onDigit: (Char) -> Unit) {
    val digits = listOf(listOf('1', '2', '3'), listOf('4', '5', '6'), listOf('7', '8', '9'), listOf('*', '0', '#'))
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        digits.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                row.forEach { digit ->
                    Surface(onClick = { onDigit(digit) }, modifier = Modifier.size(64.dp), shape = CircleShape, color = Color.White.copy(alpha = 0.1f)) {
                        Box(contentAlignment = Alignment.Center) { Text(digit.toString(), color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold) }
                    }
                }
            }
        }
    }
}

@Composable
fun SocialMiniIcon(profile: com.infocaller.app.domain.model.SocialProfile) {
    val context = LocalContext.current
    
    Surface(
        onClick = { SocialUtils.openSocialProfile(context, profile) },
        modifier = Modifier.size(36.dp),
        shape = CircleShape,
        color = Color.White.copy(alpha = 0.1f),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.1f))
    ) {
        Box(contentAlignment = Alignment.Center) {
            AsyncImage(
                model = SocialUtils.getLogoUrl(profile.platform),
                contentDescription = profile.platform,
                modifier = Modifier.size(20.dp).clip(CircleShape),
                contentScale = androidx.compose.ui.layout.ContentScale.Fit
            )
        }
    }
}

fun getCallStateText(state: Int): String {
    return when (state) {
        Call.STATE_ACTIVE -> "In Call"
        Call.STATE_RINGING -> "Incoming"
        Call.STATE_DIALING -> "Dialing"
        Call.STATE_CONNECTING -> "Connecting"
        Call.STATE_HOLDING -> "On Hold"
        Call.STATE_DISCONNECTED -> "Disconnected"
        else -> ""
    }
}
