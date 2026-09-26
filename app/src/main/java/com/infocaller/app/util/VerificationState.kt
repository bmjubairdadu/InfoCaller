package com.infocaller.app.util

import android.content.Context

/**
 * Shared state for Truecaller call/flash/missed-call verification.
 *
 * For call-based verification the OTP token is the tail of the *calling* number, which comes from a
 * Truecaller pool number and is therefore unpredictable. We cannot match on the number itself, so a
 * call is treated as the verification call whenever a call-based request is pending and its verification
 * window is still open. The window is gated on the request method being call-based so SMS verifications
 * never suppress or reject normal incoming calls.
 */
object VerificationState {
    private const val MIN_WINDOW_MS = 3 * 60 * 1000L
    private const val MAX_WINDOW_MS = 6 * 60 * 1000L

    fun isCallMethod(method: String?): Boolean {
        if (method.isNullOrBlank()) return false
        val m = method.lowercase()
        return m.contains("call") || m.contains("flash") || m.contains("miss")
    }

    /**
     * True while a call-based Truecaller verification is pending and its verification call may still
     * arrive. Only ever true for a short, user-initiated window right after requesting a call-based code.
     */
    fun hasActiveCallVerification(context: Context): Boolean {
        return try {
            val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
            val rid = prefs.getString("last_tc_request_id", null)
            if (rid.isNullOrBlank()) return false
            if (!isCallMethod(prefs.getString("last_tc_method", null))) return false
            val requestedAt = prefs.getLong("last_tc_request_at", 0L)
            if (requestedAt <= 0L) return false
            val windowMs = (prefs.getInt("last_tc_token_ttl", 300) * 1000L)
                .coerceIn(MIN_WINDOW_MS, MAX_WINDOW_MS)
            System.currentTimeMillis() - requestedAt < windowMs
        } catch (_: Exception) {
            false
        }
    }
}
