package com.infocaller.app.data.remote

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.infocaller.app.domain.engine.*
import com.infocaller.app.util.PhoneNumberUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
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
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val gson = Gson()

    override suspend fun lookup(identifier: String, type: String, context: LookupContext): PartialResult? = withContext(Dispatchers.IO) {
        if (type != IdentifierType.PHONE) return@withContext null
        val token = getAuthToken() ?: run {
            Log.w("Truecaller", "No installationId - login required (truecallerjs: installationId missing). Run login flow first.")
            return@withContext null
        }

        val countryCode = PhoneNumberUtils.getCountryCode(identifier) ?: "BD"
        val significant = PhoneNumberUtils.getSignificantNumber(identifier) ?: identifier.filter { it.isDigit() }

        // Single canonical endpoint per both reference repos
        // (sumithemmadi/truecallerjs src/search.ts + Benojir GetPhoneNumberInfo):
        // search5-noneu only. Legacy profile-view v2/v0 fallbacks were removed —
        // same backend, so they only added 2x latency/heat on every miss.
        return@withContext trySearch5(significant, countryCode, token)
    }

    override suspend fun bulkLookup(identifiers: List<String>, type: String, context: LookupContext): Map<String, PartialResult> = withContext(Dispatchers.IO) {
        if (type != IdentifierType.PHONE || identifiers.isEmpty()) return@withContext emptyMap()
        val token = getAuthToken() ?: return@withContext emptyMap()
        // truecallerjs bulk: max 30 per request (Benojir/truecallerjs limit)
        val chunk = identifiers.take(30)
        tryBulkSearch(chunk, token)
    }

    // truecallerjs src/search.ts pattern (Benojir GetPhoneNumberInfo matches):
    // GET search5-noneu/v2/search?q=<significant>&countryCode=<region>&type=4&locAddr=&placement=SEARCHRESULTS,HISTORY,DETAILS&encoding=json
    private fun trySearch5(q: String, countryCode: String, token: String): PartialResult? {
        try {
            val encQ = java.net.URLEncoder.encode(q, "UTF-8")
            val encCc = java.net.URLEncoder.encode(countryCode, "UTF-8")
            val url = "https://search5-noneu.truecaller.com/v2/search?q=$encQ&countryCode=$encCc&type=4&locAddr=&placement=SEARCHRESULTS,HISTORY,DETAILS&encoding=json"
            val req = Request.Builder().url(url)
                .addHeader("Authorization", "Bearer $token")
                .addHeader("content-type", "application/json; charset=UTF-8")
                .addHeader("Accept", "application/json")
                // Do NOT set Accept-Encoding manually: OkHttp transparently
                // decompresses gzip only when it adds the header itself. Setting
                // it manually (as before) left gzip bytes in body.string() so
                // JSON parsing failed and every lookup returned null.
                .addHeader("User-Agent", "Truecaller/11.75.5 (Android;10)")
                .build()
            httpClient.newCall(req).execute().use { resp ->
                val code = resp.code
                if (code == 401 || code == 403) { clearAuthToken(); return null }
                if (code == 429 || code == 404) return null
                if (!resp.isSuccessful) return null
                // Body is already decompressed by OkHttp (see above).
                val body = resp.body?.string() ?: return null
                val json = try { gson.fromJson(body, JsonObject::class.java) } catch (_: Exception) { null } ?: return null
                val arr = json.getAsJsonArray("data") ?: return null
                if (arr.size() == 0) return null
                val data = arr.firstOrNull()?.asJsonObject ?: return null
                if (!data.has("name")) return null
                return TruecallerParser.mapResult(data, id, version)
            }
        } catch (_: Exception) { }
        return null
    }

    private fun tryBulkSearch(numbers: List<String>, token: String): Map<String, PartialResult> {
        try {
            // truecallerjs bulk: max 30 per request; q must be URL-encoded
            // or '+' in E.164 numbers decodes to space server-side.
            val q = numbers.joinToString(",")
            // Default region from first number
            val rc = PhoneNumberUtils.getCountryCode(numbers.firstOrNull() ?: "") ?: "BD"
            val encQ = java.net.URLEncoder.encode(q, "UTF-8")
            val encRc = java.net.URLEncoder.encode(rc, "UTF-8")
            val url = "https://search5-noneu.truecaller.com/v2/bulk?q=$encQ&countryCode=$encRc&type=14&placement=SEARCHRESULTS,HISTORY,DETAILS&encoding=json"
            val req = Request.Builder().url(url)
                .addHeader("Authorization", "Bearer $token")
                .addHeader("content-type", "application/json; charset=UTF-8")
                .addHeader("Accept", "application/json")
                // Same gzip note as trySearch5: let OkHttp auto-decompress.
                .addHeader("User-Agent", "Truecaller/11.75.5 (Android;10)")
                .build()
            httpClient.newCall(req).execute().use { resp ->
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

    private fun getAuthToken(): String? {
        val token = TruecallerCloudStore.getInstallationId(context)
        if (token == null) Log.w("Truecaller", "No installationId - OTP verify required to auto-create cloud secret (truecaller_token)")
        return token
    }
    private fun clearAuthToken() { try { context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE).edit().remove("truecaller_token").apply() } catch(_:Exception){} }
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


    // NOTE: legacy startAuth/completeAuth/completeOnboarding/getDeviceId/saveAuthToken
    // were removed - LoginScreen uses TruecallerAuthManager (single live auth path).
    // This lookup-only provider keeps session helpers + AuthRequestResult/AuthVerifyResult
    // DTOs (referenced by LoginScreen/AuthViewModel).
}
