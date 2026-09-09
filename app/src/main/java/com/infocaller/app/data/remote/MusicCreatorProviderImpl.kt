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

class MusicCreatorProviderImpl(private val httpClient: OkHttpClient) : LookupProvider {
    override val id = "music_creator"
    override val name = "Music/Creator Profiles"
    override val version = "1.0.0"
    override val capabilities = setOf(Capability.PUBLIC_PROFILE, Capability.SOCIAL_MATCH, Capability.PROFILE_PHOTO, Capability.ABOUT)
    override val priority = 44
    override val costClass = CostClass.FREE

    override suspend fun lookup(identifier: String, type: String, context: LookupContext): PartialResult? = withContext(Dispatchers.IO) {
        if (type != IdentifierType.USERNAME && type != IdentifierType.FULL_NAME) return@withContext null
        val handle = identifier.trim().removePrefix("@")
        if (handle.length !in 3..40 || handle.contains(" ")) return@withContext null
        val slug = handle.lowercase().replace(Regex("[^a-z0-9._-]"), "")
        if (slug.length < 3) return@withContext null
        var name: String? = null
        var about: String? = null
        val photos = mutableListOf<PhotoCandidate>()
        val socials = mutableListOf<SocialProfile>()

        try {
            val url = "https://soundcloud.com/$slug"
            val doc = Jsoup.connect(url)
                .userAgent("Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/120.0 Safari/537.36")
                .timeout(7000).ignoreHttpErrors(true).followRedirects(true).get()
            val title = doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            val img = doc.selectFirst("meta[property=og:image]")?.attr("content")?.takeIf { it.startsWith("http") }
            if (!title.isNullOrBlank() && !title.contains("soundcloud home", true) && title.length in 2..60) {
                if (name == null) name = title.take(50)
                img?.let { photos.add(PhotoCandidate(provider = "SoundCloud", url = it, sourcePriority = 53)) }
                socials.add(SocialProfile("SoundCloud", handle, url, SocialLookupStatus.PUBLIC_MATCH))
            }
        } catch (_: Exception) { }
        try {
            val enc = java.net.URLEncoder.encode(handle, "UTF-8")
            val doc = Jsoup.connect("https://open.spotify.com/search/$enc")
                .userAgent("Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/120.0 Safari/537.36")
                .timeout(7000).ignoreHttpErrors(true).followRedirects(true).get()
            val title = doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            val img = doc.selectFirst("meta[property=og:image]")?.attr("content")?.takeIf { it.startsWith("http") }
            if (!title.isNullOrBlank() && title.contains(handle, true) && title.length in 2..60) {
                if (name == null) name = title.take(50)
                about = "Spotify artist match: $title".take(200)
                img?.let { photos.add(PhotoCandidate(provider = "Spotify", url = it, sourcePriority = 52)) }
                socials.add(SocialProfile("Spotify", handle, "https://open.spotify.com/search/$enc", SocialLookupStatus.POSSIBLE_MATCH))
            }
        } catch (_: Exception) { }
        if (socials.isEmpty()) return@withContext null
        return@withContext PartialResult(
            name = name, about = about,
            imageUrl = photos.firstOrNull()?.url,
            photoCandidates = photos.take(2),
            socialProfiles = socials,
            confidence = 0.58f, source = "Music/Creator", providerId = id, providerVersion = version
        )
    }
}
