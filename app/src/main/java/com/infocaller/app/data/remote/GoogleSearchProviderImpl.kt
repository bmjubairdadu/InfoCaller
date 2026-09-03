package com.infocaller.app.data.remote

import com.infocaller.app.domain.engine.*
import org.jsoup.Jsoup
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

class GoogleSearchProviderImpl : SearchProvider {
    override val id: String = "google_osint"
    override val name: String = "Google OSINT"
    override val version: String = "1.0.0"
    override val capabilities: Set<Capability> = setOf(Capability.PUBLIC_SEARCH)
    override val priority: Int = 40
    override val costClass: CostClass = CostClass.FREE

    override suspend fun lookup(identifier: String, type: String, context: LookupContext): PartialResult? {
        val query = when (type) {
            IdentifierType.PHONE -> URLEncoder.encode("\"${identifier.filter { it.isDigit() }}\"", StandardCharsets.UTF_8.toString())
            IdentifierType.EMAIL -> URLEncoder.encode("\"$identifier\"", StandardCharsets.UTF_8.toString())
            IdentifierType.FULL_NAME -> URLEncoder.encode("\"$identifier\"", StandardCharsets.UTF_8.toString())
            IdentifierType.USERNAME -> URLEncoder.encode("\"$identifier\"", StandardCharsets.UTF_8.toString())
            else -> return null
        }
        
        return try {
            val url = "https://www.google.com/search?q=$query&gl=bd&hl=en&num=5"
            val doc = Jsoup.connect(url)
                .userAgent("Mozilla/5.0 (Linux; Android 10; SM-G960F) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Mobile Safari/537.36")
                .timeout(5000).ignoreHttpErrors(true)
                .get()
            if (doc.text().contains("Our systems have detected unusual traffic") || doc.text().contains("CAPTCHA")) return null

            val results = doc.select("h3")
            for (res in results) {
                val text = res.text()
                if (text.contains("|") || text.contains("-")) {
                    val potentialName = text.split("|", "-").first().trim()
                    
                    val isPlaceholder = potentialName.contains("Network", ignoreCase = true) || 
                                       potentialName.contains("Identity", ignoreCase = true) ||
                                       potentialName.contains("Caller", ignoreCase = true) ||
                                       potentialName.contains("Number", ignoreCase = true) ||
                                       potentialName.contains("Info", ignoreCase = true)

                    if (!isPlaceholder && potentialName.split(" ").size in 2..4) {
                        return PartialResult(
                            name = potentialName,
                            confidence = 0.6f,
                            source = name,
                            providerId = id,
                            providerVersion = version
                        )
                    }
                }
            }
            null
        } catch (e: Exception) {
            null
        }
    }

    override suspend fun bulkLookup(identifiers: List<String>, type: String, context: LookupContext): Map<String, PartialResult> {
        if (type != IdentifierType.PHONE) return emptyMap()
        return emptyMap()
    }
}
