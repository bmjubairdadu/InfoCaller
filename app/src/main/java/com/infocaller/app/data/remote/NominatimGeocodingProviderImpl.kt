package com.infocaller.app.data.remote

import com.infocaller.app.domain.engine.*
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request


class NominatimGeocodingProviderImpl(
    private val httpClient: OkHttpClient,
    private val gson: Gson
) : LookupProvider {
    override val id: String = "nominatim_geocoding"
    override val name: String = "OpenStreetMap Intel"
    override val version: String = "1.0.0"
    override val capabilities: Set<Capability> = setOf(Capability.CITY, Capability.COUNTRY)
    override val priority: Int = 10
    override val costClass: CostClass = CostClass.FREE

    override suspend fun lookup(identifier: String, type: String, context: LookupContext): PartialResult? = withContext(Dispatchers.IO) {
        null
    }

    
    suspend fun refineLocation(city: String?, country: String?): PartialResult? = withContext(Dispatchers.IO) {
        if (city.isNullOrBlank() && country.isNullOrBlank()) return@withContext null
        
        val q = listOfNotNull(city, country).joinToString(", ")
        try {
            val url = "https://nominatim.openstreetmap.org/search?q=${java.net.URLEncoder.encode(q, "UTF-8")}&format=json&addressdetails=1&limit=1"
            val request = Request.Builder()
                .url(url)
                .addHeader("User-Agent", "InfoCaller/1.0 (Android)")
                .build()
            
            val response = httpClient.newCall(request).execute()
            if (response.isSuccessful) {
                val body = response.body?.string()
                val jsonArray = gson.fromJson(body, com.google.gson.JsonArray::class.java)
                if (jsonArray != null && jsonArray.size() > 0) {
                    val first = jsonArray.get(0).asJsonObject
                    val address = first.get("address").asJsonObject
                    
                    return@withContext PartialResult(
                        city = address.get("city")?.asString ?: address.get("town")?.asString ?: address.get("village")?.asString,
                        region = address.get("state")?.asString,
                        country = address.get("country")?.asString,
                        confidence = 0.8f,
                        source = "OpenStreetMap",
                        providerId = id,
                        providerVersion = version
                    )
                }
            }
        } catch (_: Exception) {
            null
        }
        null
    }
}
