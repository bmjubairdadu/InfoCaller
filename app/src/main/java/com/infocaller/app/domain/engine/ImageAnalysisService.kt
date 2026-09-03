package com.infocaller.app.domain.engine

import android.content.Context
import android.graphics.*
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.infocaller.app.domain.model.PhotoCandidate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.InputStream

/**
 * ImageAnalysisService performs face detection and computes real quality metrics.
 */
class ImageAnalysisService(private val context: Context) : IImageAnalysisService {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    private val detectorOptions = FaceDetectorOptions.Builder()
        .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
        .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
        .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
        .setMinFaceSize(0.1f)
        .build()

    private val detector = FaceDetection.getClient(detectorOptions)

    override suspend fun analyze(candidate: PhotoCandidate): PhotoCandidate = withContext(Dispatchers.IO) {
        try {
            val bitmap = downloadBitmap(candidate.url) ?: return@withContext candidate.copy(faceCount = 0, faceConfidence = 0f)
            val image = InputImage.fromBitmap(bitmap, 0)
            val faces = detector.process(image).await()
            val sharpness = computeLaplacianVariance(bitmap)
            if (faces.isEmpty()) return@withContext candidate.copy(faceCount = 0, imageQuality = sharpness, width = bitmap.width, height = bitmap.height, faceConfidence = 0f, faceCoverage = 0f)
            val mainFace = faces.maxByOrNull { it.boundingBox.width() * it.boundingBox.height() }!!
            val faceArea = mainFace.boundingBox.width() * mainFace.boundingBox.height()
            val imageArea = bitmap.width * bitmap.height
            val coverage = faceArea.toFloat() / imageArea
            // Clear face requires coverage 2%-60% and sharpness 0.15+ ; otherwise low confidence
            val detectionConfidence = when {
                coverage < 0.02f || coverage > 0.6f -> 0.4f
                sharpness < 0.15f -> 0.45f
                else -> 0.95f
            }
            return@withContext candidate.copy(width = bitmap.width, height = bitmap.height, faceCount = faces.size, faceConfidence = detectionConfidence, faceCoverage = coverage, imageQuality = sharpness, timestamp = System.currentTimeMillis())
        } catch (e: Exception) {
            Log.e("ImageAnalysis", "Analysis failed for ${candidate.url}: ${e.message}")
            candidate
        }
    }

    private fun downloadBitmap(url: String): Bitmap? {
        return try {
            val request = Request.Builder().url(url).build()
            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) return null
            val inputStream: InputStream = response.body?.byteStream() ?: return null
            BitmapFactory.decodeStream(inputStream)
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
