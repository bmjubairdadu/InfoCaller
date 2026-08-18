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
    override val id: String = "truecaller_v2"
    override val name: String = "Truecaller"
    override val version: String = "2.0.0"
    override val capabilities: Set<Capability> = setOf(
        Capability.PHONE_METADATA, 
        Capability.PROFILE_PHOTO, 
        Capability.ABOUT, 
        Capability.CARRIER,
        Capability.SPAM_CHECK,
        Capability.PUBLIC_SEARCH
    )

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    override suspend fun lookup(normalizedPhoneNumber: String, context: LookupContext): PartialResult? = withContext(Dispatchers.IO) {
        val prefs = this@TruecallerProviderImpl.context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        val token = prefs.getString("truecaller_token", "") ?: ""
        
        if (token.isBlank()) {
            return@withContext null // UNAVAILABLE WITHOUT AUTH
        }

        try {
            val cleanNumber = normalizedPhoneNumber.filter { it.isDigit() }
            val countryCode = PhoneNumberUtils.getCountryCode(normalizedPhoneNumber) ?: "BD"
            
            val url = "https://search5-noneu.truecaller.com/v2/search?q=$cleanNumber&countryCode=$countryCode&type=4&locAddr=&encoding=json"
            
            val request = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer $token")
                .addHeader("User-Agent", "Truecaller/11.7.5 (Android;10)")
                .addHeader("Accept", "application/json")
                .build()

            val response = httpClient.newCall(request).execute()
            if (response.isSuccessful) {
                val body = response.body?.string() ?: return@withContext null
                val json = Gson().fromJson(body, JsonObject::class.java)
                val data = json.getAsJsonArray("data")?.firstOrNull()?.asJsonObject ?: return@withContext null
                
                val name = data.get("name")?.asString
                val image = data.get("image")?.asString
                val about = data.get("about")?.asString
                
                val address = data.getAsJsonArray("addresses")?.firstOrNull()?.asJsonObject
                val city = address?.get("city")?.asString
                val country = address?.get("countryCode")?.asString
                
                val spam = data.get("spamInfo")?.asJsonObject
                val score = spam?.get("spamScore")?.asInt ?: 0
                val type = spam?.get("spamType")?.asString

                return@withContext PartialResult(
                    name = name,
                    imageUrl = image,
                    about = about,
                    city = city,
                    country = country,
                    spamScore = score,
                    spamType = type,
                    confidence = 0.95f,
                    source = "Truecaller Official",
                    providerId = id,
                    providerVersion = version
                )
            } else if (response.code == 401 || response.code == 403) {
                Log.e("Truecaller", "Auth failed (401/403)")
            }
        } catch (e: Exception) {
            Log.e("Truecaller", "Lookup error: ${e.message}")
        }
        null
    }
}
