package com.infocaller.app.data.remote

import com.infocaller.app.domain.engine.*
import org.jsoup.Jsoup
import java.net.URLEncoder
import java.nio.charset.StandardCharsets


class BingSearchProviderImpl : LookupProvider {
    override val id = "bing_osint"
    override val name = "Bing OSINT"
    override val version = "1.0.0"
    override val capabilities = setOf(Capability.PUBLIC_SEARCH, Capability.PUBLIC_PROFILE)
    override val priority = 38
    override val costClass = CostClass.FREE

    override suspend fun lookup(identifier: String, type: String, context: LookupContext): PartialResult? {
        val q = when(type){
            IdentifierType.PHONE -> "\"${identifier.filter { it.isDigit() }}\""
            IdentifierType.EMAIL -> "\"$identifier\""
            IdentifierType.USERNAME, IdentifierType.FULL_NAME -> "\"$identifier\""
            else -> return null
        }
        val url = "https://www.bing.com/search?q=${URLEncoder.encode(q, StandardCharsets.UTF_8.toString())}&setlang=en"
        return try {
            val doc = Jsoup.connect(url)
                .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/120.0 Safari/537.36")
                .timeout(7000)
                .ignoreHttpErrors(true)
                .get()
            val titles = doc.select("h2 a, h2")
            for (el in titles.take(5)) {
                val t = el.text()
                if ((t.contains("|") || t.contains("-")) && t.length in 5..50) {
                    val potential = t.split("|","-","—",":").first().trim()
                    val words = potential.split(" ").size
                    val bad = potential.contains("Bing",true) || potential.contains("Microsoft",true)
                    if (!bad && words in 2..4) {
                        return PartialResult(name = potential, confidence = 0.5f, source = name, providerId = id, providerVersion = version)
                    }
                }
            }
            null
        } catch (_: Exception){ null }
    }
}
