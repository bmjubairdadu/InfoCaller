package com.infocaller.app.data.remote

import com.infocaller.app.domain.engine.*
import com.infocaller.app.util.await
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

class SocialSearcherPhoneProviderImpl(private val httpClient: OkHttpClient) : LookupProvider {
    override val id = "social_searcher_phone"
    override val name = "Social Searcher (phone pivot)"
    override val version = "1.0.0"
    override val capabilities = setOf(Capability.PUBLIC_PROFILE, Capability.PUBLIC_SEARCH)
    override val priority = 47
    override val costClass = CostClass.FREE

    private fun ua() = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/120.0 Safari/537.36"

    override suspend fun lookup(identifier: String, type: String, context: LookupContext): PartialResult? = withContext(Dispatchers.IO) {
        if (type != IdentifierType.PHONE) return@withContext null
        val digits = identifier.filter { it.isDigit() }
        if (digits.length < 7) return@withContext null
        val e164 = if (identifier.trim().startsWith("+")) identifier.trim() else "+$digits"
        try {
            var name: String? = null
            try {
                val req = Request.Builder().url("https://sync.me/search/?number=${java.net.URLEncoder.encode(e164, "UTF-8")}")
                    .header("User-Agent", ua()).build()
                httpClient.newCall(req).await().use { r ->
                    if (r.isSuccessful) {
                        val body = r.body?.string().orEmpty()
                        val m = Regex("""<title>(.*?)</title>""", RegexOption.IGNORE_CASE).find(body)
                        val title = m?.groupValues?.getOrNull(1)?.trim()
                        // Name pivot only. Never use sync.me og:image (site logo) as a
                        // profile photo, never emit a Sync.ME "account".
                        if (!title.isNullOrBlank() && !title.contains("Sync.ME", true) &&
                            !title.contains("not found", true) && title.length in 3..60) name = title
                    }
                }
            } catch (_: Exception) { }
            if (name == null) return@withContext null
            PartialResult(
                name = name,
                imageUrl = null,
                photoCandidates = emptyList(),
                socialProfiles = emptyList(),
                confidence = 0.6f,
                source = "Social Searcher (Sync.ME phone pivot)",
                providerId = id, providerVersion = version
            )
        } catch (_: Exception) { null }
    }
}
