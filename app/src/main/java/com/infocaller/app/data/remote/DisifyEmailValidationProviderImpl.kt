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
 * Disify free email validation — no key.
 * Endpoint: https://api.disify.com/api/email/{email}
 * Response: { "format": bool, "domain": "...", "dns": bool,
 *   "disposable": bool, "whitelist": bool, ... }
 */
class DisifyEmailValidationProviderImpl(
    private val httpClient: OkHttpClient
) : LookupProvider {
    override val id = "disify_email_validation"
    override val name = "Disify Email Validation"
    override val version = "1.0.0"
    override val capabilities = setOf(Capability.EMAIL, Capability.PUBLIC_PROFILE)
    override val priority = 62
    override val costClass = CostClass.FREE

    override suspend fun lookup(identifier: String, type: String, context: LookupContext): PartialResult? =
        withContext(Dispatchers.IO) {
            if (type != IdentifierType.EMAIL) return@withContext null
            val email = identifier.trim().lowercase()
            if (!email.contains("@") || email.length > 120) return@withContext null
            try {
                val url = "https://api.disify.com/api/email/" +
                    URLEncoder.encode(email, StandardCharsets.UTF_8.toString())
                val req = Request.Builder().url(url)
                    .header("User-Agent", "InfoCaller-OSINT/2.0")
                    .header("Accept", "application/json")
                    .build()
                val resp = httpClient.newCall(req).execute()
                if (!resp.isSuccessful) return@withContext null
                val body = resp.body?.string() ?: return@withContext null
                val root = try { JsonParser.parseString(body).asJsonObject } catch (_: Exception) { return@withContext null }
                val format = root.get("format")?.asBoolean ?: return@withContext null
                if (!format) {
                    return@withContext PartialResult(
                        about = "Invalid email format.",
                        confidence = 0.9f, source = name,
                        providerId = id, providerVersion = version
                    )
                }
                val dns = root.get("dns")?.asBoolean ?: false
                val disposable = root.get("disposable")?.asBoolean ?: false
                val domain = root.get("domain")?.asString
                val about = buildString {
                    append("Email valid format")
                    append(if (dns) " • domain has MX/DNS" else " • domain DNS unverified")
                    if (disposable) append(" • DISPOSABLE address")
                    if (!domain.isNullOrBlank()) append(" • domain: $domain")
                }
                PartialResult(
                    about = about,
                    isDisposable = disposable,
                    confidence = if (disposable) 0.8f else 0.6f,
                    source = "Disify (free)",
                    providerId = id, providerVersion = version
                )
            } catch (_: Exception) { null }
        }
}
