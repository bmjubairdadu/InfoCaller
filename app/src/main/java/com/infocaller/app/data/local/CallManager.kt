package com.infocaller.app.data.local

import android.app.Activity
import android.content.Context
import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.InCallService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

object CallManager {
    const val ACTION_ANSWER_CALL = "com.infocaller.app.ACTION_ANSWER_CALL"
    const val ACTION_DECLINE_CALL = "com.infocaller.app.ACTION_DECLINE_CALL"

    private val _activeCall = MutableStateFlow<Call?>(null)
    val activeCall = _activeCall.asStateFlow()
    
    private val _callState = MutableStateFlow(Call.STATE_DISCONNECTED)
    val callState = _callState.asStateFlow()
    
    private val _isMuted = MutableStateFlow(false)
    val isMuted = _isMuted.asStateFlow()
    
    private val _isSpeakerOn = MutableStateFlow(false)
    val isSpeakerOn = _isSpeakerOn.asStateFlow()

    private val _isHolding = MutableStateFlow(false)
    val isHolding = _isHolding.asStateFlow()

    private val _isRecording = MutableStateFlow(false)
    val isRecording = _isRecording.asStateFlow()
    
    private var inCallService: java.lang.ref.WeakReference<InCallService>? = null
    private var callRecorder: CallRecorder? = null

    fun init(context: Context) {
        if (callRecorder == null) {
            callRecorder = CallRecorder(context.applicationContext)
        }
    }

    fun updateCall(call: Call?) {
        _activeCall.value = call
        _callState.value = call?.state ?: Call.STATE_DISCONNECTED
        if (call == null) {
            _isHolding.value = false
            stopRecording()
        } else {
            call.registerCallback(object : Call.Callback() {
                override fun onStateChanged(call: Call, state: Int) {
                    _callState.value = state
                    if (state == Call.STATE_DISCONNECTED) {
                        updateCall(null)
                    }
                }
            })
        }
    }
    
    fun updateAudioState(state: CallAudioState) {
        _isMuted.value = state.isMuted
        _isSpeakerOn.value = state.route == CallAudioState.ROUTE_SPEAKER
    }
    
    fun setInCallService(service: InCallService?) {
        inCallService = service?.let { java.lang.ref.WeakReference(it) }
    }

    fun mute(isMuted: Boolean) {
        inCallService?.get()?.setMuted(isMuted)
    }

    fun setSpeaker(isEnabled: Boolean) {
        @Suppress("DEPRECATION")
        val route = if (isEnabled) CallAudioState.ROUTE_SPEAKER else CallAudioState.ROUTE_WIRED_OR_EARPIECE
        inCallService?.get()?.setAudioRoute(route)
    }

    fun toggleHold() {
        val call = _activeCall.value ?: return
        if (_isHolding.value) {
            call.unhold()
            _isHolding.value = false
        } else {
            call.hold()
            _isHolding.value = true
        }
    }

    fun playDtmf(digit: Char) {
        _activeCall.value?.playDtmfTone(digit)
        _activeCall.value?.stopDtmfTone()
    }

    fun answer() {
        val call = _activeCall.value ?: return
        if (call.state == Call.STATE_RINGING) {
            call.answer(android.telecom.VideoProfile.STATE_AUDIO_ONLY)
        }
    }

    fun decline() {
        val call = _activeCall.value ?: return
        if (call.state == Call.STATE_RINGING) {
            call.reject(false, null)
        } else {
            call.disconnect()
        }
    }

    fun startRecording(@Suppress("UNUSED_PARAMETER") activity: Activity, phoneNumber: String) {
        callRecorder?.startRecording(phoneNumber)
        _isRecording.value = true
    }

    fun stopRecording() {
        callRecorder?.stopRecording()
        _isRecording.value = false
    }

    fun toggleRecording(activity: Activity, phoneNumber: String) {
        if (_isRecording.value) {
            stopRecording()
        } else {
            startRecording(activity, phoneNumber)
        }
    }
}
