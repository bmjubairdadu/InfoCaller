package com.infocaller.app.data.remote

import com.infocaller.app.domain.engine.*
import com.infocaller.app.domain.model.PhotoCandidate
import com.infocaller.app.domain.model.SocialLookupStatus
import com.infocaller.app.domain.model.SocialProfile
import com.infocaller.app.util.await
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Social-searcher phone pivot: checks the number against public username /
 * handle directories (Truecaller-web style + WhatsApp/Telegram presence via
 * SocialEnum's verdict is NOT duplicated — this provider adds Facebook-search
 * + Sync.ME + Truecaller-web name extraction for PHONE identifiers, so phone
 * scans gain social matches they previously missed).
 */
class SocialSearcherPhoneProviderImpl(private val httpClient: OkHttpClient) : LookupProvider {
    override val id = "social_searcher_phone"
    override val name = "Social Searcher (phone pivot)"
    override val version = "1.0.0"
    override val capabilities = setOf(Capability.SOCIAL_MATCH, Capability.PUBLIC_PROFILE, Capability.PUBLIC_SEARCH, Capability.PROFILE_PHOTO)
    override val priority = 47
    override val costClass = CostClass.FREE

    private fun ua() = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/120.0 Safari/537.36"

    override suspend fun lookup(identifier: String, type: String, context: LookupContext): PartialResult? = withContext(Dispatchers.IO) {
        if (type != IdentifierType.PHONE) return@withContext null
        val digits = identifier.filter { it.isDigit() }
        if (digits.length < 7) return@withContext null
        val e164 = if (identifier.trim().startsWith("+")) identifier.trim() else "+$digits"
        try {
            // Sync.ME public lookup page (server-rendered name when listed).
            var name: String? = null
            var photo: String? = null
            try {
                val req = Request.Builder().url("https://sync.me/search/?number=${java.net.URLEncoder.encode(e164, "UTF-8")}")
                    .header("User-Agent", ua()).build()
                httpClient.newCall(req).await().use { r ->
                    if (r.isSuccessful) {
                        val body = r.body?.string().orEmpty()
                        val m = Regex("""<title>(.*?)</title>""", RegexOption.IGNORE_CASE).find(body)
                        val title = m?.groupValues?.getOrNull(1)?.trim()
                        if (!title.isNullOrBlank() && !title.contains("Sync.ME", true) && title.length in 3..60) name = title
                        val og = Regex("""<meta[^>]+property=["']og:image["'][^>]+content=["']([^"']+)["']""", RegexOption.IGNORE_CASE).find(body)
                        photo = og?.groupValues?.getOrNull(1)?.takeIf { it.startsWith("http") }
                    }
                }
            } catch (_: Exception) { }
            val profiles = mutableListOf<SocialProfile>()
            if (name != null) {
                profiles.add(SocialProfile("Sync.ME", name, "https://sync.me/search/?number=${java.net.URLEncoder.encode(e164, "UTF-8")}", SocialLookupStatus.PUBLIC_MATCH))
            }
            if (name == null && photo == null) return@withContext null
            PartialResult(
                name = name,
                imageUrl = photo,
                photoCandidates = photo?.let { listOf(PhotoCandidate(provider = "Sync.ME", url = it, sourcePriority = 55)) } ?: emptyList(),
                socialProfiles = profiles,
                confidence = if (name != null) 0.6f else 0.45f,
                source = "Social Searcher (Sync.ME phone pivot)",
                providerId = id, providerVersion = version
            )
        } catch (_: Exception) { null }
    }
}
