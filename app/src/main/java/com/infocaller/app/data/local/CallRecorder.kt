package com.infocaller.app.data.local

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*

class CallRecorder(private val context: Context) {
    private var mediaRecorder: MediaRecorder? = null
    private var isRecording = false
    private var currentFile: File? = null

    fun startRecording(phoneNumber: String) {
        if (isRecording) return

        try {
            val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val fileName = "Call_${phoneNumber}_$timeStamp.amr"
            val storageDir = context.getExternalFilesDir("Recordings")
            if (storageDir?.exists() == false) {
                storageDir.mkdirs()
            }
            currentFile = File(storageDir, fileName)

            mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }.apply {
                // Try VOICE_COMMUNICATION first (captures both uplink/downlink)
                // Falls back to MIC if not permitted
                val audioSource = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    try {
                        MediaRecorder.AudioSource.VOICE_COMMUNICATION
                    } catch (_: Exception) {
                        MediaRecorder.AudioSource.MIC
                    }
                } else {
                    MediaRecorder.AudioSource.VOICE_CALL
                }
                setAudioSource(audioSource)
                setOutputFormat(MediaRecorder.OutputFormat.AMR_NB)
                setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB)
                setOutputFile(currentFile?.absolutePath)
                prepare()
                start()
            }
            isRecording = true
            Log.d("CallRecorder", "Started recording: ${currentFile?.absolutePath}")
        } catch (e: IOException) {
            Log.e("CallRecorder", "prepare() failed", e)
            fallbackToMicRecording(phoneNumber)
        } catch (e: IllegalStateException) {
            Log.e("CallRecorder", "start() failed", e)
            fallbackToMicRecording(phoneNumber)
        }
    }

    private fun fallbackToMicRecording(phoneNumber: String) {
        try {
            val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val fileName = "Call_${phoneNumber}_$timeStamp.amr"
            val storageDir = context.getExternalFilesDir("Recordings")
            currentFile = File(storageDir, fileName)

            mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.AMR_NB)
                setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB)
                setOutputFile(currentFile?.absolutePath)
                prepare()
                start()
            }
            isRecording = true
            Log.w("CallRecorder", "Fell back to MIC recording: ${currentFile?.absolutePath}")
        } catch (e: Exception) {
            Log.e("CallRecorder", "Fallback recording failed", e)
        }
    }

    fun stopRecording() {
        if (!isRecording) return

        try {
            mediaRecorder?.apply {
                stop()
                release()
            }
            mediaRecorder = null
            isRecording = false
            Log.d("CallRecorder", "Stopped recording: ${currentFile?.absolutePath}")
        } catch (e: Exception) {
            Log.e("CallRecorder", "stop() failed", e)
        }
    }
}
