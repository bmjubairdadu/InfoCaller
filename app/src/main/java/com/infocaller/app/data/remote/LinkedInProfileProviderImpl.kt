package com.infocaller.app.data.remote

import com.infocaller.app.domain.engine.*
import com.infocaller.app.domain.model.PhotoCandidate
import com.infocaller.app.domain.model.SocialLookupStatus
import com.infocaller.app.domain.model.SocialProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.jsoup.Jsoup

class LinkedInProfileProviderImpl(private val httpClient: OkHttpClient) : LookupProvider {
    override val id = "linkedin_profile"
    override val name = "LinkedIn Profile"
    override val version = "1.0.0"
    override val capabilities = setOf(Capability.PUBLIC_PROFILE, Capability.SOCIAL_MATCH, Capability.PROFILE_PHOTO, Capability.ABOUT)
    override val priority = 58
    override val costClass = CostClass.FREE

    private fun candidates(identifier: String, type: String): List<String> {
        val out = mutableListOf<String>()
        when (type) {
            IdentifierType.USERNAME, IdentifierType.FULL_NAME -> {
                val u = identifier.trim().lowercase().replace(Regex("[^a-z0-9._-]"), "")
                if (u.length in 3..40) out.add(u)
            }
            IdentifierType.EMAIL -> {
                val prefix = identifier.substringBefore("@").lowercase().replace(Regex("[^a-z0-9._-]"), "")
                if (prefix.length in 3..40) out.add(prefix)
            }
            else -> {}
        }
        contextFoundNameSlug()?.let { if (it.length in 3..60 && it !in out) out.add(it) }
        return out.distinct().take(2)
    }

    private var ctxName: String? = null
    private fun contextFoundNameSlug(): String? {
        val n = ctxName?.trim()?.lowercase() ?: return null
        if (n.length < 3 || n.length > 60) return null
        val slug = n.replace(Regex("[^a-z0-9]+"), "-").trim('-')
        return slug.takeIf { it.length in 3..60 }
    }

    override suspend fun lookup(identifier: String, type: String, context: LookupContext): PartialResult? = withContext(Dispatchers.IO) {
        ctxName = context.foundName
        for (handle in candidates(identifier, type)) {
            try {
                val url = "https://www.linkedin.com/in/$handle"
                val doc = Jsoup.connect(url)
                    .userAgent("Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/120.0 Safari/537.36")
                    .header("Accept-Language", "en-US,en;q=0.9")
                    .timeout(8000).ignoreHttpErrors(true).followRedirects(true).get()
                val title = doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
                val desc = doc.selectFirst("meta[property=og:description]")?.attr("content")?.trim()?.take(400)
                val img = doc.selectFirst("meta[property=og:image]")?.attr("content")?.takeIf { it.startsWith("http") }
                val body = doc.text()
                if (title.isNullOrBlank()) continue
                if (title.contains("sign up", true) || title.contains("join now", true)) continue
                if (body.contains("page not found", true)) continue
                val name = title.substringBefore("|").trim()
                    .takeIf { it.length in 3..60 && !it.contains("LinkedIn", true) } ?: continue
                val social = listOf(SocialProfile("LinkedIn", handle, url, SocialLookupStatus.PUBLIC_MATCH))
                return@withContext PartialResult(
                    name = name, about = desc, imageUrl = img,
                    photoCandidates = img?.let { listOf(PhotoCandidate(provider = "LinkedIn", url = it, sourcePriority = 62)) } ?: emptyList(),
                    socialProfiles = social, confidence = 0.68f,
                    source = "LinkedIn Profile", providerId = id, providerVersion = version
                )
            } catch (_: Exception) { continue }
        }
        null
    }
}
