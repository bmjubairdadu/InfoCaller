package com.infocaller.app.data.remote

import com.infocaller.app.domain.engine.*
import com.infocaller.app.domain.model.PhotoCandidate
import com.infocaller.app.domain.model.SocialLookupStatus
import com.infocaller.app.domain.model.SocialProfile
import com.infocaller.app.util.await
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.jsoup.Jsoup

class XProfileProviderImpl(private val httpClient: OkHttpClient) : LookupProvider {
    override val id = "x_profile"
    override val name = "X Profile"
    override val version = "1.0.0"
    override val capabilities = setOf(Capability.PUBLIC_PROFILE, Capability.SOCIAL_MATCH, Capability.PROFILE_PHOTO, Capability.ABOUT)
    override val priority = 56
    override val costClass = CostClass.FREE

    override suspend fun lookup(identifier: String, type: String, context: LookupContext): PartialResult? = withContext(Dispatchers.IO) {
        if (type != IdentifierType.USERNAME && type != IdentifierType.FULL_NAME && type != IdentifierType.EMAIL) return@withContext null
        val handle = when (type) {
            IdentifierType.EMAIL -> identifier.substringBefore("@")
            else -> identifier.trim().removePrefix("@")
        }.lowercase().replace(Regex("[^a-z0-9_]"), "")
        if (handle.length !in 2..30) return@withContext null
        try {
            val req = okhttp3.Request.Builder()
                .url("https://cdn.syndication.twimg.com/widgets/followbutton/info.json?screen_names=$handle")
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14)").build()
            httpClient.newCall(req).await().use { r ->
                if (r.isSuccessful) {
                    val body = r.body?.string().orEmpty()
                    if (body.length > 20 && !body.contains("suspended", true)) {
                        try {
                            val arr = com.google.gson.JsonParser.parseString(body).asJsonArray
                            val o = arr.firstOrNull()?.asJsonObject
                            val name = o?.get("name")?.takeIf { !it.isJsonNull }?.asString
                            val desc = o?.get("description")?.takeIf { !it.isJsonNull }?.asString?.take(400)
                            val avatar = o?.get("profile_image_url_https")?.takeIf { !it.isJsonNull }?.asString
                                ?.replace("_normal", "_400x400")?.takeIf { it.startsWith("http") }
                            val followers = o?.get("followers_count")?.takeIf { !it.isJsonNull }?.asInt
                            if (!name.isNullOrBlank()) {
                                val about = buildString {
                                    desc?.let { append(it.take(300)) }
                                    followers?.let { append(" | X $it followers") }
                                }.take(400).ifBlank { null }
                                val url = "https://x.com/$handle"
                                return@withContext PartialResult(
                                    name = name.take(50), about = about, imageUrl = avatar,
                                    photoCandidates = avatar?.let { listOf(PhotoCandidate(provider = "X", url = it, sourcePriority = 64)) } ?: emptyList(),
                                    socialProfiles = listOf(SocialProfile("X", handle, url, SocialLookupStatus.PUBLIC_MATCH)),
                                    confidence = 0.7f, source = "X Profile", providerId = id, providerVersion = version
                                )
                            }
                        } catch (_: Exception) { }
                    }
                }
            }
        } catch (_: Exception) { }
        try {
            val url = "https://x.com/$handle"
            val doc = Jsoup.connect(url)
                .userAgent("Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/120.0 Safari/537.36")
                .timeout(8000).ignoreHttpErrors(true).followRedirects(true).get()
            val title = doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            val desc = doc.selectFirst("meta[property=og:description]")?.attr("content")?.trim()?.take(400)
            val img = doc.selectFirst("meta[property=og:image]")?.attr("content")?.takeIf { it.startsWith("http") }
            val name = title?.substringBefore("@")?.trim()
                ?.takeIf { it.length in 2..50 && !it.contains("x.com", true) } ?: return@withContext null
            return@withContext PartialResult(
                name = name, about = desc, imageUrl = img,
                photoCandidates = img?.let { listOf(PhotoCandidate(provider = "X", url = it, sourcePriority = 60)) } ?: emptyList(),
                socialProfiles = listOf(SocialProfile("X", handle, url, SocialLookupStatus.PUBLIC_MATCH)),
                confidence = 0.6f, source = "X Profile", providerId = id, providerVersion = version
            )
        } catch (_: Exception) { null }
    }
}
