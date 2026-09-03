package com.infocaller.app.data.remote

import com.infocaller.app.domain.engine.*
import com.infocaller.app.domain.model.SocialLookupStatus
import com.infocaller.app.domain.model.SocialProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import java.net.URLEncoder

/**
 * After a photo/image is found, search by image context: DDG "reverse-like" dork by name + photo domain
 * If image domain is facebook/instagram cdn, the dork may directly hit the owning profile.
 */
class ImageSocialVerifierProvider : LookupProvider {
    override val id = "image_social_verifier"
    override val name = "Image→Social Match"
    override val version = "1.0.0"
    override val capabilities = setOf(Capability.PUBLIC_SEARCH, Capability.SOCIAL_MATCH)
    override val priority = 40
    override val costClass = CostClass.FREE

    override suspend fun lookup(identifier: String, type: String, context: LookupContext): PartialResult? = withContext(Dispatchers.IO) {
        if (!identifier.startsWith("http")) return@withContext null
        // identifier here is imageUrl passed as type FULL_NAME hack? Instead expect name+url encoded pair
        // Caller will pass imageUrl as identifier with type "IMAGE_URL" - handle both
        val imageUrl = identifier
        try {
            val domain = try { java.net.URL(imageUrl).host } catch(_:Exception){ null } ?: return@withContext null
            // If CDN is fbcdn.net / instagram cdn, we can pivot to profile search by owner name already done by Name verifier
            // Here we do a complementary dork: site:platform image host hint
            val q = "\"$domain\" site:facebook.com OR site:instagram.com"
            val url = "https://html.duckduckgo.com/html/?q=${URLEncoder.encode(q, "UTF-8")}"
            val doc = Jsoup.connect(url).userAgent("Mozilla/5.0").timeout(7000).ignoreHttpErrors(true).get()
            val href = doc.select("a.result__a").firstOrNull()?.attr("href") ?: return@withContext null
            if (!href.contains("facebook.com") && !href.contains("instagram.com")) return@withContext null
            val platform = if (href.contains("facebook")) "Facebook" else "Instagram"
            return@withContext PartialResult(
                socialProfiles = listOf(SocialProfile(platform, null, href, SocialLookupStatus.PUBLIC_MATCH)),
                confidence = 0.5f, source = "Image→Social Match", providerId = id, providerVersion = version
            )
        } catch(_:Exception){ null }
    }
}
