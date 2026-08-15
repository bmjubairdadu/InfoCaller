package com.infocaller.app.ui

import android.os.Bundle
import android.telecom.Call
import android.telecom.VideoProfile
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.infocaller.app.data.local.CallManager
import com.infocaller.app.data.local.entity.QueuePriority
import com.infocaller.app.ui.theme.*

class InCallActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            val keyguardManager = getSystemService(android.app.KeyguardManager::class.java)
            keyguardManager.requestDismissKeyguard(this, null)
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
    
    // PROGRESSIVE UI: Observe Enrichment Engine
    val enrichment by enrichmentEngine.getEnrichment(number).collectAsState(initial = null)
    
    var contactName by remember { mutableStateOf<String?>(null) }
    var contactPhotoUri by remember { mutableStateOf<String?>(null) }
    
    @Suppress("DEPRECATION")
    var callState by remember { mutableStateOf(call?.state ?: Call.STATE_DISCONNECTED) }

    val infiniteTransition = rememberInfiniteTransition(label = "Pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.2f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = FastOutSlowInEasing),
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
            if (state == Call.STATE_DISCONNECTED) {
                onDismiss()
            }
        }
    }

    DisposableEffect(call) {
        call?.registerCallback(callback)
        onDispose {
            call?.unregisterCallback(callback)
        }
    }

    LaunchedEffect(number) {
        if (number != "Unknown") {
            // 1. Get Local Contact Info
            contactName = com.infocaller.app.util.PhoneNumberUtils.getContactName(context, number)
            contactPhotoUri = com.infocaller.app.util.PhoneNumberUtils.getContactPhotoUri(context, number)

            // 2. High Priority Online Lookup (if needed)
            enrichmentEngine.enqueue(number, priority = QueuePriority.HIGH)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Background)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(GradientStart.copy(alpha = 0.3f), Background)
                    )
                )
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(top = 80.dp)
            ) {
                // Animated Avatar
                Box(contentAlignment = Alignment.Center) {
                    Box(
                        modifier = Modifier
                            .size(120.dp)
                            .scale(pulseScale)
                            .background(Primary.copy(alpha = 0.2f), CircleShape)
                    )
                    Surface(
                        modifier = Modifier.size(100.dp),
                        shape = CircleShape,
                        color = Color.White.copy(alpha = 0.1f),
                        border = androidx.compose.foundation.BorderStroke(2.dp, Color.White.copy(alpha = 0.2f))
                    ) {
                        val imageModel = remember(contactPhotoUri, enrichment?.profileImageUrl, number) {
                            contactPhotoUri ?: enrichment?.profileImageUrl ?: com.infocaller.app.util.PhoneNumberUtils.getImageUrl(number)
                        }
                        coil.compose.AsyncImage(
                            model = imageModel,
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                            placeholder = androidx.compose.ui.graphics.vector.rememberVectorPainter(Icons.Default.Person),
                            error = androidx.compose.ui.graphics.vector.rememberVectorPainter(Icons.Default.Person)
                        )
                    }
                }
                
                Spacer(modifier = Modifier.height(32.dp))
                
                // Dynamic Waveform (Simulated)
                if (callState == Call.STATE_ACTIVE) {
                    Row(
                        modifier = Modifier
                            .height(40.dp)
                            .fillMaxWidth(0.6f),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        repeat(15) { index ->
                            val h by infiniteTransition.animateFloat(
                                initialValue = 10f,
                                targetValue = 40f,
                                animationSpec = infiniteRepeatable(
                                    animation = tween(
                                        durationMillis = (500 + index * 100) % 800,
                                        easing = LinearEasing
                                    ),
                                    repeatMode = RepeatMode.Reverse
                                ),
                                label = "Waveform"
                            )
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(h.dp)
                                    .background(Primary.copy(alpha = 0.6f), CircleShape)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(24.dp))
                }

                Text(
                    text = contactName ?: enrichment?.publicName ?: "Unknown Caller",
                    style = MaterialTheme.typography.displayMedium,
                    color = Color.White,
                    fontWeight = FontWeight.ExtraBold
                )
                
                if (contactName == null && enrichment?.publicName == null) {
                    Text(
                        text = number,
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White.copy(alpha = 0.5f),
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }

                if (!enrichment?.confidence.isNullOrBlank() && contactName == null) {
                    Text(
                        text = "Confidence: ${(enrichment!!.confidence!!.toFloatOrNull() ?: 0f * 100).toInt()}%",
                        style = MaterialTheme.typography.labelSmall,
                        color = Primary
                    )
                }

                // Spam Information (Ported from reference project)
                if (enrichment?.spamStatus == "SPAM" || enrichment?.spamStatus == "SCAM" || (enrichment?.spamScore ?: 0) > 50) {
                    Card(
                        modifier = Modifier.padding(top = 12.dp),
                        colors = CardDefaults.cardColors(containerColor = Color.Red.copy(alpha = 0.1f))
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp), 
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = "Spam Detected: ${enrichment?.spamType ?: enrichment?.spamStatus}",
                                color = Color.Red,
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.labelMedium
                            )
                            if ((enrichment?.spamScore ?: 0) > 0) {
                                Text(
                                    text = "Spam Score: ${enrichment?.spamScore}",
                                    color = Color.Red.copy(alpha = 0.7f),
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                        }
                    }
                }

                // STAGE 2: Location
                val location = listOfNotNull(enrichment?.region, enrichment?.country).joinToString(", ")
                if (location.isNotBlank()) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(top = 4.dp)
                    ) {
                        Icon(Icons.Default.Place, contentDescription = null, tint = Color.White.copy(alpha = 0.6f), modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = location,
                            style = MaterialTheme.typography.titleMedium,
                            color = Color.White.copy(alpha = 0.6f)
                        )
                    }
                }

                // About / Status
                if (!enrichment?.about.isNullOrBlank()) {
                    Text(
                        text = enrichment!!.about!!,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.5f),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        maxLines = 2,
                        modifier = Modifier.padding(top = 8.dp).padding(horizontal = 32.dp)
                    )
                }

                // STAGE 3: Social Media Icons with Status
                Row(
                    modifier = Modifier.padding(top = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // WhatsApp
                    SocialIcon("WhatsApp", enrichment?.whatsappStatus)
                    // Telegram
                    SocialIcon("Telegram", enrichment?.telegramStatus)
                }

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = getCallStateText(callState),
                    style = MaterialTheme.typography.titleLarge,
                    color = if (callState == Call.STATE_RINGING) Secondary else Color.White.copy(alpha = 0.6f)
                )

                if (showDtmf) {
                    Spacer(modifier = Modifier.height(32.dp))
                    DtmfGrid(onDigit = { CallManager.playDtmf(it) })
                }
            }

            // Floating Glass Control Panel
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 40.dp)
                    .glassy(radius = 32.dp, blur = 20.dp),
                colors = CardDefaults.cardColors(containerColor = Color.Transparent)
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    if (callState == Call.STATE_RINGING) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly
                        ) {
                            InCallButton(
                                icon = Icons.Default.Call,
                                label = "Accept",
                                color = Color.Green,
                                onClick = { call?.answer(VideoProfile.STATE_AUDIO_ONLY) }
                            )
                            InCallButton(
                                icon = Icons.Default.CallEnd,
                                label = "Decline",
                                color = Color.Red,
                                onClick = { 
                                    call?.reject(false, null) 
                                    onDismiss()
                                }
                            )
                            
                            InCallButton(
                                icon = Icons.Default.Sms,
                                label = "Message",
                                color = Color.White.copy(alpha = 0.1f),
                                onClick = { 
                                    val smsIntent = android.content.Intent(android.content.Intent.ACTION_SENDTO).apply {
                                        data = "smsto:$number".toUri()
                                        putExtra("sms_body", "Can't talk right now. I'll call you back.")
                                    }
                                    context.startActivity(smsIntent)
                                    call?.disconnect()
                                }
                            )
                        }
                    } else {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly
                        ) {
                            InCallButton(
                                icon = if (isMuted) Icons.Default.MicOff else Icons.Default.Mic,
                                label = if (isMuted) "Unmute" else "Mute",
                                color = if (isMuted) Primary else Color.White.copy(alpha = 0.1f),
                                onClick = { CallManager.mute(!isMuted) }
                            )
                            InCallButton(
                                icon = Icons.AutoMirrored.Filled.VolumeUp,
                                label = "Speaker",
                                color = if (isSpeakerOn) Primary else Color.White.copy(alpha = 0.1f),
                                onClick = { CallManager.setSpeaker(!isSpeakerOn) }
                            )
                            InCallButton(
                                icon = if (isHolding) Icons.Default.PlayArrow else Icons.Default.Pause,
                                label = if (isHolding) "Resume" else "Hold",
                                color = if (isHolding) Primary else Color.White.copy(alpha = 0.1f),
                                onClick = { CallManager.toggleHold() }
                            )
                            InCallButton(
                                icon = Icons.Default.Dialpad,
                                label = "Keypad",
                                color = if (showDtmf) Primary else Color.White.copy(alpha = 0.1f),
                                onClick = { showDtmf = !showDtmf }
                            )
                        }
                        
                        Spacer(modifier = Modifier.height(24.dp))
                        
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            InCallButton(
                                icon = Icons.Default.FiberManualRecord,
                                label = if (isRecording) "Stop" else "Record",
                                color = if (isRecording) Error else Color.White.copy(alpha = 0.1f),
                                onClick = { 
                                    CallManager.toggleRecording(context as? android.app.Activity ?: return@InCallButton, number)
                                }
                            )
                            
                            Surface(
                                onClick = { call?.disconnect() },
                                modifier = Modifier
                                    .size(72.dp)
                                    .shadow(24.dp, CircleShape, spotColor = Error, ambientColor = Error),
                                shape = CircleShape,
                                color = Error
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(Icons.Default.CallEnd, contentDescription = "End", tint = Color.White, modifier = Modifier.size(32.dp))
                                }
                            }
                            
                            InCallButton(
                                icon = Icons.Default.Add,
                                label = "Add",
                                onClick = { 
                                    val intent = android.content.Intent(context, com.infocaller.app.MainActivity::class.java).apply {
                                        action = android.content.Intent.ACTION_DIAL
                                        flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                                    }
                                    context.startActivity(intent)
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SocialIcon(platform: String, status: String?) {
    if (status == null) return
    
    val icon = when (platform) {
        "WhatsApp" -> Icons.AutoMirrored.Filled.Chat
        "Telegram" -> Icons.AutoMirrored.Filled.Send
        else -> Icons.Default.Link
    }
    
    Box(contentAlignment = Alignment.BottomEnd) {
        Icon(
            imageVector = icon,
            contentDescription = platform,
            tint = Primary,
            modifier = Modifier.size(28.dp)
        )
        if (status == "CONFIRMED" || status == "PUBLIC_MATCH") {
            Icon(
                Icons.Default.CheckCircle,
                contentDescription = "Verified",
                tint = Success,
                modifier = Modifier.size(12.dp).background(Background, CircleShape)
            )
        }
    }
}

@Composable
fun DtmfGrid(onDigit: (Char) -> Unit) {
    val digits = listOf(
        listOf('1', '2', '3'),
        listOf('4', '5', '6'),
        listOf('7', '8', '9'),
        listOf('*', '0', '#')
    )
    Column(
        modifier = Modifier.glassy(radius = 24.dp).padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        digits.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                row.forEach { digit ->
                    Surface(
                        onClick = { onDigit(digit) },
                        modifier = Modifier.size(56.dp),
                        shape = CircleShape,
                        color = Color.White.copy(alpha = 0.1f)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(digit.toString(), color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun InCallButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    modifier: Modifier = Modifier,
    color: Color = Color.White.copy(alpha = 0.2f),
    onClick: () -> Unit
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(
            onClick = onClick,
            modifier = modifier.size(60.dp),
            shape = CircleShape,
            color = color
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = label, tint = Color.White, modifier = Modifier.size(28.dp))
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(text = label, color = Color.White.copy(alpha = 0.8f), fontSize = 11.sp, fontWeight = FontWeight.Medium)
    }
}

fun getCallStateText(state: Int): String {
    return when (state) {
        Call.STATE_ACTIVE -> "Active"
        Call.STATE_RINGING -> "Incoming Call"
        Call.STATE_DIALING -> "Dialing"
        Call.STATE_CONNECTING -> "Connecting"
        Call.STATE_HOLDING -> "On Hold"
        Call.STATE_DISCONNECTING -> "Disconnecting"
        Call.STATE_DISCONNECTED -> "Disconnected"
        else -> "Call"
    }
}
