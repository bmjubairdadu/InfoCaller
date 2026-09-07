package com.infocaller.app.util

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.net.Uri
import com.infocaller.app.data.local.CallManager
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

data class SoundboardEntry(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val emoji: String = "\uD83C\uDFB5",
    val kind: String = SoundboardStore.KIND_FILE,
    val payload: String = "",
    val volume: Float = 1f
)

object SoundboardStore {
    const val KIND_FILE = "file"
    const val KIND_TONE = "tone"

    private const val PREFS = "soundboard_prefs"
    private const val KEY_ENTRIES = "soundboard_entries_v3"

    fun defaultEntries(): List<SoundboardEntry> = listOf(
        SoundboardEntry(name = "Hello", emoji = "\uD83D\uDC4B", kind = KIND_TONE, payload = "chime"),
        SoundboardEntry(name = "On my way", emoji = "\uD83D\uDE97", kind = KIND_TONE, payload = "beep"),
        SoundboardEntry(name = "Call back", emoji = "\uD83D\uDCDE", kind = KIND_TONE, payload = "beep"),
        SoundboardEntry(name = "Dramatic gasp", emoji = "\uD83D\uDE31", kind = KIND_TONE, payload = "airhorn"),
        SoundboardEntry(name = "Suspense", emoji = "\uD83D\uDD75\uFE0F", kind = KIND_TONE, payload = "chime"),
        SoundboardEntry(name = "Victory", emoji = "\uD83C\uDFC6", kind = KIND_TONE, payload = "airhorn"),
        SoundboardEntry(name = "Sad trombone", emoji = "\uD83D\uDE1E", kind = KIND_TONE, payload = "beep"),
        SoundboardEntry(name = "Crickets", emoji = "\uD83E\uDD97", kind = KIND_TONE, payload = "beep"),
        SoundboardEntry(name = "Wrong number", emoji = "\uD83E\uDD2A", kind = KIND_TONE, payload = "chime"),
        SoundboardEntry(name = "Hold music", emoji = "\uD83C\uDFB6", kind = KIND_TONE, payload = "chime"),
        SoundboardEntry(name = "Beep", emoji = "\uD83D\uDD14", kind = KIND_TONE, payload = "beep"),
        SoundboardEntry(name = "Airhorn", emoji = "\uD83D\uDCE2", kind = KIND_TONE, payload = "airhorn")
    )

    fun emojiForName(name: String): String = when (name.trim().lowercase()) {
        "hello" -> "\uD83D\uDC4B"
        "on my way" -> "\uD83D\uDE97"
        "call back" -> "\uD83D\uDCDE"
        "dramatic gasp" -> "\uD83D\uDE31"
        "suspense" -> "\uD83D\uDD75\uFE0F"
        "victory" -> "\uD83C\uDFC6"
        "sad trombone" -> "\uD83D\uDE1E"
        "crickets" -> "\uD83E\uDD97"
        "wrong number" -> "\uD83E\uDD2A"
        "hold music" -> "\uD83C\uDFB6"
        "beep" -> "\uD83D\uDD14"
        "chime" -> "\uD83D\uDD14"
        "airhorn" -> "\uD83D\uDCE2"
        else -> "\uD83C\uDFB5"
    }

    fun mediaTitle(context: Context, uriString: String): String? {
        return try {
            val uri = Uri.parse(uriString)
            var title: String? = null
            try {
                val retriever = MediaMetadataRetriever()
                retriever.setDataSource(context, uri)
                title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                try { retriever.release() } catch (_: Exception) { }
            } catch (_: Exception) { }
            if (!title.isNullOrBlank()) return title
            try {
                context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val raw = cursor.getString(0) ?: ""
                        val noExt = raw.substringBeforeLast(".")
                        if (noExt.isNotBlank()) return noExt
                    }
                }
            } catch (_: Exception) { }
            null
        } catch (_: Exception) {
            null
        }
    }

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
                    emoji = o.optString("emoji", "").ifBlank { emojiForName(name) },
                    kind = o.optString("kind", KIND_FILE).let {
                        if (it == KIND_FILE || it == KIND_TONE) it else KIND_FILE
                    },
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
    private var tonePlayer: MediaPlayer? = null
    @Volatile
    var playingId: String? = null
        private set

    fun stop() {
        try { mediaPlayer?.stop() } catch (_: Exception) { }
        try { mediaPlayer?.release() } catch (_: Exception) { }
        mediaPlayer = null
        try { tonePlayer?.stop() } catch (_: Exception) { }
        try { tonePlayer?.release() } catch (_: Exception) { }
        tonePlayer = null
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
        // Media files AND the built-in funny clips both play as real audio —
        // no TTS voiceover anywhere. Video files play their audio track.
        if (entry.kind == SoundboardStore.KIND_FILE && entry.payload.isNotBlank()) {
            playFile(appContext, entry, wasSpeakerOn, onDone)
        } else {
            playTone(appContext, entry, wasSpeakerOn, onDone)
        }
    }

    private fun finish(wasSpeakerOn: Boolean, onDone: (() -> Unit)?) {
        playingId = null
        if (!wasSpeakerOn) {
            try { CallManager.setSpeaker(false) } catch (_: Exception) { }
        }
        try { onDone?.invoke() } catch (_: Exception) { }
    }

    private fun playTone(appContext: Context, entry: SoundboardEntry, wasSpeakerOn: Boolean, onDone: (() -> Unit)?) {
        // Built-in funny clips are synthesized audio patterns (no voice):
        // each payload maps to a tone sequence played through the speaker.
        try {
            val id = entry.id
            val pattern = when (entry.payload.lowercase()) {
                "airhorn" -> listOf(900L)
                "chime" -> listOf(400L, 150L, 400L)
                else -> listOf(500L)
            }
            playTonePattern(appContext, entry, pattern, 0, wasSpeakerOn, onDone)
        } catch (_: Exception) {
            stop()
            finish(wasSpeakerOn, onDone)
        }
    }

    private fun playTonePattern(
        appContext: Context,
        entry: SoundboardEntry,
        pattern: List<Long>,
        index: Int,
        wasSpeakerOn: Boolean,
        onDone: (() -> Unit)?,
    ) {
        val id = entry.id
        if (playingId != id) return
        if (index >= pattern.size) {
            if (playingId == id) { stop(); finish(wasSpeakerOn, onDone) }
            return
        }
        try {
            val tone = when (entry.payload.lowercase()) {
                "airhorn" -> android.media.ToneGenerator.TONE_CDMA_ABBR_ALERT
                "chime" -> android.media.ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD
                else -> android.media.ToneGenerator.TONE_PROP_BEEP
            }
            val gen = android.media.ToneGenerator(AudioManager.STREAM_VOICE_CALL, (entry.volume * 100).toInt().coerceIn(1, 100))
            try { tonePlayer?.release() } catch (_: Exception) { }
            tonePlayer = null
            gen.startTone(tone, pattern[index].toInt())
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                try { gen.release() } catch (_: Exception) { }
                if (playingId == id) {
                    playTonePattern(appContext, entry, pattern, index + 1, wasSpeakerOn, onDone)
                }
            }, pattern[index] + 150L)
        } catch (_: Exception) {
            if (playingId == id) { stop(); finish(wasSpeakerOn, onDone) }
        }
    }

    private fun playFile(appContext: Context, entry: SoundboardEntry, wasSpeakerOn: Boolean, onDone: (() -> Unit)?) {
        try {
            val uri = try { Uri.parse(entry.payload) } catch (_: Exception) { null }
            if (uri == null || entry.payload.isBlank()) {
                if (playingId == entry.id) { stop(); finish(wasSpeakerOn, onDone) }
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
                if (playingId == id) { stop(); finish(wasSpeakerOn, onDone) }
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
