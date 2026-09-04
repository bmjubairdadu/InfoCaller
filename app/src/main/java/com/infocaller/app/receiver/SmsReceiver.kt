package com.infocaller.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log
import com.infocaller.app.InfoCallerApplication
import com.infocaller.app.data.local.entity.QueuePriority
import com.infocaller.app.util.OtpManager
import com.infocaller.app.util.PhoneNumberUtils
import kotlinx.coroutines.*
import java.util.regex.Pattern

class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Telephony.Sms.Intents.SMS_RECEIVED_ACTION) {
            val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
            for (message in messages) {
                val body = message.displayMessageBody
                val sender = message.displayOriginatingAddress ?: ""
                val otp = extractOtp(body)
                if (otp != null) {
                    OtpManager.onOtpReceivedSync(otp)
                }
                identifySmsSender(context, sender)
            }
        }
    }

    private fun identifySmsSender(context: Context, phoneNumber: String) {
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                withTimeoutOrNull(9000) {
                    val app = context.applicationContext as InfoCallerApplication
                    val normalized = PhoneNumberUtils.normalize(phoneNumber)
                    // ContentResolver on IO, not onReceive's main thread (ANR fix).
                    val known = PhoneNumberUtils.getContactName(context, phoneNumber) != null
                    if (!known) {
                        app.enrichmentEngine.enqueue(normalized, priority = QueuePriority.HIGH)
                        app.enrichmentEngine.getEnrichment(normalized).collect { enrichment ->
                            if (enrichment != null && !enrichment.publicName.isNullOrBlank()) {
                                showSmsNotification(context, phoneNumber, enrichment)
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

    private fun showSmsNotification(context: Context, number: String, enrichment: com.infocaller.app.data.local.entity.ContactEnrichmentEntity) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU &&
            androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) return
        val channelId = "sms_identification"
        val manager = context.getSystemService(android.app.NotificationManager::class.java) ?: return
        val channel = android.app.NotificationChannel(channelId, "SMS Identification", android.app.NotificationManager.IMPORTANCE_DEFAULT)
        manager.createNotificationChannel(channel)
        val displayName = enrichment.publicName ?: number
        val carrier = enrichment.carrier ?: ""
        val builder = androidx.core.app.NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setContentTitle("SMS from $displayName")
            .setContentText("Number: $number ${if(carrier.isNotEmpty()) "- $carrier" else ""}")
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
        manager.notify(number.hashCode(), builder.build())
    }

    private fun extractOtp(body: String): String? {
        val patterns = listOf(
            Pattern.compile("(?:code|is|verification)\\s*(?:is)?\\s*(\\d{6})(?!\\d)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("(?<!\\d)(\\d{6})(?!\\d)") 
        )
        for (pattern in patterns) {
            val matcher = pattern.matcher(body)
            if (matcher.find()) return matcher.group(1)
        }
        return null
    }
}
