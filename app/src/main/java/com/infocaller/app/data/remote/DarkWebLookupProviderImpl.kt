package com.infocaller.app.data.remote

import android.util.Log
import com.infocaller.app.domain.engine.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import java.net.URLEncoder
import java.nio.charset.StandardCharsets


class DarkWebLookupProviderImpl : LookupProvider {
    override val id = "dark_web_intel"
    override val name = "Dark Web Intelligence"
    override val version = "3.0.0"
    override val capabilities: Set<Capability> = setOf(Capability.DARK_WEB_MENTION, Capability.INFOSTEALER_LEAK)
    override val priority: Int = 35
    override val costClass: CostClass = CostClass.FREE

    override suspend fun lookup(identifier: String, type: String, context: LookupContext): PartialResult? = withContext(Dispatchers.IO) {
        if (type != IdentifierType.PHONE && type != IdentifierType.EMAIL && type != IdentifierType.CRYPTO_WALLET
            && type != IdentifierType.USERNAME && type != IdentifierType.IP_ADDRESS && type != IdentifierType.DOMAIN) return@withContext null

        val refined = refineQuery(identifier, type)
        if (refined.isBlank()) return@withContext null
        val encoded = URLEncoder.encode(refined, StandardCharsets.UTF_8.toString())
        val exactEncoded = URLEncoder.encode("\"$identifier\"", StandardCharsets.UTF_8.toString())

        // Robin-style fan-out (clearnet-safe subset: Tor .onion direct needs Orbot/Tor daemon,
        // so on-device uses Ahmia clearnet + breach/paste indexes + onion-mention search).
        val jobs: List<Hit> = try {
            coroutineScope {
                val a = async { searchAhmia(encoded, identifier) }
                val b = async { searchDdgBreach(exactEncoded, identifier) }
                val c = async { searchDdgOnionMentions(exactEncoded, identifier) }
                awaitAll(a, b, c).flatten()
            }
        } catch (_: Exception) { emptyList() }

        val hits = dedupeAndScore(jobs, identifier).take(12)
        if (hits.isEmpty()) return@withContext null

        // Scrape top clearnet hits only (.onion skipped on-device), cap time.
        val scraped = scrapeTopHits(hits.take(5), identifier)
        val artifacts = extractArtifacts(scraped.values.joinToString("\n") + "\n" + hits.joinToString("\n") { it.title + " " + it.url })
        val summary = buildSummary(identifier, refined, hits, artifacts)

        val breachHit = hits.any { it.signal == Signal.BREACH }
        val onionHit = hits.any { it.signal == Signal.ONION }
        val confidence = when {
            breachHit && artifacts.isNotEmpty() -> 0.72f
            breachHit -> 0.65f
            onionHit -> 0.55f
            else -> 0.45f
        }
        return@withContext PartialResult(
            about = summary.take(900),
            confidence = confidence,
            source = "Dark Web Recon (Robin-style, clearnet)",
            providerId = id,
            providerVersion = version
        )
    }

    // ── Robin stage 1: query refinement (≤5 words, no logical operators) ──
    private fun refineQuery(identifier: String, type: String): String {
        val id = identifier.trim().take(120)
        if (id.isEmpty()) return ""
        return when (type) {
            IdentifierType.EMAIL -> "\"$id\""
            IdentifierType.CRYPTO_WALLET -> id.take(64)
            IdentifierType.IP_ADDRESS, IdentifierType.DOMAIN -> "$id breach leak"
            IdentifierType.USERNAME -> "\"$id\" breach"
            else -> "\"$id\" breach leak"
        }.split(Regex("\\s+")).take(5).joinToString(" ")
    }

    private enum class Signal { BREACH, ONION, GENERAL }
    private data class Hit(val title: String, val url: String, val snippet: String, val engine: String, val signal: Signal)

    private fun ua() = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/120.0 Safari/537.36"

    private fun fetchDoc(url: String, timeoutMs: Int = 7000): org.jsoup.nodes.Document? {
        return try {
            Jsoup.connect(url).userAgent(ua()).timeout(timeoutMs)
                .ignoreHttpErrors(true).followRedirects(true).maxBodySize(600_000).get()
        } catch (_: Exception) { null }
    }

    // ── Robin stage 2a: Ahmia clearnet (Tor index over HTTPS) ──
    private fun searchAhmia(encoded: String, identifier: String): List<Hit> {
        val out = mutableListOf<Hit>()
        try {
            val doc = fetchDoc("https://ahmia.fi/search/?q=$encoded") ?: return out
            doc.select("li.result").take(8).forEach { li ->
                val title = li.select("h4 a").firstOrNull()?.text()?.trim().orEmpty()
                val cite = li.select("cite").firstOrNull()?.text()?.trim().orEmpty()
                val link = li.select("h4 a").firstOrNull()?.attr("href")?.trim().orEmpty()
                if (title.isBlank() && cite.isBlank()) return@forEach
                val url = when {
                    link.contains(".onion") -> link.take(160)
                    cite.contains(".onion") -> cite.take(160)
                    link.startsWith("http") -> link.take(200)
                    else -> return@forEach
                }
                val isOnion = url.contains(".onion")
                out += Hit(
                    title = title.take(120).ifBlank { cite.take(120) },
                    url = url,
                    snippet = cite.take(160),
                    engine = "ahmia",
                    signal = if (isOnion) Signal.ONION else Signal.GENERAL
                )
            }
            // Fallback selectors if markup changed
            if (out.isEmpty()) {
                doc.select("a[href*=.onion]").map { it.attr("href") }.distinct().take(4).forEach { u ->
                    out += Hit(title = "Hidden service", url = u.take(160), snippet = "", engine = "ahmia", signal = Signal.ONION)
                }
            }
            Log.d("DarkWebLookup", "ahmia hits=${out.size}")
        } catch (e: Exception) { Log.w("DarkWebLookup", "ahmia failed: ${e.message}") }
        return out
    }

    // ── Robin stage 2b: breach/paste index via DuckDuckGo HTML ──
    private fun searchDdgBreach(exactEncoded: String, identifier: String): List<Hit> {
        val out = mutableListOf<Hit>()
        try {
            val q = URLEncoder.encode(
                "\"$identifier\" (site:pastebin.com OR site:ghostbin.com OR site:breachdirectory.org OR site:github.com OR site:leakcheck.net)",
                StandardCharsets.UTF_8.toString()
            )
            val doc = fetchDoc("https://html.duckduckgo.com/html/?q=$q") ?: return out
            out += parseDdg(doc, "ddg-breach", Signal.BREACH, 6)
            Log.d("DarkWebLookup", "ddg-breach hits=${out.size}")
        } catch (e: Exception) { Log.w("DarkWebLookup", "ddg-breach failed: ${e.message}") }
        return out
    }

    // ── Robin stage 2c: .onion mentions on clearnet ──
    private fun searchDdgOnionMentions(exactEncoded: String, identifier: String): List<Hit> {
        val out = mutableListOf<Hit>()
        try {
            val q = URLEncoder.encode("\"$identifier\" \".onion\"", StandardCharsets.UTF_8.toString())
            val doc = fetchDoc("https://html.duckduckgo.com/html/?q=$q") ?: return out
            val hits = parseDdg(doc, "ddg-onion", Signal.ONION, 5)
            out += hits
            // Also harvest raw .onion strings from page text (gateways quote onion URLs)
            try {
                val onions = Regex("[a-z2-7]{16,56}\\.onion").findAll(doc.text()).map { it.value }.distinct().take(3)
                onions.forEach { o -> out += Hit(title = "Hidden service mention", url = "http://$o", snippet = "", engine = "ddg-onion", signal = Signal.ONION) }
            } catch (_: Exception) { }
            Log.d("DarkWebLookup", "ddg-onion hits=${out.size}")
        } catch (e: Exception) { Log.w("DarkWebLookup", "ddg-onion failed: ${e.message}") }
        return out
    }

    private fun parseDdg(doc: org.jsoup.nodes.Document, engine: String, signal: Signal, limit: Int): List<Hit> {
        val out = mutableListOf<Hit>()
        doc.select("div.result").take(limit * 2).forEach { r ->
            val a = r.select("a.result__a").firstOrNull()
                ?: r.select("h2.result__title a").firstOrNull() ?: return@forEach
            val rawHref = a.attr("href")
            val url = decodeDdgUrl(rawHref)?.take(220) ?: return@forEach
            if (url.contains("duckduckgo.com")) return@forEach
            val title = a.text().trim().take(120)
            val snippet = r.select(".result__snippet").firstOrNull()?.text()?.trim().orEmpty().take(160)
            if (title.length < 4 && snippet.length < 10) return@forEach
            val breach = url.contains("pastebin") || url.contains("breachdirectory") || url.contains("leakcheck")
                || url.contains("ghostbin") || snippet.contains("breach", true) || snippet.contains("leak", true)
            out += Hit(title.ifBlank { url }, url, snippet, engine, if (breach) Signal.BREACH else signal)
            if (out.size >= limit) return out
        }
        return out
    }

    private fun decodeDdgUrl(href: String): String? {
        return try {
            if (href.isBlank()) return null
            if (href.startsWith("http")) return href
            val m = Regex("[?&]uddg=([^&]+)").find(href) ?: return null
            java.net.URLDecoder.decode(m.groupValues[1], "UTF-8")
        } catch (_: Exception) { null }
    }

    // ── Robin stage 3: dedupe + local relevance filter (no LLM on-device) ──
    private fun dedupeAndScore(hits: List<Hit>, identifier: String): List<Hit> {
        val seen = LinkedHashSet<String>()
        val uniq = hits.filter { h ->
            val key = h.url.lowercase().trimEnd('/').take(180)
            if (key.isBlank() || !seen.add(key)) false else true
        }
        val idLower = identifier.lowercase()
        return uniq.sortedByDescending { h ->
            var s = 0
            if (h.title.lowercase().contains(idLower.take(6))) s += 3
            if (h.snippet.lowercase().contains(idLower.take(6))) s += 2
            if (h.signal == Signal.BREACH) s += 3
            if (h.signal == Signal.ONION) s += 2
            if (h.url.contains("pastebin") || h.url.contains("breachdirectory") || h.url.contains("github.com")) s += 2
            s
        }
    }

    // ── Robin stage 4: scrape top clearnet pages (skip .onion without Tor) ──
    private fun scrapeTopHits(hits: List<Hit>, identifier: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        hits.filter { !it.url.contains(".onion") && it.url.startsWith("http") }.take(3).forEach { h ->
            try {
                val doc = fetchDoc(h.url, 6000) ?: return@forEach
                val text = doc.body()?.text().orEmpty()
                if (text.contains(identifier.take(6), ignoreCase = true) || text.length > 200) {
                    out[h.url] = text.take(2500)
                }
            } catch (_: Exception) { }
        }
        return out
    }

    private fun extractArtifacts(corpus: String): List<String> {
        val found = LinkedHashSet<String>()
        try {
            Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}").findAll(corpus).take(3).forEach { found += "email:${it.value.take(60)}" }
            Regex("\\b(bc1|[13])[a-zA-HJ-NP-Z0-9]{25,62}\\b").findAll(corpus).take(2).forEach { found += "btc:${it.value.take(20)}…" }
            Regex("\\b0x[a-fA-F0-9]{40}\\b").findAll(corpus).take(2).forEach { found += "eth:${it.value.take(14)}…" }
            Regex("[a-z2-7]{16,56}\\.onion").findAll(corpus).distinct().take(3).forEach { found += "onion:${it.value.take(30)}…" }
            val leakWords = listOf("combo", "stealer", "breach", "database", "marketplace", "forum")
            leakWords.filter { corpus.contains(it, true) }.take(3).forEach { found += "tag:$it" }
        } catch (_: Exception) { }
        return found.take(6)
    }

    // ── Robin stage 5: structured summary + pivots (local template, no LLM key needed) ──
    private fun buildSummary(identifier: String, refined: String, hits: List<Hit>, artifacts: List<String>): String {
        val sb = StringBuilder()
        sb.append("Dark-web recon (${hits.size} sources, q=\"${refined.take(60)}\"): ")
        hits.take(3).forEach { h ->
            val domain = try { java.net.URI(h.url).host ?: h.engine } catch (_: Exception) { h.engine }
            sb.append("• ${h.title.take(60)} [$domain]; ")
        }
        if (artifacts.isNotEmpty()) sb.append("Artifacts: ${artifacts.joinToString(", ").take(200)}. ")
        sb.append("Pivots: \"${identifier.take(24)} breach\", \"${identifier.take(24)} pastebin\", \"${identifier.take(24)} market\".")
        sb.append(" Note: full Tor engine sweep needs Tor/Orbot + server; on-device uses clearnet gateways.")
        return sb.toString()
    }
}
