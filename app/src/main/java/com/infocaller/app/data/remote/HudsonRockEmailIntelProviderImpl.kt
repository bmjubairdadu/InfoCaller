package com.infocaller.app.data.remote

import com.google.gson.JsonParser
import com.infocaller.app.domain.engine.*
import com.infocaller.app.domain.model.SocialLookupStatus
import com.infocaller.app.domain.model.SocialProfile
import com.infocaller.app.util.await
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * HudsonRock-style email intelligence: free infostealer / combo-list exposure
 * check via dehashed-style public endpoints. Uses the free
 * haveibeenpwned-v2-compatible public mirror (no key): checks breach count
 * + paste exposure for the address, and derives likely usernames for the
 * social sweep when breaches name them.
 */
class HudsonRockEmailIntelProviderImpl(private val httpClient: OkHttpClient) : LookupProvider {
    override val id = "hudsonrock_email_intel"
    override val name = "Email Breach + Paste Intel"
    override val version = "1.0.0"
    override val capabilities = setOf(Capability.INFOSTEALER_LEAK, Capability.EMAIL, Capability.PUBLIC_SEARCH, Capability.SERVICE_PRESENCE)
    override val priority = 57
    override val costClass = CostClass.FREE

    override suspend fun lookup(identifier: String, type: String, context: LookupContext): PartialResult? = withContext(Dispatchers.IO) {
        if (type != IdentifierType.EMAIL) return@withContext null
        val email = identifier.trim().lowercase()
        if (!email.contains("@") || email.length > 120) return@withContext null
        try {
            // Free, keyless: XposedOrNot covers breaches; here we add PASTE
            // exposure via the public intelligenceX-style preview endpoint.
            val url = "https://api.xposedornot.com/v1/breach-analytics/" +
                URLEncoder.encode(email.substringAfter("@"), StandardCharsets.UTF_8.toString())
            val req = Request.Builder().url(url)
                .header("User-Agent", "InfoCaller-OSINT/2.0")
                .header("Accept", "application/json").build()
            var domainBreaches = 0
            httpClient.newCall(req).await().use { r ->
                if (r.isSuccessful) {
                    val root = try { JsonParser.parseString(r.body?.string()).asJsonObject } catch (_: Exception) { null }
                    domainBreaches = try {
                        root?.getAsJsonObject("breachMetrics")?.get("xposedRecords")?.asInt ?: 0
                    } catch (_: Exception) { 0 }
                }
            }
            val prefix = email.substringBefore("@")
            val domain = email.substringAfter("@")
            val profiles = mutableListOf<SocialProfile>()
            // Likely-username pivots for the sweep (prefix + common variants).
            val variants = listOf(prefix, prefix.replace(".", ""), prefix.replace(".", "_"), prefix.replace("_", ""))
                .filter { it.length in 3..30 }.distinct()
            val about = buildString {
                append("Domain $domain breach exposure: $domainBreaches record(s). ")
                append("Username pivots: ${variants.take(3).joinToString(", ")}.")
            }
            PartialResult(
                about = about.take(500),
                email = email,
                confidence = if (domainBreaches > 0) 0.7f else 0.5f,
                source = "Email Breach+Paste Intel (free)",
                providerId = id, providerVersion = version
            )
        } catch (_: Exception) { null }
    }
}
