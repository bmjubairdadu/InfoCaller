package com.infocaller.app.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.provider.ContactsContract
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationCompat
import com.infocaller.app.R
import androidx.lifecycle.*
import androidx.savedstate.*
import coil.compose.AsyncImage
import com.infocaller.app.data.repository.ContactEnrichmentService
import com.infocaller.app.domain.model.Caller
import com.infocaller.app.domain.repository.CallerRepository
import com.infocaller.app.ui.theme.*
import com.infocaller.app.util.*
import kotlinx.coroutines.*

class CallOverlayService : Service(), LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {

    companion object {
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
            // Sticky restart with no number: nothing to show — do not leak a foreground service.
            stopSelf()
            return START_NOT_STICKY
        }
        showForegroundNotification()
        showOverlay(phoneNumber)
        return START_NOT_STICKY
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

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP
            y = 100
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
        // Prefer the application repository; fall back to the static instance for
        // legacy callers. Never crash composition — show a degraded card instead.
        val repository = try { app.repository } catch (_: Exception) { getRepository() }
        val enrichmentEngine = try { app.enrichmentEngine } catch (_: Exception) { null }
        val enrichmentService = remember { ContactEnrichmentService(context) }

        val normalizedNumber = remember(phoneNumber) { PhoneNumberUtils.normalize(phoneNumber) }
        val enrichment by enrichmentEngine?.getEnrichment(normalizedNumber)
            ?.collectAsState(initial = null) ?: remember { mutableStateOf(null) }.let { it as androidx.compose.runtime.State<com.infocaller.app.data.local.entity.ContactEnrichmentEntity?> }

        var contactName by remember { mutableStateOf<String?>(null) }
        var contactPhotoUri by remember { mutableStateOf<String?>(null) }
        var isBlocked by remember { mutableStateOf(value = false) }

        LaunchedEffect(normalizedNumber) {
            // ContentResolver + Room must stay off the main thread (ANR fix).
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

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .glassy(radius = 28.dp, blur = 20.dp),
            colors = CardDefaults.cardColors(containerColor = Color.Transparent)
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            brush = Brush.horizontalGradient(
                                colors = listOf(GradientStart, GradientEnd)
                            )
                        )
                        .padding(16.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (photoUrl != null) {
                            AsyncImage(
                                model = photoUrl,
                                contentDescription = null,
                                modifier = Modifier
                                    .size(64.dp)
                                    .clip(CircleShape)
                                    .border(2.dp, Color.White.copy(alpha = 0.2f), CircleShape),
                                contentScale = ContentScale.Crop,
                                placeholder = rememberVectorPainter(Icons.Default.Person),
                                error = rememberVectorPainter(Icons.Default.Person)
                            )
                        } else {
                            val initials = ContactUtils.getInitials(displayName)
                            Box(
                                modifier = Modifier
                                    .size(64.dp)
                                    .clip(CircleShape)
                                    .background(Color.White.copy(alpha = 0.1f))
                                    .border(2.dp, Color.White.copy(alpha = 0.2f), CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = initials,
                                    style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                                    color = Color.White
                                )
                            }
                        }

                        Spacer(modifier = Modifier.width(16.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = displayName,
                                style = MaterialTheme.typography.titleLarge,
                                color = Color.White,
                                fontWeight = FontWeight.Bold
                            )
                            
                            if (enrichment == null && contactName == null && !isBlocked) {
                                Text(
                                    text = "Identifying...",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color.White.copy(alpha = 0.6f)
                                )
                            }

                            Text(
                                text = phoneNumber,
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color.White.copy(alpha = 0.8f)
                            )

                            if (!enrichment?.confidence.isNullOrBlank() && contactName == null) {
                                val confidence = enrichment?.confidence?.toFloatOrNull() ?: 0f
                                if (confidence > 0f) {
                                    Text(
                                        text = "Confidence: ${(confidence * 100).toInt()}%",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = Color.White.copy(alpha = 0.9f)
                                    )
                                }
                            }

                            if (location.isNotBlank()) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Place, contentDescription = null, tint = Color.White.copy(alpha = 0.6f), modifier = Modifier.size(14.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = location,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = Color.White.copy(alpha = 0.6f)
                                    )
                                }
                            }

                            val socialProfiles = SocialUtils.fromJson(enrichment?.socialProfilesJson)
                            if (socialProfiles.isNotEmpty()) {
                                Row(
                                    modifier = Modifier.padding(top = 4.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    socialProfiles.forEach { profile ->
                                        AsyncImage(
                                            model = SocialUtils.getLogoUrl(profile.platform),
                                            contentDescription = profile.platform,
                                            modifier = Modifier.size(16.dp).clip(CircleShape),
                                            contentScale = ContentScale.Fit
                                        )
                                    }
                                }
                            }
                        }
                    }
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
