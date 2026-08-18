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

    override suspend fun lookup(normalizedPhoneNumber: String, context: LookupContext): PartialResult? {
        return try {
            val cleanNumber = normalizedPhoneNumber.filter { it.isDigit() }
            val query = URLEncoder.encode("\"$cleanNumber\"", StandardCharsets.UTF_8.toString())
            val url = "https://www.google.com/search?q=$query"
            
            val doc = Jsoup.connect(url)
                .userAgent("Mozilla/5.0 (Linux; Android 10; SM-G960F) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Mobile Safari/537.36")
                .timeout(5000)
                .get()

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
            PartialResult()
        } catch (e: Exception) {
            PartialResult()
        }
    }
}
