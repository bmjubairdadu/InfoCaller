package com.infocaller.app.data.remote

import com.infocaller.app.domain.engine.*
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

class DomainLookupProviderImpl(
    private val httpClient: OkHttpClient,
    private val gson: Gson
) : LookupProvider {
    override val id: String = "domain_lookup"
    override val name: String = "Domain Intelligence"
    override val version: String = "1.0.0"
    override val capabilities: Set<Capability> = setOf(
        Capability.DOMAIN_INTEL,
        Capability.EMAIL,
        Capability.PUBLIC_SEARCH
    )
    override val priority: Int = 65
    override val costClass: CostClass = CostClass.FREE

    override suspend fun lookup(
        identifier: String,
        type: String,
        context: LookupContext
    ): PartialResult? = withContext(Dispatchers.IO) {
        if (type != IdentifierType.DOMAIN) return@withContext null
        
        try {
            // Using a public RDAP API for basic WHOIS-like data
            val url = "https://rdap.org/domain/$identifier"
            val request = Request.Builder().url(url).build()
            val response = httpClient.newCall(request).execute()
            
            if (response.isSuccessful) {
                val json = gson.fromJson(response.body?.string(), JsonObject::class.java)
                // RDAP JSON parsing is complex, let's extract some basic info
                val entityNames = json.getAsJsonArray("entities")?.mapNotNull { 
                    it.asJsonObject.getAsJsonArray("vcardArray")?.get(1)?.asJsonArray?.find { field ->
                        field.asJsonArray.get(0).asString == "fn"
                    }?.asJsonArray?.get(3)?.asString
                }?.distinct()

                val emails = json.getAsJsonArray("entities")?.mapNotNull { 
                    it.asJsonObject.getAsJsonArray("vcardArray")?.get(1)?.asJsonArray?.find { field ->
                        field.asJsonArray.get(0).asString == "email"
                    }?.asJsonArray?.get(3)?.asString
                }?.distinct()

                return@withContext PartialResult(
                    name = entityNames?.firstOrNull(),
                    email = emails?.firstOrNull(),
                    about = "Registrant entities: ${entityNames?.joinToString(", ")}",
                    confidence = 0.8f,
                    source = "RDAP",
                    providerId = id,
                    providerVersion = version
                )
            }
        } catch (_: Exception) {}
        null
    }
}
