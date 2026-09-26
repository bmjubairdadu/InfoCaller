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
    private val enhancer = AudioEnhancer()

    private fun isDefaultDialer(): Boolean = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val tm = context.getSystemService(Context.TELECOM_SERVICE) as? android.telecom.TelecomManager
            tm?.defaultDialerPackage == context.packageName
        } else false
    } catch (_: Exception) { false } catch (_: Error) { false }

    private fun allocateUri(displayName: String): android.net.Uri? {
        val resolver = context.contentResolver
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val contentValues = android.content.ContentValues().apply {
                put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, "$displayName.m4a")
                put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "audio/mp4")
                put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, android.os.Environment.DIRECTORY_DOWNLOADS + "/InfoCaller")
                put(android.provider.MediaStore.MediaColumns.IS_PENDING, 1)
            }
            resolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
        } else {
            val storageDir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
            val appDir = File(storageDir, "InfoCaller")
            if (!appDir.exists()) appDir.mkdirs()
            val file = File(appDir, "$displayName.m4a")
            android.net.Uri.fromFile(file)
        }
    }

    private fun buildRecorder(uri: android.net.Uri, source: Int): MediaRecorder {
        val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }
        val resolver = context.contentResolver
        recorder.apply {
            setAudioSource(source)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setAudioSamplingRate(44100)
            setAudioEncodingBitRate(96000)
            setAudioChannels(1)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val pfd = resolver.openFileDescriptor(uri, "w") ?: throw IOException("Failed to open file descriptor.")
                pfd.use { setOutputFile(it.fileDescriptor) }
            } else {
                setOutputFile(uri.path)
            }
            prepare()
        }
        return recorder
    }

    fun startRecording(phoneNumber: String): Boolean {
        if (isRecording) return true

        try {
            val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val displayName = "Call_${phoneNumber}_$timeStamp"

            val canCaptureBothEnds = isDefaultDialer() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

            val attempts = if (canCaptureBothEnds) {
                listOf(
                    MediaRecorder.AudioSource.VOICE_CALL to "both-end (default dialer)",
                    MediaRecorder.AudioSource.VOICE_COMMUNICATION to "near-end fallback"
                )
            } else {
                listOf(MediaRecorder.AudioSource.VOICE_COMMUNICATION to "near-end only (not default dialer)")
            }

            var lastError: Exception? = null
            for ((source, label) in attempts) {
                var uri: android.net.Uri? = null
                var recorder: MediaRecorder? = null
                try {
                    uri = allocateUri(displayName)
                    if (uri == null) throw IOException("Failed to create new record.")
                    recorder = buildRecorder(uri, source)
                    recorder.start()

                    mediaRecorder = recorder
                    isRecording = true
                    currentUri = uri
                    Log.d("CallRecorder", "Started recording [$label]: $uri")

                    try {
                        val sessionId = try {
                            val m = recorder.javaClass.methods.firstOrNull {
                                it.name == "getAudioSessionId" && it.parameterCount == 0
                            }
                            (m?.invoke(recorder) as? Int) ?: 0
                        } catch (_: Exception) { 0 } catch (_: Error) { 0 }
                        if (sessionId > 0 && enhancer.attach(sessionId)) {
                            Log.d("CallRecorder", "Audio enhancement attached to session $sessionId")
                        }
                    } catch (e: Exception) {
                        Log.w("CallRecorder", "Audio enhancement failed: ${e.message}")
                    } catch (e: Error) {
                        Log.w("CallRecorder", "Audio enhancement error: ${e.message}")
                    }
                    return true
                } catch (e: Exception) {
                    lastError = e
                    Log.w("CallRecorder", "Source $label failed: ${e.message}")
                    try { recorder?.release() } catch (_: Exception) { }
                    try {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && uri != null) {
                            context.contentResolver.delete(uri, null, null)
                        }
                    } catch (_: Exception) { }
                }
            }
            throw lastError ?: IOException("No usable audio source")
        } catch (e: Exception) {
            Log.e("CallRecorder", "start() failed", e)
            isRecording = false
            try { mediaRecorder?.release() } catch (_: Exception) { }
            mediaRecorder = null
            currentUri = null
        }
        return false
    }

    fun stopRecording() {
        if (!isRecording) return
        try { enhancer.release() } catch (_: Exception) { } catch (_: Error) { }
        try {
            val recorder = mediaRecorder
            if (recorder != null) {
                try { recorder.stop() } catch (e: Exception) { Log.e("CallRecorder", "stop() failed", e) }
                try { recorder.release() } catch (_: Exception) { }
            }
            mediaRecorder = null
            isRecording = false

            val uri = currentUri
            if (uri != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val contentValues = android.content.ContentValues().apply {
                        put(android.provider.MediaStore.MediaColumns.IS_PENDING, 0)
                    }
                    try {
                        context.contentResolver.update(uri, contentValues, null, null)
                    } catch (e: Exception) { Log.e("CallRecorder", "IS_PENDING update failed", e) }
                }
            }
            Log.d("CallRecorder", "Stopped recording: $currentUri")
        } catch (e: Exception) {
            Log.e("CallRecorder", "stopRecording failed", e)
        } finally {
            mediaRecorder = null
            isRecording = false
        }
    }
}
