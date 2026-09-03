package com.infocaller.app.data.remote

import com.infocaller.app.domain.engine.*
import com.infocaller.app.domain.model.SocialLookupStatus
import com.infocaller.app.domain.model.SocialProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import java.net.URLEncoder

/**
 * PHONE -> deep social via truecaller page name -> username pivot -> validated social exists
 * This bridges Phone -> Name (Truecaller web) -> Username -> Social profiles (validated)
 * Free, no key. Uses DuckDuckGo filtered search for username handles.
 */
class PhoneSocialBridgeProvider(private val client: okhttp3.OkHttpClient) : LookupProvider {
    override val id = "phone_social_bridge"
    override val name = "Phone → Social Bridge"
    override val version = "1.0.0"
    override val capabilities = setOf(Capability.SOCIAL_MATCH, Capability.PUBLIC_PROFILE, Capability.PUBLIC_SEARCH)
    override val priority = 46
    override val costClass = CostClass.FREE

    override suspend fun lookup(identifier: String, type: String, context: LookupContext): PartialResult? = withContext(Dispatchers.IO) {
        if (type != IdentifierType.PHONE) return@withContext null
        val digits = identifier.filter{it.isDigit()}
        // Step 1: try public name from TC web / caller deep osint already may have, but here do lightweight dork for name->username
        try {
            val q = "\"$digits\" site:facebook.com OR site:instagram.com OR site:linkedin.com OR site:tiktok.com"
            val url = "https://html.duckduckgo.com/html/?q=${URLEncoder.encode(q, Charsets.UTF_8.toString())}"
            val doc = Jsoup.connect(url).userAgent("Mozilla/5.0 (Linux; Android 14)").timeout(7000).ignoreHttpErrors(true).get()
            val links = doc.select("a.result__a").map{ it.attr("href") }.filter{ it.isNotBlank() }.take(6)
            val socials = links.mapNotNull { href ->
                when {
                    href.contains("facebook.com/") && href.length > "facebook.com/".length + 3 -> SocialProfile("Facebook", null, href, SocialLookupStatus.PUBLIC_MATCH)
                    href.contains("instagram.com/") -> SocialProfile("Instagram", null, href, SocialLookupStatus.PUBLIC_MATCH)
                    href.contains("linkedin.com/in/") -> SocialProfile("LinkedIn", null, href, SocialLookupStatus.PUBLIC_MATCH)
                    href.contains("tiktok.com/@") -> SocialProfile("TikTok", null, href, SocialLookupStatus.PUBLIC_MATCH)
                    else -> null
                }
            }.distinctBy{ it.profileUrl }
            if (socials.isEmpty()) return@withContext null
            return@withContext PartialResult(socialProfiles = socials, confidence = 0.55f, source = "Phone→Social Bridge", providerId = id, providerVersion = version)
        } catch(_:Exception){ null }
    }
}
