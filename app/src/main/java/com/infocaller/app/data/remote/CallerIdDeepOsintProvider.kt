package com.infocaller.app.data.remote

import com.infocaller.app.domain.engine.*
import com.infocaller.app.domain.model.SocialLookupStatus
import com.infocaller.app.domain.model.SocialProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

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
        val digits = try { identifier.filter { it.isDigit() } } catch (_: Exception) { return@withContext null } catch (_: Error) { return@withContext null }
        if (digits.length < 7 || digits.length > 15) return@withContext null
        val e164 = try { if (identifier.trim().startsWith("+")) identifier.trim().take(20) else "+$digits" } catch (_: Exception) { "+$digits" } catch (_: Error) { "+$digits" }

        var webName: String? = null
        // No-login Truecaller web title — capped + cancellable, never OOM (no Jsoup DOM).
        try {
            coroutineContext.ensureActive()
            val tail = try { digits.takeLast(10).let { t -> if (digits.startsWith("880")) digits.substring(3).takeLast(10) else t } } catch (_: Exception) { digits.takeLast(10) } catch (_: Error) { digits.takeLast(10) }
            for (path in listOf("bd/$tail", "search/$tail")) {
                try {
                    coroutineContext.ensureActive()
                    val title = com.infocaller.app.util.SafeWebFetch.fetchTitle(
                        httpClient, "https://www.truecaller.com/$path",
                        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/120.0 Safari/537.36", 4500L
                    )
                    val cand = com.infocaller.app.util.SafeWebFetch.truecallerTitleToName(title)
                    if (cand != null) { webName = cand.take(40); break }
                } catch (_: Exception) { continue } catch (_: Error) { continue }
            }
        } catch (_: Exception) { } catch (_: Error) { }

        var spamAbout: String? = null
        // Spam hint via capped DuckDuckGo HTML fetch (no Jsoup DOM — regex count only).
        try {
            coroutineContext.ensureActive()
            val q = "\"$e164\" spam OR scam OR fraud"
            val url = "https://html.duckduckgo.com/html/?q=${URLEncoder.encode(q, StandardCharsets.UTF_8.toString())}"
            val body = com.infocaller.app.util.SafeWebFetch.fetchBodyCapped(
                httpClient, url,
                "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/120.0 Safari/537.36", 5000L, 80_000
            )
            if (body != null) {
                // Count result anchors without building a DOM.
                val hits = try { Regex("""result__a""").findAll(body.take(80_000)).take(20).count() } catch (_: Exception) { 0 } catch (_: Error) { 0 }
                if (hits >= 2) spamAbout = "Public spam mentions: $hits hits"
            }
        } catch (_: Exception) { } catch (_: Error) { }

        if (webName == null && spamAbout == null) return@withContext null

        return@withContext PartialResult(
            name = webName,
            imageUrl = null,
            photoCandidates = emptyList(),
            about = spamAbout,
            confidence = if (webName != null) 0.6f else 0.45f,
            source = "CallerID Deep OSINT (Truecaller Web + Spam)",
            providerId = id, providerVersion = version
        )
    }
}
