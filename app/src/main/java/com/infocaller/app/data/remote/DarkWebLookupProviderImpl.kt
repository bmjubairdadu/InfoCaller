package com.infocaller.app.data.remote

import android.util.Log
import com.infocaller.app.domain.engine.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Dark Web Intelligence Provider.
 * Uses clear-web gateways to search Tor-indexed content for identifiers.
 */
class DarkWebLookupProviderImpl : LookupProvider {
    override val id: String = "dark_web_intel"
    override val name: String = "Dark Web Intelligence"
    override val version: String = "2.0.0"
    override val capabilities: Set<Capability> = setOf(Capability.DARK_WEB_MENTION, Capability.INFOSTEALER_LEAK)
    override val priority: Int = 35
    override val costClass: CostClass = CostClass.FREE

    override suspend fun lookup(identifier: String, type: String, context: LookupContext): PartialResult? = withContext(Dispatchers.IO) {
        // Works for Phone, Email, Username, Crypto, IP, Domain - broadened
        if (type != IdentifierType.PHONE && type != IdentifierType.EMAIL && type != IdentifierType.CRYPTO_WALLET
            && type != IdentifierType.USERNAME && type != IdentifierType.IP_ADDRESS && type != IdentifierType.DOMAIN) return@withContext null

        val encodedIdentifier = URLEncoder.encode("\"$identifier\"", StandardCharsets.UTF_8.toString())
        // 100% free gateways + public breach index dorks (no key)
        val checks = listOf(
            // Ahmia (official Tor search index - clear web)
            Check("https://ahmia.fi/search/?q=$encodedIdentifier", "ahmia"),
            // Torch & Haystak via public gateways
            Check("https://onion.ws/search?q=$encodedIdentifier", "gateway"),
            Check("https://onion.pet/search?q=$encodedIdentifier", "gateway"),
            // Free breach/intel surface via search dorks using DuckDuckGo HTML (no captcha vs Google)
            Check("https://html.duckduckgo.com/html/?q=${URLEncoder.encode("site:pastebin.com OR site:github.com OR site:breachdirectory.org \"$identifier\"", StandardCharsets.UTF_8.toString())}", "ddg_breach"),
            // Intelligence X free search via clear web
            Check("https://intelx.io/search?term=$encodedIdentifier", "intelx"),
        )

        val findings = mutableListOf<String>()
        for (c in checks) {
            try {
                Log.d("DarkWebLookup", "Searching via ${c.kind}: ${c.url.take(90)}")
                val doc = Jsoup.connect(c.url)
                    .userAgent("Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/120.0 Safari/537.36")
                    .timeout(8000)
                    .ignoreHttpErrors(true)
                    .followRedirects(true)
                    .get()

                when (c.kind) {
                    "ahmia" -> {
                        val cites = doc.select("li.result cite").map { it.text() }.filter { it.contains(".onion") }
                        if (cites.isNotEmpty()) findings.add("Ahmia .onion: ${cites.take(2).joinToString(", ")}")
                        val titles = doc.select("li.result h4 a").map { it.text() }.take(3)
                        if (titles.isNotEmpty() && cites.isEmpty()) findings.add("Ahmia hits: ${titles.joinToString(" | ").take(120)}")
                    }
                    "gateway" -> {
                        val onions = doc.select("a[href*=.onion]").map { it.attr("href") }.distinct().take(3)
                        if (onions.isNotEmpty()) findings.add("Hidden service: ${onions.joinToString(", ")}")
                    }
                    "ddg_breach" -> {
                        val links = doc.select("a.result__url").map { it.attr("href") }.filter { it.isNotBlank() }.take(5)
                        val bad = links.filter { it.contains("pastebin") || it.contains("breachdirectory") || it.contains("github.com") || it.contains("intelx") }
                        if (bad.isNotEmpty()) findings.add("Breach/Paste hits: ${bad.take(3).joinToString(", ")}")
                    }
                    "intelx" -> {
                        if (doc.text().contains(identifier.take(6), ignoreCase = true) && doc.select("a").size > 5)
                            findings.add("IntelX index hit")
                    }
                }
                if (findings.size >= 2) break // enough signal, save battery
            } catch (e: Exception) {
                Log.w("DarkWebLookup", "${c.kind} failed: ${e.message}")
            }
        }

        if (findings.isNotEmpty()) {
            return@withContext PartialResult(
                about = "Dark/Breach surface: ${findings.joinToString(" | ").take(400)}",
                confidence = 0.55f,
                source = "Dark Web Recon (Ahmia+Gateways+Breach)",
                providerId = id,
                providerVersion = version
            )
        }
        return@withContext null
    }
    private data class Check(val url:String, val kind:String)
}
