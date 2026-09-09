package com.infocaller.app.util

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * OTP auto-verify bus. Sources:
 * - SMS codes (SmsReceiver, when RECEIVE_SMS granted)
 * - Missed-call tail digits (CallBroadcastReceiver ringing/idle path)
 * - WhatsApp codes are NOT auto-readable (no API) — user types them manually.
 * The login screen always keeps manual entry visible since the code may
 * arrive on a different phone.
 */
object OtpManager {
    /** Codes older than this are never auto-filled (stale-code verify fix). */
    const val OTP_TTL_MS = 10 * 60 * 1000L

    /** Last 6-digit SMS code seen, with arrival timestamp (stale guard). */
    private data class TimedCode(val code: String, val at: Long)
    private val _lastOtp = MutableStateFlow<TimedCode?>(null)
    val lastOtpFlow: StateFlow<String?> = _lastOtp
        .map { timed -> timed?.takeIf { System.currentTimeMillis() - it.at < OTP_TTL_MS }?.code }
        .stateIn(
            CoroutineScope(Dispatchers.Default),
            SharingStarted.Eagerly, null
        )
    val otpFlow get() = lastOtpFlow

    /** Last missed-call tail (digits) seen — used for flash-call verification. */
    private val _lastMissedCallTail = MutableStateFlow<TimedCode?>(null)
    val missedCallFlow: StateFlow<String?> = _lastMissedCallTail
        .map { timed -> timed?.takeIf { System.currentTimeMillis() - it.at < OTP_TTL_MS }?.code }
        .stateIn(
            CoroutineScope(Dispatchers.Default),
            SharingStarted.Eagerly, null
        )

    suspend fun onOtpReceived(otp: String) { _lastOtp.value = TimedCode(otp, System.currentTimeMillis()) }
    fun onOtpReceivedSync(otp: String) { _lastOtp.value = TimedCode(otp, System.currentTimeMillis()) }
    fun onMissedCallTailSync(tail: String) { _lastMissedCallTail.value = TimedCode(tail, System.currentTimeMillis()) }
    /**
     * Number-bound missed-call tail: records WHICH caller left the tail so a
     * random missed call can never verify someone else's OTP. The LoginScreen
     * only auto-fills when the tail's source number matches the pending
     * verification caller.
     */
    fun onMissedCallTailSync(tail: String, sourceNumber: String?) {
        _lastMissedCallTail.value = TimedCode(tail, System.currentTimeMillis())
        if (!sourceNumber.isNullOrBlank()) {
            _lastMissedCallSource.value = TimedCode(
                sourceNumber.filter { it.isDigit() }.takeLast(11),
                System.currentTimeMillis()
            )
        }
    }
    /** Source number of the last missed-call tail (digits only, TTL-guarded). */
    private val _lastMissedCallSource = MutableStateFlow<TimedCode?>(null)
    val missedCallSourceFlow: StateFlow<String?> = _lastMissedCallSource
        .map { timed -> timed?.takeIf { System.currentTimeMillis() - it.at < OTP_TTL_MS }?.code }
        .stateIn(
            CoroutineScope(Dispatchers.Default),
            SharingStarted.Eagerly, null
        )
    fun clearOtp() { _lastOtp.value = null }
    fun clearMissedCallTail() { _lastMissedCallTail.value = null; _lastMissedCallSource.value = null }
}
