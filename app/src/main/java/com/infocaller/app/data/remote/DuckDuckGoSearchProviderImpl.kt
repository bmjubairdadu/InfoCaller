package com.infocaller.app.data.remote

import com.infocaller.app.domain.engine.*
import com.infocaller.app.domain.model.SocialProfile
import com.infocaller.app.domain.model.SocialLookupStatus
import org.jsoup.Jsoup
import java.net.URLEncoder
import java.nio.charset.StandardCharsets


class DuckDuckGoSearchProviderImpl : LookupProvider {
    override val id = "duckduckgo_osint"
    override val name = "DuckDuckGo OSINT"
    override val version = "1.0.0"
    override val capabilities = setOf(Capability.PUBLIC_SEARCH, Capability.SOCIAL_MATCH, Capability.PUBLIC_PROFILE)
    override val priority = 44
    override val costClass = CostClass.FREE

    override suspend fun lookup(identifier: String, type: String, context: LookupContext): PartialResult? {
        val query = when (type) {
            IdentifierType.PHONE -> "\"${identifier.filter { it.isDigit() }}\""
            IdentifierType.EMAIL -> "\"$identifier\""
            IdentifierType.USERNAME -> "\"$identifier\""
            IdentifierType.FULL_NAME -> "\"$identifier\""
            IdentifierType.DOMAIN -> "site:$identifier"
            else -> return null
        }
        val finalQ = if (type == IdentifierType.PHONE) "$query site:facebook.com OR site:instagram.com OR site:linkedin.com OR site:truecaller.com"
                     else query
        val url = "https://html.duckduckgo.com/html/?q=${URLEncoder.encode(finalQ, StandardCharsets.UTF_8.toString())}"
        return try {
            val doc = Jsoup.connect(url)
                .userAgent("Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/120.0 Safari/537.36")
                .timeout(7000)
                .ignoreHttpErrors(true)
                .get()
            val titles = doc.select("h2.result__title a, a.result__a").map { it.text() to it.attr("href") }
            val snippets = doc.select(".result__snippet").map { it.text() }

            for ((title, href) in titles.take(5)) {
                if (href.isBlank()) continue
                val isSocial = href.contains("facebook.com") || href.contains("instagram.com") || href.contains("linkedin.com")
                if (isSocial) {
                    val socials = titles.mapNotNull { (t, h) ->
                        if (h.contains("facebook.com")) SocialProfile("Facebook", null, h, SocialLookupStatus.PUBLIC_MATCH)
                        else if (h.contains("instagram.com")) SocialProfile("Instagram", null, h, SocialLookupStatus.PUBLIC_MATCH)
                        else if (h.contains("linkedin.com")) SocialProfile("LinkedIn", null, h, SocialLookupStatus.PUBLIC_MATCH)
                        else null
                    }.distinctBy { it.profileUrl }
                    if (socials.isNotEmpty()) {
                        return PartialResult(
                            socialProfiles = socials.take(5),
                            confidence = 0.55f,
                            source = "DuckDuckGo Recon",
                            providerId = id, providerVersion = version
                        )
                    }
                }
                if (title.contains("|") || title.contains("-")) {
                    val potential = title.split("|","-","·",":").first().trim()
                    val notGeneric = !potential.contains("Facebook",true) && !potential.contains("Instagram",true)
                        && !potential.contains("LinkedIn",true) && !potential.contains("Truecaller",true)
                        && potential.split(" ").size in 2..4 && potential.length in 5..32
                    if (notGeneric) {
                        return PartialResult(name = potential, confidence = 0.52f, source = name, providerId = id, providerVersion = version)
                    }
                }
            }
            if (titles.isNotEmpty()) {
                return PartialResult(about = "Public mentions: ${titles.take(2).joinToString(" | ") { it.first }.take(180)}", confidence = 0.4f, source = name, providerId = id, providerVersion = version)
            }
            null
        } catch (_: Exception) { null }
    }
}
