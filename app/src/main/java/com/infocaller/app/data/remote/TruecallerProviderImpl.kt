package com.infocaller.app.data.remote

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.infocaller.app.domain.engine.*
import com.infocaller.app.util.PhoneNumberUtils
import com.infocaller.app.util.await
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class TruecallerProviderImpl(private val context: Context) : LookupProvider {
    override val id: String = "truecaller_authorized"
    override val name: String = "Truecaller"
    override val version: String = "3.1.0"
    override val capabilities: Set<Capability> = setOf(
        Capability.PUBLIC_SEARCH,
        Capability.PROFILE_PHOTO,
        Capability.CITY,
        Capability.COUNTRY,
        Capability.TIMEZONE,
        Capability.CARRIER,
        Capability.ALTERNATE_NAME,
        Capability.EMAIL
    )
    override val priority: Int = 85
    override val costClass: CostClass = CostClass.LOW

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(9, TimeUnit.SECONDS)
        .readTimeout(9, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .build()

    private val gson = Gson()
    @Volatile private var lastFailureReason: String? = null
    fun describeLastFailure(): String? = lastFailureReason

    override suspend fun lookup(identifier: String, type: String, context: LookupContext): PartialResult? = withContext(Dispatchers.IO) {
        try {
            if (type != IdentifierType.PHONE) return@withContext null
            val token = try { getAuthToken() } catch (_: Exception) { null } catch (_: Error) { null }
            if (token == null) {
                Log.w("Truecaller", "No installationId - trying no-login web fallback.")
                // No-login fallback keeps Truecaller useful even before OTP login.
                return@withContext tryWebFallback(identifier)
            }

            val countryCode = try { PhoneNumberUtils.getCountryCode(identifier) } catch (_: Exception) { null } catch (_: Error) { null } ?: "BD"
            val significant = try { PhoneNumberUtils.getSignificantNumber(identifier) } catch (_: Exception) { null } catch (_: Error) { null }
                ?: try { identifier.filter { it.isDigit() } } catch (_: Exception) { "" } catch (_: Error) { "" }
            if (significant.isBlank()) {
                lastFailureReason = "empty significant number"
                return@withContext tryWebFallback(identifier)
            }
            val apiResult = try { trySearch5(significant, countryCode, token) } catch (_: Exception) { null } catch (_: Error) { null }
            if (apiResult != null) return@withContext apiResult
            // API failed (expired token / protobuf / rate-limit): web fallback still gives a name.
            return@withContext tryWebFallback(identifier)
        } catch (_: Exception) { null } catch (_: Error) { null }
    }

    /**
     * No-login Truecaller web fallback. Capped + cancellable + never OOM:
     * uses [com.infocaller.app.util.SafeWebFetch] title fetch instead of Jsoup DOM.
     * Returns a light name-only result (or null) — never throws.
     */
    private suspend fun tryWebFallback(identifier: String): PartialResult? {
        return try {
            coroutineContext.ensureActive()
            val digits = try { identifier.filter { it.isDigit() } } catch (_: Exception) { "" } catch (_: Error) { "" }
            if (digits.length < 7 || digits.length > 15) return null
            val tail = try { digits.takeLast(10) } catch (_: Exception) { return null } catch (_: Error) { return null }
            if (tail.length < 7) return null
            var name: String? = null
            for (path in listOf("bd/$tail", "search/$tail")) {
                try {
                    coroutineContext.ensureActive()
                    val title = com.infocaller.app.util.SafeWebFetch.fetchTitle(
                        httpClient, "https://www.truecaller.com/$path",
                        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/120.0 Safari/537.36",
                        4500L
                    )
                    val cand = com.infocaller.app.util.SafeWebFetch.truecallerTitleToName(title)
                    if (cand != null) { name = cand.take(50); break }
                } catch (_: Exception) { continue } catch (_: Error) { continue }
            }
            if (name.isNullOrBlank()) {
                lastFailureReason = lastFailureReason ?: "no Truecaller record"
                return null
            }
            lastFailureReason = null
            PartialResult(
                name = name,
                confidence = 0.6f,
                source = "Truecaller",
                providerId = id, providerVersion = version
            )
        } catch (_: Exception) { null } catch (_: Error) { null }
    }

    override suspend fun bulkLookup(identifiers: List<String>, type: String, context: LookupContext): Map<String, PartialResult> = withContext(Dispatchers.IO) {
        try {
            if (type != IdentifierType.PHONE || identifiers.isEmpty()) return@withContext emptyMap()
            val token = try { getAuthToken() } catch (_: Exception) { null } catch (_: Error) { null } ?: return@withContext emptyMap()
            val chunk = try { identifiers.take(30) } catch (_: Exception) { emptyList() } catch (_: Error) { emptyList() }
            if (chunk.isEmpty()) return@withContext emptyMap()
            tryBulkSearch(chunk, token)
        } catch (_: Exception) { emptyMap() } catch (_: Error) { emptyMap() }
    }
    private suspend fun trySearch5(q: String, countryCode: String, token: String): PartialResult? {
        val cleanQuery = try { q.filter { it.isDigit() } } catch (_: Exception) { "" } catch (_: Error) { "" }
        if (cleanQuery.isEmpty() || cleanQuery.length > 15) {
            lastFailureReason = "empty significant number"
            return null
        }
        val safeToken = try { token.trim().take(300) } catch (_: Exception) { token } catch (_: Error) { token }
        if (safeToken.length < 12) {
            lastFailureReason = "missing installationId"
            return null
        }
        val encQ = try { URLEncoder.encode(cleanQuery, "UTF-8") } catch (_: Exception) { cleanQuery } catch (_: Error) { cleanQuery }
        val region = try { countryCode.uppercase().takeIf { it.length == 2 } } catch (_: Exception) { null } catch (_: Error) { null } ?: "BD"
        val encCc = try { URLEncoder.encode(region, "UTF-8") } catch (_: Exception) { region } catch (_: Error) { region }

        val hosts = listOf(
            "https://search5-noneu.truecaller.com",
            "https://search5-asia-south1.truecaller.com"
        )

        var lastError: String? = null
        for (host in hosts) {
            try {
                coroutineContext.ensureActive()
                // truecallerjs-style query: placement increases hit-rate on search5 hosts.
                val url = "$host/v2/search?q=$encQ&countryCode=$encCc&type=4&placement=SEARCHRESULTS,HISTORY,DETAILS&encoding=json"
                val req = Request.Builder().url(url)
                    .addHeader("Authorization", "Bearer $safeToken")
                    .addHeader("content-type", "application/json; charset=UTF-8")
                    .addHeader("Accept", "application/json")
                    .addHeader("Accept-Language", "en-US,en;q=0.9")
                    .addHeader("Accept-Encoding", "gzip")
                    .addHeader("User-Agent", "Truecaller/11.75.5 (Android;10)")
                    .build()

                val result = try {
                    httpClient.newCall(req).await().use { resp ->
                        val code = try { resp.code } catch (_: Exception) { -1 } catch (_: Error) { -1 }
                        if (code == 401 || code == 403) {
                            val probe = try { resp.peekBody(512).string().take(200) } catch (_: Exception) { null } catch (_: Error) { null }
                            if (probe?.contains("suspended", true) == true) {
                                return@use "account suspended (HTTP $code) - fresh OTP login needed" to null
                            }
                            try { clearAuthToken() } catch (_: Exception) { } catch (_: Error) { }
                            return@use "unauthorized token (HTTP $code)" to null
                        }
                        if (code == 429) return@use "rate limited (HTTP 429)" to null
                        if (code == 404) return@use "number not found (HTTP 404)" to null
                        if (!resp.isSuccessful) return@use "search HTTP $code" to null
                        val ct = try { resp.header("Content-Type").orEmpty() } catch (_: Exception) { "" } catch (_: Error) { "" }
                        if (ct.contains("protobuf", true)) {
                            return@use "protobuf response (v2/search now binary) - OTP login via app flow" to null
                        }

                        val body = try { readMaybeGzipBody(resp) } catch (_: Exception) { null } catch (_: Error) { null }
                            ?: return@use "empty search body" to null
                        if (body.length > 500_000) return@use "oversized search body" to null
                        val json = try { gson.fromJson(body, JsonObject::class.java) } catch (_: Exception) { null } catch (_: Error) { null }
                            ?: return@use "invalid search JSON" to null

                        val status: Int? = try {
                            val el = json.get("status")
                            if (el != null && !el.isJsonNull && el.isJsonPrimitive && el.asJsonPrimitive.isNumber) el.asInt else null
                        } catch (_: Exception) { null } catch (_: Error) { null }
                        if (status != null && status !in listOf(0, 1, 2)) {
                            val msg = try {
                                val m = json.get("message")
                                if (m != null && !m.isJsonNull && m.isJsonPrimitive && m.asJsonPrimitive.isString) m.asString.take(200) else "search rejected"
                            } catch (_: Exception) { "search rejected" } catch (_: Error) { "search rejected" }
                            return@use msg to null
                        }

                        val arr = try {
                            val el = json.get("data")
                            if (el != null && el.isJsonArray) el.asJsonArray else null
                        } catch (_: Exception) { null } catch (_: Error) { null }
                            ?: return@use "search has no data array" to null
                        if (arr.size() == 0) return@use "no Truecaller record" to null

                        val data = try {
                            val first = arr.firstOrNull()
                            if (first != null && first.isJsonObject) first.asJsonObject else null
                        } catch (_: Exception) { null } catch (_: Error) { null }
                            ?: return@use "invalid search record" to null
                        val hasName = try { data.has("name") } catch (_: Exception) { false } catch (_: Error) { false }
                        if (!hasName) return@use "record has no name" to null

                        val mapped = try { TruecallerParser.mapResult(data, id, version) } catch (_: Exception) { null } catch (_: Error) { null }
                        if (mapped == null) return@use "parse failed" to null
                        // Empty name + empty photo = useless; don't surface as success.
                        if (mapped.name.isNullOrBlank() && mapped.imageUrl.isNullOrBlank() && mapped.photoCandidates.isEmpty()) {
                            return@use "record has no name" to null
                        }
                        null to mapped
                    }
                } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (_: Error) { "search failed (low memory)" to null }
                catch (e: Exception) { (e.message ?: e.javaClass.simpleName).take(200) to null }

                if (result.second != null) {
                    lastFailureReason = null
                    return result.second
                }
                lastError = result.first
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Error) { lastError = "low memory" }
            catch (e: Exception) {
                lastError = try { (e.message ?: e.javaClass.simpleName).take(200) } catch (_: Exception) { "search failed" } catch (_: Error) { "search failed" }
            }
        }
        lastFailureReason = lastError
        return null
    }

    private suspend fun tryBulkSearch(numbers: List<String>, token: String): Map<String, PartialResult> {
        try {
            coroutineContext.ensureActive()
            val clean = numbers.mapNotNull { try { it.filter { c -> c.isDigit() }.takeLast(15).takeIf { s -> s.length >= 7 } } catch (_: Exception) { null } catch (_: Error) { null } }.distinct().take(30)
            if (clean.isEmpty()) return emptyMap()
            val q = clean.joinToString(",")
            val rc = try { PhoneNumberUtils.getCountryCode(clean.firstOrNull() ?: "") } catch (_: Exception) { null } catch (_: Error) { null } ?: "BD"
            val encQ = try { java.net.URLEncoder.encode(q, "UTF-8") } catch (_: Exception) { return emptyMap() } catch (_: Error) { return emptyMap() }
            val encRc = try { java.net.URLEncoder.encode(rc, "UTF-8") } catch (_: Exception) { rc } catch (_: Error) { rc }
            val safeToken = try { token.trim().take(300) } catch (_: Exception) { token } catch (_: Error) { token }
            if (safeToken.length < 12) return emptyMap()
            val url = "https://search5-noneu.truecaller.com/v2/bulk?q=$encQ&countryCode=$encRc&type=14&placement=SEARCHRESULTS,HISTORY,DETAILS&encoding=json"
            val req = Request.Builder().url(url)
                .addHeader("Authorization", "Bearer $safeToken")
                .addHeader("content-type", "application/json; charset=UTF-8")
                .addHeader("Accept", "application/json")
                .addHeader("User-Agent", "Truecaller/11.75.5 (Android;10)")
                .build()
            httpClient.newCall(req).await().use { resp ->
                if (!resp.isSuccessful) return emptyMap()
                val body = try { resp.peekBody(500_000L).string() } catch (_: Exception) { null } catch (_: Error) { null } ?: return emptyMap()
                if (body.length > 500_000) return emptyMap()
                val json = try { gson.fromJson(body, JsonObject::class.java) } catch (_: Exception) { return emptyMap() } catch (_: Error) { return emptyMap() }
                val arr = try {
                    val el = json.get("data")
                    if (el != null && el.isJsonArray) el.asJsonArray else return emptyMap()
                } catch (_: Exception) { return emptyMap() } catch (_: Error) { return emptyMap() }
                val map = mutableMapOf<String, PartialResult>()
                val n = minOf(arr.size(), 30)
                for (i in 0 until n) {
                    try {
                        coroutineContext.ensureActive()
                        val el = try { arr.get(i) } catch (_: Exception) { continue } catch (_: Error) { continue }
                        val obj = if (el != null && el.isJsonObject) el.asJsonObject else continue
                        val hasName = try { obj.has("name") } catch (_: Exception) { false } catch (_: Error) { false }
                        if (!hasName) continue
                        val phonesEl = try { obj.get("phones") } catch (_: Exception) { null } catch (_: Error) { null }
                        val phones = if (phonesEl != null && phonesEl.isJsonArray) phonesEl.asJsonArray else continue
                        val firstPhone = try { phones.firstOrNull() } catch (_: Exception) { null } catch (_: Error) { null }
                        val phoneObj = if (firstPhone != null && firstPhone.isJsonObject) firstPhone.asJsonObject else continue
                        val phone = try {
                            val e = phoneObj.get("e164Format")
                            if (e != null && !e.isJsonNull && e.isJsonPrimitive && e.asJsonPrimitive.isString) e.asString.take(20) else null
                        } catch (_: Exception) { null } catch (_: Error) { null } ?: continue
                        val mapped = try { TruecallerParser.mapResult(obj, id, version) } catch (_: Exception) { null } catch (_: Error) { null } ?: continue
                        map[phone] = mapped
                        if (map.size >= 30) break
                    } catch (_: Exception) { continue } catch (_: Error) { continue }
                }
                return map
            }
        } catch (_: Exception) { return emptyMap() } catch (_: Error) { return emptyMap() }
    }

    private fun readMaybeGzipBody(resp: okhttp3.Response): String? {
        val bytes = try { resp.body?.bytes() } catch (_: Exception) { null } catch (_: Error) { null } ?: return null
        // Hard cap: never inflate more than ~512KB for a caller-id record.
        if (bytes.size > 2_000_000) return null
        if (bytes.size > 1 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte()) {
            return try {
                java.util.zip.GZIPInputStream(java.io.ByteArrayInputStream(bytes)).use { it.readBytes().take(512_000).toByteArray().toString(Charsets.UTF_8) }
            } catch (_: Exception) { null } catch (_: Error) { null }
        }
        if (bytes.size > 512_000) return bytes.take(512_000).toByteArray().toString(Charsets.UTF_8)
        return try { bytes.toString(Charsets.UTF_8) } catch (_: Exception) { null } catch (_: Error) { null }
    }

    private fun getAuthToken(): String? {
        val prefs = try {
            context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        } catch (_: Exception) { return TruecallerCloudStore.getInstallationId(context)?.let(::normalizeToken) }
        val direct = try { prefs.getString("truecaller_token", null)?.let(::normalizeToken) } catch (_: Exception) { null }
        val token = direct ?: TruecallerCloudStore.getInstallationId(context)?.let(::normalizeToken)
        if (token == null) {
            lastFailureReason = "missing installationId"
            Log.w("Truecaller", "No installationId - OTP verify required to auto-create cloud secret (truecaller_token)")
        }
        return token
    }

    private fun normalizeToken(raw: String?): String? {
        val cleaned = raw?.trim()?.trim('"')?.trim() ?: return null
        return cleaned.takeIf { it.length >= 12 }
    }
    private fun clearAuthToken() {
        try {
            context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE).edit()
                .remove("truecaller_token")
                .remove("last_tc_request_id")
                .remove("last_tc_phone")
                .apply()
        } catch (_: Exception) { }
    }
    fun hasValidToken(): Boolean = TruecallerCloudStore.hasValidSession(context)

    data class AuthRequestResult(
        val requestId: String,
        val method: String,
        val tokenTtl: Int,
        val statusCode: Int = 1,
        val errorMessage: String? = null
    )

    data class AuthVerifyResult(
        val success: Boolean,
        val errorMessage: String? = null
    )

}
