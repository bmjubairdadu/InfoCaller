package com.infocaller.app.data.remote

import com.infocaller.app.BuildConfig
import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonSyntaxException
import com.infocaller.app.domain.engine.*
import com.infocaller.app.util.PhoneNumberUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.UnknownHostException
import java.util.*
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

    // truecallerjs: generateRandomString(16) [a-z0-9] + random device like src/data/phones.ts
    private fun generateRandomString(len: Int): String {
        val chars = "abcdefghijklmnopqrstuvwxyz0123456789"
        return (1..len).map { chars.random() }.joinToString("")
    }
    private fun randomDevice(): Pair<String,String> {
        val devices = listOf(
            "Xiaomi" to "M2010J19SG", "Samsung" to "SM-A525F", "OnePlus" to "CPH2449",
            "Realme" to "RMX2185", "Oppo" to "CPH2333", "Vivo" to "V2231", "Samsung" to "SM-G998B"
        )
        return devices.random()
    }

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
            if (code == 401 || code == 403) { Log.w("Truecaller","search5 auth $code"); clearAuthToken(); return null }
            if (code == 429 || code == 404) return null
            if (resp.isSuccessful) {
                val body = resp.body?.string() ?: return null
                val json = try { gson.fromJson(body, JsonObject::class.java) } catch (_: Exception) { null } ?: return null
                val arr = json.getAsJsonArray("data") ?: return null
                if (arr.size() == 0) return null
                val data = arr.firstOrNull()?.asJsonObject ?: return null
                if (!data.has("name")) return null
                return TruecallerParser.mapResult(data, id, version)
            } else {
                Log.w("Truecaller","search5 non-200: $code")
            }
        } catch (e: Exception) { Log.e("Truecaller","search5 failed: ${e.message}") }
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
        } catch (e: Exception) { Log.e("Truecaller","bulk failed: ${e.message}"); return emptyMap() }
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
                Log.w("Truecaller", "V2 auth failed $code - token expired/invalid, clearing")
                clearAuthToken()
                return null
            }
            if (code == 429) {
                Log.w("Truecaller", "V2 rate limited 429")
                return null
            }
            if (code == 404) {
                Log.d("Truecaller", "V2 not found for $q")
                return null
            }
            if (response.isSuccessful) {
                val body = response.body?.string() ?: return null
                val json = try { gson.fromJson(body, JsonObject::class.java) } catch (e: Exception) { null } ?: return null
                // Truecaller returns {"data":[]} for not found - check empty array explicitly
                val dataArr = json.getAsJsonArray("data") ?: return null
                if (dataArr.size() == 0) {
                    Log.d("Truecaller", "V2 empty data for $q")
                    return null
                }
                val data = dataArr.firstOrNull()?.asJsonObject ?: return null
                return TruecallerParser.mapResult(data, id, version)
            } else {
                Log.w("Truecaller", "V2 non-200: $code body=${response.body?.string()?.take(300)}")
            }
        } catch (e: Exception) {
            Log.e("Truecaller", "V2 lookup failed: ${e.message}", e)
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
            if (code == 401 || code == 403) { Log.w("Truecaller","V0 auth failed $code"); clearAuthToken(); return null }
            if (code == 404 || code == 429) return null
            if (response.isSuccessful) {
                val body = response.body?.string() ?: return null
                val json = try { gson.fromJson(body, JsonObject::class.java) } catch (e: Exception) { null } ?: return null
                val dataArr = json.getAsJsonArray("data")
                if (dataArr != null && dataArr.size() == 0) return null
                val data = dataArr?.firstOrNull()?.asJsonObject ?: json
                // Validate that data actually contains a name - otherwise it's not a hit
                if (!data.has("name") && !data.has("names")) return null
                return TruecallerParser.mapResult(data, id, version)
            }
        } catch (e: Exception) {
            Log.e("Truecaller", "V0 lookup failed: ${e.message}", e)
        }
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

    private fun getDeviceId(): String {
        val prefs = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        var deviceId = prefs.getString("tc_device_id", null)
        if (deviceId == null) {
            deviceId = UUID.randomUUID().toString().replace("-", "").substring(0, 16)
            prefs.edit().putString("tc_device_id", deviceId).apply()
        }
        return deviceId
    }

    // Exactly mirrors truecallerjs/src/login.ts + src/verifyOtp.ts + src/search.ts
    // Do NOT change without revisiting truecallerjs: https://github.com/sumithemmadi/truecallerjs
    suspend fun startAuth(phoneNumber: String): AuthRequestResult? = withContext(Dispatchers.IO) {
        val clientSecret = if (BuildConfig.TRUECALLER_CLIENT_SECRET.isNotBlank()) BuildConfig.TRUECALLER_CLIENT_SECRET else "lvc22mp3l1sfv6ujg83rd17btt"
        try {
            val normalized = PhoneNumberUtils.normalize(phoneNumber)
            val countryCode = PhoneNumberUtils.getCountryCode(normalized) ?: "BD"
            val significant = PhoneNumberUtils.getSignificantNumber(normalized) ?: normalized.filter { it.isDigit() }
            val dialingCode = PhoneNumberUtils.getDialingCode(normalized) ?: 880
            // truecallerjs uses ONLY account-asia-south1 for login (src/login.ts)
            val endpoints = listOf("https://account-asia-south1.truecaller.com/v2/sendOnboardingOtp", "https://account-noneu.truecaller.com/v2/sendOnboardingOtp")
            var lastException: Exception? = null
            var attempt = 2 // truecallerjs sequenceNo=2
            // truecallerjs/src/login.ts: buildVersion 5 / major 11 / minor 7, user-agent 11.75.5, OS 10, random device + random 16-hex deviceId
            val (randManuf, randModel) = randomDevice()
            for (url in endpoints) {
                try {
                    Log.d("TruecallerAuth", "Attempting $url with number: $significant, dialing: $dialingCode (truecallerjs compat)")
                    val installationDetails = JsonObject().apply {
                        add("app", JsonObject().apply {
                            addProperty("buildVersion", 5)
                            addProperty("majorVersion", 11)
                            addProperty("minorVersion", 7)
                            addProperty("store", "GOOGLE_PLAY")
                        })
                        add("device", JsonObject().apply {
                            addProperty("deviceId", generateRandomString(16))
                            addProperty("language", "en")
                            addProperty("manufacturer", randManuf)
                            addProperty("model", randModel)
                            addProperty("osName", "Android")
                            addProperty("osVersion", "10")
                            add("mobileServices", gson.toJsonTree(listOf("GMS")))
                        })
                        addProperty("language", "en")
                    }
                    val requestBodyJson = JsonObject().apply {
                        addProperty("countryCode", countryCode)
                        addProperty("dialingCode", dialingCode)
                        add("installationDetails", installationDetails)
                        addProperty("phoneNumber", significant)
                        addProperty("region", "region-2")
                        addProperty("sequenceNo", attempt)
                    }
                    val request = Request.Builder()
                        .url(url)
                        // truecallerjs/src/login.ts + src/verifyOtp.ts: single header clientsecret lowercase
                        .addHeader("clientsecret", clientSecret)
                        .addHeader("user-agent", "Truecaller/11.75.5 (Android;10)")
                        .addHeader("accept-encoding", "gzip")
                        .addHeader("content-type", "application/json; charset=UTF-8")
                        .post(requestBodyJson.toString().toRequestBody("application/json; charset=UTF-8".toMediaType()))
                        .build()
                    val response = httpClient.newCall(request).execute()
                    val body = response.body?.string()
                    Log.d("TruecallerAuth", "Response from $url: ${response.code}")
                    if (body != null) {
                        try {
                            val json = gson.fromJson(body, JsonObject::class.java)
                            val status = json.get("status")?.asInt ?: 0
                            val msg = json.get("message")?.asString ?: "No message"
                            Log.d("TruecallerAuth", "Parsed Status: $status, Msg: $msg")
                            if (response.isSuccessful && (status == 1 || status == 9)) {
                                val requestId = json.get("requestId")?.asString ?: ""
                                val method = json.get("method")?.asString?.lowercase() ?: "sms"
                                val ttl = json.get("tokenTtl")?.asInt ?: 60
                                Log.i("TruecallerAuth", "OTP Sent Successfully via $method. ID: $requestId")
                                return@withContext AuthRequestResult(requestId, method, ttl, status)
                            } else if (status == 12 && requestBodyJson.has("region")) {
                                Log.w("TruecallerAuth", "Status 12 (Region mismatch), retrying without region parameter")
                                requestBodyJson.remove("region")
                                val retryRequest = request.newBuilder().post(requestBodyJson.toString().toRequestBody("application/json; charset=UTF-8".toMediaType())).build()
                                val retryResponse = httpClient.newCall(retryRequest).execute()
                                val retryBody = retryResponse.body?.string()
                                if (retryBody != null) {
                                    val retryJson = gson.fromJson(retryBody, JsonObject::class.java)
                                    val retryStatus = retryJson.get("status")?.asInt ?: 0
                                    Log.d("TruecallerAuth", "Retry Status: $retryStatus")
                                    if (retryResponse.isSuccessful && (retryStatus == 1 || retryStatus == 9)) {
                                        val requestId = retryJson.get("requestId")?.asString ?: ""
                                        val method = retryJson.get("method")?.asString?.lowercase() ?: "sms"
                                        val ttl = retryJson.get("tokenTtl")?.asInt ?: 60
                                        return@withContext AuthRequestResult(requestId, method, ttl, retryStatus)
                                    }
                                }
                            } else if (status == 3) {
                                val token = json.get("installationId")?.asString ?: json.get("accessToken")?.asString
                                android.util.Log.i("TruecallerAuth", "User already logged in.")
                                if (token != null) return@withContext AuthRequestResult(token, "already_logged_in", 0, 3)
                            }
                            if (url == endpoints.last()) return@withContext AuthRequestResult("", "", 0, status, msg)
                        } catch (e: Exception) {
                            Log.e("TruecallerAuth", "JSON parsing error: ${e.message}")
                            lastException = e
                        }
                    }
                } catch (e: Exception) {
                    Log.e("TruecallerAuth", "Network/Execution error on $url: ${e.message}")
                    lastException = e
                }
                attempt++
            }
            val errorMsg = when (lastException) {
                is UnknownHostException -> "DNS failure - no internet"
                is IOException -> "Connection timeout or refused"
                is JsonSyntaxException -> "Malformed response from Truecaller"
                else -> lastException?.message ?: "Unknown error"
            }
            return@withContext AuthRequestResult("", "", 0, -1, errorMsg)
        } catch (e: Exception) {
            Log.e("TruecallerAuth", "Fatal auth error: ${e.message}")
            return@withContext AuthRequestResult("", "", 0, -1, e.message ?: "Fatal connection error")
        }
    }

    suspend fun completeAuth(phoneNumber: String, requestId: String, otp: String): AuthVerifyResult = withContext(Dispatchers.IO) {
        if (requestId.length > 20 && !requestId.contains("-")) {
            saveAuthToken(requestId)
            return@withContext AuthVerifyResult(true)
        }
        try {
            val normalized = PhoneNumberUtils.normalize(phoneNumber)
            val countryCode = PhoneNumberUtils.getCountryCode(normalized) ?: "BD"
            val significant = PhoneNumberUtils.getSignificantNumber(normalized) ?: normalized.filter { it.isDigit() }
            val dialingCode = PhoneNumberUtils.getDialingCode(normalized) ?: 880
            // truecallerjs/src/verifyOtp.ts: POST https://account-asia-south1.truecaller.com/v1/verifyOnboardingOtp
            val postData = JsonObject().apply {
                addProperty("countryCode", countryCode)
                addProperty("dialingCode", dialingCode)
                addProperty("phoneNumber", significant)
                addProperty("requestId", requestId)
                addProperty("token", otp.filter { it.isDigit() })
            }
            val request = Request.Builder()
                .url("https://account-asia-south1.truecaller.com/v1/verifyOnboardingOtp")
                .addHeader("content-type", "application/json; charset=UTF-8")
                .addHeader("accept-encoding", "gzip")
                .addHeader("user-agent", "Truecaller/11.75.5 (Android;10)")
                .addHeader("clientsecret", "lvc22mp3l1sfv6ujg83rd17btt")
                .post(postData.toString().toRequestBody("application/json; charset=UTF-8".toMediaType()))
                .build()
            val response = httpClient.newCall(request).execute()
            val body = response.body?.string()
            if (body != null) {
                val json = try { gson.fromJson(body, JsonObject::class.java) } catch (e: Exception) { null }
                if (json != null) {
                    val status = json.get("status")?.asInt ?: 0
                    val token = json.get("installationId")?.asString ?: json.get("accessToken")?.asString
                    if (response.isSuccessful && status == 2 && token != null) {
                        saveAuthToken(token)
                        return@withContext AuthVerifyResult(true)
                    } else if (status == 17) {
                        return@withContext completeOnboarding(phoneNumber, requestId, otp)
                    } else {
                        return@withContext AuthVerifyResult(false, json.get("message")?.asString ?: "Verification failed")
                    }
                } else {
                    return@withContext AuthVerifyResult(false, "Invalid server response")
                }
            }
        } catch (e: Exception) { }
        AuthVerifyResult(false, "Network error during verification")
    }

    private suspend fun completeOnboarding(phoneNumber: String, requestId: String, otp: String): AuthVerifyResult = withContext(Dispatchers.IO) {
        try {
            val normalized = PhoneNumberUtils.normalize(phoneNumber)
            val countryCode = PhoneNumberUtils.getCountryCode(normalized) ?: "BD"
            val significant = PhoneNumberUtils.getSignificantNumber(normalized) ?: normalized.filter { it.isDigit() }
            val dialingCode = PhoneNumberUtils.getDialingCode(normalized) ?: 880
            val url = "https://account-noneu.truecaller.com/v1/completeOnboarding"
            val requestBody = JsonObject().apply {
                addProperty("countryCode", countryCode)
                addProperty("dialingCode", dialingCode)
                addProperty("phoneNumber", significant)
                addProperty("requestId", requestId)
                addProperty("token", otp.filter { it.isDigit() })
                addProperty("firstName", "Info")
                addProperty("lastName", "User")
            }.toString().toRequestBody("application/json; charset=UTF-8".toMediaType())
            val request = Request.Builder()
                .url(url)
                .addHeader("content-type", "application/json; charset=UTF-8")
                .addHeader("accept-encoding", "gzip")
                .addHeader("user-agent", "Truecaller/11.75.5 (Android;10)")
                .addHeader("clientsecret", "lvc22mp3l1sfv6ujg83rd17btt")
                .post(requestBody)
                .build()
            val response = httpClient.newCall(request).execute()
            val body = response.body?.string()
            if (body != null) {
                val json = gson.fromJson(body, JsonObject::class.java)
                val token = json.get("installationId")?.asString ?: json.get("accessToken")?.asString
                if (response.isSuccessful && token != null) {
                    saveAuthToken(token)
                    return@withContext AuthVerifyResult(true)
                } else {
                    return@withContext AuthVerifyResult(false, json.get("message")?.asString ?: "Sign-up failed")
                }
            }
        } catch (e: Exception) { }
        AuthVerifyResult(false, "Network error during sign-up")
    }

    private fun saveAuthToken(token: String) {
        TruecallerCloudStore.saveInstallationId(context, token)
    }
}
