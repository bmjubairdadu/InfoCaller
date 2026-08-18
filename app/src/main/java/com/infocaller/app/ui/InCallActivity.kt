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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.core.net.toUri
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
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
    val context = androidx.compose.ui.platform.LocalContext.current
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
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "PulseScale"
    )

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

    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        val bgGradient = if (isBlocked || enrichment?.spamStatus == "SPAM") {
            Brush.verticalGradient(listOf(Error.copy(alpha = 0.4f), MaterialTheme.colorScheme.background))
        } else {
            Brush.verticalGradient(listOf(Primary.copy(alpha = 0.25f), MaterialTheme.colorScheme.background))
        }

        Box(modifier = Modifier.fillMaxSize().background(bgGradient))

        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(top = 80.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Box(modifier = Modifier.size(120.dp).scale(pulseScale).background(Primary.copy(alpha = 0.15f), CircleShape))
                    Surface(
                        modifier = Modifier.size(100.dp),
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        border = androidx.compose.foundation.BorderStroke(2.dp, Color.White.copy(alpha = 0.1f))
                    ) {
                        val imageModel = contactPhotoUri ?: enrichment?.profileImageUrl
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
                                Text(text = initials, style = MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight.Bold), color = Primary)
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(32.dp))
                Text(text = contactName ?: enrichment?.publicName ?: "Unknown Caller", style = MaterialTheme.typography.displayMedium, fontWeight = FontWeight.ExtraBold, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                Text(text = number, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))

                if (enrichment?.spamStatus == "SPAM" || (enrichment?.spamScore ?: 0) > 50) {
                    Card(modifier = Modifier.padding(top = 16.dp), colors = CardDefaults.cardColors(containerColor = Error.copy(alpha = 0.15f))) {
                        Text(text = "POTENTIAL SPAM", color = Error, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
                    }
                }

                val location = LocationUtils.formatCallerLocation(enrichment?.city, enrichment?.region, enrichment?.country)
                if (location.isNotBlank()) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                        Icon(Icons.Default.Place, null, tint = Primary, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(location, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
                Text(text = getCallStateText(callState), style = MaterialTheme.typography.titleLarge, color = if (callState == Call.STATE_RINGING) Secondary else MaterialTheme.colorScheme.onSurfaceVariant)
            }

            Box(modifier = Modifier.fillMaxWidth().padding(bottom = 48.dp)) {
                if (callState == Call.STATE_RINGING) {
                    IncomingCallControls(
                        onAccept = { call?.answer(VideoProfile.STATE_AUDIO_ONLY) },
                        onDecline = { call?.reject(false, null); onDismiss() }
                    )
                } else {
                    ActiveCallControls(
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
fun IncomingCallControls(onAccept: () -> Unit, onDecline: () -> Unit) {
    val density = LocalDensity.current
    val screenWidth = LocalConfiguration.current.screenWidthDp.dp
    val sliderWidth = screenWidth - 64.dp
    val sliderWidthPx = with(density) { sliderWidth.toPx() }
    var offsetX by remember { mutableFloatStateOf(0f) }
    val handleSize = 72.dp
    val handleSizePx = with(density) { handleSize.toPx() }
    val maxOffset = (sliderWidthPx - handleSizePx) / 2

    Box(modifier = Modifier.fillMaxWidth().height(handleSize + 16.dp).padding(horizontal = 32.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.05f)), contentAlignment = Alignment.Center) {
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Decline", color = Error.copy(alpha = 0.4f), fontWeight = FontWeight.Bold)
            Text("Answer", color = Success.copy(alpha = 0.4f), fontWeight = FontWeight.Bold)
        }
        Box(modifier = Modifier.offset { IntOffset(offsetX.roundToInt(), 0) }.size(handleSize).shadow(12.dp, CircleShape).background(brush = Brush.linearGradient(colors = when { offsetX > 40f -> listOf(Success, Success.copy(alpha = 0.7f)); offsetX < -40f -> listOf(Error, Error.copy(alpha = 0.7f)); else -> listOf(Primary, PrimaryVariant) }), shape = CircleShape).draggable(orientation = Orientation.Horizontal, state = rememberDraggableState { delta -> offsetX = (offsetX + delta).coerceIn(-maxOffset, maxOffset) }, onDragStopped = { if (offsetX >= maxOffset * 0.7f) onAccept() else if (offsetX <= -maxOffset * 0.7f) onDecline(); offsetX = 0f }), contentAlignment = Alignment.Center) {
            Icon(imageVector = when { offsetX > 40f -> Icons.Default.Call; offsetX < -40f -> Icons.Default.CallEnd; else -> Icons.Default.UnfoldMoreDouble }, contentDescription = null, tint = if (offsetX.roundToInt() == 0) Color.Black else Color.White, modifier = Modifier.size(32.dp))
        }
    }
}

@Composable
fun ActiveCallControls(isMuted: Boolean, isSpeakerOn: Boolean, isHolding: Boolean, isRecording: Boolean, onMute: () -> Unit, onSpeaker: () -> Unit, onHold: () -> Unit, onEnd: () -> Unit, onKeypad: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            InCallButton(icon = if (isMuted) Icons.Default.MicOff else Icons.Default.Mic, label = "Mute", active = isMuted, onClick = onMute)
            InCallButton(icon = Icons.AutoMirrored.Filled.VolumeUp, label = "Speaker", active = isSpeakerOn, onClick = onSpeaker)
            InCallButton(icon = if (isRecording) Icons.Default.FiberManualRecord else Icons.Default.RadioButtonUnchecked, label = if (isRecording) "Recording" else "Record", active = isRecording, onClick = { CallManager.toggleRecording(context as? android.app.Activity ?: return@InCallButton, "") })
            InCallButton(icon = if (isHolding) Icons.Default.PlayArrow else Icons.Default.Pause, label = if (isHolding) "Resume" else "Hold", active = isHolding, onClick = onHold)
            InCallButton(icon = Icons.Default.Dialpad, label = "Keypad", onClick = onKeypad)
        }
        Spacer(modifier = Modifier.height(40.dp))
        Surface(onClick = onEnd, modifier = Modifier.size(80.dp).shadow(20.dp, CircleShape), shape = CircleShape, color = Error) {
            Box(contentAlignment = Alignment.Center) { Icon(Icons.Default.CallEnd, null, tint = Color.White, modifier = Modifier.size(36.dp)) }
        }
    }
}

@Composable
fun InCallButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, active: Boolean = false, onClick: () -> Unit) {
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
