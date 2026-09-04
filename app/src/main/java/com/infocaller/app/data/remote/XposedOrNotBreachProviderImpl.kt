package com.infocaller.app.data.remote

import com.google.gson.JsonParser
import com.infocaller.app.domain.engine.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * XposedOrNot free breach check — no key, CORS-open GET.
 * Endpoint: https://api.xposedornot.com/v1/check-email/{email}
 * Response: { "breaches": [[ "Breach1", ... ]] } or { "Error": "Not found" }.
 */
class XposedOrNotBreachProviderImpl(
    private val httpClient: OkHttpClient
) : LookupProvider {
    override val id = "xposedornot_breach"
    override val name = "XposedOrNot Breach Check"
    override val version = "1.0.0"
    override val capabilities = setOf(Capability.INFOSTEALER_LEAK, Capability.EMAIL, Capability.PUBLIC_SEARCH)
    override val priority = 58
    override val costClass = CostClass.FREE

    override suspend fun lookup(identifier: String, type: String, context: LookupContext): PartialResult? =
        withContext(Dispatchers.IO) {
            if (type != IdentifierType.EMAIL) return@withContext null
            val email = identifier.trim().lowercase()
            if (!email.contains("@") || email.length > 120) return@withContext null
            try {
                val url = "https://api.xposedornot.com/v1/check-email/" +
                    URLEncoder.encode(email, StandardCharsets.UTF_8.toString())
                val req = Request.Builder().url(url)
                    .header("User-Agent", "InfoCaller-OSINT/2.0")
                    .header("Accept", "application/json")
                    .build()
                val resp = httpClient.newCall(req).execute()
                val body = resp.body?.string() ?: return@withContext null
                val root = try { JsonParser.parseString(body).asJsonObject } catch (_: Exception) { return@withContext null }
                if (root.has("Error")) return@withContext null // "Not found" = clean
                val breachesEl = root.get("breaches") ?: return@withContext null
                val names = try {
                    breachesEl.asJsonArray.flatMap { inner ->
                        try { inner.asJsonArray.map { it.asString } } catch (_: Exception) { emptyList() }
                    }.distinct()
                } catch (_: Exception) { emptyList() }
                if (names.isEmpty()) return@withContext null
                PartialResult(
                    about = "Email seen in ${names.size} breach(es): ${names.take(6).joinToString(", ")}",
                    confidence = 0.85f,
                    source = "XposedOrNot (free)",
                    providerId = id, providerVersion = version
                )
            } catch (_: Exception) { null }
        }
}
