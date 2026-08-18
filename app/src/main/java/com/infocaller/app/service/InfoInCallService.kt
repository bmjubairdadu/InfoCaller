package com.infocaller.app.service

import android.content.Intent
import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.InCallService
import android.util.Log
import com.infocaller.app.InfoCallerApplication
import com.infocaller.app.data.local.CallManager
import com.infocaller.app.ui.InCallActivity
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest

class InfoInCallService : InCallService() {
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var enrichmentJob: Job? = null

    @Deprecated("Use onCallEndpointChanged instead", ReplaceWith("onCallEndpointChanged"))
    override fun onCallAudioStateChanged(audioState: CallAudioState) {
        @Suppress("DEPRECATION")
        super.onCallAudioStateChanged(audioState)
        CallManager.updateAudioState(audioState)
    }

    override fun onCallAdded(call: Call) {
        super.onCallAdded(call)
        Log.d("InfoInCallService", "Call Added: ${call.details?.handle}")
        
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
        
        enrichmentJob?.cancel()
        enrichmentJob = serviceScope.launch {
            app.enrichmentEngine.getEnrichment(normalizedNumber).collectLatest { enrichment ->
                showIncomingCallNotification(call, enrichment)
            }
        }
    }

    private fun showIncomingCallNotification(call: Call, enrichment: com.infocaller.app.data.local.entity.ContactEnrichmentEntity? = null) {
        val number = call.details?.handle?.schemeSpecificPart ?: "Unknown"
        val channelId = "incoming_calls"
        val notificationManager = getSystemService(android.app.NotificationManager::class.java)

        val channel = android.app.NotificationChannel(
            channelId, 
            "Incoming Calls", 
            android.app.NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            setSound(null, null) 
            enableVibration(true)
            enableLights(true)
        }
        notificationManager.createNotificationChannel(channel)

        val intent = Intent(this, InCallActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = android.app.PendingIntent.getActivity(
            this, 
            0, 
            intent, 
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
        )

        // Answer Action
        val answerIntent = Intent(this, com.infocaller.app.receiver.CallBroadcastReceiver::class.java).apply {
            action = CallManager.ACTION_ANSWER_CALL
        }
        val answerPendingIntent = android.app.PendingIntent.getBroadcast(
            this, 101, answerIntent, android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
        )

        // Decline Action
        val declineIntent = Intent(this, com.infocaller.app.receiver.CallBroadcastReceiver::class.java).apply {
            action = CallManager.ACTION_DECLINE_CALL
        }
        val declinePendingIntent = android.app.PendingIntent.getBroadcast(
            this, 102, declineIntent, android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
        )

        val displayName = enrichment?.publicName ?: com.infocaller.app.util.PhoneNumberUtils.getContactName(this, number) ?: number
        val location = com.infocaller.app.util.LocationUtils.formatCallerLocation(enrichment?.city, enrichment?.region, enrichment?.country)
        val subText = if (enrichment == null && displayName == number) "Identifying..." else location

        val notification = androidx.core.app.NotificationCompat.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.ic_menu_call)
            .setContentTitle(displayName)
            .setContentText(if (displayName != number) number else subText)
            .setSubText(if (displayName != number) subText else null)
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_MAX)
            .setCategory(androidx.core.app.NotificationCompat.CATEGORY_CALL)
            .setFullScreenIntent(pendingIntent, true)
            .setOngoing(true)
            .setAutoCancel(false)
            .setVisibility(androidx.core.app.NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(android.R.drawable.ic_menu_call, "Answer", answerPendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Decline", declinePendingIntent)
            .build()

        notificationManager.notify(1, notification)
    }

    override fun onCallRemoved(call: Call) {
        super.onCallRemoved(call)
        Log.d("InfoInCallService", "Call Removed")
        enrichmentJob?.cancel()
        getSystemService(android.app.NotificationManager::class.java).cancel(1)
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
