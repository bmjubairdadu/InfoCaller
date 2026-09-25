package com.infocaller.app.service

import android.app.*
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.provider.ContactsContract
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.CallEnd
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationCompat
import com.infocaller.app.R
import androidx.lifecycle.*
import androidx.savedstate.*
import coil.compose.AsyncImage
import com.infocaller.app.data.repository.ContactEnrichmentService
import com.infocaller.app.domain.repository.CallerRepository
import com.infocaller.app.ui.theme.*
import com.infocaller.app.util.*
import kotlinx.coroutines.*

class CallOverlayService : Service(), LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {
    companion object {
        const val INCOMING_NOTIFICATION_ID = 4201
        const val CLOSE_OVERLAY_ACTION = "com.infocaller.app.CLOSE_OVERLAY"
        @Volatile
        private var repositoryInstance: CallerRepository? = null

        fun setRepository(repository: CallerRepository) {
            repositoryInstance = repository
        }

        fun getRepository(): CallerRepository? = repositoryInstance
    }

    private lateinit var windowManager: WindowManager
    private var overlayView: ComposeView? = null

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val store = ViewModelStore()
    private val savedStateRegistryController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle = lifecycleRegistry
    override val viewModelStore: ViewModelStore = store
    override val savedStateRegistry: SavedStateRegistry = savedStateRegistryController.savedStateRegistry

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val phoneNumber = intent?.getStringExtra("EXTRA_PHONE_NUMBER")
        if (phoneNumber.isNullOrBlank()) {
            clearIncomingCallNotification()
            stopSelf()
            return START_NOT_STICKY
        }
        showForegroundNotification()
        publishIncomingCallNotification(phoneNumber, null)
        showOverlay(phoneNumber)
        registerCloseReceiver()
        refreshIncomingName(phoneNumber)
        return START_NOT_STICKY
    }

    private fun refreshIncomingName(phoneNumber: String) {
        try {
            CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
                val name = try {
                    val normalized = PhoneNumberUtils.normalize(phoneNumber)
                    val cached = (getRepository() as? com.infocaller.app.domain.repository.CallerRepository)
                        ?.let { repo -> try { repo.getSharedRegistryCaller(normalized) } catch (_: Exception) { null } }
                    cached?.displayName
                        ?: com.infocaller.app.util.PhoneNumberUtils.getContactName(this@CallOverlayService, phoneNumber)
                } catch (_: Exception) { null } catch (_: Error) { null }
                if (!name.isNullOrBlank()) {
                    publishIncomingCallNotification(phoneNumber, name)
                }
            }
        } catch (_: Exception) { } catch (_: Error) { }
    }

    private val closeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == CLOSE_OVERLAY_ACTION) {
                clearIncomingCallNotification()
                stopSelf()
            }
        }
    }

    private fun registerCloseReceiver() {
        try {
            val filter = IntentFilter(CLOSE_OVERLAY_ACTION)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(closeReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                registerReceiver(closeReceiver, filter)
            }
        } catch (_: Exception) { } catch (_: Error) { }
    }

    private fun showForegroundNotification() {
        val channelId = "call_overlay_channel"
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val ch = NotificationChannel(channelId, "Call Overlay", NotificationManager.IMPORTANCE_MIN).apply {
                setShowBadge(false); enableVibration(false); setSound(null,null)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
        val n = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Caller info")
            .setContentText("Showing caller identification")
            .setSmallIcon(R.drawable.app_logo)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .setAutoCancel(false)
            .setOnlyAlertOnce(true)
            .build()
        startForeground(1, n)
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            try { if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) stopForeground(STOP_FOREGROUND_DETACH) else @Suppress("DEPRECATION") stopForeground(false) } catch(_:Exception){}
        }, 400)
    }

    private fun publishIncomingCallNotification(phoneNumber: String, displayName: String?) {
        try {
            val channelId = "incoming_call_channel"
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                val mgr = getSystemService(NotificationManager::class.java)
                if (mgr.getNotificationChannel(channelId) == null) {
                    val ch = NotificationChannel(channelId, "Incoming calls", NotificationManager.IMPORTANCE_HIGH).apply {
                        setShowBadge(false)
                        enableVibration(true)
                        vibrationPattern = longArrayOf(0, 400, 200, 400)
                        lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                        setBypassDnd(false)
                    }
                    mgr.createNotificationChannel(ch)
                }
            }

            val normalized = PhoneNumberUtils.normalize(phoneNumber)
            val fullScreen = PendingIntent.getActivity(
                this, normalized.hashCode(),
                Intent(this, com.infocaller.app.MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    putExtra("EXTRA_OPEN_NUMBER", phoneNumber)
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val answer = PendingIntent.getBroadcast(
                this, normalized.hashCode() + 1,
                Intent(this, com.infocaller.app.receiver.CallActionReceiver::class.java).apply {
                    action = com.infocaller.app.receiver.CallActionReceiver.ACTION_ANSWER
                    putExtra("EXTRA_PHONE_NUMBER", phoneNumber)
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val decline = PendingIntent.getBroadcast(
                this, normalized.hashCode() + 2,
                Intent(this, com.infocaller.app.receiver.CallActionReceiver::class.java).apply {
                    action = com.infocaller.app.receiver.CallActionReceiver.ACTION_DECLINE
                    putExtra("EXTRA_PHONE_NUMBER", phoneNumber)
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val title = displayName?.takeIf { it.isNotBlank() } ?: phoneNumber

            val builder = NotificationCompat.Builder(this, channelId)
                .setSmallIcon(R.drawable.app_logo)
                .setContentTitle(title)
                .setContentText("Incoming call from $phoneNumber")
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setOngoing(true)
                .setAutoCancel(false)
                .setFullScreenIntent(fullScreen, true)
                .setContentIntent(fullScreen)
                .addAction(android.R.drawable.ic_menu_call, "Answer", answer)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Decline", decline)

            getSystemService(NotificationManager::class.java)
                .notify(INCOMING_NOTIFICATION_ID, builder.build())
        } catch (_: Exception) { } catch (_: Error) { }
    }

    private fun clearIncomingCallNotification() {
        try { getSystemService(NotificationManager::class.java).cancel(INCOMING_NOTIFICATION_ID) } catch (_: Exception) { } catch (_: Error) { }
    }

    private fun showOverlay(phoneNumber: String) {
        overlayView?.let {
            try {
                windowManager.removeView(it)
            } catch (e: Exception) {
                Log.e("CallOverlayService", "Failed to remove old overlay", e)
            }
        }

        if (!android.provider.Settings.canDrawOverlays(this)) {
            Log.w("CallOverlayService", "Overlay permission not granted")
            return
        }

        val flags = WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
            WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH

        val layoutType = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            layoutType,
            flags,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.CENTER
        }

        overlayView = ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@CallOverlayService)
            setViewTreeViewModelStoreOwner(this@CallOverlayService)
            setViewTreeSavedStateRegistryOwner(this@CallOverlayService)

            setContent {
                MaterialTheme {
                    OverlayUI(phoneNumber)
                }
            }
        }

        try {
            windowManager.addView(overlayView, params)
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        } catch (e: Exception) {
            Log.e("CallOverlayService", "Failed to add overlay", e)
        }
    }

    @Composable
    private fun OverlayUI(phoneNumber: String) {
        val context = LocalContext.current
        val app = context.applicationContext as com.infocaller.app.InfoCallerApplication
        val repository = try { app.repository } catch (_: Exception) { getRepository() }
        val enrichmentEngine = try { app.enrichmentEngine } catch (_: Exception) { null }
        val enrichmentService = remember {
            ContactEnrichmentService(
                context,
                try { app.lookupEngine } catch (_: Exception) { null },
                try { app.repository } catch (_: Exception) { repository },
                try { app.database } catch (_: Exception) { null }
            )
        }

        val normalizedNumber = remember(phoneNumber) { PhoneNumberUtils.normalize(phoneNumber) }
        val enrichment by enrichmentEngine?.getEnrichment(normalizedNumber)
            ?.collectAsState(initial = null) ?: remember { mutableStateOf(null) }.let { it as androidx.compose.runtime.State<com.infocaller.app.data.local.entity.ContactEnrichmentEntity?> }

        var contactName by remember { mutableStateOf<String?>(null) }
        var contactPhotoUri by remember { mutableStateOf<String?>(null) }
        var isBlocked by remember { mutableStateOf(value = false) }

        LaunchedEffect(normalizedNumber) {
            val (name, photo) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                PhoneNumberUtils.getContactName(context, phoneNumber) to
                    PhoneNumberUtils.getContactPhotoUri(context, phoneNumber)
            }
            contactName = name
            contactPhotoUri = photo
            isBlocked = try {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    repository?.isBlocked(normalizedNumber) == true
                }
            } catch (_: Exception) { false }

            try {
                enrichmentEngine?.enqueue(normalizedNumber, priority = com.infocaller.app.data.local.entity.QueuePriority.HIGH)
            } catch (_: Exception) { }
        }

        val displayName = contactName ?: enrichment?.publicName ?: "Unknown Caller"
        val photoUrl = contactPhotoUri ?: enrichment?.profileImageUrl
        val location = LocationUtils.formatCallerLocation(enrichment?.city, enrichment?.region, enrichment?.country)
        val overlaySocials = remember(enrichment?.socialProfilesJson) {
            try { SocialUtils.filteredUsedProfiles(SocialUtils.fromJson(enrichment?.socialProfilesJson)) }
            catch (_: Exception) { emptyList() }
        }

        val pulseT = androidx.compose.animation.core.rememberInfiniteTransition(label = "pulse")
        val glowT = androidx.compose.animation.core.rememberInfiniteTransition(label = "glow")
        val ringT = androidx.compose.animation.core.rememberInfiniteTransition(label = "ring")

        val pulse by pulseT.animateFloat(            initialValue = 0.82f,
            targetValue = 1.10f,
            animationSpec = androidx.compose.animation.core.infiniteRepeatable(
                animation = androidx.compose.animation.core.tween(1500, easing = androidx.compose.animation.core.FastOutSlowInEasing),
                repeatMode = androidx.compose.animation.core.RepeatMode.Reverse
            ),
            label = "pulseScale"
        )
        val glow by glowT.animateFloat(
            initialValue = 0.10f,
            targetValue = 0.30f,
            animationSpec = androidx.compose.animation.core.infiniteRepeatable(
                animation = androidx.compose.animation.core.tween(1500, easing = androidx.compose.animation.core.FastOutSlowInEasing),
                repeatMode = androidx.compose.animation.core.RepeatMode.Reverse
            ),
            label = "glowAlpha"
        )
        val ringAlpha by ringT.animateFloat(
            initialValue = 0.45f,
            targetValue = 0f,
            animationSpec = androidx.compose.animation.core.infiniteRepeatable(
                animation = androidx.compose.animation.core.tween(2200),
                repeatMode = androidx.compose.animation.core.RepeatMode.Restart
            ),
            label = "ringAlpha"
        )
        androidx.compose.animation.AnimatedVisibility(
            visible = true,
            enter = androidx.compose.animation.fadeIn(
                androidx.compose.animation.core.tween(350)
            ) + androidx.compose.animation.slideInVertically(
                androidx.compose.animation.core.tween(350)
            ) { -it / 3 }
        ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .shadow(24.dp, RoundedCornerShape(28.dp), clip = false, ambientColor = Color(0xFF4FC3F7), spotColor = Color(0xFF4FC3F7))
                .glassy(radius = 28.dp, blur = 20.dp),
            colors = CardDefaults.cardColors(containerColor = Color.Transparent)
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            brush = Brush.verticalGradient(
                                colors = listOf(Color(0xFF1B2B4D), Color(0xFF0B1224), Color(0xFF0E1830))
                            )
                        )
                        .padding(20.dp)
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(contentAlignment = Alignment.Center) {
                            Box(
                                modifier = Modifier
                                    .size((150 * pulse).dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFF4FC3F7).copy(alpha = ringAlpha * 0.35f))
                            )
                            Box(
                                modifier = Modifier
                                    .size((120 * pulse).dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFF4FC3F7).copy(alpha = ringAlpha * 0.25f))
                            )
                            Box(
                                modifier = Modifier
                                    .size((88 * pulse).dp)
                                    .clip(CircleShape)
                                    .background(
                                        Brush.radialGradient(
                                            listOf(
                                                Color(0xFF4FC3F7).copy(alpha = glow),
                                                Color.Transparent
                                            )
                                        )
                                    )
                            )
                            Box(
                                modifier = Modifier.size(88.dp).clip(CircleShape)
                                    .background(Color.White.copy(alpha = 0.12f))
                                    .border(3.dp, Color(0xFFE8B84B), CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                if (photoUrl != null) {
                                    AsyncImage(
                                        model = photoUrl,
                                        contentDescription = null,
                                        modifier = Modifier.fillMaxSize().clip(CircleShape),
                                        contentScale = ContentScale.Crop,
                                        placeholder = rememberVectorPainter(Icons.Default.Person),
                                        error = rememberVectorPainter(Icons.Default.Person)
                                    )
                                } else {
                                    Text(
                                        text = ContactUtils.getInitials(displayName),
                                        style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                                        color = Color.White
                                    )
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = if (isBlocked) "Blocked Caller" else "Incoming Call",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isBlocked) Color(0xFFEF4444) else Color(0xFF4FC3F7),
                            fontWeight = FontWeight.ExtraBold,
                            letterSpacing = 2.sp
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = displayName,
                            style = MaterialTheme.typography.headlineSmall,
                            color = Color.White,
                            fontWeight = FontWeight.ExtraBold,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                        Text(
                            text = try { PhoneNumberUtils.formatAsYouType(phoneNumber) } catch (_: Exception) { phoneNumber },
                            style = MaterialTheme.typography.bodyLarge,
                            color = Color.White.copy(alpha = 0.75f),
                            modifier = Modifier.padding(top = 2.dp)
                        )
                        if (enrichment == null && contactName == null && !isBlocked) {
                            Text(
                                text = "Identifying…",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White.copy(alpha = 0.55f),
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }
                        if (location.isNotBlank()) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(top = 8.dp)
                            ) {
                                Icon(Icons.Default.Place, contentDescription = null, tint = Color.White.copy(alpha = 0.6f), modifier = Modifier.size(14.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = location,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = Color.White.copy(alpha = 0.75f)
                                )
                            }
                        }
                        if (!enrichment?.nid.isNullOrBlank()) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Surface(
                                color = Color.White.copy(alpha = 0.1f),
                                shape = RoundedCornerShape(20.dp),
                                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFE8B84B).copy(alpha = 0.5f))
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                                ) {
                                    Icon(Icons.Default.Fingerprint, contentDescription = null, tint = Color(0xFFE8B84B), modifier = Modifier.size(14.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    val overlayNid = enrichment!!.nid + if (!enrichment!!.dob.isNullOrBlank()) " · ${enrichment!!.dob}" else ""
                                    Text(
                                        text = "NID $overlayNid",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = Color.White,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                        if (overlaySocials.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(10.dp))
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                overlaySocials.take(5).forEach { profile ->
                                    OverlaySocialBadge(profile = profile)
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OverlayCallButton(
                                icon = Icons.Rounded.CallEnd,
                                label = "DECLINE",
                                baseColor = Color(0xFFEF4444),
                                deepColor = Color(0xFFB91C1C),
                                onTap = { openInCallScreen(context) }
                            )
                            OverlayCallButton(
                                icon = Icons.Rounded.Call,
                                label = "ANSWER",
                                baseColor = Color(0xFF22C55E),
                                deepColor = Color(0xFF15803D),
                                onTap = { openInCallScreen(context) }
                            )
                        }
                        Text(
                            text = "Tap to open call controls",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White.copy(alpha = 0.4f),
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                }
            }
        }
        }
    }

    private fun openInCallScreen(context: Context) {
        try {
            val intent = Intent(context, com.infocaller.app.ui.InCallActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            context.startActivity(intent)
        } catch (_: Exception) { }
    }

    @Composable
    private fun OverlayCallButton(
        icon: androidx.compose.ui.graphics.vector.ImageVector,
        label: String,
        baseColor: Color,
        deepColor: Color,
        onTap: () -> Unit,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Surface(
                onClick = onTap,
                modifier = Modifier.size(72.dp).shadow(16.dp, CircleShape),
                shape = CircleShape,
                color = Color.Transparent,
                border = androidx.compose.foundation.BorderStroke(2.dp, Color.White.copy(alpha = 0.25f))
            ) {
                Box(
                    modifier = Modifier.fillMaxSize().background(Brush.linearGradient(listOf(baseColor, deepColor))),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier.size(42.dp).clip(CircleShape).background(Color.White),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(icon, label, tint = deepColor, modifier = Modifier.size(24.dp))
                    }
                }
            }
            Text(label, modifier = Modifier.padding(top = 8.dp), color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.sp)
        }
    }

    @Composable
    private fun OverlaySocialBadge(profile: com.infocaller.app.domain.model.SocialProfile) {
        val brand = when (profile.platform.lowercase()) {
            "whatsapp" -> Color(0xFF25D366)
            "telegram" -> Color(0xFF229ED9)
            "facebook" -> Color(0xFF1877F2)
            "instagram" -> Color(0xFFE1306C)
            "linkedin" -> Color(0xFF0A66C2)
            "twitter", "x" -> Color(0xFF1D9BF0)
            "youtube" -> Color(0xFFFF0000)
            "tiktok" -> Color(0xFF69C9D0)
            "github" -> Color(0xFF9E9E9E)
            "spotify" -> Color(0xFF1DB954)
            "reddit" -> Color(0xFFFF4500)
            else -> Color(0xFFFBBF24)
        }
        Surface(
            modifier = Modifier.size(34.dp).shadow(6.dp, CircleShape),
            shape = CircleShape,
            color = Color.White.copy(alpha = 0.08f),
            border = androidx.compose.foundation.BorderStroke(2.dp, brand.copy(alpha = 0.8f))
        ) {
            Box(contentAlignment = Alignment.Center) {
                var logoFailed by remember(profile.platform) { mutableStateOf(false) }
                if (!logoFailed) {
                    AsyncImage(
                        model = SocialUtils.getLogoUrl(profile.platform),
                        contentDescription = profile.platform,
                        modifier = Modifier.size(18.dp).clip(CircleShape),
                        contentScale = ContentScale.Fit,
                        onError = { logoFailed = true }
                    )
                }
                if (logoFailed) {
                    Text(profile.platform.firstOrNull()?.uppercase() ?: "?", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 10.sp)
                }
            }
        }
    }

    private fun getContactDisplayName(context: Context, phoneNumber: String): String? {
        val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(phoneNumber))
        val projection = arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME)
        val cursor = context.contentResolver.query(uri, projection, null, null, null)

        cursor?.use {
            if (it.moveToFirst()) {
                val nameIndex = it.getColumnIndex(ContactsContract.PhoneLookup.DISPLAY_NAME)
                if (nameIndex != -1) return it.getString(nameIndex)
            }
        }
        return null
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(closeReceiver) } catch (_: Exception) { } catch (_: Error) { }
        clearIncomingCallNotification()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)

        overlayView?.let {
            try {
                windowManager.removeView(it)
            } catch (_: Exception) {}
        }
        overlayView = null
        serviceScope.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
