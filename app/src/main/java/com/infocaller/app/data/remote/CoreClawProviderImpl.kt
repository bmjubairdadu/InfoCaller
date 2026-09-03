package com.infocaller.app.data.remote

import android.content.Context
import android.util.Log
import com.infocaller.app.domain.engine.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * CoreClaw Intelligence Provider.
 * High-value business and professional leads from Google Maps and web crawling.
 */
class CoreClawProviderImpl(private val context: Context) : LookupProvider {
    override val id: String = "coreclaw"
    override val name: String = "CoreClaw Business"
    override val version: String = "1.0.0"
    override val capabilities: Set<Capability> = setOf(
        Capability.BUSINESS,
        Capability.PUBLIC_SEARCH,
        Capability.CITY,
        Capability.COUNTRY
    )
    override val priority: Int = 20
    override val costClass: CostClass = CostClass.HIGH

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .build()

    override suspend fun lookup(identifier: String, type: String, context: LookupContext): PartialResult? = withContext(Dispatchers.IO) {
        if (type != IdentifierType.PHONE) return@withContext null
        val normalizedPhoneNumber = identifier
        val apiKey = getApiKey() ?: return@withContext null
        
        try {
            // Simulated implementation of CoreClaw worker-run trigger for phone lookup
            // Based on their Electron app logic.
            val url = "https://api.coreclaw.com/api/v2/workers/coreclaw~google-maps-scraper/run?sync=true"
            val jsonBody = JSONObject().apply {
                put("query", normalizedPhoneNumber)
                put("maxResults", 1)
            }.toString()
            
            val mediaType = "application/json".toMediaType()
            val requestBody = jsonBody.toRequestBody(mediaType)
            val request = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer $apiKey")
                .post(requestBody)
                .build()

            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val body = response.body?.string() ?: return@withContext null
                val json = JSONObject(body)
                val results = json.optJSONArray("results")
                if (results != null && results.length() > 0) {
                    val business = results.optJSONObject(0)
                    return@withContext PartialResult(
                        name = business.optString("name"),
                        about = business.optString("category"),
                        city = business.optString("city"),
                        country = business.optString("country"),
                        isBusiness = true,
                        confidence = 0.9f,
                        source = name,
                        providerId = id,
                        providerVersion = version
                    )
                }
            }
        } catch (e: Exception) {
            Log.e("CoreClaw", "Lookup failed", e)
        }
        null
    }

    override suspend fun bulkLookup(identifiers: List<String>, type: String, context: LookupContext): Map<String, PartialResult> = withContext(Dispatchers.IO) {
        if (type != IdentifierType.PHONE) return@withContext emptyMap()
        emptyMap()
    }

    private fun getApiKey(): String? {
        val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        return prefs.getString("coreclaw_api_key", "")?.takeIf { it.isNotBlank() }
            ?: com.infocaller.app.BuildConfig.CORECLAW_API_KEY.takeIf { it.isNotBlank() }
    }
}
