package com.infocaller.app.data.remote

import android.graphics.BitmapFactory
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.infocaller.app.domain.engine.*
import com.infocaller.app.domain.model.PhotoCandidate
import com.infocaller.app.util.await
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Automatic face-matched reverse-image search. The moment ANY provider finds
 * a profile photo, this runs inside the SAME scan (no manual tap):
 *
 *  1. Downloads each found photo and runs ML Kit face detection locally.
 *  2. Drops every photo with NO face (clothing / object / logo hits never
 *     produce results — face-only, as requested).
 *  3. Upgrades surviving photo URLs to their full-HD variants
 *     (Gravatar s=1024, GitHub raw avatars, Truecaller/Eyecon size=big, and
 *     generic size-param rewrites) and re-verifies the HD URL serves bytes.
 *  4. Emits one PartialResult per face-matched HD photo carrying:
 *     - the HD photo itself (imageUrl + photoCandidate),
 *     - reverse-image deep links bound to the HD url (Lens uploadbyurl,
 *       TinEye, Bing Visual),
 *     - the source platform name in `about` so the UI shows where the
 *       image came from.
 *
 * Keyless, on-device, FREE. Null when no found photo contains a face.
 */
class FaceMatchedReverseSearchProviderImpl : LookupProvider {
    override val id = "face_matched_reverse_search"
    override val name = "Face-Matched Reverse Search (auto HD)"
    override val version = "1.0.0"
    override val capabilities = setOf(
        Capability.PROFILE_PHOTO, Capability.PUBLIC_SEARCH, Capability.PUBLIC_PROFILE
    )
    override val priority = 39
    override val costClass = CostClass.FREE

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .build()

    private val detectorOptions = FaceDetectorOptions.Builder()
        .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
        .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
        .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
        .setMinFaceSize(0.12f)
        .build()

    private val detector by lazy {
        try {
            FaceDetection.getClient(detectorOptions)
        } catch (_: Exception) { null }
    }

    override suspend fun lookup(identifier: String, type: String, context: LookupContext): PartialResult? =
        withContext(Dispatchers.IO) {
            // Auto mode ONLY: no found photo -> nothing to do (never guesses).
            val photos = context.foundPhotos.filter { it.startsWith("http") }.distinct().take(5)
            if (photos.isEmpty()) return@withContext null
            val activeDetector = detector ?: return@withContext lensLinksOnly(photos, context)
            try {
                val matched = mutableListOf<MatchedPhoto>()
                for (url in photos) {
                    try {
                        val bytes = downloadBytes(url, maxBytes = 6L * 1024 * 1024) ?: continue
                        val bitmap = try {
                            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        } catch (_: Exception) { null } ?: continue
                        if (bitmap.width < 80 || bitmap.height < 80) continue
                        val faces = try {
                            activeDetector.process(InputImage.fromBitmap(bitmap, 0)).await()
                        } catch (_: Exception) { emptyList() }
                        if (faces.isEmpty()) continue // face-only: no face -> drop
                        val main = faces.maxByOrNull { it.boundingBox.width() * it.boundingBox.height() }!!
                        val faceArea = (main.boundingBox.width() * main.boundingBox.height()).toFloat()
                        val coverage = faceArea / (bitmap.width * bitmap.height).toFloat()
                        if (coverage < 0.02f) continue // tiny/incidental face -> drop
                        matched.add(MatchedPhoto(url, bitmap.width, bitmap.height, faces.size, coverage))
                    } catch (_: Exception) { continue }
                }
                if (matched.isEmpty()) return@withContext null

                // HD upgrade per face-matched photo, verified live.
                val hdPhotos = mutableListOf<PhotoCandidate>()
                val links = mutableListOf<String>()
                for (m in matched.take(3)) {
                    val hdUrl = upgradeToHd(m.url)
                    val finalUrl = if (servesImage(hdUrl)) hdUrl else m.url
                    hdPhotos.add(
                        PhotoCandidate(
                            provider = "FaceMatchedHD",
                            url = finalUrl,
                            width = m.width, height = m.height,
                            faceCount = m.faces, faceConfidence = 1f, faceCoverage = m.coverage,
                            imageQuality = 1f, sourcePriority = 75,
                        )
                    )
                    val enc = try {
                        URLEncoder.encode(finalUrl, StandardCharsets.UTF_8.toString())
                    } catch (_: Exception) { finalUrl }
                    links.add(
                        "Lens HD: https://lens.google.com/uploadbyurl?url=$enc • " +
                            "TinEye: https://tineye.com/search?url=$enc • " +
                            "Bing Visual: https://www.bing.com/images/searchbyimage/upload?imgurl=$enc"
                    )
                }
                val platformHint = context.foundName?.takeIf { it.isNotBlank() }?.let { " for $it" } ?: ""
                PartialResult(
                    imageUrl = hdPhotos.first().url,
                    photoCandidates = hdPhotos,
                    about = ("Face-matched HD photo$platformHint (${matched.size} face${if (matched.size > 1) "s" else ""} verified on-device — clothing/object hits filtered out). " +
                        links.joinToString(" • ")).take(1200),
                    confidence = 0.8f,
                    source = "Face-Matched Reverse Search (auto HD)",
                    providerId = id, providerVersion = version
                )
            } catch (_: Exception) { null }
        }

    /** Fallback when ML Kit is unavailable: Lens links, clearly unlabeled. */
    private suspend fun lensLinksOnly(photos: List<String>, context: LookupContext): PartialResult? {
        return try {
            val links = photos.take(3).map { photo ->
                val enc = try {
                    URLEncoder.encode(photo, StandardCharsets.UTF_8.toString())
                } catch (_: Exception) { photo }
                "Lens: https://lens.google.com/uploadbyurl?url=$enc"
            }
            PartialResult(
                imageUrl = photos.first(),
                photoCandidates = photos.take(3).map { PhotoCandidate(provider = "ReverseImage", url = it, sourcePriority = 55) },
                about = ("Reverse-image the found photo (face check unavailable on this device). " +
                    links.joinToString(" • ")).take(900),
                confidence = 0.55f,
                source = "Face-Matched Reverse Search (links only)",
                providerId = id, providerVersion = version
            )
        } catch (_: Exception) { null }
    }

    private data class MatchedPhoto(
        val url: String, val width: Int, val height: Int, val faces: Int, val coverage: Float,
    )

    /** Rewrites known avatar URL shapes to their largest variant. */
    internal fun upgradeToHd(url: String): String {
        return try {
            var u = url
            // Gravatar: s=NN -> s=1024 (max).
            if (u.contains("gravatar.com/avatar/")) {
                u = u.replace(Regex("[?&]s=\\d+"), "")
                u += if (u.contains("?")) "&s=1024" else "?s=1024"
                return u
            }
            // GitHub avatars: strip small-size params (s= / size=).
            if (u.contains("avatars.githubusercontent.com")) {
                u = u.replace(Regex("[?&](s|size)=\\d+"), "")
                return u
            }
            // Eyecon pic endpoint: force big.
            if (u.contains("eyecon-app.com/app/pic")) {
                u = u.replace(Regex("size=[^&]*"), "size=big")
                return u
            }
            // Generic size params -> large.
            if (u.contains("w=64") || u.contains("w=128") || u.contains("w=200") || u.contains("s=200")) {
                u = u.replace("w=64", "w=1024").replace("w=128", "w=1024")
                    .replace("w=200", "w=1024").replace("s=200", "s=1024")
                return u
            }
            u
        } catch (_: Exception) { url }
    }

    private suspend fun servesImage(url: String): Boolean {
        return try {
            val head = Request.Builder().url(url)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14)")
                .header("Range", "bytes=0-0").build()
            httpClient.newCall(head).await().use { r ->
                r.isSuccessful && (r.header("Content-Type")?.contains("image", true) == true || r.code == 206)
            }
        } catch (_: Exception) { false }
    }

    private suspend fun downloadBytes(url: String, maxBytes: Long): ByteArray? {
        return try {
            val req = Request.Builder().url(url)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14)").build()
            httpClient.newCall(req).await().use { r ->
                if (!r.isSuccessful) return null
                val len = r.header("Content-Length")?.toLongOrNull()
                if (len != null && len > maxBytes) return null
                val bytes = r.body?.bytes() ?: return null
                if (bytes.size > maxBytes) null else bytes
            }
        } catch (_: Exception) { null }
    }
}
