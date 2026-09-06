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
    private var proximityLock: com.infocaller.app.util.ProximityLock? = null

    fun setProximityHeld(held: Boolean) {
        try { proximityLock?.setHeld(held) } catch (_: Exception) { }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        proximityLock = try {
            com.infocaller.app.util.ProximityLock(this)
        } catch (_: Exception) {
            null
        }
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

    override fun onDestroy() {
        try { proximityLock?.release() } catch (_: Exception) { }
        proximityLock = null
        super.onDestroy()
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

    val activity = context as? ComponentActivity

    DisposableEffect(Unit) {
        CallManager.init(context)
        onDispose {
            try { (activity as? InCallActivity)?.setProximityHeld(false) } catch (_: Exception) { }
        }
    }

    DisposableEffect(callState, isSpeakerOn) {
        // Proximity screen-off: held ONLY during an active earpiece call —
        // sensor blanks the screen at the ear and wakes it when pulled away.
        // Released while ringing (user needs answer buttons), on speaker
        // (phone is away from the face), or when the call ends.
        val hold = com.infocaller.app.util.ProximityPolicy.shouldHold(callState, isSpeakerOn)
        try {
            (activity as? InCallActivity)?.setProximityHeld(hold)
        } catch (_: Exception) { }
        onDispose { }
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

    val socialProfiles = remember(enrichment?.socialProfilesJson) {
        SocialUtils.fromJson(enrichment?.socialProfilesJson)
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        val imageModel = contactPhotoUri ?: enrichment?.profileImageUrl
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
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(top = 40.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
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
                
                val usedSocials = remember(socialProfiles) { com.infocaller.app.util.SocialUtils.filteredUsedProfiles(socialProfiles) }
                if (usedSocials.isNotEmpty()) {
                    Row(modifier = Modifier.padding(top = 24.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        usedSocials.forEach { profile -> SocialMiniIcon(profile) }
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))
                
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

                        // Big tap targets: red decline (left) + green answer
                        // (right) with handset glyphs, kept alongside the
                        // swipe gesture for users who prefer it.
                        TapAnswerRow(
                            onAccept = { call?.answer(VideoProfile.STATE_AUDIO_ONLY) },
                            onDecline = { call?.reject(false, null); onDismiss() }
                        )
                        Spacer(modifier = Modifier.height(16.dp))
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
    SocialBrandIcon(profile, size = 36.dp, iconSize = 20.dp, showLabel = false)
}

@Composable
fun SocialBrandIcon(
    profile: com.infocaller.app.domain.model.SocialProfile,
    size: androidx.compose.ui.unit.Dp = androidx.compose.ui.unit.Dp(36f),
    iconSize: androidx.compose.ui.unit.Dp = androidx.compose.ui.unit.Dp(20f),
    showLabel: Boolean = false,
) {
    val context = LocalContext.current
    // Brand-tinted ring per platform so each social account reads as its logo.
    val brand = SocialBrandColors.forPlatform(profile.platform)

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(
            onClick = { SocialUtils.openSocialProfile(context, profile) },
            modifier = Modifier.size(size).shadow(8.dp, CircleShape),
            shape = CircleShape,
            color = Color.White.copy(alpha = 0.08f),
            border = androidx.compose.foundation.BorderStroke(2.dp, brand.copy(alpha = 0.75f))
        ) {
            Box(contentAlignment = Alignment.Center) {
                Box(
                    modifier = Modifier.matchParentSize().clip(CircleShape)
                        .background(Brush.radialGradient(listOf(brand.copy(alpha = 0.35f), Color.Transparent)))
                )
                AsyncImage(
                    model = SocialUtils.getLogoUrl(profile.platform),
                    contentDescription = profile.platform,
                    modifier = Modifier.size(iconSize).clip(CircleShape),
                    contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                    error = rememberVectorPainter(Icons.Default.Share),
                    placeholder = rememberVectorPainter(Icons.Default.Share)
                )
            }
        }
        if (showLabel) {
            Text(
                profile.platform.uppercase(),
                modifier = Modifier.padding(top = 6.dp),
                fontSize = 9.sp,
                color = Color.White.copy(alpha = 0.6f),
                fontWeight = FontWeight.Bold
            )
        }
    }
}

object SocialBrandColors {
    fun forPlatform(platform: String): Color = when (platform.lowercase()) {
        "whatsapp" -> Color(0xFF25D366)
        "telegram" -> Color(0xFF229ED9)
        "facebook" -> Color(0xFF1877F2)
        "instagram" -> Color(0xFFE1306C)
        "linkedin" -> Color(0xFF0A66C2)
        "twitter", "x" -> Color(0xFF1D9BF0)
        "youtube" -> Color(0xFFFF0000)
        "tiktok" -> Color(0xFF69C9D0)
        "github" -> Color(0xFF9E9E9E)
        "snapchat" -> Color(0xFFFFFC00)
        "spotify" -> Color(0xFF1DB954)
        "reddit" -> Color(0xFFFF4500)
        else -> Color(0xFFFBBF24)
    }
}

/**
 * Big tap accept/decline: red decline circle (left) + green answer circle
 * (right) with handset glyphs + labels. Haptic on press. Kept alongside
 * SwipeToAnswer for users who prefer the gesture.
 */
@Composable
fun TapAnswerRow(onAccept: () -> Unit, onDecline: () -> Unit) {
    val haptic = LocalHapticFeedback.current
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        TapAnswerButton(
            icon = Icons.Rounded.CallEnd,
            label = "DECLINE",
            base = Color(0xFFEF4444),
            deep = Color(0xFFB91C1C),
            onTap = {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onDecline()
            }
        )
        TapAnswerButton(
            icon = Icons.Rounded.Call,
            label = "ANSWER",
            base = Color(0xFF22C55E),
            deep = Color(0xFF15803D),
            onTap = {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onAccept()
            }
        )
    }
}

@Composable
private fun TapAnswerButton(
    icon: ImageVector,
    label: String,
    base: Color,
    deep: Color,
    onTap: () -> Unit,
) {
    var pressed by remember { mutableStateOf(false) }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(
            onClick = onTap,
            modifier = Modifier.size(88.dp).shadow(24.dp, CircleShape).scale(if (pressed) 0.92f else 1f),
            shape = CircleShape,
            color = Color.Transparent,
            border = androidx.compose.foundation.BorderStroke(2.dp, Color.White.copy(alpha = 0.25f))
        ) {
            Box(
                modifier = Modifier.fillMaxSize().background(Brush.linearGradient(listOf(base, deep))),
                contentAlignment = Alignment.Center
            ) {
                // White circular badge behind the glyph = logo-button look.
                Box(
                    modifier = Modifier.size(52.dp).clip(CircleShape).background(Color.White),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(icon, label, tint = deep, modifier = Modifier.size(30.dp))
                }
            }
        }
        Text(label, modifier = Modifier.padding(top = 10.dp), color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.sp)
    }
    LaunchedEffect(pressed) {
        if (pressed) {
            kotlinx.coroutines.delay(150)
            pressed = false
        }
    }
}

/**
 * Active-call control grid: mute / speaker / record / hold / keypad on row
 * one, a "More" button opening mic-routes, add-call, video, and contacts
 * shortcuts on row two, and the red end-call button below.
 */
@Composable
fun ActiveCallControls(number: String, isMuted: Boolean, isSpeakerOn: Boolean, isHolding: Boolean, isRecording: Boolean, onMute: () -> Unit, onSpeaker: () -> Unit, onHold: () -> Unit, onEnd: () -> Unit, onKeypad: () -> Unit) {
    val context = LocalContext.current
    var showMore by remember { mutableStateOf(false) }
    var showSoundboard by remember { mutableStateOf(false) }
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
        Spacer(modifier = Modifier.height(20.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            InCallButton(icon = Icons.Rounded.MoreHoriz, label = "More", onClick = { showMore = true })
            InCallButton(icon = Icons.Rounded.MusicNote, label = "Sounds", onClick = { showSoundboard = true })
            InCallButton(icon = Icons.Rounded.PersonAdd, label = "Add call", onClick = {
                try {
                    val intent = android.content.Intent(android.content.Intent.ACTION_DIAL)
                    context.startActivity(intent)
                } catch (_: Exception) { }
            })
            InCallButton(icon = Icons.Rounded.Videocam, label = "Video", onClick = {
                // Video upgrade needs carrier/IMS support — fall back to the
                // system dialer for video-capable handling instead of a no-op.
                try {
                    val intent = android.content.Intent(android.content.Intent.ACTION_DIAL)
                    context.startActivity(intent)
                } catch (_: Exception) { }
            })
            InCallButton(icon = Icons.Rounded.Contacts, label = "Contacts", onClick = {
                try {
                    val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                        data = android.provider.ContactsContract.Contacts.CONTENT_URI
                        flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    context.startActivity(intent)
                } catch (_: Exception) { }
            })
        }
        Spacer(modifier = Modifier.height(36.dp))
        Surface(
            onClick = onEnd,
            modifier = Modifier.size(80.dp).shadow(24.dp, CircleShape),
            shape = CircleShape,
            color = Color.Transparent,
            border = androidx.compose.foundation.BorderStroke(2.dp, Color.White.copy(alpha = 0.25f))
        ) {
            Box(
                modifier = Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Error, Color(0xFFB91C1C)))),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier.size(48.dp).clip(CircleShape).background(Color.White),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Rounded.CallEnd, null, tint = Color(0xFFB91C1C), modifier = Modifier.size(28.dp))
                }
            }
        }
        Text("END CALL", modifier = Modifier.padding(top = 10.dp), color = Error, fontSize = 13.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.sp)
    }
    if (showMore) {
        MoreControlsSheet(
            onDismiss = { showMore = false },
            onMute = { onMute(); showMore = false },
            onSpeaker = { onSpeaker(); showMore = false },
            onHold = { onHold(); showMore = false },
            onKeypad = { onKeypad(); showMore = false },
        )
    }
    if (showSoundboard) {
        InCallSoundboardSheet(onDismiss = { showSoundboard = false })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MoreControlsSheet(
    onDismiss: () -> Unit,
    onMute: () -> Unit,
    onSpeaker: () -> Unit,
    onHold: () -> Unit,
    onKeypad: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("More controls", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Color.White)
            Spacer(modifier = Modifier.height(8.dp))
            MoreRow(icon = Icons.Rounded.Mic, title = "Mute microphone", onClick = onMute)
            MoreRow(icon = Icons.AutoMirrored.Filled.VolumeUp, title = "Speaker output", onClick = onSpeaker)
            MoreRow(icon = Icons.Rounded.Pause, title = "Hold / resume", onClick = onHold)
            MoreRow(icon = Icons.Rounded.Dialpad, title = "Keypad", onClick = onKeypad)
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun MoreRow(icon: ImageVector, title: String, onClick: () -> Unit) {
    Surface(onClick = onClick, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), color = Color.White.copy(alpha = 0.06f)) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = Color.White, modifier = Modifier.size(24.dp))
            Spacer(modifier = Modifier.width(16.dp))
            Text(title, color = Color.White, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InCallSoundboardSheet(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val entries = remember { com.infocaller.app.util.SoundboardStore.load(context) }
    var playingId by remember { mutableStateOf<String?>(com.infocaller.app.util.SoundboardPlayer.playingId) }

    DisposableEffect(Unit) {
        onDispose {
            if (playingId == null) com.infocaller.app.util.SoundboardPlayer.stop()
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.fillMaxWidth().padding(24.dp)) {
            Text("Soundboard", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Color.White)
            Text(
                "Sounds play through the speaker so the other side hears them. Customize buttons in Settings → Soundboard.",
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.6f),
                modifier = Modifier.padding(top = 4.dp)
            )
            Spacer(modifier = Modifier.height(16.dp))
            androidx.compose.foundation.layout.FlowRow(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                entries.forEach { entry ->
                    val isPlaying = playingId == entry.id
                    Surface(
                        onClick = {
                            try {
                                com.infocaller.app.util.SoundboardPlayer.play(context, entry) {
                                    playingId = com.infocaller.app.util.SoundboardPlayer.playingId
                                }
                                playingId = com.infocaller.app.util.SoundboardPlayer.playingId
                            } catch (_: Exception) { }
                        },
                        shape = RoundedCornerShape(20.dp),
                        color = if (isPlaying) Primary else Color.White.copy(alpha = 0.08f),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.2f))
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(entry.emoji, fontSize = 24.sp)
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                if (isPlaying) "Stop" else entry.name,
                                color = Color.White,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
            if (entries.isEmpty()) {
                Text("No sounds yet — add some in Settings → Soundboard.", color = Color.White.copy(alpha = 0.6f))
            }
            Spacer(modifier = Modifier.height(16.dp))
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
