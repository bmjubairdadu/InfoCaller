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

class TelegramDeepProviderImpl(private val httpClient: OkHttpClient) : LookupProvider {
    override val id = "telegram_deep"
    override val name = "Telegram Deep Profile"
    override val version = "1.0.0"
    override val capabilities = setOf(Capability.PUBLIC_PROFILE, Capability.SOCIAL_MATCH, Capability.PROFILE_PHOTO, Capability.ABOUT, Capability.TELEGRAM_LINK)
    override val priority = 53
    override val costClass = CostClass.FREE

    private fun scrape(url: String): Triple<String?, String?, String?> {
        return try {
            val doc = Jsoup.connect(url)
                .userAgent("Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/120.0 Safari/537.36")
                .timeout(8000).ignoreHttpErrors(true).followRedirects(true).get()
            if (doc.text().contains("login", true) && doc.select("meta[property=og:title]").isEmpty()) {
                return Triple(null, null, null)
            }
            val title = doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
                ?.takeIf { it.length in 2..80 && !it.contains("Telegram", true) }
            val desc = doc.selectFirst("meta[property=og:description]")?.attr("content")?.trim()?.take(350)
            val img = doc.selectFirst("meta[property=og:image]")?.attr("content")?.takeIf { it.startsWith("http") }
            Triple(title, desc, img)
        } catch (_: Exception) { Triple(null, null, null) }
    }

    override suspend fun lookup(identifier: String, type: String, context: LookupContext): PartialResult? = withContext(Dispatchers.IO) {
        when (type) {
            IdentifierType.USERNAME, IdentifierType.FULL_NAME -> {
                val handle = identifier.trim().removePrefix("@").lowercase()
                    .replace(Regex("[^a-z0-9_]"), "")
                if (handle.length !in 4..32) return@withContext null
                val url = "https://t.me/$handle"
                val (name, bio, img) = scrape(url)
                if (name == null && img == null) return@withContext null
                return@withContext PartialResult(
                    name = name?.take(50), about = bio, imageUrl = img,
                    photoCandidates = img?.let { listOf(PhotoCandidate(provider = "Telegram", url = it, sourcePriority = 59)) } ?: emptyList(),
                    socialProfiles = listOf(SocialProfile("Telegram", handle, url, SocialLookupStatus.PUBLIC_MATCH)),
                    confidence = if (name != null) 0.7f else 0.52f,
                    source = "Telegram Deep", providerId = id, providerVersion = version
                )
            }
            IdentifierType.PHONE -> {
                val digits = identifier.filter { it.isDigit() }
                if (digits.length < 7) return@withContext null
                val url = "https://t.me/+$digits"
                val (name, bio, img) = scrape(url)
                if (name == null && img == null) return@withContext null
                return@withContext PartialResult(
                    name = name?.take(50), about = bio, imageUrl = img,
                    photoCandidates = img?.let { listOf(PhotoCandidate(provider = "Telegram", url = it, sourcePriority = 59)) } ?: emptyList(),
                    socialProfiles = listOf(SocialProfile("Telegram", digits, url, SocialLookupStatus.PUBLIC_MATCH)),
                    confidence = if (name != null) 0.72f else 0.55f,
                    source = "Telegram Deep", providerId = id, providerVersion = version
                )
            }
            else -> return@withContext null
        }
    }
}
