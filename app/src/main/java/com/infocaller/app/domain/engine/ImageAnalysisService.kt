package com.infocaller.app.domain.engine

import android.content.Context
import android.graphics.*
import com.infocaller.app.domain.model.PhotoCandidate
import com.infocaller.app.util.await
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
            val bitmap = downloadBitmap(candidate.url) ?: return@withContext candidate
            val sharpness = computeLaplacianVariance(bitmap)
            return@withContext candidate.copy(
                faceCount = 0,
                imageQuality = sharpness,
                width = bitmap.width,
                height = bitmap.height,
                faceConfidence = 0f,
                faceCoverage = 0f
            )
        } catch (_: Exception) {
            candidate
        }
    }

    private suspend fun downloadBitmap(url: String): Bitmap? {
        return try {
            val request = Request.Builder().url(url).build()
            httpClient.newCall(request).await().use { response ->
                if (!response.isSuccessful) return null
                response.body?.byteStream()?.use { inputStream ->
                    BitmapFactory.decodeStream(inputStream)
                }
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun computeLaplacianVariance(bitmap: Bitmap): Float {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        val grayscale = FloatArray(pixels.size)
        for (i in pixels.indices) {
            val p = pixels[i]
            grayscale[i] = 0.299f * Color.red(p) + 0.587f * Color.green(p) + 0.114f * Color.blue(p)
        }

        val laplacian = FloatArray(pixels.size)
        var mean = 0f
        var count = 0
        for (y in 1 until height - 1) {
            for (x in 1 until width - 1) {
                val idx = y * width + x
                val sum = grayscale[idx - width] + grayscale[idx - 1] +
                          grayscale[idx + 1] + grayscale[idx + width] -
                          4 * grayscale[idx]
                laplacian[idx] = sum
                mean += sum
                count++
            }
        }
        mean /= count

        var variance = 0f
        for (y in 1 until height - 1) {
            for (x in 1 until width - 1) {
                val idx = y * width + x
                val diff = laplacian[idx] - mean
                variance += diff * diff
            }
        }
        val result = variance / count
        return minOf(1.0f, result / 1000f)
    }
}
