package com.infocaller.app.data.remote

import android.content.Context
import android.util.Log
import com.infocaller.app.domain.engine.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Query

interface NumverifyApi {
    @GET("validate")
    suspend fun validateNumber(
        @Query("access_key") apiKey: String,
        @Query("number") number: String
    ): Response<com.infocaller.app.data.remote.model.NumverifyResponse>
}

class NumverifyProviderImpl(
    private val context: Context,
    private val keyManager: ProviderKeyManager? = null
) : LookupProvider {
    override val id: String = "numverify"
    override val name: String = "Numverify Global"
    override val version: String = "1.0.0"
    override val capabilities: Set<Capability> = setOf(
        Capability.PHONE_METADATA,
        Capability.CARRIER,
        Capability.COUNTRY,
        Capability.CITY,
        Capability.LINE_TYPE
    )
    override val priority: Int = 90
    override val costClass: CostClass = CostClass.LOW

    private val api: NumverifyApi by lazy {
        Retrofit.Builder()
            .baseUrl("http://apilayer.net/api/")
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(NumverifyApi::class.java)
    }

    override suspend fun lookup(identifier: String, type: String, context: LookupContext): PartialResult? = withContext(Dispatchers.IO) {
        if (type != IdentifierType.PHONE) return@withContext null
        val normalizedPhoneNumber = identifier
        val apiKey = keyManager?.getActiveKey("numverify") ?: getApiKey()
        val cleanNumber = normalizedPhoneNumber.filter { it.isDigit() || it == '+' }
        
        try {
            // Attempt with provided key
            val response = api.validateNumber(apiKey, cleanNumber)
            if (response.isSuccessful) {
                val data = response.body() ?: return@withContext null
                if (data.valid == true) {
                    return@withContext mapResult(data)
                }
            } else if (response.code() == 401 || response.code() == 403) {
                keyManager?.rotateKey("numverify", apiKey)
            }
            
            // Fallback: Public secret logic if authorized key fails or is default
            if (apiKey == "a49a33617e138f4159df5f543e02627f") {
                val publicResult = performPublicLookup(cleanNumber)
                if (publicResult != null) return@withContext publicResult
            }
        } catch (e: Exception) {
            Log.e("Numverify", "Lookup failed", e)
        }
        null
    }

    override suspend fun bulkLookup(identifiers: List<String>, type: String, context: LookupContext): Map<String, PartialResult> = withContext(Dispatchers.IO) {
        if (type != IdentifierType.PHONE) return@withContext emptyMap()
        emptyMap()
    }

    private fun mapResult(data: com.infocaller.app.data.remote.model.NumverifyResponse): PartialResult {
        return PartialResult(
            carrier = data.carrier,
            country = data.country_name,
            city = data.location,
            lineType = data.line_type,
            confidence = 0.9f,
            source = name,
            providerId = id,
            providerVersion = version
        )
    }

    private fun performPublicLookup(number: String): PartialResult? {
        try {
            val okClient = okhttp3.OkHttpClient.Builder().followRedirects(true).build()
            
            // 1. Get Secret from Homepage
            val homeReq = okhttp3.Request.Builder().url("https://numverify.com/").build()
            val homeRes = okClient.newCall(homeReq).execute()
            val homeHtml = homeRes.body?.string() ?: return null
            
            val secret = homeHtml.substringAfter("name=\"scl_request_secret\" value=\"").substringBefore("\"")
            if (secret == homeHtml) return null
            
            // 2. Generate Key (MD5 of number + secret)
            val toHash = number + secret
            val md = java.security.MessageDigest.getInstance("MD5")
            val hash = md.digest(toHash.toByteArray()).joinToString("") { "%02x".format(it) }
            
            // 3. Query Public AJAX endpoint
            val url = "https://numverify.com/php_helper_scripts/phone_api.php?secret_key=$hash&number=$number"
            val req = okhttp3.Request.Builder()
                .url(url)
                .header("X-Requested-With", "XMLHttpRequest")
                .header("Referer", "https://numverify.com/")
                .build()
            
            val res = okClient.newCall(req).execute()
            val body = res.body?.string() ?: return null
            
            // Fix: Check if body is actually a JSON object before parsing
            val jsonElement = com.google.gson.JsonParser.parseString(body)
            if (!jsonElement.isJsonObject) {
                Log.w("Numverify", "Public lookup returned non-object: $body")
                return null
            }
            
            val json = jsonElement.asJsonObject
            if (json.get("valid")?.asBoolean == true) {
                return PartialResult(
                    carrier = json.get("carrier")?.asString,
                    country = json.get("country_name")?.asString,
                    city = json.get("location")?.asString,
                    lineType = json.get("line_type")?.asString,
                    confidence = 0.8f,
                    source = "$name (Public)",
                    providerId = id,
                    providerVersion = version
                )
            }
        } catch (e: Exception) {
            Log.e("Numverify", "Public lookup failed", e)
        }
        return null
    }

    private fun getApiKey(): String {
        val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        return prefs.getString("numverify_api_key", "")?.takeIf { it.isNotBlank() } 
            ?: "a49a33617e138f4159df5f543e02627f"
    }
}
