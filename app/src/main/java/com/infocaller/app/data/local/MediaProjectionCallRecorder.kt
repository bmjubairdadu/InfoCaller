package com.infocaller.app.data.local

import android.app.Activity
import android.content.Context
import android.media.MediaRecorder
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.os.Environment
import android.util.Log
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*

class MediaProjectionCallRecorder(
    private val context: Context
) {
    private var mediaRecorder: MediaRecorder? = null
    private var isRecording = false
    private var currentFile: File? = null
    private var noiseSuppressor: NoiseSuppressor? = null

    /**
     * Start recording with noise suppression (VOICE_COMMUNICATION source)
     * Enables: Acoustic Echo Cancellation (AEC), Noise Suppression (NS), Automatic Gain Control (AGC)
     * No permission dialog needed - works on all Android versions
     */
    fun startNoiseSuppressedMicRecording(activity: Activity, phoneNumber: String): Boolean {
        if (isRecording) {
            Log.w("MediaProjectionRecorder", "Already recording")
            return false
        }

        try {
            val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val fileName = "Call_${phoneNumber}_${timeStamp}_NS.mp4"
            val storageDir = getStorageDirectory()
            storageDir?.mkdirs()
            currentFile = File(storageDir, fileName)

            mediaRecorder = MediaRecorder().apply {
                // VOICE_COMMUNICATION enables:
                // - Acoustic Echo Cancellation (AEC)
                // - Noise Suppression (NS) 
                // - Automatic Gain Control (AGC)
                val audioSource = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    MediaRecorder.AudioSource.VOICE_COMMUNICATION
                } else {
                    MediaRecorder.AudioSource.VOICE_CALL
                }
                setAudioSource(audioSource)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioEncodingBitRate(128000)
                setAudioSamplingRate(48000)
                setOutputFile(currentFile?.absolutePath)
                
                // Minimal video for container
                setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                setVideoSize(176, 144)
                setVideoFrameRate(1)
                setVideoEncodingBitRate(10000)
                
                prepare()
                start()
            }
            
            // Also create NoiseSuppressor for additional processing if needed
            initNoiseSuppressor()
            
            isRecording = true
            Log.d("MediaProjectionRecorder", "Started NOISE-SUPPRESSED MIC recording: ${currentFile?.absolutePath}")
            return true
        } catch (e: Exception) {
            Log.e("MediaProjectionRecorder", "Noise-suppressed recording failed", e)
            return false
        }
    }

    private fun initNoiseSuppressor() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
            try {
                noiseSuppressor = NoiseSuppressor.create(0) // 0 = default audio session
                noiseSuppressor?.enabled = true
                Log.d("MediaProjectionRecorder", "NoiseSuppressor enabled: ${noiseSuppressor?.enabled}")
            } catch (e: Exception) {
                Log.w("MediaProjectionRecorder", "NoiseSuppressor not available", e)
            }
        }
    }

    fun stopRecording(): File? {
        if (!isRecording) return null

        try {
            mediaRecorder?.apply {
                stop()
                release()
            }
            mediaRecorder = null
            isRecording = false
            
            // Release noise suppressor
            noiseSuppressor?.release()
            noiseSuppressor = null
            
            val file = currentFile
            currentFile = null
            
            Log.d("MediaProjectionRecorder", "Stopped recording: ${file?.absolutePath}")
            return file
        } catch (e: Exception) {
            Log.e("MediaProjectionRecorder", "stop() failed", e)
            return null
        }
    }

    fun release() {
        stopRecording()
    }

    fun isRecording(): Boolean = isRecording

    fun isNoiseSuppressionAvailable(): Boolean {
        return NoiseSuppressor.isAvailable()
    }

    private fun getStorageDirectory(): File? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            context.getExternalFilesDir(Environment.DIRECTORY_MUSIC)?.let { dir ->
                File(dir, "CallRecordings").apply { mkdirs() }
            }
        } else {
            context.getExternalFilesDir("Recordings")?.apply { mkdirs() }
        }
    }

    companion object {
        const val DEFAULT_REQUEST_CODE = 1234
    }
}