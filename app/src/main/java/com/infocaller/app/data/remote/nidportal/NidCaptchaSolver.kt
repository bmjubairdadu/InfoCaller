package com.infocaller.app.data.remote.nidportal

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Automatic captcha solver for the NID portal's simple 5-character image
 * captcha (white glyphs on dark noisy background, e.g. "nd64x").
 *
 * Pipeline (all on-device, ML Kit text-recognition — already a project dep):
 *  1. Upscale 2x + grayscale + adaptive threshold (white text isolation).
 *  2. ML Kit latin OCR, first 5 alphanumerics wins.
 *  3. Up to [maxAttempts] fresh captchas per session; the caller retries
 *     validate with each fresh reading.
 *
 * Returns null when OCR is unavailable or unreadable — callers then fall
 * back to showing the captcha, never to a wrong guess loop.
 */
object NidCaptchaSolver {

    private val recognizer by lazy {
        try {
            TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        } catch (_: Exception) { null }
    }

    /** Downloads bytes already fetched by [NidPortalService]; solves to text. */
    suspend fun solve(pngBytes: ByteArray): String? = withContext(Dispatchers.Default) {
        try {
            val rec = recognizer ?: return@withContext null
            val bmp = try {
                BitmapFactory.decodeByteArray(pngBytes, 0, pngBytes.size)
            } catch (_: Exception) { null } ?: return@withContext null
            // Try raw first (fast path), then preprocessed variants.
            val candidates: List<(Bitmap) -> Bitmap> = listOf(
                { b -> b },
                { b -> isolateWhiteText(b) },
                { b -> isolateWhiteText(b, threshold = 100) },
            )
            for (prep in candidates) {
                try {
                    val processed = try { prep(bmp) } catch (_: Exception) { continue }
                    val text = withTimeoutOrNull(8_000L) {
                        rec.process(InputImage.fromBitmap(processed, 0)).await().text
                    } ?: continue
                    clean(text)?.let { return@withContext it }
                } catch (_: Exception) { continue }
            }
            null
        } catch (_: Exception) { null }
    }

    /** Keeps the 5-char captcha reading; null when nothing captcha-shaped. */
    internal fun clean(raw: String): String? {
        val alnum = raw.filter { it.isLetterOrDigit() }.lowercase()
        if (alnum.isEmpty()) return null
        // Portal captchas are exactly 5 [a-z0-9] chars.
        if (alnum.length == 5) return alnum
        if (alnum.length > 5) {
            // OCR sometimes merges noise: prefer the 5-char window with the
            // most letters (captchas mix letters+digits, noise is digit-heavy).
            var best: String? = null
            var bestLetters = -1
            for (i in 0..alnum.length - 5) {
                val w = alnum.substring(i, i + 5)
                val letters = w.count { it.isLetter() }
                if (letters > bestLetters) { bestLetters = letters; best = w }
            }
            if (bestLetters >= 1) return best
        }
        // Short reading (4 chars): accept only if it has a letter (digit-only
        // fragments are almost always noise halves).
        if (alnum.length == 4 && alnum.any { it.isLetter() }) return alnum
        return null
    }

    /**
     * White-glyph isolation: upscale 2x, grayscale, threshold — the portal
     * renders near-white text on a dark noisy background, so a high
     * threshold keeps glyphs and drops most background speckle.
     */
    internal fun isolateWhiteText(src: Bitmap, threshold: Int = 140): Bitmap {
        val w = src.width
        val h = src.height
        val big = Bitmap.createScaledBitmap(src, w * 2, h * 2, true)
        val out = Bitmap.createBitmap(big.width, big.height, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(big.width * big.height)
        big.getPixels(pixels, 0, big.width, 0, 0, big.width, big.height)
        for (i in pixels.indices) {
            val p = pixels[i]
            val lum = (0.299 * Color.red(p) + 0.587 * Color.green(p) + 0.114 * Color.blue(p)).toInt()
            pixels[i] = if (lum >= threshold) Color.WHITE else Color.BLACK
        }
        out.setPixels(pixels, 0, big.width, 0, 0, big.width, big.height)
        return out
    }
}
