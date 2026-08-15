package com.infocaller.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager
import android.util.Log
import com.infocaller.app.service.CallOverlayService

class CallBroadcastReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == TelephonyManager.ACTION_PHONE_STATE_CHANGED) {
            val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE)
            val phoneNumber = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)
            
            Log.d("CallBroadcastReceiver", "State: $state, Number: $phoneNumber")
            
            if (state == TelephonyManager.EXTRA_STATE_RINGING && phoneNumber != null) {
                // Skip overlay if we are the default dialer (InCallActivity handles it)
                if (com.infocaller.app.permissions.PermissionManager.isDefaultDialer(context)) {
                    Log.d("CallBroadcastReceiver", "Default dialer, skipping overlay")
                    return
                }

                val serviceIntent = Intent(context, CallOverlayService::class.java).apply {
                    putExtra("EXTRA_PHONE_NUMBER", phoneNumber)
                }
                try {
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                        context.startForegroundService(serviceIntent)
                    } else {
                        context.startService(serviceIntent)
                    }
                } catch (e: Exception) {
                    Log.e("CallBroadcastReceiver", "Failed to start service", e)
                }
            } else if (state == TelephonyManager.EXTRA_STATE_IDLE) {
                val serviceIntent = Intent(context, CallOverlayService::class.java)
                context.stopService(serviceIntent)
            }
        }
    }
}
