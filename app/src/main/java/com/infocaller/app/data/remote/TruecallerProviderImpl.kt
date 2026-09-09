package com.infocaller.app.data.remote

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.infocaller.app.domain.engine.*
import com.infocaller.app.util.PhoneNumberUtils
import com.infocaller.app.util.await
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
        if (type != IdentifierType.PHONE) return@withContext null
        val token = getAuthToken() ?: run {
            Log.w("Truecaller", "No installationId - login required (truecallerjs: installationId missing). Run login flow first.")
            return@withContext null
        }

        val countryCode = PhoneNumberUtils.getCountryCode(identifier) ?: "BD"
        val significant = PhoneNumberUtils.getSignificantNumber(identifier) ?: identifier.filter { it.isDigit() }
        return@withContext trySearch5(significant, countryCode, token)
    }

    override suspend fun bulkLookup(identifiers: List<String>, type: String, context: LookupContext): Map<String, PartialResult> = withContext(Dispatchers.IO) {
        if (type != IdentifierType.PHONE || identifiers.isEmpty()) return@withContext emptyMap()
        val token = getAuthToken() ?: return@withContext emptyMap()
        val chunk = identifiers.take(30)
        tryBulkSearch(chunk, token)
    }
    private suspend fun trySearch5(q: String, countryCode: String, token: String): PartialResult? {
        val cleanQuery = q.filter { it.isDigit() }
        if (cleanQuery.isEmpty()) {
            lastFailureReason = "empty significant number"
            return null
        }
        val encQ = URLEncoder.encode(cleanQuery, "UTF-8")
        val region = countryCode.uppercase().takeIf { it.length == 2 } ?: "BD"
        val encCc = URLEncoder.encode(region, "UTF-8")

        val hosts = listOf(
            "https://search5-noneu.truecaller.com",
            "https://search5-asia-south1.truecaller.com"
        )

        var lastError: String? = null
        for (host in hosts) {
            try {
                val url = "$host/v2/search?q=$encQ&countryCode=$encCc&type=4&encoding=json"
                val req = Request.Builder().url(url)
                    .addHeader("Authorization", "Bearer ${token.trim()}")
                    .addHeader("content-type", "application/json; charset=UTF-8")
                    .addHeader("Accept", "application/json")
                    .addHeader("Accept-Encoding", "gzip")
                    .addHeader("User-Agent", "Truecaller/26.35.7 (Android;11)")
                    .build()

                val result = httpClient.newCall(req).await().use { resp ->
                    val code = resp.code
                    if (code == 401 || code == 403) {
                        val probe = try { resp.body?.string()?.take(200) } catch (_: Exception) { null }
                        if (probe?.contains("suspended", true) == true) {
                            return@use "account suspended (HTTP $code) - fresh OTP login needed" to null
                        }
                        clearAuthToken()
                        return@use "unauthorized token (HTTP $code)" to null
                    }
                    if (code == 429) return@use "rate limited (HTTP 429)" to null
                    if (code == 404) return@use "number not found (HTTP 404)" to null
                    if (!resp.isSuccessful) return@use "search HTTP $code" to null
                    val ct = resp.header("Content-Type").orEmpty()
                    if (ct.contains("protobuf", true)) {
                        return@use "protobuf response (v2/search now binary) - OTP login via app flow" to null
                    }

                    val body = readMaybeGzipBody(resp) ?: return@use "empty search body" to null
                    val json = try { gson.fromJson(body, JsonObject::class.java) } catch (_: Exception) { null } ?: return@use "invalid search JSON" to null

                    if (json.has("status") && json.get("status")?.asInt !in listOf(null, 0, 1, 2)) {
                        val msg = json.get("message")?.takeIf { !it.isJsonNull }?.asString ?: "search rejected"
                        return@use msg to null
                    }

                    val arr = json.getAsJsonArray("data") ?: return@use "search has no data array" to null
                    if (arr.size() == 0) return@use "no Truecaller record" to null

                    val data = arr.firstOrNull()?.asJsonObject ?: return@use "invalid search record" to null
                    if (!data.has("name")) return@use "record has no name" to null

                    null to TruecallerParser.mapResult(data, id, version)
                }

                if (result.second != null) {
                    lastFailureReason = null
                    return result.second
                }
                lastError = result.first
            } catch (e: Exception) {
                lastError = e.message ?: e.javaClass.simpleName
            }
        }
        lastFailureReason = lastError
        return null
    }

    private suspend fun tryBulkSearch(numbers: List<String>, token: String): Map<String, PartialResult> {
        try {
            val q = numbers.joinToString(",")
            val rc = PhoneNumberUtils.getCountryCode(numbers.firstOrNull() ?: "") ?: "BD"
            val encQ = java.net.URLEncoder.encode(q, "UTF-8")
            val encRc = java.net.URLEncoder.encode(rc, "UTF-8")
            val url = "https://search5-noneu.truecaller.com/v2/bulk?q=$encQ&countryCode=$encRc&type=14&placement=SEARCHRESULTS,HISTORY,DETAILS&encoding=json"
            val req = Request.Builder().url(url)
                .addHeader("Authorization", "Bearer $token")
                .addHeader("content-type", "application/json; charset=UTF-8")
                .addHeader("Accept", "application/json")
                .addHeader("User-Agent", "Truecaller/11.75.5 (Android;10)")
                .build()
            httpClient.newCall(req).await().use { resp ->
                if (!resp.isSuccessful) return emptyMap()
                val body = resp.body?.string() ?: return emptyMap()
                val json = try { gson.fromJson(body, JsonObject::class.java) } catch (_: Exception) { return emptyMap() }
                val arr = json.getAsJsonArray("data") ?: return emptyMap()
                val map = mutableMapOf<String, PartialResult>()
                for (el in arr) {
                    val obj = el.asJsonObject ?: continue
                    val phones = obj.getAsJsonArray("phones") ?: continue
                    val phone = phones.firstOrNull()?.asJsonObject?.get("e164Format")?.asString ?: continue
                    if (!obj.has("name")) continue
                    map[phone] = TruecallerParser.mapResult(obj, id, version)
                }
                return map
            }
        } catch (_: Exception) { return emptyMap() }
    }

    private fun readMaybeGzipBody(resp: okhttp3.Response): String? {
        val bytes = try { resp.body?.bytes() } catch (_: Exception) { null } ?: return null
        if (bytes.size > 1 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte()) {
            return try {
                java.util.zip.GZIPInputStream(java.io.ByteArrayInputStream(bytes)).use { it.readBytes().toString(Charsets.UTF_8) }
            } catch (_: Exception) { null }
        }
        return bytes.toString(Charsets.UTF_8)
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
