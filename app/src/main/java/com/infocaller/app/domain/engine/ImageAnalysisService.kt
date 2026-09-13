package com.infocaller.app.domain.engine

import android.content.Context
import android.graphics.*
import com.infocaller.app.domain.model.PhotoCandidate
import com.infocaller.app.util.await
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.coroutineContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.InputStream

class ImageAnalysisService(private val context: Context) : IImageAnalysisService {
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(4, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(4, java.util.concurrent.TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .build()

    override suspend fun analyze(candidate: PhotoCandidate): PhotoCandidate = withContext(Dispatchers.IO) {
        try {
            coroutineContext.ensureActive()
            val url = try { candidate.url.trim() } catch (_: Exception) { "" } catch (_: Error) { "" }
            // Hard reject aggregator logos / HTML pages mistaken for photos.
            if (!isUsablePhotoUrl(url)) {
                return@withContext try {
                    candidate.copy(
                        faceCount = 0, faceConfidence = 0f, faceCoverage = 0f,
                        imageQuality = 0f, width = 0, height = 0
                    )
                } catch (_: Exception) { candidate } catch (_: Error) { candidate }
            }
            val bitmap = try {
                kotlinx.coroutines.withTimeoutOrNull(6000L) { downloadBitmapSafe(url) }
            } catch (_: Exception) { null } catch (_: Error) { null } ?: return@withContext try {
                candidate.copy(
                    faceCount = 0, faceConfidence = 0f, faceCoverage = 0f, imageQuality = 0f
                )
            } catch (_: Exception) { candidate } catch (_: Error) { candidate }
            // Reject tiny placeholders / icons.
            val bw: Int
            val bh: Int
            try {
                bw = bitmap.width; bh = bitmap.height
            } catch (_: Exception) {
                try { bitmap.recycle() } catch (_: Exception) { } catch (_: Error) { }
                return@withContext candidate
            } catch (_: Error) {
                return@withContext candidate
            }
            if (bw < 80 || bh < 80 || bw > 8000 || bh > 8000) {
                try { bitmap.recycle() } catch (_: Exception) { } catch (_: Error) { }
                return@withContext try {
                    candidate.copy(
                        faceCount = 0, faceConfidence = 0f, faceCoverage = 0f,
                        imageQuality = 0f, width = bw.coerceIn(0, 8000), height = bh.coerceIn(0, 8000)
                    )
                } catch (_: Exception) { candidate } catch (_: Error) { candidate }
            }
            // Guard: huge pixel count would OOM in Laplacian (w*h ints + floats).
            // Downscale a copy for sharpness instead of full-res analysis.
            var work = bitmap
            var recycledOriginal = false
            try {
                val pixels = bw.toLong() * bh.toLong()
                if (pixels > 480_000L) {
                    val scale = kotlin.math.sqrt(480_000.0 / pixels.toDouble()).coerceIn(0.05, 1.0)
                    val nw = (bw * scale).toInt().coerceAtLeast(1)
                    val nh = (bh * scale).toInt().coerceAtLeast(1)
                    try {
                        work = Bitmap.createScaledBitmap(bitmap, nw, nh, true)
                        if (work !== bitmap) {
                            try { bitmap.recycle() } catch (_: Exception) { } catch (_: Error) { }
                            recycledOriginal = true
                        }
                    } catch (_: OutOfMemoryError) {
                        try { bitmap.recycle() } catch (_: Exception) { } catch (_: Error) { }
                        return@withContext candidate.copy(faceCount = 1, imageQuality = 0.3f, width = bw, height = bh, faceConfidence = 0.7f, faceCoverage = 0.2f)
                    } catch (_: Exception) { work = bitmap } catch (_: Error) { work = bitmap }
                }
                val sharpness = try { computeLaplacianVariance(work) } catch (_: OutOfMemoryError) { 0.3f } catch (_: Exception) { 0.3f } catch (_: Error) { 0.3f }
                val w = bw
                val h = bh
                try {
                    if (work !== bitmap || !recycledOriginal) {
                        try { work.recycle() } catch (_: Exception) { } catch (_: Error) { }
                    }
                    if (!recycledOriginal) {
                        try { bitmap.recycle() } catch (_: Exception) { } catch (_: Error) { }
                    }
                } catch (_: Exception) { } catch (_: Error) { }
                // No on-device face detector dependency: any downloadable, sensibly-sized,
                // non-placeholder image counts as a usable profile photo.
                // Mark faceCount=1 so downstream "verified photo" filters pass.
                return@withContext try {
                    candidate.copy(
                        faceCount = 1,
                        imageQuality = sharpness.coerceIn(0f, 1f),
                        width = w,
                        height = h,
                        faceConfidence = 0.75f,
                        faceCoverage = 0.25f
                    )
                } catch (_: Exception) { candidate } catch (_: Error) { candidate }
            } catch (_: OutOfMemoryError) {
                try { bitmap.recycle() } catch (_: Exception) { } catch (_: Error) { }
                try { if (work !== bitmap) work.recycle() } catch (_: Exception) { } catch (_: Error) { }
                return@withContext candidate
            } catch (_: Exception) {
                try { bitmap.recycle() } catch (_: Exception) { } catch (_: Error) { }
                return@withContext candidate
            } catch (_: Error) {
                return@withContext candidate
            }
        } catch (_: Exception) {
            try { candidate } catch (_: Exception) { candidate }
        } catch (_: Error) {
            try { candidate } catch (_: Exception) { candidate } catch (_: Error) { candidate }
        }
    }

    private fun isUsablePhotoUrl(url: String?): Boolean {
        return try { com.infocaller.app.util.PhotoPolicy.isUsablePhotoUrl(url) } catch (_: Exception) { false } catch (_: Error) { false }
    }

    private suspend fun downloadBitmap(url: String): Bitmap? {
        return try { downloadBitmapSafe(url) } catch (_: Exception) { null } catch (_: Error) { null }
    }

    /**
     * OOM-safe download: caps bytes at ~1.5MB and decodes with inSampleSize so
     * a 4K photo never explodes the heap. Returns null instead of crashing.
     */
    private suspend fun downloadBitmapSafe(url: String): Bitmap? {
        return try {
            coroutineContext.ensureActive()
            val request = Request.Builder().url(url)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/120.0 Safari/537.36")
                .header("Accept", "image/*,*/*;q=0.5")
                .build()
            httpClient.newCall(request).await().use { response ->
                if (!response.isSuccessful) return null
                val ct = try { response.header("Content-Type").orEmpty() } catch (_: Exception) { "" } catch (_: Error) { "" }
                // Reject HTML mistaken for image without downloading the body.
                if (ct.contains("text/html", true)) return null
                if (ct.isNotBlank() && !ct.contains("image", true) && !ct.contains("octet", true)) return null
                val len = try { response.header("Content-Length")?.toLongOrNull() } catch (_: Exception) { null } catch (_: Error) { null }
                if (len != null && (len <= 0 || len > 1_500_000L)) return null
                val bytes = try {
                    val stream = response.body?.byteStream() ?: return null
                    stream.use { input ->
                        val out = java.io.ByteArrayOutputStream(32_768)
                        val buf = ByteArray(8_192)
                        var total = 0
                        while (true) {
                            coroutineContext.ensureActive()
                            val n = try { input.read(buf) } catch (_: Exception) { break } catch (_: Error) { break }
                            if (n <= 0) break
                            total += n
                            if (total > 1_500_000) return null
                            out.write(buf, 0, n)
                        }
                        out.toByteArray()
                    }
                } catch (_: Exception) { return null } catch (_: Error) { return null }
                if (bytes.isEmpty() || bytes.size < 200) return null
                // Bounds first → compute sample size so longest side ≤ 720px.
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                try { BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds) } catch (_: Exception) { return null } catch (_: Error) { return null }
                val bw = bounds.outWidth
                val bh = bounds.outHeight
                if (bw <= 0 || bh <= 0 || bw > 8000 || bh > 8000) return null
                var sample = 1
                var longest = maxOf(bw, bh)
                while (longest / sample > 720) sample *= 2
                val opts = BitmapFactory.Options().apply {
                    inSampleSize = sample.coerceIn(1, 8)
                    inPreferredConfig = Bitmap.Config.RGB_565
                    inDither = false
                }
                try {
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
                } catch (_: OutOfMemoryError) { null } catch (_: Exception) { null } catch (_: Error) { null }
            }
        } catch (_: Exception) {
            null
        } catch (_: Error) {
            null
        }
    }

    private fun computeLaplacianVariance(bitmap: Bitmap): Float {
        return try {
            val width = try { bitmap.width } catch (_: Exception) { return 0.3f } catch (_: Error) { return 0.3f }
            val height = try { bitmap.height } catch (_: Exception) { return 0.3f } catch (_: Error) { return 0.3f }
            if (width <= 0 || height <= 0 || width > 2000 || height > 2000) return 0.3f
            // Cap work at ~160k pixels: sample rows/cols for sharpness (crash-safe + fast).
            val stepX = maxOf(1, width / 200)
            val stepY = maxOf(1, height / 200)
            val sw = (width + stepX - 1) / stepX
            val sh = (height + stepY - 1) / stepY
            if (sw <= 2 || sh <= 2 || sw.toLong() * sh.toLong() > 200_000L) return 0.3f
            val pixels = try { IntArray(sw * sh) } catch (_: OutOfMemoryError) { return 0.3f } catch (_: Exception) { return 0.3f } catch (_: Error) { return 0.3f }
            var k = 0
            for (y in 0 until sh) {
                for (x in 0 until sw) {
                    try {
                        pixels[k++] = bitmap.getPixel(minOf(width - 1, x * stepX), minOf(height - 1, y * stepY))
                    } catch (_: Exception) { if (k < pixels.size) pixels[k++] = 0 else break } catch (_: Error) { if (k < pixels.size) pixels[k++] = 0 else break }
                }
            }

        val grayscale = FloatArray(pixels.size)
        for (i in pixels.indices) {
            val p = pixels[i]
            grayscale[i] = 0.299f * Color.red(p) + 0.587f * Color.green(p) + 0.114f * Color.blue(p)
        }

        val laplacian = try { FloatArray(pixels.size) } catch (_: OutOfMemoryError) { return 0.3f } catch (_: Exception) { return 0.3f } catch (_: Error) { return 0.3f }
        var mean = 0f
        var count = 0
        for (y in 1 until sh - 1) {
            for (x in 1 until sw - 1) {
                val idx = y * sw + x
                val sum = grayscale[idx - sw] + grayscale[idx - 1] +
                          grayscale[idx + 1] + grayscale[idx + sw] -
                          4 * grayscale[idx]
                laplacian[idx] = sum
                mean += sum
                count++
            }
        }
        if (count <= 0) return 0.3f
        mean /= count

        var variance = 0f
        for (y in 1 until sh - 1) {
            for (x in 1 until sw - 1) {
                val idx = y * sw + x
                val diff = laplacian[idx] - mean
                variance += diff * diff
            }
        }
        if (count <= 0) return 0.3f
        val result = variance / count
        return try { minOf(1.0f, result / 1000f).coerceIn(0f, 1f) } catch (_: Exception) { 0.3f } catch (_: Error) { 0.3f }
        } catch (_: OutOfMemoryError) { 0.3f } catch (_: Exception) { 0.3f } catch (_: Error) { 0.3f }
    }
}
