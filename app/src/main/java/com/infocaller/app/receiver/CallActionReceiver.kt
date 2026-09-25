package com.infocaller.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.telecom.TelecomManager

class CallActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val raw = intent.getStringExtra("EXTRA_PHONE_NUMBER") ?: return
        val number = try {
            com.infocaller.app.util.PhoneNumberUtils.normalize(raw)
        } catch (_: Exception) { raw.trim() }

        try {
            context.getSystemService(android.app.NotificationManager::class.java)
                ?.cancel(com.infocaller.app.service.CallOverlayService.INCOMING_NOTIFICATION_ID)
        } catch (_: Exception) { } catch (_: Error) { }

        try {
            context.sendBroadcast(
                Intent("com.infocaller.app.CLOSE_OVERLAY").apply {
                    setPackage(context.packageName)
                    putExtra("EXTRA_PHONE_NUMBER", number)
                }
            )
        } catch (_: Exception) { } catch (_: Error) { }

        if (action == ACTION_ANSWER) {
            try {
                val telecom = context.getSystemService(TelecomManager::class.java)
                if (telecom == null) return
                val uri = Uri.fromParts("tel", raw, null)
                var called = false
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                    try {
                        val m = telecom.javaClass.getMethod("acceptRingingCall", android.net.Uri::class.java)
                        m.invoke(telecom, uri)
                        called = true
                    } catch (_: Exception) { }
                }
                if (!called) {
                    try {
                        val m = telecom.javaClass.getMethod("acceptRingingCall", String::class.java)
                        m.invoke(telecom, raw)
                    } catch (e: Exception) {
                        Log.w("CallActionReceiver", "acceptRingingCall failed: ${e.message}")
                    }
                }
            } catch (e: Exception) {
                Log.w("CallActionReceiver", "answer failed: ${e.message}")
            }
        } else if (action == ACTION_DECLINE) {
            try {
                val telecom = context.getSystemService(TelecomManager::class.java)
                telecom?.endCall()
            } catch (e: Exception) {
                Log.w("CallActionReceiver", "endCall failed: ${e.message}")
            }
        }
    }

    companion object {
        const val ACTION_ANSWER = "com.infocaller.app.ACTION_ANSWER"
        const val ACTION_DECLINE = "com.infocaller.app.ACTION_DECLINE"
    }
}
