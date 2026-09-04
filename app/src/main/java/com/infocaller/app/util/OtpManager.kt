package com.infocaller.app.util

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * OTP auto-verify bus. Sources:
 * - SMS codes (SmsReceiver, when RECEIVE_SMS granted)
 * - Missed-call tail digits (CallBroadcastReceiver ringing/idle path)
 * - WhatsApp codes are NOT auto-readable (no API) — user types them manually.
 * The login screen always keeps manual entry visible since the code may
 * arrive on a different phone.
 */
object OtpManager {
    /** Last 6-digit SMS code seen. */
    private val _lastOtp = MutableStateFlow<String?>(null)
    val lastOtpFlow = _lastOtp.asStateFlow()
    val otpFlow get() = _lastOtp.asStateFlow()

    /** Last missed-call tail (digits) seen — used for flash-call verification. */
    private val _lastMissedCallTail = MutableStateFlow<String?>(null)
    val missedCallFlow get() = _lastMissedCallTail.asStateFlow()

    suspend fun onOtpReceived(otp: String) { _lastOtp.value = otp }
    fun onOtpReceivedSync(otp: String) { _lastOtp.value = otp }
    fun onMissedCallTailSync(tail: String) { _lastMissedCallTail.value = tail }
    fun clearOtp() { _lastOtp.value = null }
    fun clearMissedCallTail() { _lastMissedCallTail.value = null }
}
