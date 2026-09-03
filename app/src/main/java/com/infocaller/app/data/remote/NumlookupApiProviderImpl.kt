package com.infocaller.app.data.remote

import android.content.Context
import android.util.Log
import com.infocaller.app.domain.engine.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * NumLookupAPI Provider (everapi).
 * Provides high-quality carrier and geographic metadata.
 */
class NumlookupApiProviderImpl(private val context: Context) : LookupProvider {
    override val id: String = "numlookupapi"
    override val name: String = "NumLookup Global"
    override val version: String = "1.0.0"
    override val capabilities: Set<Capability> = setOf(
        Capability.PHONE_METADATA,
        Capability.CARRIER,
        Capability.COUNTRY,
        Capability.CITY,
        Capability.LINE_TYPE
    )
    override val priority: Int = 40
    override val costClass: CostClass = CostClass.LOW

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .build()

    override suspend fun lookup(identifier: String, type: String, context: LookupContext): PartialResult? = withContext(Dispatchers.IO) {
        if (type != IdentifierType.PHONE) return@withContext null
        val normalizedPhoneNumber = identifier
        val apiKey = getApiKey() ?: return@withContext null
        val cleanNumber = normalizedPhoneNumber.filter { it.isDigit() || it == '+' }
        
        try {
            val url = "https://api.numlookupapi.com/v1/validate/$cleanNumber"
            val request = Request.Builder()
                .url(url)
                .addHeader("apikey", apiKey)
                .build()

            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val body = response.body?.string() ?: return@withContext null
                val json = JSONObject(body)
                
                if (json.optBoolean("valid", false)) {
                    return@withContext PartialResult(
                        carrier = json.optString("carrier"),
                        country = json.optString("country_name"),
                        city = json.optString("location"),
                        lineType = json.optString("line_type"),
                        confidence = 0.9f,
                        source = name,
                        providerId = id,
                        providerVersion = version
                    )
                }
            }
        } catch (e: Exception) {
            Log.e("NumLookupAPI", "Lookup failed", e)
        }
        null
    }

    override suspend fun bulkLookup(identifiers: List<String>, type: String, context: LookupContext): Map<String, PartialResult> = withContext(Dispatchers.IO) {
        if (type != IdentifierType.PHONE) return@withContext emptyMap()
        emptyMap()
    }

    private fun getApiKey(): String? {
        val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        return prefs.getString("numlookup_api_key", "")?.takeIf { it.isNotBlank() }
            ?: com.infocaller.app.BuildConfig.NUMLOOKUP_API_KEY.takeIf { it.isNotBlank() }
    }
}
