package com.infocaller.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import com.infocaller.app.util.OtpManager
import com.infocaller.app.util.SmsOtpParser

class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        try {
            if (intent.action == Telephony.Sms.Intents.SMS_RECEIVED_ACTION) {
                val pendingVerification = hasPendingVerification(context)
                val messages = try {
                    Telephony.Sms.Intents.getMessagesFromIntent(intent)
                } catch (_: Exception) { null } catch (_: Error) { null } ?: return
                for (message in messages) {
                    try {
                        val body = try { message.displayMessageBody } catch (_: Exception) { null } catch (_: Error) { null }
                        val sender = try { message.displayOriginatingAddress } catch (_: Exception) { null } catch (_: Error) { null } ?: ""
                        if (pendingVerification) {
                            val otp = SmsOtpParser.extractOtp(body)
                            if (otp != null) {
                                OtpManager.onOtpReceivedSync(otp)
                            }
                        } else if (isVerificationSender(sender, body ?: "")) {
                            val otp = SmsOtpParser.extractOtp(body)
                            if (otp != null) {
                                OtpManager.onOtpReceivedSync(otp)
                            }
                        }
                    } catch (_: Exception) { continue } catch (_: Error) { continue }
                }
            }
        } catch (_: Exception) { } catch (_: Error) { }
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
}
