package com.infocaller.app.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.net.toUri

data class UssdEntry(
    val code: String,
    val label: String = "",
    val id: Long = System.currentTimeMillis()
)

object UssdStore {
    fun defaultEntries(): List<UssdEntry> = OSINTManager.getCommonUssdCodes().map {
        UssdEntry(code = it.url, label = it.title)
    }

    fun isUssd(input: String): Boolean {
        val t = input.trim()
        if (t.isEmpty()) return false
        return t.startsWith("*") || t.startsWith("#") || (t.contains("*") && t.endsWith("#"))
    }

    fun run(context: Context, rawCode: String, phoneAccountHandle: android.telecom.PhoneAccountHandle? = null) {
        val code = rawCode.trim()
        if (code.isEmpty()) return
        // USSD must go through ACTION_CALL with the SAME encoding the dialer
        // uses for '#', plus the caller's chosen PhoneAccountHandle so dual-SIM
        // users can pick the SIM. Route through the shared placeCall so USSD
        // hits the telephony stack correctly.
        try {
            com.infocaller.app.util.SimManager.placeCall(context, code, phoneAccountHandle)
        } catch (_: Exception) {
            try {
                val encoded = code.replace("#", Uri.encode("#"))
                val intent = Intent(Intent.ACTION_DIAL).apply {
                    data = "tel:$encoded".toUri()
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            } catch (_: Exception) { }
        }
    }
}
