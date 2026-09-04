package com.infocaller.app.util

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

object OtpManager {
    private val _lastOtp = MutableStateFlow<String?>(null)
    val lastOtpFlow = _lastOtp.asStateFlow()
    val otpFlow get() = _lastOtp.asStateFlow()

    suspend fun onOtpReceived(otp: String) { _lastOtp.value = otp }
    fun onOtpReceivedSync(otp: String) { _lastOtp.value = otp }
    fun clearOtp() { _lastOtp.value = null }
}
