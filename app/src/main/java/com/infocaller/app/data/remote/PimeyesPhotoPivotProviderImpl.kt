package com.infocaller.app.data.remote

import com.infocaller.app.domain.engine.*
import com.infocaller.app.domain.model.PhotoCandidate
import com.infocaller.app.domain.model.SocialLookupStatus
import com.infocaller.app.domain.model.SocialProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup

class PimeyesPhotoPivotProviderImpl(private val httpClient: OkHttpClient) : LookupProvider {
    override val id = "pimeyes_photo_pivot"
    override val name = "Face-Search Photo Pivot"
    override val version = "1.0.0"
    override val capabilities = setOf(Capability.PROFILE_PHOTO, Capability.SOCIAL_MATCH, Capability.PUBLIC_SEARCH)
    override val priority = 36
    override val costClass = CostClass.FREE

    override suspend fun lookup(identifier: String, type: String, context: LookupContext): PartialResult? = withContext(Dispatchers.IO) {
        try {
            val autoPhotos = context.foundPhotos.filter { it.startsWith("http") }.distinct().take(3)
            // No photo, no pivot: generic Pimeyes links are noise (see ReverseImage gate).
            if (autoPhotos.isEmpty()) return@withContext null
            val about = buildString {
                if (autoPhotos.isNotEmpty()) {
                    append("Face-search the caller's found photo (${autoPhotos.size}): ")
                    autoPhotos.forEachIndexed { i, photo ->
                        val enc = try { java.net.URLEncoder.encode(photo, "UTF-8") } catch (_: Exception) { photo }
                        if (i > 0) append(" • ")
                        append("Lens photo${i + 1} https://lens.google.com/uploadbyurl?url=$enc")
                    }
                    append(" • Pimeyes https://pimeyes.com • FaceCheck https://facecheck.id. ")
                    append("Same face on 2+ public profiles = strong match.")
                } else {
                    append("Run a face search on the caller's photo: ")
                    append("Pimeyes https://pimeyes.com • FaceCheck https://facecheck.id • ")
                    append("Google Lens https://lens.google.com/v3/upload. ")
                    append("Same face on 2+ public profiles = strong match.")
                }
            }
            PartialResult(
                imageUrl = autoPhotos.firstOrNull(),
                photoCandidates = autoPhotos.map { PhotoCandidate(provider = "FaceSearchPivot", url = it, sourcePriority = 52) },
                about = about.take(900),
                confidence = if (autoPhotos.isNotEmpty()) 0.6f else 0.4f,
                source = "Face-Search Pivot (Pimeyes/FaceCheck/Lens${if (autoPhotos.isNotEmpty()) " — auto photo" else ""})",
                providerId = id, providerVersion = version
            )
        } catch (_: Exception) { null }
    }
}
