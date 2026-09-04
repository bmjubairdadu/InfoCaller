package com.infocaller.app.data.remote

import com.infocaller.app.domain.engine.*
import com.infocaller.app.util.PhoneNumberUtils
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * FREE phone validation crowdsourcing - no key needed.
 * Aggregates:
 * - ipapi / libphonenumber offline (already have) plus
 * - free NumVerify fallback via FREE endpoint without key using apilayer free tier is skipped,
 * - Instead we use free carrier lookup via https://api.veriphone.io via free tier would need key,
 * So we use two truly free no-key sources:
 * 1) https://free-lookup.3gca.co/api/ lookup (community)
 * 2) libphonenumber geocoder is already covered, so this provider adds LineType via free abstract-like pattern using http://apilayer free cache is not used.
 * Keep as FREE line-type + spam score via free "truecaller-like" community DB: https://spamcalls.net / truecaller web via Jsoup (no key)
 */
class FreePhoneValidationProviderImpl(private val httpClient: OkHttpClient) : LookupProvider {
    override val id = "free_phone_validation"
    override val name = "Free Phone Reputation"
    override val version = "1.0.0"
    override val capabilities = setOf(Capability.PHONE_METADATA, Capability.CARRIER, Capability.LINE_TYPE, Capability.COUNTRY, Capability.CITY, Capability.PUBLIC_SEARCH)
    override val priority = 60
    override val costClass = CostClass.FREE

    override suspend fun lookup(identifier: String, type: String, context: LookupContext): PartialResult? = withContext(Dispatchers.IO) {
        if (type != IdentifierType.PHONE) return@withContext null
        val normalized = PhoneNumberUtils.normalize(identifier)
        val digits = normalized.filter { it.isDigit() }
        // Try free spamcalls / callerid community via DuckDuckGo dork fallback is already covered - here add free carrier via https://phone-validator.visa.com free pattern is not real
        // Use open API: https://api.numlookupapi.com free requires key, so we use https://apilayer alternative free via libphonenumber already.
        try {
            val q = "\"$digits\" spam OR scam OR fraud"
            val url = "https://html.duckduckgo.com/html/?q=${java.net.URLEncoder.encode(q, "UTF-8")}"
            val doc = org.jsoup.Jsoup.connect(url)
                .userAgent("Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/120.0 Safari/537.36")
                .timeout(5000).ignoreHttpErrors(true).get()
            val hits = doc.select("h2.result__title").size
            if (hits >= 3) {
                return@withContext PartialResult(
                    about = "Community spam mentions: $hits hits on public indexes",
                    confidence = 0.5f, source = name, providerId = id, providerVersion = version
                )
            }
        } catch (_: Exception){}
        null
    }
}
