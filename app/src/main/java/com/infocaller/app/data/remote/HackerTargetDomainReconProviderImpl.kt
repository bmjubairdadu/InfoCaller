package com.infocaller.app.data.remote

import com.google.gson.JsonParser
import com.infocaller.app.domain.engine.*
import com.infocaller.app.util.await
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Free domain + IP recon — no keys.
 *  - HackerTarget reverse-IP (free tier, rate-limited): https://api.hackertarget.com/reverseiplookup/?q=
 *  - ip-api.com free (45 req/min): https://ip-api.com/json/{ip}?fields=...
 *  - Adds co-hosted domains + hosting/ISP context useful when pivoting from email domains.
 */
class HackerTargetDomainReconProviderImpl(
    private val httpClient: OkHttpClient
) : LookupProvider {
    override val id = "hackertarget_domain_recon"
    override val name = "HackerTarget Domain Recon"
    override val version = "1.0.0"
    override val capabilities = setOf(
        Capability.CITY,
        Capability.COUNTRY, Capability.CARRIER, Capability.PUBLIC_SEARCH
    )
    override val priority = 55
    override val costClass = CostClass.FREE

    override suspend fun lookup(identifier: String, type: String, context: LookupContext): PartialResult? =
        withContext(Dispatchers.IO) {
            if (type != IdentifierType.IP_ADDRESS && type != IdentifierType.DOMAIN) return@withContext null
            val target = identifier.trim().lowercase()
            if (target.isBlank() || target.length > 120) return@withContext null
            try {
                // Strictly one-by-one: reverse-IP finishes, then IP intel.
                val cohosted: String? = try { reverseIp(target) } catch (_: Exception) { null }
                try { kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]?.ensureActive() } catch (_: Exception) { }
                val intel: PartialResult? = try { ipIntel(target) } catch (_: Exception) { null }
                if (cohosted == null && intel == null) return@withContext null
                val about = buildString {
                    if (!cohosted.isNullOrBlank()) append("Co-hosted: $cohosted. ")
                    val intelAbout = intel?.about
                    if (!intelAbout.isNullOrBlank()) append(intelAbout)
                }.trim().take(700)
                // intel carries city/country in its own PartialResult; merge here
                val base = intel ?: PartialResult(confidence = 0.5f)
                base.copy(
                    about = about.ifBlank { null },
                    confidence = if (cohosted != null) 0.65f else base.confidence,
                    source = "HackerTarget + ip-api (free)",
                    providerId = id, providerVersion = version
                )
            } catch (_: Exception) { null }
        }

    private suspend fun getText(url: String): String? {
        return try {
            val req = Request.Builder().url(url)
                .header("User-Agent", "InfoCaller-OSINT/2.0").build()
            httpClient.newCall(req).await().use { resp ->
                if (!resp.isSuccessful) return null
                resp.body?.string()?.take(4000)
            }
        } catch (_: Exception) { null }
    }

    private suspend fun reverseIp(target: String): String? {
        val body = getText("https://api.hackertarget.com/reverseiplookup/?q=$target") ?: return null
        if (body.contains("error", true) || body.contains("No records", true)) return null
        val lines = body.lines().map { it.trim() }.filter { it.isNotEmpty() && it.contains(".") }.take(8)
        if (lines.isEmpty()) return null
        return lines.joinToString(", ").take(300)
    }

    private suspend fun ipIntel(target: String): PartialResult? {
        // Only for literal IPs (v4 heuristic); domains resolve via reverse lookup text
        if (!Regex("^\\d{1,3}(\\.\\d{1,3}){3}$").matches(target)) return null
        val body = getText(
            "https://ip-api.com/json/$target?fields=status,country,regionName,city,timezone,isp,org,as,mobile,proxy,hosting"
        ) ?: return null
        val root = try { JsonParser.parseString(body).asJsonObject } catch (_: Exception) { return null }
        if (root.get("status")?.asString != "success") return null
        fun s(k: String) = root.get(k)?.takeIf { !it.isJsonNull }?.asString
        val hosting = try { root.get("hosting")?.asBoolean } catch (_: Exception) { null }
        return PartialResult(
            city = s("city"), country = s("country"), region = s("regionName"),
            timezone = s("timezone"), carrier = s("isp"),
            about = listOfNotNull(
                s("org")?.let { "ORG: $it" },
                s("as")?.let { "AS: $it" },
                hosting?.let { if (it) "hosting/DC range" else null }
            ).joinToString(", ").takeIf { it.isNotBlank() },
            isBusiness = hosting,
            confidence = 0.9f
        )
    }
}
