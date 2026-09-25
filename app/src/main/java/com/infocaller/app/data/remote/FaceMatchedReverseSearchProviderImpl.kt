package com.infocaller.app.data.remote

import com.infocaller.app.domain.engine.*
import com.infocaller.app.domain.model.PhotoCandidate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

class FaceMatchedReverseSearchProviderImpl : LookupProvider {
    override val id = "face_matched_reverse_search"
    override val name = "Face-Matched Reverse Search (auto HD)"
    override val version = "1.0.0"
    override val capabilities = setOf(
        Capability.PROFILE_PHOTO, Capability.PUBLIC_SEARCH, Capability.PUBLIC_PROFILE
    )
    override val priority = 39
    override val costClass = CostClass.FREE

    override suspend fun lookup(identifier: String, type: String, context: LookupContext): PartialResult? =
        withContext(Dispatchers.IO) {
            val photos = context.foundPhotos.filter { it.startsWith("http") }.distinct().take(5)
            if (photos.isEmpty()) return@withContext null
            return@withContext lensLinksOnly(photos, context)
        }

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
}
