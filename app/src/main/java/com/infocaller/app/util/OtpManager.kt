package com.infocaller.app.util

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

object OtpManager {
    const val OTP_TTL_MS = 10 * 60 * 1000L

    private data class TimedCode(val code: String, val at: Long)
    private val _lastOtp = MutableStateFlow<TimedCode?>(null)
    val lastOtpFlow: StateFlow<String?> = _lastOtp
        .map { timed -> timed?.takeIf { System.currentTimeMillis() - it.at < OTP_TTL_MS }?.code }
        .stateIn(
            CoroutineScope(Dispatchers.Default),
            SharingStarted.Eagerly, null
        )
    val otpFlow get() = lastOtpFlow

    private val _lastMissedCallTail = MutableStateFlow<TimedCode?>(null)
    val missedCallFlow: StateFlow<String?> = _lastMissedCallTail
        .map { timed -> timed?.takeIf { System.currentTimeMillis() - it.at < OTP_TTL_MS }?.code }
        .stateIn(
            CoroutineScope(Dispatchers.Default),
            SharingStarted.Eagerly, null
        )

    data class MissedCallEvent(
        val tail: String,
        val sourceNumber: String? = null,
        val isIdle: Boolean = false,
        val timestamp: Long = System.currentTimeMillis()
    )
    private val _missedCallEvent = MutableStateFlow<MissedCallEvent?>(null)
    val missedCallEventFlow: StateFlow<MissedCallEvent?> get() = _missedCallEvent

    suspend fun onOtpReceived(otp: String) { _lastOtp.value = TimedCode(otp, System.currentTimeMillis()) }
    fun onOtpReceivedSync(otp: String) { _lastOtp.value = TimedCode(otp, System.currentTimeMillis()) }
    fun onMissedCallTailSync(tail: String, sourceNumber: String? = null, isIdle: Boolean = false) {
        val now = System.currentTimeMillis()
        _lastMissedCallTail.value = TimedCode(tail, now)
        _missedCallEvent.value = MissedCallEvent(tail, sourceNumber, isIdle, now)
        if (!sourceNumber.isNullOrBlank()) {
            _lastMissedCallSource.value = TimedCode(
                sourceNumber.filter { it.isDigit() }.takeLast(11),
                now
            )
        }
    }

    private val _lastMissedCallSource = MutableStateFlow<TimedCode?>(null)
    val missedCallSourceFlow: StateFlow<String?> = _lastMissedCallSource
        .map { timed -> timed?.takeIf { System.currentTimeMillis() - it.at < OTP_TTL_MS }?.code }
        .stateIn(
            CoroutineScope(Dispatchers.Default),
            SharingStarted.Eagerly, null
        )
    fun clearOtp() { _lastOtp.value = null }
    fun clearMissedCallTail() {
        _lastMissedCallTail.value = null
        _lastMissedCallSource.value = null
        _missedCallEvent.value = null
    }
}
