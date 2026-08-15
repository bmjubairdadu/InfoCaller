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
import androidx.lifecycle.*
import androidx.savedstate.*
import coil.compose.AsyncImage
import com.infocaller.app.data.repository.ContactEnrichmentService
import com.infocaller.app.domain.model.Caller
import com.infocaller.app.domain.model.SpamStatus
import com.infocaller.app.domain.repository.CallerRepository
import com.infocaller.app.ui.theme.GradientEnd
import com.infocaller.app.ui.theme.GradientStart
import com.infocaller.app.ui.theme.glassy
import com.infocaller.app.util.PhoneNumberUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

    private val serviceJob = Job()

    override fun onCreate() {
        super.onCreate()
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val phoneNumber = intent?.getStringExtra("EXTRA_PHONE_NUMBER")
        
        showForegroundNotification()
        
        if (phoneNumber != null) {
            showOverlay(phoneNumber)
        }
        
        return START_STICKY
    }

    private fun showForegroundNotification() {
        val channelId = "call_overlay_channel"
        val channel = NotificationChannel(channelId, "Call Overlay Service", NotificationManager.IMPORTANCE_LOW)
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("InfoCaller Active")
            .setContentText("Identifying incoming call...")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        startForeground(1, notification)
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
        val repository = getRepository() 
            ?: error("CallerRepository not initialized. Call CallOverlayService.setRepository() in Application.onCreate()")
        val context = LocalContext.current
        val enrichmentService = remember { ContactEnrichmentService(context) }
        var caller by remember { mutableStateOf<Caller?>(null) }
        var isBlocked by remember { mutableStateOf(value = false) }
        
        val normalizedNumber = remember(phoneNumber) { PhoneNumberUtils.normalize(phoneNumber) }
        val dynamicPhotoUrl = remember(normalizedNumber) { PhoneNumberUtils.getImageUrl(normalizedNumber) }

        LaunchedEffect(normalizedNumber) {
            val contactName = com.infocaller.app.util.PhoneNumberUtils.getContactName(context, phoneNumber)
            val contactPhoto = com.infocaller.app.util.PhoneNumberUtils.getContactPhotoUri(context, phoneNumber)
            
            // Full modular lookup
            val lookupEngine = (context.applicationContext as com.infocaller.app.InfoCallerApplication).lookupEngine
            val result = lookupEngine.performLookup(normalizedNumber)
            
            caller = Caller(
                phoneNumber = phoneNumber, 
                displayName = contactName ?: result.name, 
                alias = result.sources.firstOrNull(),
                photoUrl = contactPhoto ?: result.imageUrl,
                organization = result.carrier,
                country = result.country,
                region = result.region,
                carrier = result.carrier,
                spamScore = result.spamScore,
                reportCount = 0,
                isVerified = contactName != null || result.confidence > 0.7f,
                spamStatus = result.spamStatus,
                socialMediaLinks = result.socialProfiles.mapNotNull { it.profileUrl }
            )
            
            isBlocked = repository.isBlocked(normalizedNumber)

            // Auto-enrich contacts
            if (contactName == null && result.name != null && result.confidence > 0.5f) {
                val exists = enrichmentService.checkIfContactExists(normalizedNumber)
                if (!exists) {
                    enrichmentService.enrichAndSaveContact(
                        phoneNumber = normalizedNumber,
                        displayName = result.name
                    )
                }
            }
        }

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
                                colors = if (isBlocked || (caller?.spamStatus == SpamStatus.SPAM))
                                    listOf(Color(0xFFEF4444), Color(0xFFB91C1C))
                                else
                                    listOf(GradientStart, GradientEnd)
                            )
                        )
                        .padding(16.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AsyncImage(
                            model = caller?.photoUrl ?: dynamicPhotoUrl,
                            contentDescription = null,
                            modifier = Modifier
                                .size(64.dp)
                                .clip(CircleShape)
                                .border(2.dp, Color.White.copy(alpha = 0.2f), CircleShape),
                            contentScale = ContentScale.Crop,
                            placeholder = rememberVectorPainter(Icons.Default.Person),
                            error = rememberVectorPainter(Icons.Default.Person)
                        )

                        Spacer(modifier = Modifier.width(16.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            val nameText = when {
                                isBlocked -> "SPAM DETECTED"
                                caller?.displayName != null -> caller?.displayName!!
                                else -> "Unknown Caller"
                            }
                            
                            Text(
                                text = nameText,
                                style = MaterialTheme.typography.titleLarge,
                                color = Color.White,
                                fontWeight = FontWeight.Bold
                            )
                            
                            if (caller == null && !isBlocked) {
                                Text(
                                    text = "No additional information available.",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color.White.copy(alpha = 0.6f)
                                )
                            }

                            Text(
                                text = phoneNumber,
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color.White.copy(alpha = 0.8f)
                            )

                            // STAGE 2: Display Location
                            val location = listOfNotNull(caller?.region, caller?.country).joinToString(", ")
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

                            // STAGE 3: Social Media Links
                            if (caller?.socialMediaLinks?.isNotEmpty() == true) {
                                Row(
                                    modifier = Modifier.padding(top = 4.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    caller!!.socialMediaLinks.forEach { link ->
                                        val icon = when {
                                            link.contains("wa.me") -> Icons.AutoMirrored.Filled.Chat
                                            link.contains("t.me") -> Icons.AutoMirrored.Filled.Send
                                            else -> Icons.Default.Link
                                        }
                                        Icon(icon, contentDescription = null, tint = Color.White.copy(alpha = 0.7f), modifier = Modifier.size(16.dp))
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
        serviceJob.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
