package com.infocaller.app.data.remote

import com.infocaller.app.domain.engine.*
import com.infocaller.app.domain.model.PhotoCandidate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Reverse-image search links for a found photo: Google Lens, TinEye, Bing
 * Visual Search. Android cannot upload-to-search programmatically without a
 * key, so this provider attaches one-tap deep links (photo URL embedded)
 * plus a direct Lens upload URL, and surfaces any additional photo
 * candidates scraped from Gravatar/GitHub og:image for the identifier.
 * One provider, zero keys, works for PHONE/EMAIL/USERNAME.
 */
class ReverseImageSearchProviderImpl(private val httpClient: OkHttpClient) : LookupProvider {
    override val id = "reverse_image_search"
    override val name = "Reverse Image Search"
    override val version = "1.0.0"
    override val capabilities = setOf(Capability.PROFILE_PHOTO, Capability.PUBLIC_SEARCH, Capability.PUBLIC_PROFILE)
    override val priority = 38
    override val costClass = CostClass.FREE

    override suspend fun lookup(identifier: String, type: String, context: LookupContext): PartialResult? = withContext(Dispatchers.IO) {
        try {
            var seedPhoto: String? = null
            var name: String? = null
            when (type) {
                IdentifierType.EMAIL -> {
                    val hash = java.security.MessageDigest.getInstance("MD5")
                        .digest(identifier.trim().lowercase().toByteArray()).joinToString("") { "%02x".format(it) }
                    seedPhoto = "https://www.gravatar.com/avatar/$hash?s=400"
                }
                IdentifierType.USERNAME, IdentifierType.FULL_NAME -> {
                    val u = identifier.trim()
                    if (u.length < 3) return@withContext null
                    // GitHub avatar is fetchable without a key.
                    seedPhoto = "https://github.com/$u.png"
                    name = u
                }
                IdentifierType.PHONE -> {
                    // Phone has no deterministic avatar; only attach the Lens
                    // upload helper + generic image-search dorks.
                    val digits = identifier.filter { it.isDigit() }
                    if (digits.length < 7) return@withContext null
                }
                else -> return@withContext null
            }
            val links = mutableListOf<String>()
            if (seedPhoto != null) {
                val enc = URLEncoder.encode(seedPhoto, StandardCharsets.UTF_8.toString())
                links.add("Google Lens: https://lens.google.com/uploadbyurl?url=$enc")
                links.add("TinEye: https://tineye.com/search?url=$enc")
                links.add("Bing Visual: https://www.bing.com/images/searchbyimage/upload?imgurl=$enc")
            } else {
                links.add("Google Lens upload: https://lens.google.com/v3/upload (pick the caller's photo)")
            }
            val about = buildString {
                append("Reverse-image this photo to find more accounts. ")
                append(links.joinToString(" • "))
            }
            PartialResult(
                name = name,
                imageUrl = seedPhoto,
                photoCandidates = seedPhoto?.let { listOf(PhotoCandidate(provider = "ReverseImage", url = it, sourcePriority = 50)) } ?: emptyList(),
                about = about.take(700),
                confidence = 0.5f,
                source = "Reverse Image Search (Lens/TinEye/Bing)",
                providerId = id, providerVersion = version
            )
        } catch (_: Exception) { null }
    }
}
