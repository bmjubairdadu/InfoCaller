package com.infocaller.app.data.remote

import com.infocaller.app.domain.engine.*
import com.infocaller.app.domain.model.SocialLookupStatus
import com.infocaller.app.domain.model.SocialProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import java.net.URLEncoder


class NameSocialVerifierProvider : LookupProvider {
    override val id = "name_social_verifier"
    override val name = "Name→Social Match"
    override val version = "1.0.0"
    override val capabilities = setOf(Capability.PUBLIC_SEARCH, Capability.SOCIAL_MATCH, Capability.PUBLIC_PROFILE)
    override val priority = 42
    override val costClass = CostClass.FREE

    override suspend fun lookup(identifier: String, type: String, context: LookupContext): PartialResult? = withContext(Dispatchers.IO) {
        if (type != IdentifierType.FULL_NAME) return@withContext null
        if (identifier.length < 4 || identifier.length > 40) return@withContext null
        val name = identifier.trim()
        try {
            val platforms = listOf("facebook.com","instagram.com","linkedin.com","tiktok.com","youtube.com","twitter.com","github.com")
            val q = "\"$name\" (site:facebook.com OR site:instagram.com OR site:linkedin.com OR site:tiktok.com)"
            val url = "https://html.duckduckgo.com/html/?q=${URLEncoder.encode(q, "UTF-8")}"
            val doc = Jsoup.connect(url).userAgent("Mozilla/5.0 (Linux; Android 14)").timeout(8000).ignoreHttpErrors(true).get()
            val links = doc.select("a.result__a").map { it.attr("href") to it.text() }.filter { it.first.isNotBlank() }.take(8)
            val profiles = mutableListOf<SocialProfile>()
            val nameWords = name.lowercase().split(" ").filter { it.length >= 2 }.toSet()
            for ((href, title) in links) {
                val platform = platforms.firstOrNull { href.contains(it) } ?: continue
                val text = "$title $href".lowercase()
                val wordMatch = nameWords.count { text.contains(it) } >= 1
                if (!wordMatch) continue
                val label = when {
                    platform.contains("facebook") -> "Facebook"
                    platform.contains("instagram") -> "Instagram"
                    platform.contains("linkedin") -> "LinkedIn"
                    platform.contains("tiktok") -> "TikTok"
                    platform.contains("youtube") -> "YouTube"
                    platform.contains("twitter") -> "Twitter"
                    platform.contains("github") -> "GitHub"
                    else -> platform
                }
                if (href.length < platform.length + 5) continue
                if (!href.contains(platform)) continue
                profiles.add(SocialProfile(label, name, href, SocialLookupStatus.PUBLIC_MATCH))
            }
            if (profiles.isEmpty()) return@withContext null
            return@withContext PartialResult(
                socialProfiles = profiles.distinctBy { it.profileUrl },
                confidence = 0.6f, source = "Name→Social Match", providerId = id, providerVersion = version
            )
        } catch (_: Exception) { null }
    }
}
