package com.infocaller.app.util

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

object OtpManager {
    const val OTP_TTL_MS = 10 * 60 * 1000L

    private data class TimedCode(val code: String, val at: Long)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _lastOtp = MutableStateFlow<TimedCode?>(null)
    val lastOtpFlow: StateFlow<String?> = _lastOtp.derivedNullable { timed ->
        timed?.takeIf { System.currentTimeMillis() - it.at < OTP_TTL_MS }?.code
    }
    val otpFlow get() = lastOtpFlow

    private val _lastMissedCallTail = MutableStateFlow<TimedCode?>(null)
    val missedCallFlow: StateFlow<String?> = _lastMissedCallTail.derivedNullable { timed ->
        timed?.takeIf { System.currentTimeMillis() - it.at < OTP_TTL_MS }?.code
    }

    data class MissedCallEvent(
        val tail: String,
        val sourceNumber: String? = null,
        val isIdle: Boolean = false,
        val timestamp: Long = System.currentTimeMillis()
    )
    private val _missedCallEvent = MutableStateFlow<MissedCallEvent?>(null)
    val missedCallEventFlow: StateFlow<MissedCallEvent?> get() = _missedCallEvent

    private val _lastMissedCallSource = MutableStateFlow<TimedCode?>(null)
    val missedCallSourceFlow: StateFlow<String?> = _lastMissedCallSource.derivedNullable { timed ->
        timed?.takeIf { System.currentTimeMillis() - it.at < OTP_TTL_MS }?.code
    }

    init {
        scope.launch {
            while (isActive) {
                delay(30_000L)
                val now = System.currentTimeMillis()
                listOf(_lastOtp, _lastMissedCallTail, _lastMissedCallSource).forEach { f ->
                    val v = f.value
                    if (v != null && now - v.at >= OTP_TTL_MS) f.value = null
                }
                val ev = _missedCallEvent.value
                if (ev != null && now - ev.timestamp >= OTP_TTL_MS) _missedCallEvent.value = null
            }
        }
    }

    private fun MutableStateFlow<TimedCode?>.derivedNullable(
        transform: (TimedCode?) -> String?
    ): StateFlow<String?> {
        val holder = MutableStateFlow(transform(value))
        scope.launch {
            collect { timed -> holder.value = transform(timed) }
        }
        return holder
    }

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

    fun clearOtp() { _lastOtp.value = null }
    fun clearMissedCallTail() {
        _lastMissedCallTail.value = null
        _lastMissedCallSource.value = null
        _missedCallEvent.value = null
    }
}
