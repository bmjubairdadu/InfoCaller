package com.infocaller.app.data.remote

import android.util.Log
import com.infocaller.app.domain.engine.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import java.net.URLEncoder
import java.nio.charset.StandardCharsets


class LeakLookupProviderImpl : LookupProvider {
    override val id: String = "leak_intel"
    override val name: String = "Leak Intelligence"
    override val version: String = "1.0.0"
    override val capabilities: Set<Capability> = setOf(Capability.INFOSTEALER_LEAK)
    override val priority: Int = 40
    override val costClass: CostClass = CostClass.FREE

    override suspend fun lookup(identifier: String, type: String, context: LookupContext): PartialResult? = withContext(Dispatchers.IO) {
        if (type != IdentifierType.PHONE && type != IdentifierType.EMAIL && type != IdentifierType.USERNAME) return@withContext null
        val qPhone = identifier.filter { it.isDigit() }.takeIf { it.length >= 7 } ?: identifier
        try {
            val query = "\"$qPhone\" site:intelx.io OR site:dehashed.com OR site:breachdirectory.org OR site:pastebin.com OR site:github.com"
            val encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8.toString())
            val searchUrl = "https://html.duckduckgo.com/html/?q=$encodedQuery"

            val doc = Jsoup.connect(searchUrl)
                .userAgent("Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/120.0 Safari/537.36")
                .timeout(7000)
                .ignoreHttpErrors(true)
                .get()

            val leakSources = mutableListOf<String>()
            doc.select("a.result__url, a.result__a").forEach { a ->
                val href = a.attr("href")
                if (href.contains("intelx.io")) leakSources.add("Intelligence X")
                if (href.contains("dehashed.com")) leakSources.add("Dehashed")
                if (href.contains("breachdirectory.org")) leakSources.add("BreachDirectory")
                if (href.contains("pastebin.com")) leakSources.add("Pastebin")
                if (href.contains("github.com")) leakSources.add("GitHub Leak")
            }

            if (leakSources.isNotEmpty()) {
                return@withContext PartialResult(
                    about = "Potential exposure found in: ${leakSources.distinct().joinToString(", ")}",
                    confidence = 0.6f,
                    source = "Breach Discovery (Free DDG)",
                    providerId = id,
                    providerVersion = version
                )
            }
        } catch (e: Exception) {
            Log.e("LeakLookup", "Error checking for leaks", e)
        }
        return@withContext null
    }

    override suspend fun bulkLookup(identifiers: List<String>, type: String, context: LookupContext): Map<String, PartialResult> = withContext(Dispatchers.IO) {
        if (type != IdentifierType.PHONE) return@withContext emptyMap()
        emptyMap()
    }
}
