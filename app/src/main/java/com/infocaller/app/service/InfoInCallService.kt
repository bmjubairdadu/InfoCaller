package com.infocaller.app.service

import android.content.Intent
import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.InCallService
import android.util.Log
import com.infocaller.app.data.local.CallManager
import com.infocaller.app.ui.InCallActivity

class InfoInCallService : InCallService() {

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
        } else {
            val intent = Intent(this, InCallActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            startActivity(intent)
        }
    }

    private fun showIncomingCallNotification(call: Call) {
        val number = call.details?.handle?.schemeSpecificPart ?: "Unknown"
        val channelId = "incoming_calls"
        val notificationManager = getSystemService(android.app.NotificationManager::class.java)

        val channel = android.app.NotificationChannel(
            channelId, 
            "Incoming Calls", 
            android.app.NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            setSound(null, null) // Use system ringer
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

        val notification = androidx.core.app.NotificationCompat.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.ic_menu_call)
            .setContentTitle("Incoming Call")
            .setContentText("Identifying: $number")
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_MAX)
            .setCategory(androidx.core.app.NotificationCompat.CATEGORY_CALL)
            .setFullScreenIntent(pendingIntent, true)
            .setOngoing(true)
            .setAutoCancel(false)
            .setVisibility(androidx.core.app.NotificationCompat.VISIBILITY_PUBLIC)
            .build()

        notificationManager.notify(1, notification)
    }

    override fun onCallRemoved(call: Call) {
        super.onCallRemoved(call)
        Log.d("InfoInCallService", "Call Removed")
        getSystemService(android.app.NotificationManager::class.java).cancel(1)
        if (CallManager.activeCall.value == call) {
            CallManager.updateCall(null)
            CallManager.setInCallService(null)
        }
    }
}
