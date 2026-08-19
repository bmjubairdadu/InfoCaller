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

class TruecallerProviderImpl(private val context: Context) : LookupProvider {
    override val id: String = "truecaller_v2"
    override val name: String = "Truecaller"
    override val version: String = "2.1.0"
    override val capabilities: Set<Capability> = setOf(
        Capability.PHONE_METADATA, 
        Capability.PROFILE_PHOTO, 
        Capability.ABOUT, 
        Capability.CARRIER,
        Capability.SPAM_CHECK,
        Capability.PUBLIC_SEARCH,
        Capability.ALTERNATE_NAME,
        Capability.CITY,
        Capability.COUNTRY,
        Capability.TIMEZONE,
        Capability.EMAIL,
        Capability.PUBLIC_PROFILE
    )
    override val priority: Int = 80
    override val costClass: CostClass = CostClass.LOW

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    private val gson = Gson()

    suspend fun startAuth(phoneNumber: String): String? = withContext(Dispatchers.IO) {
        try {
            val cleanNumber = phoneNumber.filter { it.isDigit() }
            val url = "https://account-asia-south1.truecaller.com/v1/sendOtp"
            val requestBody = JsonObject().apply {
                addProperty("phoneNumber", cleanNumber)
                addProperty("countryCode", PhoneNumberUtils.getCountryCode(phoneNumber) ?: "BD")
                addProperty("installationId", java.util.UUID.randomUUID().toString())
            }.toString().toRequestBody("application/json".toMediaType())

            val request = Request.Builder()
                .url(url)
                .post(requestBody)
                .build()

            val response = httpClient.newCall(request).execute()
            if (response.isSuccessful) {
                val body = response.body?.string()
                return@withContext gson.fromJson(body, JsonObject::class.java).get("requestId")?.asString
            }
        } catch (e: Exception) {
            Log.e("TruecallerAuth", "OTP send failed", e)
        }
        null
    }

    suspend fun completeAuth(phoneNumber: String, requestId: String, otp: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val url = "https://account-asia-south1.truecaller.com/v1/verifyOtp"
            val requestBody = JsonObject().apply {
                addProperty("phoneNumber", phoneNumber.filter { it.isDigit() })
                addProperty("requestId", requestId)
                addProperty("otp", otp)
            }.toString().toRequestBody("application/json".toMediaType())

            val request = Request.Builder()
                .url(url)
                .post(requestBody)
                .build()

            val response = httpClient.newCall(request).execute()
            if (response.isSuccessful) {
                val body = response.body?.string()
                val token = gson.fromJson(body, JsonObject::class.java).get("accessToken")?.asString
                if (token != null) {
                    context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
                        .edit().putString("truecaller_token", token).apply()
                    return@withContext true
                }
            }
        } catch (e: Exception) {
            Log.e("TruecallerAuth", "OTP verify failed", e)
        }
        false
    }

    fun getStatus(): String {
        val token = getAuthToken()
        return if (token.isNullOrBlank()) "NOT_CONFIGURED" else "AUTHORIZED"
    }

    override suspend fun lookup(normalizedPhoneNumber: String, context: LookupContext): PartialResult? = withContext(Dispatchers.IO) {
        val token = getAuthToken() ?: return@withContext null

        try {
            val cleanNumber = normalizedPhoneNumber.filter { it.isDigit() }
            val countryCode = PhoneNumberUtils.getCountryCode(normalizedPhoneNumber) ?: "BD"
            
            val url = "https://search5-noneu.truecaller.com/v2/search?" +
                    "q=$cleanNumber&" +
                    "countryCode=$countryCode&" +
                    "type=4&" +
                    "locAddr=&" +
                    "placement=SEARCHRESULTS,HISTORY,DETAILS&" +
                    "encoding=json"
            
            val request = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer $token")
                .addHeader("User-Agent", "Truecaller/11.7.5 (Android;10)")
                .addHeader("Accept", "application/json")
                .build()

            val response = httpClient.newCall(request).execute()
            if (response.isSuccessful) {
                val body = response.body?.string() ?: return@withContext null
                val json = gson.fromJson(body, JsonObject::class.java)
                val data = json.getAsJsonArray("data")?.firstOrNull()?.asJsonObject ?: return@withContext null
                
                return@withContext TruecallerParser.mapResult(data, id, version)
            } else if (response.code == 401 || response.code == 403) {
                Log.e("Truecaller", "Auth failed (401/403)")
            }
        } catch (e: Exception) {
            Log.e("Truecaller", "Lookup error: ${e.message}")
        }
        null
    }

    override suspend fun bulkLookup(normalizedPhoneNumbers: List<String>, context: LookupContext): Map<String, PartialResult> = withContext(Dispatchers.IO) {
        val token = getAuthToken() ?: return@withContext emptyMap()
        if (normalizedPhoneNumbers.isEmpty()) return@withContext emptyMap()

        try {
            val cleanNumbers = normalizedPhoneNumbers.joinToString(",") { it.filter { c -> c.isDigit() } }
            val countryCode = PhoneNumberUtils.getCountryCode(normalizedPhoneNumbers.first()) ?: "BD"
            
            val url = "https://search5-noneu.truecaller.com/v2/bulk?q=$cleanNumbers&countryCode=$countryCode"
            
            val request = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer $token")
                .addHeader("User-Agent", "Truecaller/11.7.5 (Android;10)")
                .addHeader("Accept", "application/json")
                .build()

            val response = httpClient.newCall(request).execute()
            if (response.isSuccessful) {
                val body = response.body?.string() ?: return@withContext emptyMap()
                val json = gson.fromJson(body, JsonObject::class.java)
                val dataArray = json.getAsJsonArray("data") ?: return@withContext emptyMap()
                
                return@withContext dataArray.mapNotNull { it.asJsonObject }.associate {
                    val phone = it.get("phones")?.asJsonArray?.firstOrNull()?.asJsonObject?.get("e164Number")?.asString ?: ""
                    phone to TruecallerParser.mapResult(it, id, version)
                }
            }
        } catch (e: Exception) {
            Log.e("Truecaller", "Bulk lookup error: ${e.message}")
        }
        emptyMap()
    }

    private fun getAuthToken(): String? {
        val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        return prefs.getString("truecaller_token", "").takeIf { !it.isNullOrBlank() }
    }
}
