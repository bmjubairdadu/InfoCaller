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
        val username = identifier.trim().lowercase().replace(Regex("[^a-z0-9._-]"), "")
        if (username.length < 3 || username.length > 40) return@withContext null
        try {
            // Your m.facebook.com.har shows the logged-out web flow lands on
            // Bloks login / account-recovery screens (com.bloks.www.caa.*),
            // so www.facebook.com usually answers with a login wall instead
            // of the profile. Try www first, then the lightweight mbasic
            // host which still renders public pages without JS.
            val doc = fetchWww(username) ?: fetchMbasic(username) ?: return@withContext null
            val title = doc.selectFirst("meta[property=og:title]")?.attr("content")?.takeIf { it.isNotBlank() }
                ?: doc.selectFirst("title")?.text()?.substringBefore("|")?.trim()
            val bio = doc.selectFirst("meta[property=og:description]")?.attr("content")?.takeIf { it.isNotBlank() }
                ?: doc.selectFirst("meta[name=description]")?.attr("content")
            var photo = doc.selectFirst("meta[property=og:image]")?.attr("content")?.takeIf { it.startsWith("http") }
            if (photo != null && photo.contains("rsrc.php")) photo = null

            if (title.isNullOrBlank() || title.contains("not found", true) || doc.text().lowercase().contains("page not found")) return@withContext null
            if (doc.text().contains("content isn’t available", true) || doc.text().contains("this page isn't available", true)) return@withContext null

            val name = title.takeIf { it.length in 3..50 && !it.startsWith("Facebook") } ?: username
            val canonicalUrl = "https://www.facebook.com/$username"
            val social = mutableListOf<com.infocaller.app.domain.model.SocialProfile>()
            social.add(com.infocaller.app.domain.model.SocialProfile("Facebook", username, canonicalUrl, com.infocaller.app.domain.model.SocialLookupStatus.PUBLIC_MATCH))

            return@withContext PartialResult(
                name = name,
                about = bio?.take(400),
                imageUrl = photo,
                photoCandidates = photo?.let { listOf(PhotoCandidate(provider="Facebook", url=it, sourcePriority=60)) } ?: emptyList(),
                socialProfiles = social,
                confidence = 0.65f, source = name, providerId = id, providerVersion = version
            )
        } catch (_: Exception) { null }
    }

    private fun isLoginWall(doc: org.jsoup.nodes.Document): Boolean {
        val t = doc.text()
        return t.contains("log in to facebook", true) ||
            t.contains("log into facebook", true) ||
            t.contains("create new account", true) ||
            doc.selectFirst("#login_form, form[action*=login]") != null
    }

    /** Returns the doc, or null when it is a login wall / failure. */
    private fun fetchDoc(url: String, ua: String): org.jsoup.nodes.Document? {
        return try {
            val doc = Jsoup.connect(url)
                .userAgent(ua)
                .header("Accept-Language", "en-US,en;q=0.9")
                .timeout(8000).ignoreHttpErrors(true).followRedirects(true).get()
            if (isLoginWall(doc)) null else doc
        } catch (_: Exception) { null }
    }

    private fun fetchWww(username: String) = fetchDoc(
        "https://www.facebook.com/$username",
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/120.0 Safari/537.36",
    )

    private fun fetchMbasic(username: String) = fetchDoc(
        "https://mbasic.facebook.com/$username",
        "Mozilla/5.0 (Linux; Android 11; Mi A2 Lite Build/RQ3A.211001.001) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/96.0.4664.104 Mobile Safari/537.36",
    )
}
