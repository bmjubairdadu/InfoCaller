package com.infocaller.app.data.remote

import com.infocaller.app.domain.engine.*
import com.infocaller.app.domain.model.SocialProfile
import com.infocaller.app.domain.model.SocialLookupStatus
import com.infocaller.app.domain.model.PhotoCandidate
import org.jsoup.Jsoup
import java.net.URLEncoder
import java.nio.charset.StandardCharsets


class DorkingProviderImpl : SearchProvider {
    override val id: String = "google_dorks_authorized"
    override val name: String = "Search Engine Intelligence"
    override val version: String = "2.0.0"
    override val capabilities: Set<Capability> = setOf(Capability.PUBLIC_SEARCH, Capability.PUBLIC_PROFILE)
    override val priority: Int = 33
    override val costClass: CostClass = CostClass.FREE

    override suspend fun lookup(identifier: String, type: String, context: LookupContext): PartialResult? {
        val dorks = when (type) {
            IdentifierType.PHONE -> listOf(
                "site:facebook.com \"$identifier\"",
                "site:instagram.com \"$identifier\"",
                "site:linkedin.com \"$identifier\"",
                "site:tiktok.com \"$identifier\"",
                "site:youtube.com \"$identifier\""
            )
            IdentifierType.USERNAME, IdentifierType.EMAIL -> listOf(
                "site:github.com \"$identifier\"",
                "site:linkedin.com \"$identifier\""
            )
            else -> return null
        }

        val socialProfiles = mutableListOf<SocialProfile>()
        val foundName: String? = null

        val query = URLEncoder.encode(dorks.joinToString(" OR "), StandardCharsets.UTF_8.toString())
        val url = "https://html.duckduckgo.com/html/?q=$query"

        try {
            val doc = Jsoup.connect(url).userAgent("Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/120.0 Safari/537.36").timeout(7000).ignoreHttpErrors(true).get()
            doc.select("div.g").forEach { res ->
                val link = res.select("a").attr("href")
                if (link.isBlank()) return@forEach

                if (isValidProfileLink(link)) {
                    val platform = when {
                        link.contains("facebook.com") -> "Facebook"
                        link.contains("instagram.com") -> "Instagram"
                        link.contains("linkedin.com") -> "LinkedIn"
                        else -> null
                    }
                    if (platform != null) {
                        socialProfiles.add(SocialProfile(platform, null, link, SocialLookupStatus.PUBLIC_MATCH))
                    }
                }
            }

            if (socialProfiles.isEmpty()) return null

            return PartialResult(
                name = foundName,
                socialProfiles = socialProfiles.distinctBy { it.profileUrl },
                confidence = 0.3f,
                source = "Web Intelligence",
                providerId = id,
                providerVersion = version
            )
        } catch (e: Exception) {
            return null
        }
    }

    private fun isValidProfileLink(url: String): Boolean {
        val clean = url.lowercase().removeSuffix("/")
        val platforms = listOf("facebook.com", "instagram.com", "linkedin.com/in", "twitter.com", "x.com")
        return platforms.any { p -> clean.contains(p) && clean.length > (p.length + 10) }
    }

    override suspend fun bulkLookup(identifiers: List<String>, type: String, context: LookupContext): Map<String, PartialResult> {
        return emptyMap()
    }
}
