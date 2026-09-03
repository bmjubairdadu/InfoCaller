package com.infocaller.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager
import android.util.Log
import com.infocaller.app.data.local.CallManager
import com.infocaller.app.service.CallOverlayService
import com.infocaller.app.util.PhoneNumberUtils
import kotlinx.coroutines.*

class CallBroadcastReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val prefs = context.getSharedPreferences("call_state_prefs", Context.MODE_PRIVATE)
        when (intent.action) {
            TelephonyManager.ACTION_PHONE_STATE_CHANGED -> handlePhoneStateChanged(context, intent, prefs)
            CallManager.ACTION_ANSWER_CALL -> CallManager.answer()
            CallManager.ACTION_DECLINE_CALL -> CallManager.decline()
        }
    }

    private fun handlePhoneStateChanged(context: Context, intent: Intent, prefs: android.content.SharedPreferences) {
        val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE)
        val phoneNumber = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)
        val lastState = prefs.getString("last_state", TelephonyManager.EXTRA_STATE_IDLE)
        val lastNumber = prefs.getString("last_number", null)
        
        if (state == TelephonyManager.EXTRA_STATE_RINGING) {
            prefs.edit().putString("last_number", phoneNumber).apply()
            if (phoneNumber != null) {
                com.infocaller.app.util.OtpManager.onOtpReceived(phoneNumber)
            }
            if (com.infocaller.app.permissions.PermissionManager.isDefaultDialer(context)) {
                prefs.edit().putString("last_state", state).apply()
                return
            }
            if (phoneNumber != null) {
                val serviceIntent = Intent(context, CallOverlayService::class.java).apply {
                    putExtra("EXTRA_PHONE_NUMBER", phoneNumber)
                }
                try {
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                        context.startForegroundService(serviceIntent)
                    } else {
                        context.startService(serviceIntent)
                    }
                } catch (e: Exception) { }
            }
        } else if (state == TelephonyManager.EXTRA_STATE_IDLE) {
            if (lastState == TelephonyManager.EXTRA_STATE_RINGING) {
                val missedNumber = lastNumber ?: phoneNumber
                if (missedNumber != null) identifyMissedCall(context, missedNumber)
            }
            if (phoneNumber == null && lastState == TelephonyManager.EXTRA_STATE_RINGING) {
                val pendingResult = goAsync()
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        delay(2000)
                        val resolvedNumber: String? = com.infocaller.app.util.ContactUtils.getLastIncomingCallNumber(context)
                        if (resolvedNumber != null) {
                            com.infocaller.app.util.OtpManager.onOtpReceived(resolvedNumber)
                            identifyMissedCall(context, resolvedNumber)
                        }
                    } finally {
                        pendingResult.finish()
                    }
                }
            }
            val serviceIntent = Intent(context, CallOverlayService::class.java)
            context.stopService(serviceIntent)
            prefs.edit().remove("last_number").apply()
        }
        prefs.edit().putString("last_state", state ?: TelephonyManager.EXTRA_STATE_IDLE).apply()
    }

    private fun identifyMissedCall(context: Context, phoneNumber: String) {
        val app = context.applicationContext as com.infocaller.app.InfoCallerApplication
        val normalized = PhoneNumberUtils.normalize(phoneNumber)
        if (PhoneNumberUtils.getContactName(context, phoneNumber) == null) {
            val pendingResult = goAsync()
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    app.enrichmentEngine.enqueue(normalized, priority = com.infocaller.app.data.local.entity.QueuePriority.HIGH)
                    withTimeoutOrNull(15000) {
                        app.enrichmentEngine.getEnrichment(normalized).collect { enrichment ->
                            if (enrichment != null && !enrichment.publicName.isNullOrBlank()) {
                                showMissedCallNotification(context, phoneNumber, enrichment)
                                cancel()
                            }
                        }
                    }
                } finally {
                    pendingResult.finish()
                }
            }
        }
    }

    private fun showMissedCallNotification(context: Context, number: String, enrichment: com.infocaller.app.data.local.entity.ContactEnrichmentEntity) {
        val channelId = "missed_calls"
        val manager = context.getSystemService(android.app.NotificationManager::class.java)
        val channel = android.app.NotificationChannel(channelId, "Missed Calls", android.app.NotificationManager.IMPORTANCE_DEFAULT)
        manager.createNotificationChannel(channel)
        val displayName = enrichment.publicName ?: number
        val carrier = enrichment.carrier ?: ""
        val builder = androidx.core.app.NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.stat_notify_missed_call)
            .setContentTitle("Missed call from $displayName")
            .setContentText("Number: $number ${if(carrier.isNotEmpty()) "- $carrier" else ""}")
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
        manager.notify(number.hashCode(), builder.build())
    }
}
