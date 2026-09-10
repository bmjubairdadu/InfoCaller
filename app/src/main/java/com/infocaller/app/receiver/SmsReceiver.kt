package com.infocaller.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import com.infocaller.app.util.OtpManager
import java.util.regex.Pattern

class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Telephony.Sms.Intents.SMS_RECEIVED_ACTION) {
            val pendingVerification = hasPendingVerification(context)
            val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
            for (message in messages) {
                val body = message.displayMessageBody
                val sender = message.displayOriginatingAddress ?: ""
                // OTP-ONLY: never enrich, notify, or store SMS content.
                // When a Truecaller verification is pending, extract the code
                // from any sender (short codes / gateways). Otherwise only
                // accept codes from obvious verification senders.
                if (pendingVerification) {
                    val otp = extractOtp(body)
                    if (otp != null) {
                        OtpManager.onOtpReceivedSync(otp)
                    }
                } else if (isVerificationSender(sender, body)) {
                    val otp = extractOtp(body)
                    if (otp != null) {
                        OtpManager.onOtpReceivedSync(otp)
                    }
                }
            }
        }
    }

    private fun hasPendingVerification(context: Context): Boolean {
        return try {
            val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
            !prefs.getString("last_tc_request_id", null).isNullOrBlank()
        } catch (_: Exception) { false }
    }

    private fun isVerificationSender(sender: String, body: String): Boolean {
        val s = sender.lowercase()
        val b = body.lowercase()
        if (s.contains("truecaller")) return true
        if (s.length <= 6 && s.any { it.isDigit() }) return true
        if (s.length <= 11 && s.all { it.isLetterOrDigit() || it == '-' || it == ' ' } &&
            (b.contains("truecaller") || b.contains("verification") || b.contains("verify"))) return true
        return b.contains("truecaller") &&
            (b.contains("code") || b.contains("otp") || b.contains("verification") || b.contains("verify"))
    }

    private fun extractOtp(body: String): String? {
        val labeled = Pattern.compile("(?:code|otp|verification|verify|pin|password)[^\\d]{0,20}(\\d{4,10})(?!\\d)", Pattern.CASE_INSENSITIVE)
        labeled.matcher(body).let { m -> if (m.find()) return m.group(1)?.takeIf { it.length in 4..10 } }
        val patterns = listOf(
            Pattern.compile("(?:code|is|verification)\\s*(?:is)?\\s*(\\d{4,10})(?!\\d)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("(?<!\\d)(\\d{6})(?!\\d)"),
            Pattern.compile("(?<!\\d)(\\d{4,10})(?!\\d)")
        )
        for (pattern in patterns) {
            val matcher = pattern.matcher(body)
            if (matcher.find()) {
                val found = matcher.group(1)
                if (!found.isNullOrBlank() && found.length in 4..10) return found
            }
        }
        return null
    }
}
