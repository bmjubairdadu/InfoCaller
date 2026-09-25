package com.infocaller.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import com.google.android.gms.auth.api.phone.SmsRetriever
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.common.api.Status
import com.infocaller.app.util.OtpManager
import com.infocaller.app.util.SmsOtpParser

class SmsUserConsentReceiver(
    private val onConsentIntent: (consentIntent: Intent) -> Unit
) : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != SmsRetriever.SMS_RETRIEVED_ACTION) return
        val extras: Bundle = intent.extras ?: return
        @Suppress("DEPRECATION")
        val status = extras.get(SmsRetriever.EXTRA_STATUS) as? Status ?: return
        when (status.statusCode) {
            CommonStatusCodes.SUCCESS -> {
                @Suppress("DEPRECATION")
                val consentIntent: Intent? =
                    extras.getParcelable(SmsRetriever.EXTRA_CONSENT_INTENT)
                if (consentIntent != null) {
                    try {
                        onConsentIntent(consentIntent)
                    } catch (e: Exception) {
                        Log.w("SmsConsent", "Failed to launch consent intent: ${e.message}")
                    }
                } else {
                    val msg = extras.getString(SmsRetriever.EXTRA_SMS_MESSAGE)
                    onConsentSmsMessage(msg)
                }
            }
            else -> {
                Log.d("SmsConsent", "Consent retrieve timeout / not found: ${status.statusCode}")
            }
        }
    }

    companion object {
        fun onConsentSmsMessage(message: String?): String? {
            val otp = SmsOtpParser.extractOtp(message)
            if (otp != null) {
                OtpManager.onOtpReceivedSync(otp)
            }
            return otp
        }
    }
}
