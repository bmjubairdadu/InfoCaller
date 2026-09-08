package com.infocaller.app.data.remote

import com.infocaller.app.domain.engine.*
import com.infocaller.app.domain.model.PhotoCandidate
import com.infocaller.app.domain.model.SocialLookupStatus
import com.infocaller.app.domain.model.SocialProfile
import com.infocaller.app.util.await
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup

/**
 * YouTube channel extractor (keyless oEmbed + og:* scrape).
 * oEmbed (youtube.com/oembed?url=...) returns verified title/author with zero
 * scraping; falls back to channel-handle page og tags for avatar/about.
 * Extracts: channel name, description, avatar.
 */
class YouTubeProfileProviderImpl(private val httpClient: OkHttpClient) : LookupProvider {
    override val id = "youtube_profile"
    override val name = "YouTube Channel"
    override val version = "1.0.0"
    override val capabilities = setOf(Capability.PUBLIC_PROFILE, Capability.SOCIAL_MATCH, Capability.PROFILE_PHOTO, Capability.ABOUT)
    override val priority = 52
    override val costClass = CostClass.FREE

    override suspend fun lookup(identifier: String, type: String, context: LookupContext): PartialResult? = withContext(Dispatchers.IO) {
        if (type != IdentifierType.USERNAME && type != IdentifierType.FULL_NAME) return@withContext null
        val handle = identifier.trim().removePrefix("@").take(40)
        if (handle.length < 2 || handle.contains(" ")) return@withContext null
        val pageUrl = "https://www.youtube.com/@$handle"
        // 1) oEmbed: authoritative title for the handle.
        var oembedTitle: String? = null
        try {
            val enc = java.net.URLEncoder.encode(pageUrl, "UTF-8")
            val req = Request.Builder().url("https://www.youtube.com/oembed?url=$enc&format=json")
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14)").build()
            httpClient.newCall(req).await().use { r ->
                if (r.isSuccessful) {
                    try {
                        val j = com.google.gson.JsonParser.parseString(r.body?.string()).asJsonObject
                        oembedTitle = j.get("title")?.takeIf { !it.isJsonNull }?.asString
                            ?.takeIf { it.length in 2..80 }
                        j.get("author_name")?.takeIf { !it.isJsonNull }?.asString
                            ?.takeIf { it.length in 2..60 }?.let { oembedTitle = it }
                    } catch (_: Exception) { }
                }
            }
        } catch (_: Exception) { }
        // 2) Page og:* for avatar + description.
        var avatar: String? = null
        var desc: String? = null
        try {
            val doc = Jsoup.connect(pageUrl)
                .userAgent("Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/120.0 Safari/537.36")
                .timeout(8000).ignoreHttpErrors(true).followRedirects(true).get()
            if (doc.text().contains("404 not found", true)) {
                if (oembedTitle == null) return@withContext null
            }
            avatar = doc.selectFirst("meta[property=og:image]")?.attr("content")?.takeIf { it.startsWith("http") }
            desc = doc.selectFirst("meta[property=og:description]")?.attr("content")?.trim()?.take(400)
        } catch (_: Exception) { }
        if (oembedTitle == null && avatar == null) return@withContext null
        return@withContext PartialResult(
            name = oembedTitle, about = desc, imageUrl = avatar,
            photoCandidates = avatar?.let { listOf(PhotoCandidate(provider = "YouTube", url = it, sourcePriority = 58)) } ?: emptyList(),
            socialProfiles = listOf(SocialProfile("YouTube", handle, pageUrl, SocialLookupStatus.PUBLIC_MATCH)),
            confidence = if (oembedTitle != null) 0.68f else 0.5f,
            source = "YouTube Channel", providerId = id, providerVersion = version
        )
    }
}
