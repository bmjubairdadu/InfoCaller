package com.infocaller.app.data.remote

import com.infocaller.app.domain.engine.*
import com.infocaller.app.util.await
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

class PhoneSocialBridgeProviderImpl(private val httpClient: OkHttpClient) : LookupProvider {
    override val id = "phone_social_bridge"
    override val name = "Phone → Social Bridge"
    override val version = "1.0.0"
    override val capabilities = setOf(Capability.PUBLIC_PROFILE, Capability.PUBLIC_SEARCH)
    override val priority = 49
    override val costClass = CostClass.FREE

    private fun ua() = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/120.0 Safari/537.36"

    override suspend fun lookup(identifier: String, type: String, context: LookupContext): PartialResult? = withContext(Dispatchers.IO) {
        if (type != IdentifierType.PHONE) return@withContext null
        val digits = identifier.filter { it.isDigit() }
        if (digits.length < 7) return@withContext null
        val e164 = if (identifier.trim().startsWith("+")) identifier.trim() else "+$digits"
        var name: String? = null
        for (path in listOf("bd/${digits.takeLast(10)}", "search/${digits.takeLast(10)}")) {
            try {
                val doc = org.jsoup.Jsoup.connect("https://www.truecaller.com/$path")
                    .userAgent(ua()).timeout(6000).ignoreHttpErrors(true).followRedirects(true).get()
                val title = doc.select("title").text()
                val cand = title.substringBefore("- Truecaller").trim()
                    .takeIf { it.length in 3..50 && !it.contains("Truecaller", true) }
                if (cand != null) { name = cand; break }
            } catch (_: Exception) { }
        }
        try {
            val req = Request.Builder()
                .url("https://sync.me/search/?number=${java.net.URLEncoder.encode(e164, "UTF-8")}")
                .header("User-Agent", ua()).build()
            httpClient.newCall(req).await().use { r ->
                if (r.isSuccessful) {
                    val body = r.body?.string().orEmpty()
                    val title = Regex("""<title>(.*?)</title>""", RegexOption.IGNORE_CASE)
                        .find(body)?.groupValues?.getOrNull(1)?.trim()
                    if (!title.isNullOrBlank() && !title.contains("Sync.ME", true) &&
                        !title.contains("not found", true) && title.length in 3..60) {
                        if (name == null) name = title
                    }
                    // Never use sync.me og:image: it is the sync.me site logo,
                    // not the person's profile photo. Ignore it + SEO description.
                }
            }
        } catch (_: Exception) { }
        // Never emit Sync.ME / blind wa.me / t.me profiles: none of these prove a
        // real account opened with this number. Only verified scrapes may add them.
        if (name == null) return@withContext null
        return@withContext PartialResult(
            name = name, about = null, imageUrl = null,
            photoCandidates = emptyList(),
            socialProfiles = emptyList(),
            confidence = 0.62f,
            source = "Phone → Social Bridge", providerId = id, providerVersion = version
        )
    }
}
