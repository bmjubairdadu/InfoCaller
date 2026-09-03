package com.infocaller.app.data.remote

import com.infocaller.app.domain.engine.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class LocalRegionalMetadataProvider : PhoneMetadataProvider {
    override val id: String = "local_regional"
    override val name: String = "Regional Intel"
    override val version: String = "1.0.0"
    override val capabilities: Set<Capability> = setOf(
        Capability.CARRIER,
        Capability.CITY,
        Capability.COUNTRY,
        Capability.PHONE_METADATA
    )
    override val priority: Int = 800
    override val costClass: CostClass = CostClass.FREE

    override suspend fun lookup(identifier: String, type: String, context: LookupContext): PartialResult? = withContext(Dispatchers.IO) {
        if (type != IdentifierType.PHONE) return@withContext null
        val cleanNumber = identifier.filter { it.isDigit() }
        
        if (cleanNumber.startsWith("880")) {
            val prefix = cleanNumber.substring(3, 5)
            val operator = when (prefix) {
                "17", "13" -> "Grameenphone"
                "18", "16" -> "Robi"
                "19", "14" -> "Banglalink"
                "15" -> "Teletalk"
                else -> null
            }
            
            val city = when {
                cleanNumber.startsWith("8802") -> "Dhaka"
                cleanNumber.startsWith("88031") -> "Chattogram"
                cleanNumber.startsWith("88041") -> "Khulna"
                cleanNumber.startsWith("88051") -> "Bogra"
                cleanNumber.startsWith("88061") -> "Rajshahi"
                cleanNumber.startsWith("88071") -> "Pabna"
                cleanNumber.startsWith("88081") -> "Comilla"
                cleanNumber.startsWith("88091") -> "Mymensingh"
                cleanNumber.startsWith("880421") -> "Jessore"
                cleanNumber.startsWith("880821") -> "Sylhet"
                else -> null
            }

            if (operator != null || city != null) {
                return@withContext PartialResult(
                    carrier = operator ?: "BTCL",
                    city = city,
                    country = "Bangladesh",
                    confidence = 1.0f,
                    source = "BD Metadata",
                    providerId = id,
                    providerVersion = version
                )
            }
        }
        null
    }

    override suspend fun bulkLookup(identifiers: List<String>, type: String, context: LookupContext): Map<String, PartialResult> = withContext(Dispatchers.IO) {
        emptyMap()
    }
}
