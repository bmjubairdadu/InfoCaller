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
            if (phoneNumber != null) {
                prefs.edit().putString("last_number", phoneNumber).apply()
            }
            if (phoneNumber != null) {
                val clean = phoneNumber.substringBefore(';').substringBefore('?')
                val allDigits = clean.filter { it.isDigit() }
                val digits = if (allDigits.length >= 6) allDigits.takeLast(6) else allDigits
                if (digits.isNotBlank()) {
                    com.infocaller.app.util.OtpManager.onMissedCallTailSync(digits, clean, isIdle = false)
                }
            } else {
                val pendingResult = goAsync()
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        delay(2000)
                        val resolved: String? = com.infocaller.app.util.ContactUtils.getLastIncomingCallNumber(context)
                        if (resolved != null) {
                            val clean = resolved.substringBefore(';').substringBefore('?')
                            val allDigits = clean.filter { it.isDigit() }
                            val d = if (allDigits.length >= 6) allDigits.takeLast(6) else allDigits
                            if (d.isNotBlank()) com.infocaller.app.util.OtpManager.onMissedCallTailSync(d, clean, isIdle = false)
                        }
                    } finally {
                        pendingResult.finish()
                    }
                }
            }
            // Verification calls never show the caller-ID overlay.
            try {
                val verNum = phoneNumber
                    ?: prefs.getString("last_number", null)
                    ?: com.infocaller.app.util.OtpManager.missedCallSourceFlow.value.orEmpty()
                if (!verNum.isNullOrBlank() && isVerificationCall(context, verNum)) {
                    prefs.edit().putString("last_state", state).apply()
                    return
                }
            } catch (_: Exception) { }
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
                val missedNumber = lastNumber ?: phoneNumber ?: prefs.getString("last_number", null)
                if (missedNumber != null) {
                    val clean = missedNumber.substringBefore(';').substringBefore('?')
                    val allDigits = clean.filter { it.isDigit() }
                    val digits = if (allDigits.length >= 6) allDigits.takeLast(6) else allDigits
                    if (digits.isNotBlank() && isVerificationCall(context, clean)) {
                        com.infocaller.app.util.OtpManager.onMissedCallTailSync(digits, clean, isIdle = true)
                    }
                    if (!isStaleVerificationTail(context, clean)) {
                        identifyMissedCall(context, clean)
                    }
                }
            }
            if (phoneNumber == null && lastState == TelephonyManager.EXTRA_STATE_RINGING) {
                val pendingResult = goAsync()
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        delay(1500)
                        val resolvedNumber: String? = com.infocaller.app.util.ContactUtils.getLastIncomingCallNumber(context)
                        if (resolvedNumber != null) {
                            val clean = resolvedNumber.substringBefore(';').substringBefore('?')
                            val allDigits = clean.filter { it.isDigit() }
                            val d = if (allDigits.length >= 6) allDigits.takeLast(6) else allDigits
                            if (d.isNotBlank()) com.infocaller.app.util.OtpManager.onMissedCallTailSync(d, clean, isIdle = true)
                            if (!isStaleVerificationTail(context, clean)) {
                                identifyMissedCall(context, clean)
                            }
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

    private fun isVerificationCall(context: Context, ringingNumber: String): Boolean {
        // While a Truecaller OTP request is pending, treat ANY incoming call as
        // the verification (missed/flash) call: capture its tail and reject it.
        // The live Truecaller verify API decides whether the code is valid.
        return try {
            val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
            val pendingPhone = prefs.getString("last_tc_phone", null) ?: return false
            val pendingRid = prefs.getString("last_tc_request_id", null)
            !pendingRid.isNullOrBlank() && PhoneNumberUtils.normalize(pendingPhone).isNotBlank()
        } catch (_: Exception) { false }
    }

    private fun isStaleVerificationTail(context: Context, number: String): Boolean {
        // Suppress the missed-call notification/scan for the verification call
        // itself while its OTP request is still pending.
        return try {
            val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
            if (prefs.getString("last_tc_request_id", null).isNullOrBlank()) return false
            isVerificationCall(context, number)
        } catch (_: Exception) { false }
    }

    private fun identifyMissedCall(context: Context, phoneNumber: String) {
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                withTimeoutOrNull(9000) {
                    val app = context.applicationContext as com.infocaller.app.InfoCallerApplication
                    val normalized = PhoneNumberUtils.normalize(phoneNumber)
                    val online = try { app.enrichmentEngine.isOnline.value } catch (_: Exception) { true }
                    val known = PhoneNumberUtils.getContactName(context, phoneNumber) != null
                    val startedAt = System.currentTimeMillis()
                    if (online && !known) {
                        app.enrichmentEngine.enqueue(normalized, priority = com.infocaller.app.data.local.entity.QueuePriority.HIGH)
                        app.enrichmentEngine.getEnrichment(normalized).collect { enrichment ->
                            if (enrichment != null && !enrichment.publicName.isNullOrBlank()) {
                                showMissedCallNotification(context, phoneNumber, enrichment, startedAt, scanned = true)
                                cancel()
                            }
                        }
                    } else {
                        val cached = try {
                            app.database.enrichmentDao().getEnrichmentSync(normalized)
                        } catch (_: Exception) { null }
                        if (!known || cached != null) {
                            showMissedCallNotification(context, phoneNumber, cached, startedAt, scanned = online)
                        }
                    }
                }
            } catch (_: Exception) {
            } finally {
                try { pendingResult.finish() } catch (_: Exception) { }
            }
        }
    }

    private fun showMissedCallNotification(
        context: Context,
        number: String,
        enrichment: com.infocaller.app.data.local.entity.ContactEnrichmentEntity?,
        startedAt: Long = System.currentTimeMillis(),
        scanned: Boolean = false,
    ) {
        val channelId = "missed_calls"
        val manager = context.getSystemService(android.app.NotificationManager::class.java)
        val channel = android.app.NotificationChannel(channelId, "Missed Calls", android.app.NotificationManager.IMPORTANCE_DEFAULT)
        manager.createNotificationChannel(channel)
        val displayName = enrichment?.publicName ?: number
        val carrier = enrichment?.carrier ?: ""
        val elapsedSec = ((System.currentTimeMillis() - startedAt) / 1000).coerceAtLeast(0)
        val detail = when {
            scanned && enrichment?.publicName != null -> "Scan successful · ${elapsedSec}s · Number: $number${if (carrier.isNotEmpty()) " - $carrier" else ""}"
            else -> "Called ${elapsedSec}s ago · Number: $number${if (carrier.isNotEmpty()) " - $carrier" else ""}"
        }
        val detailsIntent = android.content.Intent(context, com.infocaller.app.MainActivity::class.java).apply {
            action = android.content.Intent.ACTION_VIEW
            data = android.net.Uri.parse("infocaller://details/${android.net.Uri.encode(number)}")
            flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val contentIntent = try {
            android.app.PendingIntent.getActivity(
                context, number.hashCode(), detailsIntent,
                android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
            )
        } catch (_: Exception) { null }
        val builder = androidx.core.app.NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.stat_notify_missed_call)
            .setContentTitle("Missed call from $displayName")
            .setContentText(detail)
            .setStyle(androidx.core.app.NotificationCompat.BigTextStyle().bigText(detail))
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
        if (contentIntent != null) builder.setContentIntent(contentIntent)
        manager.notify(number.hashCode(), builder.build())
    }
}
