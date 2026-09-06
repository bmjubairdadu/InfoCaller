package com.infocaller.app.util

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.ToneGenerator
import android.net.Uri
import android.speech.tts.TextToSpeech
import android.telecom.CallAudioState
import com.infocaller.app.data.local.CallManager
import java.util.Locale
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

data class SoundboardEntry(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val emoji: String = "\uD83D\uDD0A",
    val kind: String = SoundboardStore.KIND_TONE,
    val payload: String = "",
    val volume: Float = 1f
)

object SoundboardStore {
    const val KIND_TONE = "tone"
    const val KIND_TTS = "tts"
    const val KIND_FILE = "file"

    private const val PREFS = "soundboard_prefs"
    private const val KEY_ENTRIES = "soundboard_entries_v1"

    fun defaultEntries(): List<SoundboardEntry> = listOf(
        SoundboardEntry(name = "Hello", emoji = "\uD83D\uDC4B", kind = KIND_TTS, payload = "Hello! Can you hear me?"),
        SoundboardEntry(name = "On my way", emoji = "\uD83D\uDE97", kind = KIND_TTS, payload = "I am on my way, please wait a moment."),
        SoundboardEntry(name = "Call back", emoji = "\uD83D\uDCDE", kind = KIND_TTS, payload = "I cannot talk right now, I will call you back."),
        SoundboardEntry(name = "Beep", emoji = "\uD83D\uDD14", kind = KIND_TONE, payload = "beep"),
        SoundboardEntry(name = "Chime", emoji = "\uD83D\uDD14", kind = KIND_TONE, payload = "chime"),
        SoundboardEntry(name = "Airhorn", emoji = "\uD83D\uDCE2", kind = KIND_TONE, payload = "airhorn")
    )

    fun load(context: Context): List<SoundboardEntry> {
        return try {
            val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_ENTRIES, null) ?: return defaultEntries()
            if (raw.isBlank()) return defaultEntries()
            val arr = JSONArray(raw)
            val out = ArrayList<SoundboardEntry>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val name = o.optString("name", "").trim()
                if (name.isEmpty()) continue
                out += SoundboardEntry(
                    id = o.optString("id", "").ifBlank { UUID.randomUUID().toString() },
                    name = name,
                    emoji = o.optString("emoji", "\uD83D\uDD0A"),
                    kind = o.optString("kind", KIND_TONE),
                    payload = o.optString("payload", ""),
                    volume = o.optDouble("volume", 1.0).toFloat().coerceIn(0f, 1f)
                )
            }
            if (out.isEmpty()) defaultEntries() else out
        } catch (_: Exception) {
            defaultEntries()
        }
    }

    fun save(context: Context, entries: List<SoundboardEntry>) {
        try {
            val arr = JSONArray()
            entries.forEach {
                val o = JSONObject()
                o.put("id", it.id)
                o.put("name", it.name.trim())
                o.put("emoji", it.emoji)
                o.put("kind", it.kind)
                o.put("payload", it.payload)
                o.put("volume", it.volume.toDouble())
                arr.put(o)
            }
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_ENTRIES, arr.toString()).apply()
        } catch (_: Exception) { }
    }
}

object SoundboardPlayer {
    @Volatile
    private var mediaPlayer: MediaPlayer? = null
    @Volatile
    private var tts: TextToSpeech? = null
    @Volatile
    private var toneGenerator: ToneGenerator? = null
    @Volatile
    var playingId: String? = null
        private set

    fun stop() {
        try { mediaPlayer?.stop() } catch (_: Exception) { }
        try { mediaPlayer?.release() } catch (_: Exception) { }
        mediaPlayer = null
        try { tts?.stop() } catch (_: Exception) { }
        try { toneGenerator?.stopTone() } catch (_: Exception) { }
        try { toneGenerator?.release() } catch (_: Exception) { }
        toneGenerator = null
        playingId = null
    }

    fun play(context: Context, entry: SoundboardEntry, onDone: (() -> Unit)? = null) {
        val appContext = context.applicationContext
        if (playingId == entry.id) {
            stop()
            try { onDone?.invoke() } catch (_: Exception) { }
            return
        }
        stop()
        playingId = entry.id
        val wasSpeakerOn = try { CallManager.isSpeakerOn.value } catch (_: Exception) { false }
        if (!wasSpeakerOn) {
            try { CallManager.setSpeaker(true) } catch (_: Exception) { }
        }
        try {
            val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            try { audioManager?.mode = AudioManager.MODE_IN_COMMUNICATION } catch (_: Exception) { }
        } catch (_: Exception) { }
        when (entry.kind) {
            SoundboardStore.KIND_TTS -> playTts(appContext, entry, wasSpeakerOn, onDone)
            SoundboardStore.KIND_FILE -> playFile(appContext, entry, wasSpeakerOn, onDone)
            else -> playTone(entry, wasSpeakerOn, onDone)
        }
    }

    private fun finish(wasSpeakerOn: Boolean, onDone: (() -> Unit)?) {
        playingId = null
        if (!wasSpeakerOn) {
            try { CallManager.setSpeaker(false) } catch (_: Exception) { }
        }
        try { onDone?.invoke() } catch (_: Exception) { }
    }

    private fun playTone(entry: SoundboardEntry, wasSpeakerOn: Boolean, onDone: (() -> Unit)?) {
        try {
            val gen = ToneGenerator(AudioManager.STREAM_VOICE_CALL, (entry.volume * 100).toInt().coerceIn(1, 100))
            toneGenerator = gen
            val id = entry.id
            when (entry.payload.lowercase()) {
                "chime" -> {
                    gen.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 400)
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        if (playingId == id) {
                            try { gen.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 400) } catch (_: Exception) { }
                        }
                    }, 450)
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        if (playingId == id) { stop(); finish(wasSpeakerOn, onDone) }
                    }, 1100)
                }
                "airhorn" -> {
                    gen.startTone(ToneGenerator.TONE_CDMA_ABBR_ALERT, 900)
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        if (playingId == id) { stop(); finish(wasSpeakerOn, onDone) }
                    }, 1000)
                }
                else -> {
                    gen.startTone(ToneGenerator.TONE_PROP_BEEP, 500)
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        if (playingId == id) { stop(); finish(wasSpeakerOn, onDone) }
                    }, 600)
                }
            }
        } catch (_: Exception) {
            stop()
            finish(wasSpeakerOn, onDone)
        }
    }

    private fun playTts(appContext: Context, entry: SoundboardEntry, wasSpeakerOn: Boolean, onDone: (() -> Unit)?) {
        try { tts?.shutdown() } catch (_: Exception) { }
        tts = TextToSpeech(appContext) { status ->
            val id = entry.id
            if (status != TextToSpeech.SUCCESS) {
                stop()
                finish(wasSpeakerOn, onDone)
                return@TextToSpeech
            }
            try {
                val engine = tts ?: return@TextToSpeech
                engine.language = Locale.getDefault()
                val attrs = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
                try { engine.setAudioAttributes(attrs) } catch (_: Exception) { }
                try { engine.setSpeechRate(1f) } catch (_: Exception) { }
                try { engine.setPitch(1f) } catch (_: Exception) { }
                engine.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) { }
                    override fun onError(utteranceId: String?) {
                        if (playingId == id) { stop(); finish(wasSpeakerOn, onDone) }
                    }
                    override fun onDone(utteranceId: String?) {
                        if (playingId == id) { stop(); finish(wasSpeakerOn, onDone) }
                    }
                })
                val text = entry.payload.ifBlank { entry.name }
                engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
            } catch (_: Exception) {
                stop()
                finish(wasSpeakerOn, onDone)
            }
        }
    }

    private fun playFile(appContext: Context, entry: SoundboardEntry, wasSpeakerOn: Boolean, onDone: (() -> Unit)?) {
        try {
            val uri = try { Uri.parse(entry.payload) } catch (_: Exception) { null }
            if (uri == null) {
                playTone(entry.copy(payload = "beep"), wasSpeakerOn, onDone)
                return
            }
            val id = entry.id
            val player = MediaPlayer()
            mediaPlayer = player
            player.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            try { player.setDataSource(appContext, uri) } catch (_: Exception) {
                stop()
                playTone(entry.copy(payload = "beep"), wasSpeakerOn, onDone)
                return
            }
            player.setOnCompletionListener {
                if (playingId == id) { stop(); finish(wasSpeakerOn, onDone) }
            }
            player.setOnErrorListener { _, _, _ ->
                if (playingId == id) { stop(); finish(wasSpeakerOn, onDone) }
                true
            }
            try { player.setVolume(entry.volume, entry.volume) } catch (_: Exception) { }
            player.prepare()
            player.start()
        } catch (_: Exception) {
            stop()
            finish(wasSpeakerOn, onDone)
        }
    }
}
