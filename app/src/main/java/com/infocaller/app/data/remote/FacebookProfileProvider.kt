package com.infocaller.app.data.remote

import android.util.Log
import com.infocaller.app.domain.engine.*
import com.infocaller.app.domain.model.PhotoCandidate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup

/**
 * Facebook public profile data extractor (no login, based on facebook-scraper logic: kevinzg/facebook-scraper).
 * Uses public web profile scrape: https://www.facebook.com/{username} via Jsoup.
 * Extracts: name, bio/about, profile photo, cover, work/education if present in og tags, following.
 * Free, no API key. Inspired by Osintgram's profile approach but for Facebook.
 */
class FacebookProfileProvider : LookupProvider {
    override val id = "facebook_profile"
    override val name = "Facebook Profile (public)"
    override val version = "1.0.0"
    override val capabilities = setOf(Capability.PUBLIC_PROFILE, Capability.SOCIAL_MATCH, Capability.PROFILE_PHOTO, Capability.ABOUT)
    override val priority = 44
    override val costClass = CostClass.FREE

    override suspend fun lookup(identifier: String, type: String, context: LookupContext): PartialResult? = withContext(Dispatchers.IO) {
        if (type != IdentifierType.USERNAME && type != IdentifierType.FULL_NAME) return@withContext null
        // identifier is username or name-derived slug
        val username = identifier.trim().lowercase().replace(Regex("[^a-z0-9._-]"), "")
        if (username.length < 3 || username.length > 40) return@withContext null
        try {
            val url = "https://www.facebook.com/$username"
            val doc = Jsoup.connect(url)
                .userAgent("Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/120.0 Safari/537.36")
                .header("Accept-Language","en-US,en;q=0.9")
                .timeout(8000).ignoreHttpErrors(true).followRedirects(true).get()
            val title = doc.selectFirst("meta[property=og:title]")?.attr("content")?.takeIf { it.isNotBlank() }
                ?: doc.selectFirst("title")?.text()?.substringBefore("|")?.trim()
            val bio = doc.selectFirst("meta[property=og:description]")?.attr("content")?.takeIf { it.isNotBlank() }
                ?: doc.selectFirst("meta[name=description]")?.attr("content")
            var photo = doc.selectFirst("meta[property=og:image]")?.attr("content")?.takeIf { it.startsWith("http") }
            // Photo may be fbcdn.net
            if (photo != null && photo.contains("rsrc.php")) photo = null // placeholder

            // Require at least title to be considered a real profile (avoid 404/locked)
            if (title.isNullOrBlank() || title.contains("not found", true) || doc.text().lowercase().contains("page not found")) return@withContext null
            // Check blocked/private indicator
            if (doc.text().contains("content isn’t available", true) || doc.text().contains("this page isn't available", true)) return@withContext null

            val name = title.takeIf { it.length in 3..50 && !it.startsWith("Facebook") } ?: username
            val social = mutableListOf<com.infocaller.app.domain.model.SocialProfile>()
            social.add(com.infocaller.app.domain.model.SocialProfile("Facebook", username, url, com.infocaller.app.domain.model.SocialLookupStatus.PUBLIC_MATCH))

            return@withContext PartialResult(
                name = name,
                about = bio?.take(400),
                imageUrl = photo,
                photoCandidates = photo?.let { listOf(PhotoCandidate(provider="Facebook", url=it, sourcePriority=60)) } ?: emptyList(),
                socialProfiles = social,
                confidence = 0.65f, source = name, providerId = id, providerVersion = version
            )
        } catch (e: Exception) { Log.w("FacebookProfile", "fail $username: ${e.message}"); null }
    }
}
