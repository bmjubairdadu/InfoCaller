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
    private var currentUri: android.net.Uri? = null

    fun startRecording(phoneNumber: String) {
        if (isRecording) return

        try {
            val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val displayName = "Call_${phoneNumber}_$timeStamp"
            val resolver = context.contentResolver
            
            val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val contentValues = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, "$displayName.amr")
                    put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "audio/amr")
                    put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, android.os.Environment.DIRECTORY_DOWNLOADS + "/InfoCaller")
                    put(android.provider.MediaStore.MediaColumns.IS_PENDING, 1)
                }
                resolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
            } else {
                val storageDir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
                val appDir = File(storageDir, "InfoCaller")
                if (!appDir.exists()) appDir.mkdirs()
                val file = File(appDir, "$displayName.amr")
                android.net.Uri.fromFile(file)
            }

            if (uri == null) throw IOException("Failed to create new record.")

            mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }.apply {
                // Try VOICE_COMMUNICATION for best quality on supported devices
                setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
                setOutputFormat(MediaRecorder.OutputFormat.AMR_NB)
                setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB)
                
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val pfd = resolver.openFileDescriptor(uri, "w") ?: throw IOException("Failed to open file descriptor.")
                    setOutputFile(pfd.fileDescriptor)
                } else {
                    setOutputFile(uri.path)
                }
                
                prepare()
                start()
            }
            
            isRecording = true
            currentUri = uri
            Log.d("CallRecorder", "Started recording: $uri")
        } catch (e: Exception) {
            Log.e("CallRecorder", "start() failed", e)
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
            
            val uri = currentUri
            if (uri != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val contentValues = android.content.ContentValues().apply {
                        put(android.provider.MediaStore.MediaColumns.IS_PENDING, 0)
                    }
                    context.contentResolver.update(uri, contentValues, null, null)
                }
            }
            Log.d("CallRecorder", "Stopped recording: $currentUri")
        } catch (e: Exception) {
            Log.e("CallRecorder", "stop() failed", e)
        }
    }
}
