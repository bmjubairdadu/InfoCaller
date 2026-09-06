package com.infocaller.app.util

import android.content.Context
import android.os.PowerManager
import android.telecom.Call

object ProximityPolicy {
    fun shouldHold(callState: Int, isSpeakerOn: Boolean): Boolean {
        return callState == Call.STATE_ACTIVE && !isSpeakerOn
    }
}

class ProximityLock(context: Context) {
    private val lock: PowerManager.WakeLock? = try {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        if (powerManager == null) {
            null
        } else if (!powerManager.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK)) {
            null
        } else {
            powerManager.newWakeLock(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, "InfoCaller:proximity")
                .apply { setReferenceCounted(false) }
        }
    } catch (_: Exception) {
        null
    }

    fun setHeld(held: Boolean) {
        val wakeLock = lock ?: return
        try {
            if (held) {
                if (!wakeLock.isHeld) wakeLock.acquire()
            } else {
                if (wakeLock.isHeld) wakeLock.release()
            }
        } catch (_: Exception) { }
    }

    fun release() {
        val wakeLock = lock ?: return
        try {
            if (wakeLock.isHeld) wakeLock.release()
        } catch (_: Exception) { }
    }
}
