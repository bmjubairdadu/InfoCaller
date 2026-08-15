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

class TruecallerProviderImpl(private val context: Context) : LookupProvider {
    override val id: String = "truecaller_legacy"
    override val name: String = "Truecaller Legacy"
    override val version: String = "1.0.0"
    override val capabilities: Set<Capability> = setOf(
        Capability.PHONE_METADATA, 
        Capability.PROFILE_PHOTO, 
        Capability.ABOUT, 
        Capability.CARRIER,
        Capability.SPAM_CHECK
    )

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    override suspend fun lookup(normalizedPhoneNumber: String, context: LookupContext): PartialResult = withContext(Dispatchers.IO) {
        val prefs = this@TruecallerProviderImpl.context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        val token = prefs.getString("truecaller_token", "") ?: ""
        
        if (token.isBlank()) {
            return@withContext PartialResult()
        }

        try {
            val cleanNumber = normalizedPhoneNumber.filter { it.isDigit() }
            // The reference project uses countryCode dynamically. For now, we'll try to extract from normalized or use BD as default.
            val countryCode = if (normalizedPhoneNumber.startsWith("+880")) "BD" else "BD" 
            
            val url = "https://search5-noneu.truecaller.com/v2/search?q=$cleanNumber&countryCode=$countryCode&type=4&locAddr=&placement=SEARCHRESULTS,HISTORY,DETAILS&encoding=json"
            
            val request = Request.Builder()
                .url(url)
                .addHeader("accept", "application/json")
                .addHeader("authorization", "Bearer $token")
                .addHeader("accept-encoding", "gzip")
                .addHeader("user-agent", "Truecaller/11.7.5 (Android;10)")
                .build()

            val response = httpClient.newCall(request).execute()
            if (response.isSuccessful) {
                val body = response.body?.string()
                val json = Gson().fromJson(body, JsonObject::class.java)
                val data = json.getAsJsonArray("data")?.firstOrNull()?.asJsonObject
                
                if (data != null) {
                    val name = data.get("name")?.asString
                    val image = data.get("image")?.asString
                    
                    val addressObj = data.getAsJsonArray("addresses")?.firstOrNull()?.asJsonObject
                    val city = addressObj?.get("city")?.asString
                    val country = addressObj?.get("countryCode")?.asString
                    
                    val spamObj = data.get("spamInfo")?.asJsonObject
                    val spamScore = spamObj?.get("spamScore")?.asInt ?: 0
                    val spamType = spamObj?.get("spamType")?.asString
                    
                    return@withContext PartialResult(
                        name = name,
                        imageUrl = image,
                        region = city,
                        country = country,
                        spamScore = spamScore,
                        spamType = spamType,
                        confidence = 0.9f,
                        source = this@TruecallerProviderImpl.name,
                        providerId = id,
                        providerVersion = version
                    )
                }
            }
        } catch (e: Exception) {
            Log.e("TruecallerProvider", "Lookup failed", e)
        }
        PartialResult()
    }
}
