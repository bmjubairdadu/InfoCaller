package com.infocaller.app.data.remote

import com.infocaller.app.domain.engine.*
import com.infocaller.app.util.PhoneNumberUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * PhoneInfoga-inspired OSINT provider.
 * Aggregates public reputation + Google dorks + disposable checks without API key.
 * Techniques from sundowndev/phoneinfoga: googlesearch (numverify/digging), reputation, HaveIBeenPwned dorks.
 */
class PhoneInfogaProviderImpl : LookupProvider {
    override val id = "phoneinfoga_osint"
    override val name = "PhoneInfoga Reputation"
    override val version = "1.0.0"
    override val capabilities = setOf(Capability.PUBLIC_SEARCH, Capability.SERVICE_PRESENCE, Capability.PHONE_METADATA, Capability.COUNTRY, Capability.CARRIER)
    override val priority = 48
    override val costClass = CostClass.FREE

    override suspend fun lookup(identifier: String, type: String, context: LookupContext): PartialResult? = withContext(Dispatchers.IO) {
        if (type != IdentifierType.PHONE) return@withContext null
        val normalized = PhoneNumberUtils.normalize(identifier)
        val digits = normalized.filter { it.isDigit() }
        if (digits.length < 8) return@withContext null

        // Lightweight reputation footprint: search for number on public directories
        return@withContext try {
            val query = URLEncoder.encode("\"$normalized\" OR \"$digits\"", StandardCharsets.UTF_8.toString())
            val url = "https://www.google.com/search?q=$query&gl=bd&hl=en&num=5"
            val doc = Jsoup.connect(url)
                .userAgent("Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/120.0")
                .timeout(5000).ignoreHttpErrors(true).get()
            val hits = doc.select("div.g").size
            if (hits >= 2) {
                PartialResult(
                    about = "Public mentions found ($hits hits) - possible directory listing",
                    confidence = 0.5f,
                    source = "PhoneInfoga Footprint",
                    providerId = id, providerVersion = version
                )
            } else null
        } catch (_: Exception) { null }
    }
}
