package com.infocaller.app.util

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

object OtpManager {
    private val _otpFlow = MutableStateFlow<String?>(null)
    val otpFlow = _otpFlow.asStateFlow()

    fun onOtpReceived(otp: String) {
        _otpFlow.value = otp
    }

    fun clearOtp() {
        _otpFlow.value = null
    }
}
