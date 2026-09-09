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

class PinterestMediumProviderImpl(private val httpClient: OkHttpClient) : LookupProvider {
    override val id = "pinterest_medium_profiles"
    override val name = "Pinterest/Medium/Dev Profiles"
    override val version = "1.0.0"
    override val capabilities = setOf(Capability.PUBLIC_PROFILE, Capability.SOCIAL_MATCH, Capability.PROFILE_PHOTO, Capability.ABOUT)
    override val priority = 46
    override val costClass = CostClass.FREE

    private data class Target(val platform: String, val url: String)

    private fun targets(handle: String): List<Target> = listOf(
        Target("Pinterest", "https://www.pinterest.com/$handle/"),
        Target("Medium", "https://medium.com/@$handle"),
        Target("DevTo", "https://dev.to/$handle"),
        Target("Hashnode", "https://hashnode.com/@$handle"),
        Target("Kaggle", "https://www.kaggle.com/$handle"),
    )

    override suspend fun lookup(identifier: String, type: String, context: LookupContext): PartialResult? = withContext(Dispatchers.IO) {
        if (type != IdentifierType.USERNAME && type != IdentifierType.FULL_NAME) return@withContext null
        val handle = identifier.trim().removePrefix("@").lowercase()
            .replace(Regex("[^a-z0-9._-]"), "")
        if (handle.length !in 3..30) return@withContext null
        val names = mutableListOf<String>()
        val abouts = mutableListOf<String>()
        val photos = mutableListOf<PhotoCandidate>()
        val socials = mutableListOf<SocialProfile>()
        for (t in targets(handle)) {
            try {
                val doc = Jsoup.connect(t.url)
                    .userAgent("Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/120.0 Safari/537.36")
                    .timeout(7000).ignoreHttpErrors(true).followRedirects(true).get()
                if (doc.text().contains("page not found", true)) continue
                if (doc.text().contains("user not found", true)) continue
                val title = doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
                val desc = doc.selectFirst("meta[property=og:description]")?.attr("content")?.trim()?.take(300)
                val img = doc.selectFirst("meta[property=og:image]")?.attr("content")?.takeIf { it.startsWith("http") }
                val ok = !title.isNullOrBlank() && title.length in 2..60 &&
                    !title.contains("not found", true) && !title.contains("pinterest home", true) &&
                    !title.contains("medium home", true)
                if (!ok && img == null) continue
                socials.add(SocialProfile(t.platform, handle, t.url, SocialLookupStatus.PUBLIC_MATCH))
                title?.takeIf { it.length in 2..60 }?.let { names.add(it) }
                desc?.let { abouts.add("${t.platform}: $it") }
                img?.let { photos.add(PhotoCandidate(provider = t.platform, url = it, sourcePriority = 52)) }
                if (socials.size >= 3) break
            } catch (_: Exception) { continue }
        }
        if (socials.isEmpty()) return@withContext null
        return@withContext PartialResult(
            name = names.firstOrNull()?.take(50),
            about = abouts.take(2).joinToString(" • ").take(400).ifBlank { null },
            imageUrl = photos.firstOrNull()?.url,
            photoCandidates = photos.take(3),
            socialProfiles = socials,
            confidence = 0.62f, source = "Pinterest/Medium/Dev", providerId = id, providerVersion = version
        )
    }
}
