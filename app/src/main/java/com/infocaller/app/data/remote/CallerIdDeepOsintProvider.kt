package com.infocaller.app.data.remote

import com.infocaller.app.domain.engine.*
import com.infocaller.app.domain.model.SocialLookupStatus
import com.infocaller.app.domain.model.SocialProfile
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Deep OSINT Caller ID - 100% FREE, no key.
 * Aggregates ideas from caller-id topic repos:
 * - Benojir/Caller-ID: search5-noneu is primary (Truecaller already handles), here we add spam + address deep pivot
 * - BioHazard786/Alternate: local privacy-first enrichment
 * - Combines truecaller web + OSINT dorks + spam reputation (like truecallerjs getSpamInfo)
 * This provider does HTTP-only OSINT that truecallerjs can't: truecaller web page scrape + spam reputation + alternate dorks
 */
class CallerIdDeepOsintProvider(
    private val httpClient: OkHttpClient
) : LookupProvider {
    override val id = "callerid_deep_osint"
    override val name = "Caller ID Deep OSINT"
    override val version = "1.0.0"
    override val capabilities = setOf(Capability.PUBLIC_SEARCH, Capability.PUBLIC_PROFILE, Capability.SOCIAL_MATCH, Capability.DARK_WEB_MENTION)
    override val priority = 48
    override val costClass = CostClass.FREE

    override suspend fun lookup(identifier: String, type: String, context: LookupContext): PartialResult? = withContext(Dispatchers.IO) {
        if (type != IdentifierType.PHONE) return@withContext null
        val digits = identifier.filter { it.isDigit() }
        if (digits.length < 7) return@withContext null
        val e164 = if (identifier.trim().startsWith("+")) identifier.trim() else "+$digits"

        // 1) Truecaller web page scrape (public, no auth) - name fallback
        var webName: String? = null
        var webImage: String? = null
        try {
            val clean = digits.takeLast(10).let { if (digits.startsWith("880")) digits.substring(3) else it }
            val tcWeb = "https://www.truecaller.com/search/bd/$clean"
            val doc = Jsoup.connect(tcWeb)
                .userAgent("Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/120.0 Safari/537.36")
                .timeout(5000).ignoreHttpErrors(true).followRedirects(true).get()
            val title = doc.select("title").text()
            // Truecaller web title is like "John Doe - Truecaller"
            if (title.contains("- Truecaller", true)) {
                webName = title.substringBefore("- Truecaller").trim().takeIf { it.length in 3..40 && !it.contains("Truecaller", true) }
            }
            webImage = doc.select("meta[property=og:image]").attr("content").takeIf { it.startsWith("http") }
        } catch (_: Exception) {}

        // 2) Spam reputation via free dork (sumithemmadi spamInfo equivalent via web)
        var spamAbout: String? = null
        try {
            val q = "\"$e164\" spam OR scam OR fraud site:truecaller.com OR site:whocallsme.com OR site:shouldianswer"
            val url = "https://html.duckduckgo.com/html/?q=${URLEncoder.encode(q, StandardCharsets.UTF_8.toString())}"
            val doc = Jsoup.connect(url).userAgent("Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/120.0 Safari/537.36")
                .timeout(6000).ignoreHttpErrors(true).get()
            val hits = doc.select("a.result__a").size
            if (hits >= 2) spamAbout = "Public spam mentions: $hits hits"
        } catch (_: Exception) {}

        if (webName == null && webImage == null && spamAbout == null) return@withContext null

        val photoCandidates = mutableListOf<com.infocaller.app.domain.model.PhotoCandidate>()
        if (!webImage.isNullOrBlank()) photoCandidates.add(com.infocaller.app.domain.model.PhotoCandidate(provider="Truecaller Web", url=webImage, sourcePriority=55, timestamp=System.currentTimeMillis()))

        return@withContext PartialResult(
            name = webName,
            imageUrl = webImage,
            photoCandidates = photoCandidates,
            about = spamAbout,
            confidence = if (webName != null) 0.6f else 0.45f,
            source = "CallerID Deep OSINT (Truecaller Web + Spam)",
            providerId = id, providerVersion = version
        )
    }
}
