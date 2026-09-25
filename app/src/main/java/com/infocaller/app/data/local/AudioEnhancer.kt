package com.infocaller.app.data.local

import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.util.Log

class AudioEnhancer {

    private var noiseSuppressor: NoiseSuppressor? = null
    private var gainControl: AutomaticGainControl? = null
    private var echoCanceler: AcousticEchoCanceler? = null

    val isAvailable: Boolean
        get() = try {
            NoiseSuppressor.isAvailable()
        } catch (_: Exception) { false } catch (_: Error) { false }

    fun attach(sessionId: Int): Boolean {
        if (sessionId <= 0) return false
        var any = false

        try {
            if (NoiseSuppressor.isAvailable()) {
                noiseSuppressor = NoiseSuppressor.create(sessionId)?.apply { enabled = true }
                if (noiseSuppressor?.enabled == true) any = true
            }
        } catch (e: Exception) {
            Log.w("AudioEnhancer", "NoiseSuppressor unavailable: ${e.message}")
        } catch (e: Error) {
            Log.w("AudioEnhancer", "NoiseSuppressor error: ${e.message}")
        }

        try {
            if (AutomaticGainControl.isAvailable()) {
                gainControl = AutomaticGainControl.create(sessionId)?.apply { enabled = true }
                if (gainControl?.enabled == true) any = true
            }
        } catch (e: Exception) {
            Log.w("AudioEnhancer", "AGC unavailable: ${e.message}")
        } catch (e: Error) {
            Log.w("AudioEnhancer", "AGC error: ${e.message}")
        }

        try {
            if (AcousticEchoCanceler.isAvailable()) {
                echoCanceler = AcousticEchoCanceler.create(sessionId)?.apply {
                    enabled = true
                }
                if (echoCanceler?.enabled == true) any = true
            }
        } catch (e: Exception) {
            Log.w("AudioEnhancer", "AEC unavailable: ${e.message}")
        } catch (e: Error) {
            Log.w("AudioEnhancer", "AEC error: ${e.message}")
        }

        return any
    }

    fun release() {
        try { noiseSuppressor?.release() } catch (_: Exception) { } catch (_: Error) { }
        try { gainControl?.release() } catch (_: Exception) { } catch (_: Error) { }
        try { echoCanceler?.release() } catch (_: Exception) { } catch (_: Error) { }
        noiseSuppressor = null
        gainControl = null
        echoCanceler = null
    }
}
