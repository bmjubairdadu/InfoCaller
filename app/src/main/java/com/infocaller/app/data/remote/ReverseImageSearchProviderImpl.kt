package com.infocaller.app.data.remote

import com.infocaller.app.domain.engine.*
import com.infocaller.app.domain.model.PhotoCandidate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

class ReverseImageSearchProviderImpl(private val httpClient: OkHttpClient) : LookupProvider {
    override val id = "reverse_image_search"
    override val name = "Reverse Image Search"
    override val version = "1.0.0"
    override val capabilities = setOf(Capability.PROFILE_PHOTO, Capability.PUBLIC_SEARCH, Capability.PUBLIC_PROFILE)
    override val priority = 38
    override val costClass = CostClass.FREE

    private fun isTrustedSeed(url: String): Boolean {
        return try {
            val lower = url.trim().lowercase()
            if (lower.isBlank()) return false
            // Only identity-grade photos seed reverse-image search: Truecaller pic,
            // database pic, email pics (Gravatar/GitHub/GitLab/Unavatar), Eyecon cache.
            if (lower.startsWith("file://")) return lower.contains("eyecon") || lower.contains("manual")
            if (!lower.startsWith("http")) return false
            lower.contains("gravatar.com/avatar") || lower.contains("secure.gravatar.com") ||
                lower.contains("avatars.githubusercontent.com") || lower.contains("unavatar.io") ||
                lower.contains("github.com") || lower.contains("gitlab.com") ||
                lower.contains("eyecon") || lower.contains("truecaller")
        } catch (_: Exception) { false }
    }

    override suspend fun lookup(identifier: String, type: String, context: LookupContext): PartialResult? = withContext(Dispatchers.IO) {
        try {
            // Prefer engine-tagged trusted photos (Truecaller / database / email pics);
            // fall back to filtering raw found photos so random social thumbnails
            // never seed reverse-image matches.
            val trustedCtx = try { context.trustedPhotos.filter { it.isNotBlank() }.distinct().take(3) } catch (_: Exception) { emptyList() } catch (_: Error) { emptyList() }
            val autoPhotos = if (trustedCtx.isNotEmpty()) {
                trustedCtx
            } else {
                try { context.foundPhotos.filter { isTrustedSeed(it) }.distinct().take(3) } catch (_: Exception) { emptyList() } catch (_: Error) { emptyList() }
            }
            if (autoPhotos.isNotEmpty()) {
                val httpSeeds = autoPhotos.filter { it.startsWith("http") }.take(3)
                val links = httpSeeds.flatMap { photo ->
                    val enc = URLEncoder.encode(photo, StandardCharsets.UTF_8.toString())
                    listOf(
                        "Google Lens: https://lens.google.com/uploadbyurl?url=$enc",
                        "TinEye: https://tineye.com/search?url=$enc",
                        "Bing Visual: https://www.bing.com/images/searchbyimage/upload?imgurl=$enc"
                    )
                }.toMutableList()
                if (links.isEmpty()) {
                    links.add("Google Lens upload: https://lens.google.com/v3/upload (pick the caller's photo)")
                }
                val about = buildString {
                    append("Reverse-image the caller's verified photo (${autoPhotos.size} photo${if (autoPhotos.size > 1) "s" else ""}, Truecaller/database/email only) to find more accounts. ")
                    append(links.joinToString(" • "))
                }
                return@withContext PartialResult(
                    imageUrl = httpSeeds.firstOrNull() ?: autoPhotos.first(),
                    photoCandidates = autoPhotos.map { PhotoCandidate(provider = "ReverseImage", url = it, sourcePriority = 55) },
                    about = about.take(900),
                    confidence = 0.65f,
                    source = "Reverse Image Search (Lens/TinEye/Bing — verified photo)",
                    providerId = id, providerVersion = version
                )
            }
            if (type == IdentifierType.PHONE) return@withContext null
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
                    seedPhoto = "https://github.com/$u.png"
                    name = u
                }
                IdentifierType.PHONE -> {
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
