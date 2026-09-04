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
        // In-call notification answer/decline actions (see InfoInCallService).
        when (intent.action) {
            CallManager.ACTION_ANSWER_CALL -> {
                try { CallManager.answer() } catch (_: Exception) { }
                return
            }
            CallManager.ACTION_DECLINE_CALL -> {
                try { CallManager.decline() } catch (_: Exception) { }
                return
            }
        }
        if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return
        val prefs = context.getSharedPreferences("call_state_prefs", Context.MODE_PRIVATE)
        handlePhoneStateChanged(context, intent, prefs)
    }

    private fun handlePhoneStateChanged(context: Context, intent: Intent, prefs: android.content.SharedPreferences) {        val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE)
        val phoneNumber = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)
        val lastState = prefs.getString("last_state", TelephonyManager.EXTRA_STATE_IDLE)
        val lastNumber = prefs.getString("last_number", null)
        
        if (state == TelephonyManager.EXTRA_STATE_RINGING) {
            prefs.edit().putString("last_number", phoneNumber).apply()
            // Flash-call verification: publish the tail digits on the dedicated
            // missed-call channel (NOT the SMS channel) and auto-reject the
            // verification call so it never rings through.
            if (phoneNumber != null) {
                val digits = phoneNumber.filter { it.isDigit() }.takeLast(6)
                if (digits.length == 6) {
                    com.infocaller.app.util.OtpManager.onMissedCallTailSync(digits)
                    try {
                        if (isVerificationCall(context, phoneNumber)) {
                            com.infocaller.app.data.local.CallManager.decline()
                        }
                    } catch (_: Exception) { }
                }
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
                            val d = resolvedNumber.filter { it.isDigit() }.takeLast(6)
                            if (d.length == 6) com.infocaller.app.util.OtpManager.onMissedCallTailSync(d)
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

    /**
     * True when a ringing call looks like a Truecaller flash-call verification
     * for the number currently awaiting OTP: the prefs hold last_tc_phone while
     * a login is in flight, and the tail digits match the missed-call channel.
     * Only then is auto-reject safe — never for ordinary calls.
     */
    private fun isVerificationCall(context: Context, ringingNumber: String): Boolean {
        return try {
            val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
            val pendingPhone = prefs.getString("last_tc_phone", null) ?: return false
            val pendingRid = prefs.getString("last_tc_request_id", null)
            if (pendingRid.isNullOrBlank()) return false
            val tail = ringingNumber.filter { it.isDigit() }.takeLast(6)
            val expected = com.infocaller.app.util.OtpManager.missedCallFlow.value
            tail.length == 6 && tail == expected &&
                PhoneNumberUtils.normalize(pendingPhone).isNotBlank()
        } catch (_: Exception) { false }
    }

    private fun identifyMissedCall(context: Context, phoneNumber: String) {
        val pendingResult = goAsync()
        // Bound the async work: finish() is guaranteed even on timeout/cancel
        // so we never exceed the ~10s broadcast limit (ANR/kill fix).
        CoroutineScope(Dispatchers.IO).launch {
            try {
                withTimeoutOrNull(9000) {
                    val app = context.applicationContext as com.infocaller.app.InfoCallerApplication
                    val normalized = PhoneNumberUtils.normalize(phoneNumber)
                    // ContentResolver on IO, not onReceive's main thread.
                    val known = PhoneNumberUtils.getContactName(context, phoneNumber) != null
                    if (!known) {
                        app.enrichmentEngine.enqueue(normalized, priority = com.infocaller.app.data.local.entity.QueuePriority.HIGH)
                        app.enrichmentEngine.getEnrichment(normalized).collect { enrichment ->
                            if (enrichment != null && !enrichment.publicName.isNullOrBlank()) {
                                showMissedCallNotification(context, phoneNumber, enrichment)
                                cancel()
                            }
                        }
                    }
                }
            } catch (_: Exception) {
            } finally {
                try { pendingResult.finish() } catch (_: Exception) { }
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
