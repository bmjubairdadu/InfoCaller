package com.infocaller.app.data.remote

import com.infocaller.app.domain.engine.*
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

class CryptoLookupProviderImpl(
    private val httpClient: OkHttpClient,
    private val gson: Gson
) : LookupProvider {
    override val id: String = "crypto_lookup"
    override val name: String = "Crypto Intelligence"
    override val version: String = "1.0.0"
    override val capabilities: Set<Capability> = setOf(
        Capability.CRYPTO_RECON,
        Capability.PUBLIC_SEARCH
    )
    override val priority: Int = 50
    override val costClass: CostClass = CostClass.FREE

    override suspend fun lookup(
        identifier: String,
        type: String,
        context: LookupContext
    ): PartialResult? = withContext(Dispatchers.IO) {
        if (type != IdentifierType.CRYPTO_WALLET) return@withContext null
        
        try {
            val url = "https://blockchain.info/rawaddr/$identifier"
            val request = Request.Builder().url(url).build()
            // Response must be closed (use{}) or connections leak.
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val json = try {
                    gson.fromJson(response.body?.string(), JsonObject::class.java)
                } catch (_: Exception) {
                    return@withContext null
                }
                val txCount = json.get("n_tx")?.asInt ?: 0
                val totalReceived = json.get("total_received")?.asLong ?: 0L
                
                return@withContext PartialResult(
                    about = "BTC Wallet: $txCount transactions, Total Received: ${totalReceived / 100000000.0} BTC",
                    confidence = 0.9f,
                    source = "Blockchain.info",
                    providerId = id,
                    providerVersion = version
                )
            }
        } catch (_: Exception) {}
        null
    }
}
