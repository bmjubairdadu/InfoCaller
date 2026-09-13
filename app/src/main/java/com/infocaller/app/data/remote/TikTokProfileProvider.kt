package com.infocaller.app.data.remote

import android.util.Log
import com.infocaller.app.domain.engine.*
import com.infocaller.app.domain.model.PhotoCandidate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup

class TikTokProfileProvider : LookupProvider {
    override val id = "tiktok_profile"
    override val name = "TikTok Profile"
    override val version = "1.0.0"
    override val capabilities = setOf(Capability.PUBLIC_PROFILE, Capability.SOCIAL_MATCH, Capability.PROFILE_PHOTO, Capability.ABOUT)
    override val priority = 46
    override val costClass = CostClass.FREE

    override suspend fun lookup(identifier: String, type: String, context: LookupContext): PartialResult? = withContext(Dispatchers.IO) {
        if (type != IdentifierType.USERNAME && type != IdentifierType.FULL_NAME) return@withContext null
        val username = identifier.trim().lowercase().replace(Regex("[^a-z0-9._]"), "")
        if (username.length < 2 || username.length > 40) return@withContext null
        try {
            val url = "https://www.tiktok.com/@$username"
            val doc = Jsoup.connect(url)
                .userAgent("Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/120.0 Safari/537.36")
                .header("Accept-Language","en-US,en;q=0.9")
                .timeout(8000).maxBodySize(100_000).ignoreHttpErrors(true).followRedirects(true).get()
            // Live probe: missing TikTok pages return HTTP 200 with empty og:title.
            // Strict: no og:title mentioning the handle = not a real account.
            val ogTitle = doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim()?.takeIf { it.isNotBlank() }
            if (ogTitle.isNullOrBlank()) return@withContext null
            if (!ogTitle.contains(username, true) && !ogTitle.contains("@", false)) return@withContext null
            if (ogTitle.contains("TikTok", true) && ogTitle.length < 12) return@withContext null
            val ogDesc = doc.selectFirst("meta[property=og:description]")?.attr("content")
            val ogImageRaw = doc.selectFirst("meta[property=og:image]")?.attr("content")?.takeIf{ it.startsWith("http") }
            val ogImage = try { if (com.infocaller.app.util.PhotoPolicy.isUsablePhotoUrl(ogImageRaw)) ogImageRaw else null } catch (_: Exception) { null } catch (_: Error) { null }
            val name = ogTitle.substringBefore("(").trim().takeIf{ it.length in 2..50 && !it.contains("TikTok", true) }
            val bio = ogDesc?.take(400)
            if (doc.text().contains("Couldn't find this account", true)) return@withContext null
            val social = listOf(com.infocaller.app.domain.model.SocialProfile("TikTok", username, url, com.infocaller.app.domain.model.SocialLookupStatus.PUBLIC_MATCH))
            return@withContext PartialResult(
                name = name,
                about = bio,
                imageUrl = ogImage,
                photoCandidates = ogImage?.let{ listOf(PhotoCandidate(provider="TikTok", url=it, sourcePriority=55)) } ?: emptyList(),
                socialProfiles = social,
                confidence = 0.6f, source = name, providerId = id, providerVersion = version
            )
        } catch (_: Exception) { null }
    }
}
