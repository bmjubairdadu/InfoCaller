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
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
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

        // Primary: truecallerjs pattern - search5-noneu (Benojir/Caller-ID + sumithemmadi/truecallerjs)
        val s5 = trySearch5(significant, countryCode, token)
        if (s5 != null) return@withContext s5

        // Fallback: profile-view-noneu v2 (existing)
        val v2Result = tryV2Search(significant, countryCode, token)
        if (v2Result != null) return@withContext v2Result

        tryV0Search(significant, token)
    }

    override suspend fun bulkLookup(identifiers: List<String>, type: String, context: LookupContext): Map<String, PartialResult> = withContext(Dispatchers.IO) {
        if (type != IdentifierType.PHONE || identifiers.isEmpty()) return@withContext emptyMap()
        val token = getAuthToken() ?: return@withContext emptyMap()
        // truecallerjs bulk: max 30 per request (Benojir/truecallerjs limit)
        val chunk = identifiers.take(30)
        tryBulkSearch(chunk, token)
    }

    // truecallerjs pattern: search5-noneu (primary, as in Benojir/Caller-ID GetPhoneNumberInfo + sumithemmadi/truecallerjs src/search.ts)
    private fun trySearch5(q: String, countryCode: String, token: String): PartialResult? {
        try {
            val url = "https://search5-noneu.truecaller.com/v2/search?q=$q&countryCode=$countryCode&type=4&locAddr=&encoding=json"
            val req = Request.Builder().url(url)
                .addHeader("Authorization", "Bearer $token")
                .addHeader("Accept", "application/json")
                .addHeader("Accept-Encoding", "gzip")
                .addHeader("User-Agent", "Truecaller/11.75.5 (Android;10)")
                .build()
            val resp = httpClient.newCall(req).execute()
            val code = resp.code
            if (code == 401 || code == 403) { clearAuthToken(); return null }
            if (code == 429 || code == 404) return null
            if (resp.isSuccessful) {
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
            val q = numbers.joinToString(",")
            // Default region from first number
            val rc = PhoneNumberUtils.getCountryCode(numbers.firstOrNull() ?: "") ?: "BD"
            val url = "https://search5-noneu.truecaller.com/v2/bulk?q=$q&countryCode=$rc&type=14&placement=SEARCHRESULTS,HISTORY,DETAILS&encoding=json"
            val req = Request.Builder().url(url)
                .addHeader("Authorization", "Bearer $token")
                .addHeader("Accept", "application/json")
                .addHeader("User-Agent", "Truecaller/11.75.5 (Android;10)")
                .build()
            val resp = httpClient.newCall(req).execute()
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
        } catch (_: Exception) { return emptyMap() }
    }

    private fun tryV2Search(q: String, countryCode: String, token: String): PartialResult? {
        try {
            val url = "https://profile-view-noneu.truecaller.com/v2/search?q=$q&countryCode=$countryCode&type=4&encoding=json"
            val request = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer $token")
                .addHeader("User-Agent", "Truecaller/15.34.6 (Android;14)")
                .addHeader("Accept", "application/json")
                .addHeader("Host", "profile-view-noneu.truecaller.com")
                .addHeader("Connection", "Keep-Alive")
                .build()

            val response = httpClient.newCall(request).execute()
            val code = response.code
            if (code == 401 || code == 403) {
                clearAuthToken()
                response.close()
                return null
            }
            if (code == 429 || code == 404) { response.close(); return null }
            if (!response.isSuccessful) { response.close(); return null }
            val body = response.body?.string()
            response.close()
            if (body == null) return null
            val json = try { gson.fromJson(body, JsonObject::class.java) } catch (_: Exception) { null } ?: return null
            // Truecaller returns {"data":[]} for not found - check empty array explicitly
            val dataArr = json.getAsJsonArray("data") ?: return null
            if (dataArr.size() == 0) return null
            val data = dataArr.firstOrNull()?.asJsonObject ?: return null
            return TruecallerParser.mapResult(data, id, version)
        } catch (_: Exception) {
        }
        return null
    }

    private fun tryV0Search(q: String, token: String): PartialResult? {
        try {
            val url = "https://profile-view-noneu.truecaller.com/v0/wsm/search?encoding=json"
            val bodyObj = JsonObject().apply {
                addProperty("isPhoneBookContact", false)
                addProperty("phoneNumber", q.toLongOrNull() ?: 0L)
            }
            val request = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer $token")
                .addHeader("Content-Type", "application/json")
                .addHeader("User-Agent", "Truecaller/15.34.6 (Android;14)")
                .post(bodyObj.toString().toRequestBody("application/json".toMediaType()))
                .build()

            val response = httpClient.newCall(request).execute()
            val code = response.code
            if (code == 401 || code == 403) { clearAuthToken(); response.close(); return null }
            if (code == 404 || code == 429) { response.close(); return null }
            if (!response.isSuccessful) { response.close(); return null }
            val body = response.body?.string()
            response.close()
            if (body == null) return null
            val json = try { gson.fromJson(body, JsonObject::class.java) } catch (_: Exception) { null } ?: return null
            val dataArr = json.getAsJsonArray("data")
            if (dataArr != null && dataArr.size() == 0) return null
            val data = dataArr?.firstOrNull()?.asJsonObject ?: json
            // Validate that data actually contains a name - otherwise it's not a hit
            if (!data.has("name") && !data.has("names")) return null
            return TruecallerParser.mapResult(data, id, version)
        } catch (_: Exception) { }
        return null
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
