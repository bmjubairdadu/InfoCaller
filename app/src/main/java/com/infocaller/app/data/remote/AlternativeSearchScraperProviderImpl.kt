package com.infocaller.app.data.remote

import com.infocaller.app.domain.engine.*
import com.infocaller.app.domain.model.SocialLookupStatus
import com.infocaller.app.domain.model.SocialProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Alternative search-engine scrapers — free, no key.
 * Google frequently CAPTCHAs on-device; these three render server-side
 * and rarely block stock mobile UAs:
 *  - Mojeek:   https://www.mojeek.com/search?q=
 *  - Brave:    https://search.brave.com/search?q=
 *  - Startpage:https://www.startpage.com/sp/search?query= (HTML fallback)
 * Fans out in parallel, returns first social/name evidence found.
 */
class AlternativeSearchScraperProviderImpl : LookupProvider {
    override val id = "alt_search_scraper"
    override val name = "Alt Search Scraper"
    override val version = "1.0.0"
    override val capabilities = setOf(Capability.PUBLIC_SEARCH, Capability.SOCIAL_MATCH, Capability.PUBLIC_PROFILE)
    override val priority = 39
    override val costClass = CostClass.FREE

    private fun ua() =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/120.0 Safari/537.36"

    override suspend fun lookup(identifier: String, type: String, context: LookupContext): PartialResult? =
        withContext(Dispatchers.IO) {
            val query = when (type) {
                IdentifierType.PHONE -> {
                    val digits = identifier.filter { it.isDigit() }
                    if (digits.length < 7) return@withContext null
                    "\"$digits\" (site:facebook.com OR site:instagram.com OR site:linkedin.com OR site:tiktok.com OR site:truecaller.com)"
                }
                IdentifierType.EMAIL -> if (identifier.contains("@")) "\"${identifier.trim()}\"" else return@withContext null
                IdentifierType.USERNAME, IdentifierType.FULL_NAME -> {
                    val q = identifier.trim()
                    if (q.length < 3) return@withContext null
                    "\"$q\""
                }
                IdentifierType.DOMAIN -> "site:${identifier.trim()}"
                else -> return@withContext null
            }
            val enc = URLEncoder.encode(query, StandardCharsets.UTF_8.toString())
            val results: List<Pair<String, String>> = try {
                coroutineScope {
                    val a = async { scrapeMojeek(enc) }
                    val b = async { scrapeBrave(enc) }
                    val c = async { scrapeStartpage(enc) }
                    awaitAll(a, b, c).flatten()
                }
            } catch (_: Exception) { emptyList() }
            if (results.isEmpty()) return@withContext null

            val socials = results.mapNotNull { hit ->
                val href = hit.second
                when {
                    href.contains("facebook.com/") && href.length > 25 -> SocialProfile("Facebook", null, href, SocialLookupStatus.PUBLIC_MATCH)
                    href.contains("instagram.com/") && !href.contains("/p/") -> SocialProfile("Instagram", null, href, SocialLookupStatus.PUBLIC_MATCH)
                    href.contains("linkedin.com/in/") -> SocialProfile("LinkedIn", null, href, SocialLookupStatus.PUBLIC_MATCH)
                    href.contains("tiktok.com/@") -> SocialProfile("TikTok", null, href, SocialLookupStatus.PUBLIC_MATCH)
                    href.contains("github.com/") && href.count { it == '/' } <= 4 -> SocialProfile("GitHub", null, href, SocialLookupStatus.PUBLIC_MATCH)
                    else -> null
                }
            }.distinctBy { it.profileUrl }.take(5)
            if (socials.isNotEmpty()) {
                return@withContext PartialResult(
                    socialProfiles = socials, confidence = 0.55f,
                    source = "Alt Search (Mojeek/Brave/Startpage)",
                    providerId = id, providerVersion = version
                )
            }
            // Fallback: name-like first title
            for (hit in results.take(6)) {
                val title = hit.first
                if ((title.contains("|") || title.contains("-") || title.contains("·")) && title.length in 6..60) {
                    val candidate = title.split("|", "-", "·", ":").first().trim()
                    val words = candidate.split(" ").size
                    val bad = candidate.contains("Mojeek", true) || candidate.contains("Brave", true) ||
                        candidate.contains("Startpage", true) || candidate.contains("Search", true)
                    if (!bad && words in 2..4 && candidate.length in 5..36) {
                        return@withContext PartialResult(
                            name = candidate, confidence = 0.48f, source = name,
                            providerId = id, providerVersion = version
                        )
                    }
                }
            }
            val first = results.firstOrNull() ?: return@withContext null
            PartialResult(
                about = "Public mention: ${first.first.take(180)}",
                confidence = 0.38f, source = name,
                providerId = id, providerVersion = version
            )
        }

    private data class Hit(val title: String, val href: String)

    private fun scrapeMojeek(enc: String): List<Pair<String, String>> {
        return try {
            val doc = Jsoup.connect("https://www.mojeek.com/search?q=$enc")
                .userAgent(ua()).timeout(7000).ignoreHttpErrors(true).get()
            doc.select("a.title, h2 a, ul.results-standard li a").mapNotNull {
                val h = it.attr("abs:href").ifBlank { it.attr("href") }
                val t = it.text().trim()
                if (h.startsWith("http") && t.isNotBlank()) t to h else null
            }.take(6)
        } catch (_: Exception) { emptyList() }
    }

    private fun scrapeBrave(enc: String): List<Pair<String, String>> {
        return try {
            val doc = Jsoup.connect("https://search.brave.com/search?q=$enc")
                .userAgent(ua()).timeout(7000).ignoreHttpErrors(true).get()
            doc.select("div.result a[href], a.result-header").mapNotNull {
                val h = it.attr("abs:href").ifBlank { it.attr("href") }
                val t = it.text().trim()
                if (h.startsWith("http") && t.isNotBlank() && !h.contains("brave.com")) t to h else null
            }.distinctBy { it.second }.take(6)
        } catch (_: Exception) { emptyList() }
    }

    private fun scrapeStartpage(enc: String): List<Pair<String, String>> {
        return try {
            val doc = Jsoup.connect("https://www.startpage.com/sp/search?query=$enc")
                .userAgent(ua()).timeout(7000).ignoreHttpErrors(true).get()
            doc.select("a.result-link, div.w-gl__result a").mapNotNull {
                val h = it.attr("abs:href").ifBlank { it.attr("href") }
                val t = it.text().trim()
                if (h.startsWith("http") && t.isNotBlank() && !h.contains("startpage.com")) t to h else null
            }.distinctBy { it.second }.take(6)
        } catch (_: Exception) { emptyList() }
    }
}
