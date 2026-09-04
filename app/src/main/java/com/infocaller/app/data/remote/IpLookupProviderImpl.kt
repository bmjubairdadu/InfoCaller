package com.infocaller.app.data.remote

import com.infocaller.app.domain.engine.*
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

class IpLookupProviderImpl(
    private val httpClient: OkHttpClient,
    private val gson: Gson
) : LookupProvider {
    override val id: String = "ip_lookup"
    override val name: String = "IP Intelligence"
    override val version: String = "1.0.0"
    override val capabilities: Set<Capability> = setOf(
        Capability.IP_RECON,
        Capability.CITY,
        Capability.COUNTRY,
        Capability.TIMEZONE,
        Capability.CARRIER
    )
    override val priority: Int = 70
    override val costClass: CostClass = CostClass.FREE

    override suspend fun lookup(
        identifier: String,
        type: String,
        context: LookupContext
    ): PartialResult? = withContext(Dispatchers.IO) {
        if (type != IdentifierType.IP_ADDRESS) return@withContext null
        
        // Validate input: only literal IPv4/IPv6 reach the network.
        if (!identifier.matches(Regex("^[0-9a-fA-F:.]{3,45}$"))) return@withContext null
        try {
            val url = "https://ip-api.com/json/$identifier?fields=status,message,country,countryCode,regionName,city,zip,timezone,isp,org,as,mobile,proxy,hosting"
            val request = Request.Builder().url(url).build()
            httpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val json = gson.fromJson(response.body?.string(), JsonObject::class.java)
                    if (json.get("status").asString == "success") {
                        return@withContext PartialResult(
                            city = json.get("city")?.asString,
                            country = json.get("country")?.asString,
                            region = json.get("regionName")?.asString,
                            timezone = json.get("timezone")?.asString,
                            carrier = json.get("isp")?.asString,
                            about = "ORG: ${json.get("org")?.asString}, AS: ${json.get("as")?.asString}",
                            isBusiness = json.get("hosting")?.asBoolean,
                            confidence = 1.0f,
                            source = "IP-API",
                            providerId = id,
                            providerVersion = version
                        )
                    }
                }
            }
        } catch (_: Exception) {}
        null
    }
}
