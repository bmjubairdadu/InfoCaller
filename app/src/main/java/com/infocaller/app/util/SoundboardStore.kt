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
    private const val KEY_ENTRIES = "soundboard_entries_v4"
    private const val KEY_LEGACY_V3 = "soundboard_entries_v3"

    fun defaultEntries(): List<SoundboardEntry> = listOf(
        SoundboardEntry(name = "Airhorn blast", emoji = "\uD83D\uDCE2", kind = KIND_TONE, payload = "airhorn"),
        SoundboardEntry(name = "Sad trombone", emoji = "\uD83D\uDE1E", kind = KIND_TONE, payload = "trombone"),
        SoundboardEntry(name = "Dramatic gasp", emoji = "\uD83D\uDE31", kind = KIND_TONE, payload = "gasp"),
        SoundboardEntry(name = "Slide whistle up", emoji = "\uD83C\uDFB6", kind = KIND_TONE, payload = "slide_up"),
        SoundboardEntry(name = "Slide whistle down", emoji = "\uD83D\uDE2D", kind = KIND_TONE, payload = "slide_down"),
        SoundboardEntry(name = "Boing", emoji = "\uD83E\uDD2A", kind = KIND_TONE, payload = "boing"),
        SoundboardEntry(name = "Record scratch", emoji = "\uD83D\uDCBF", kind = KIND_TONE, payload = "scratch"),
        SoundboardEntry(name = "Crickets", emoji = "\uD83E\uDD97", kind = KIND_TONE, payload = "crickets"),
        SoundboardEntry(name = "Rimshot ba-dum", emoji = "\uD83E\uDD41", kind = KIND_TONE, payload = "rimshot"),
        SoundboardEntry(name = "Victory fanfare", emoji = "\uD83C\uDFC6", kind = KIND_TONE, payload = "fanfare"),
        SoundboardEntry(name = "Wrong number", emoji = "\uD83D\uDCDE", kind = KIND_TONE, payload = "buzzer"),
        SoundboardEntry(name = "Suspense sting", emoji = "\uD83D\uDD75\uFE0F", kind = KIND_TONE, payload = "sting")
    )

    val EMOJI_CHOICES: List<String> = listOf(
        "\uD83D\uDCE2", "\uD83D\uDE1E", "\uD83D\uDE31", "\uD83C\uDFB6",
        "\uD83D\uDE2D", "\uD83E\uDD2A", "\uD83D\uDCBF", "\uD83E\uDD97",
        "\uD83E\uDD41", "\uD83C\uDFC6", "\uD83D\uDCDE", "\uD83D\uDD75\uFE0F",
        "\uD83D\uDC4B", "\uD83D\ude02", "\uD83D\ude2D", "\uD83C\uDF89",
        "\uD83D\udd14", "\uD83C\udfb5", "\uD83D\ude97", "\uD83D\udcbf",
        "\uD83C\udfae", "\uD83D\ude3B", "\uD83C\udf1f", "\uD83D\uDC80"
    )

    fun emojiForName(name: String): String = when (name.trim().lowercase()) {
        "airhorn blast", "airhorn" -> "\uD83D\uDCE2"
        "sad trombone" -> "\uD83D\uDE1E"
        "dramatic gasp" -> "\uD83D\uDE31"
        "slide whistle up" -> "\uD83C\uDFB6"
        "slide whistle down" -> "\uD83D\uDE2D"
        "boing" -> "\uD83E\uDD2A"
        "record scratch" -> "\uD83D\uDCBF"
        "crickets" -> "\uD83E\uDD97"
        "rimshot ba-dum", "rimshot" -> "\uD83E\uDD41"
        "victory fanfare", "victory" -> "\uD83C\uDFC6"
        "wrong number" -> "\uD83D\uDCDE"
        "suspense sting", "suspense" -> "\uD83D\uDD75\uFE0F"
        "hello" -> "\uD83D\uDC4B"
        "beep", "chime" -> "\uD83D\uDD14"
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
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val raw = prefs.getString(KEY_ENTRIES, null)
            if (raw == null && prefs.contains(KEY_LEGACY_V3)) {
                try { prefs.edit().remove(KEY_LEGACY_V3).apply() } catch (_: Exception) { }
                val fresh = defaultEntries()
                save(context, fresh)
                return fresh
            }
            if (raw.isNullOrBlank()) return defaultEntries()
            val arr = JSONArray(raw)
            val out = ArrayList<SoundboardEntry>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val name = o.optString("name", "").trim()
                if (name.isEmpty()) continue
                val kind = o.optString("kind", KIND_FILE).let {
                    if (it == KIND_FILE || it == KIND_TONE) it else KIND_FILE
                }
                var payload = o.optString("payload", "")
                if (kind == KIND_TONE && payload.lowercase() !in FunnySynth.ALL_KEYS) payload = ""
                if (kind == KIND_TONE && payload.isBlank()) {
                    payload = FunnySynth.keyForName(name) ?: "boing"
                }
                out += SoundboardEntry(
                    id = o.optString("id", "").ifBlank { UUID.randomUUID().toString() },
                    name = name,
                    emoji = o.optString("emoji", "").ifBlank { emojiForName(name) },
                    kind = kind,
                    payload = payload,
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

object FunnySynth {
    val ALL_KEYS: Set<String> = setOf(
        "airhorn", "trombone", "gasp", "slide_up", "slide_down", "boing",
        "scratch", "crickets", "rimshot", "fanfare", "buzzer", "sting"
    )

    fun keyForName(name: String): String? = when (name.trim().lowercase()) {
        "airhorn blast", "airhorn" -> "airhorn"
        "sad trombone" -> "trombone"
        "dramatic gasp" -> "gasp"
        "slide whistle up" -> "slide_up"
        "slide whistle down" -> "slide_down"
        "boing" -> "boing"
        "record scratch" -> "scratch"
        "crickets" -> "crickets"
        "rimshot ba-dum", "rimshot" -> "rimshot"
        "victory fanfare", "victory" -> "fanfare"
        "wrong number" -> "buzzer"
        "suspense sting", "suspense" -> "sting"
        else -> null
    }

    private const val SR = 22050

    fun renderPcm(key: String): ShortArray = when (key.lowercase()) {
        "airhorn" -> chord(listOf(466.0, 622.0, 932.0), 0.9, wave = Wave.SAW)
        "trombone" -> trombone()
        "gasp" -> sweep(300.0, 1250.0, 0.32, Wave.SINE, withBreath = true)
        "slide_up" -> sweep(420.0, 1650.0, 0.7, Wave.SINE)
        "slide_down" -> sweep(1600.0, 320.0, 0.7, Wave.SINE)
        "boing" -> boing()
        "scratch" -> scratch()
        "crickets" -> crickets()
        "rimshot" -> rimshot()
        "fanfare" -> fanfare()
        "buzzer" -> square(140.0, 0.55)
        "sting" -> chord(listOf(110.0, 116.5, 220.0), 0.9, wave = Wave.SINE)
        else -> sweep(500.0, 900.0, 0.4, Wave.SINE)
    }

    private enum class Wave { SINE, SAW, SQUARE }

    private fun sample(wave: Wave, phase: Double): Double = when (wave) {
        Wave.SINE -> kotlin.math.sin(phase)
        Wave.SAW -> 2.0 * (phase / (2 * Math.PI) % 1.0) - 1.0
        Wave.SQUARE -> if (kotlin.math.sin(phase) >= 0) 1.0 else -1.0
    }

    private fun toShort(d: Double): Short =
        (d.coerceIn(-1.0, 1.0) * 30000).toInt().toShort()

    private fun chord(freqs: List<Double>, durSec: Double, wave: Wave): ShortArray {
        val n = (SR * durSec).toInt()
        val out = ShortArray(n)
        for (i in 0 until n) {
            val t = i.toDouble() / SR
            val env = (t / 0.02).coerceAtMost(1.0) * (1.0 - t / durSec * 0.55)
            var s = 0.0
            freqs.forEach { f -> s += sample(wave, 2 * Math.PI * f * t) }
            s /= freqs.size
            out[i] = toShort(s * env * 0.9)
        }
        return out
    }

    private fun sweep(f0: Double, f1: Double, durSec: Double, wave: Wave, withBreath: Boolean = false): ShortArray {
        val n = (SR * durSec).toInt()
        val out = ShortArray(n)
        var phase = 0.0
        val rnd = java.util.Random(7)
        for (i in 0 until n) {
            val t = i.toDouble() / SR
            val f = f0 + (f1 - f0) * (t / durSec)
            phase += 2 * Math.PI * f / SR
            val env = kotlin.math.sin(Math.PI * (t / durSec)).coerceAtLeast(0.05)
            var s = sample(wave, phase)
            if (withBreath) s = s * 0.7 + rnd.nextGaussian() * 0.18
            out[i] = toShort(s * env * 0.85)
        }
        return out
    }

    private fun square(freq: Double, durSec: Double): ShortArray {
        val n = (SR * durSec).toInt()
        val out = ShortArray(n)
        for (i in 0 until n) {
            val t = i.toDouble() / SR
            val env = (t / 0.015).coerceAtMost(1.0) * (1.0 - t / durSec * 0.35)
            out[i] = toShort(sample(Wave.SQUARE, 2 * Math.PI * freq * t) * env * 0.55)
        }
        return out
    }

    private fun trombone(): ShortArray {
        val notes = listOf(392.0 to 0.24, 370.0 to 0.24, 349.0 to 0.24, 311.0 to 0.62)
        val total = notes.sumOf { (SR * it.second).toInt() }
        val out = ShortArray(total)
        var idx = 0
        notes.forEach { (f, dur) ->
            val n = (SR * dur).toInt()
            for (i in 0 until n) {
                val t = i.toDouble() / SR
                val vib = 1.0 + 0.03 * kotlin.math.sin(2 * Math.PI * 5.5 * t)
                val env = (t / 0.03).coerceAtMost(1.0) *
                    (1.0 - (i.toDouble() / n) * (if (dur > 0.5) 0.75 else 0.45))
                out[idx++] = toShort(sample(Wave.SAW, 2 * Math.PI * f * vib * t) * env * 0.6)
            }
        }
        return out
    }

    private fun boing(): ShortArray {
        val dur = 0.65
        val n = (SR * dur).toInt()
        val out = ShortArray(n)
        var phase = 0.0
        for (i in 0 until n) {
            val t = i.toDouble() / SR
            val f = (320.0 - 180.0 * (t / dur)) * (1.0 + 0.25 * kotlin.math.sin(2 * Math.PI * 11 * t) * kotlin.math.exp(-t * 4))
            phase += 2 * Math.PI * f / SR
            val env = kotlin.math.exp(-t * 3.2)
            out[i] = toShort(sample(Wave.SINE, phase) * env * 0.9)
        }
        return out
    }

    private fun scratch(): ShortArray {
        val dur = 0.55
        val n = (SR * dur).toInt()
        val out = ShortArray(n)
        val rnd = java.util.Random(21)
        for (i in 0 until n) {
            val t = i.toDouble() / SR
            val seg = (t / dur * 3).toInt() % 2
            val f = if (seg == 0) 900.0 + 1400.0 * (t % 0.18) / 0.18 else 2300.0 - 1400.0 * (t % 0.18) / 0.18
            val tone = kotlin.math.sin(2 * Math.PI * f * t) * 0.5
            val noise = rnd.nextGaussian() * 0.5
            val env = kotlin.math.sin(Math.PI * (t / dur)).coerceAtLeast(0.1)
            out[i] = toShort((tone + noise) * env * 0.55)
        }
        return out
    }

    private fun crickets(): ShortArray {
        val dur = 1.3
        val n = (SR * dur).toInt()
        val out = ShortArray(n)
        for (i in 0 until n) {
            val t = i.toDouble() / SR
            val gate = if ((t * 18) % 1.0 < 0.45) 1.0 else 0.06
            val env = (t / 0.05).coerceAtMost(1.0) * (1.0 - t / dur * 0.4)
            out[i] = toShort(kotlin.math.sin(2 * Math.PI * 4200 * t) * gate * env * 0.5)
        }
        return out
    }

    private fun rimshot(): ShortArray {
        val dur = 0.32
        val n = (SR * dur).toInt()
        val out = ShortArray(n)
        val rnd = java.util.Random(3)
        for (i in 0 until n) {
            val t = i.toDouble() / SR
            val env = kotlin.math.exp(-t * 22)
            val s = rnd.nextGaussian() * 0.6 + kotlin.math.sin(2 * Math.PI * 180 * t) * 0.55
            out[i] = toShort(s * env * 0.9)
        }
        return out
    }

    private fun fanfare(): ShortArray {
        val notes = listOf(523.25 to 0.13, 659.25 to 0.13, 783.99 to 0.13, 1046.5 to 0.45)
        val total = notes.sumOf { (SR * it.second).toInt() }
        val out = ShortArray(total)
        var idx = 0
        notes.forEach { (f, dur) ->
            val n = (SR * dur).toInt()
            for (i in 0 until n) {
                val t = i.toDouble() / SR
                val env = (t / 0.015).coerceAtMost(1.0) * (1.0 - i.toDouble() / n * 0.6)
                val s = (sample(Wave.SQUARE, 2 * Math.PI * f * t) * 0.35 +
                    sample(Wave.SINE, 2 * Math.PI * f * 2 * t) * 0.3 +
                    sample(Wave.SINE, 2 * Math.PI * f * t) * 0.5)
                out[idx++] = toShort(s * env * 0.7)
            }
        }
        return out
    }
}

object SoundboardPlayer {
    @Volatile
    private var mediaPlayer: MediaPlayer? = null
    @Volatile
    private var synthTrack: android.media.AudioTrack? = null
    @Volatile
    var playingId: String? = null
        private set

    fun stop() {
        try { mediaPlayer?.stop() } catch (_: Exception) { }
        try { mediaPlayer?.release() } catch (_: Exception) { }
        mediaPlayer = null
        try { synthTrack?.stop() } catch (_: Exception) { }
        try { synthTrack?.release() } catch (_: Exception) { }
        synthTrack = null
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
        try {
            val id = entry.id
            val key = entry.payload.lowercase().ifBlank { FunnySynth.keyForName(entry.name) ?: "boing" }
            val pcm = try { FunnySynth.renderPcm(key) } catch (_: Exception) { null }
            if (pcm == null || pcm.isEmpty()) {
                if (playingId == id) { stop(); finish(wasSpeakerOn, onDone) }
                return
            }
            playPcmWav(appContext, entry, pcm, wasSpeakerOn, onDone)
        } catch (_: Exception) {
            stop()
            finish(wasSpeakerOn, onDone)
        }
    }

    private fun playPcmWav(
        appContext: Context,
        entry: SoundboardEntry,
        pcm: ShortArray,
        wasSpeakerOn: Boolean,
        onDone: (() -> Unit)?,
    ) {
        val id = entry.id
        try {
            val sr = 22050
            val vol = entry.volume.coerceIn(0f, 1f)
            val scaled = ShortArray(pcm.size) { i -> (pcm[i] * vol).toInt().toShort() }
            val track = android.media.AudioTrack.Builder()
                .setAudioAttributes(
                    android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    android.media.AudioFormat.Builder()
                        .setEncoding(android.media.AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sr)
                        .setChannelMask(android.media.AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes((scaled.size * 2).coerceAtLeast(4096))
                .setTransferMode(android.media.AudioTrack.MODE_STATIC)
                .build()
            track.write(scaled, 0, scaled.size)
            try { synthTrack?.release() } catch (_: Exception) { }
            synthTrack = track
            track.setPlaybackPositionUpdateListener(object : android.media.AudioTrack.OnPlaybackPositionUpdateListener {
                override fun onMarkerReached(t: android.media.AudioTrack?) {
                    if (playingId == id) { stop(); finish(wasSpeakerOn, onDone) }
                }
                override fun onPeriodicNotification(t: android.media.AudioTrack?) { }
            })
            track.setNotificationMarkerPosition(scaled.size)
            track.play()
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
