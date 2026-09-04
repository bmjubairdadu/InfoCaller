package com.infocaller.app.service

import android.content.Context
import android.content.Intent
import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.InCallService
import android.util.Log
import com.infocaller.app.InfoCallerApplication
import com.infocaller.app.data.local.CallManager
import com.infocaller.app.ui.InCallActivity
import androidx.core.net.toUri
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest

class InfoInCallService : InCallService() {
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    // Track one enrichment collector per call so call-waiting (2nd onCallAdded)
    // no longer cancels the first call's observation.
    private val enrichmentJobs = java.util.concurrent.ConcurrentHashMap<Call, Job>()

    @Deprecated("Use onCallEndpointChanged instead", ReplaceWith("onCallEndpointChanged"))
    override fun onCallAudioStateChanged(audioState: CallAudioState) {
        @Suppress("DEPRECATION")
        super.onCallAudioStateChanged(audioState)
        CallManager.updateAudioState(audioState)
    }

    override fun onCallAdded(call: Call) {
        super.onCallAdded(call)

        CallManager.updateCall(call)
        CallManager.setInCallService(this)
        
        @Suppress("DEPRECATION")
        val state = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            call.details?.state ?: Call.STATE_DISCONNECTED
        } else {
            call.state
        }
        
        if (state == Call.STATE_RINGING) {
            showIncomingCallNotification(call)
            startEnrichmentObservation(call)
        } else {
            val intent = Intent(this, InCallActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            startActivity(intent)
        }
    }

    private fun startEnrichmentObservation(call: Call) {
        val number = call.details?.handle?.schemeSpecificPart ?: return
        val normalizedNumber = com.infocaller.app.util.PhoneNumberUtils.normalize(number)
        val app = application as InfoCallerApplication

        enrichmentJobs[call]?.cancel()
        enrichmentJobs[call] = serviceScope.launch {
            try {
                app.enrichmentEngine.getEnrichment(normalizedNumber).collectLatest { enrichment ->
                    // Skip if this call is gone (prevents notifying for a stale call).
                    if (enrichmentJobs.containsKey(call)) showIncomingCallNotification(call, enrichment)
                }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) { }
        }
    }

    private fun showIncomingCallNotification(call: Call, enrichment: com.infocaller.app.data.local.entity.ContactEnrichmentEntity? = null) {
        val number = call.details?.handle?.schemeSpecificPart ?: "Unknown"
        val channelId = "incoming_calls"
        val notificationManager = getSystemService(android.app.NotificationManager::class.java) ?: return

        val prefs = getSharedPreferences("app_prefs", MODE_PRIVATE)
        val customRingtoneUri = prefs.getString("custom_ringtone_uri", null)?.takeIf { it.startsWith("content://") }

        // Channel sound is immutable after first creation — only set a validated content URI.
        val channel = android.app.NotificationChannel(
            channelId,
            "Incoming Calls",
            android.app.NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            try {
                if (customRingtoneUri != null) {
                    setSound(customRingtoneUri.toUri(), android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build())
                } else {
                    setSound(null, null)
                }
            } catch (_: Exception) { try { setSound(null, null) } catch (_: Exception) { } }
            enableVibration(true)
            enableLights(true)
        }
        try { notificationManager.createNotificationChannel(channel) } catch (_: Exception) { return }

        val intent = Intent(this, InCallActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = android.app.PendingIntent.getActivity(
            this, 
            0, 
            intent, 
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
        )

        val answerIntent = Intent(this, com.infocaller.app.receiver.CallBroadcastReceiver::class.java).apply {
            action = CallManager.ACTION_ANSWER_CALL
        }
        val answerPendingIntent = android.app.PendingIntent.getBroadcast(
            this, 101, answerIntent, android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
        )

        val declineIntent = Intent(this, com.infocaller.app.receiver.CallBroadcastReceiver::class.java).apply {
            action = CallManager.ACTION_DECLINE_CALL
        }
        val declinePendingIntent = android.app.PendingIntent.getBroadcast(
            this, 102, declineIntent, android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
        )

        val displayName = enrichment?.publicName ?: com.infocaller.app.util.PhoneNumberUtils.getContactName(this, number) ?: number
        val location = com.infocaller.app.util.LocationUtils.formatCallerLocation(enrichment?.city, enrichment?.region, enrichment?.country)
        val subText = if (enrichment == null && displayName == number) "Identifying..." else location

        val isScreenOn = (getSystemService(Context.POWER_SERVICE) as android.os.PowerManager).isInteractive
        val notification = androidx.core.app.NotificationCompat.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.ic_menu_call)
            .setContentTitle(displayName)
            .setContentText(if (displayName != number) number else subText)
            .setSubText(if (displayName != number) subText else null)
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_MAX)
            .setCategory(androidx.core.app.NotificationCompat.CATEGORY_CALL)
            .setFullScreenIntent(pendingIntent, isScreenOn.not())
            .setOngoing(true)
            .setAutoCancel(false)
            .setOnlyAlertOnce(true)
            .setColor(0xFFFBBF24.toInt())
            .setColorized(true)
            .setVisibility(androidx.core.app.NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(android.R.drawable.ic_menu_call, "Answer", answerPendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Decline", declinePendingIntent)
            
        val photoUrl = enrichment?.profileImageUrl ?: com.infocaller.app.util.PhoneNumberUtils.getContactPhotoUri(this, number)
        if (photoUrl != null) {
            try {
                val loader = coil.ImageLoader(this)
                val request = coil.request.ImageRequest.Builder(this)
                    .data(photoUrl)
                    .target(
                        onSuccess = { result ->
                            // Coil may return non-bitmap drawables (placeholders/errors) —
                            // only set the icon when we actually got a bitmap.
                            val bitmap = (result as? android.graphics.drawable.BitmapDrawable)?.bitmap
                            if (bitmap != null) {
                                notification.setLargeIcon(bitmap)
                                try { notificationManager.notify(1, notification.build()) } catch (_: Exception) { }
                            }
                        }
                    )
                    .build()
                loader.enqueue(request)
            } catch (e: Exception) {
                Log.e("InfoInCallService", "Failed to load notification icon", e)
            }
        }

        try { notificationManager.notify(1, notification.build()) } catch (_: Exception) { }
    }

    override fun onCallRemoved(call: Call) {
        super.onCallRemoved(call)
        enrichmentJobs.remove(call)?.cancel()
        try { getSystemService(android.app.NotificationManager::class.java)?.cancel(1) } catch (_: Exception) { }
        if (CallManager.activeCall.value == call) {
            CallManager.updateCall(null)
            CallManager.setInCallService(null)
        }
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }
}
