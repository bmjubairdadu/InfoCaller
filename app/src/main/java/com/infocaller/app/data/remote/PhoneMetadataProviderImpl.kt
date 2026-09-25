package com.infocaller.app.data.remote

import android.content.Context
import com.infocaller.app.domain.engine.*
import com.infocaller.app.util.PhoneNumberUtils

class PhoneMetadataProviderImpl(private val context: Context) : PhoneMetadataProvider {
    override val id: String = "offline_metadata"
    override val name: String = "Offline Metadata"
    override val version: String = "1.0.0"
    override val capabilities: Set<Capability> = setOf(Capability.PHONE_METADATA, Capability.CARRIER)
    override val priority: Int = 100
    override val costClass: CostClass = CostClass.FREE

    override suspend fun lookup(identifier: String, type: String, context: LookupContext): PartialResult? {
        if (type != IdentifierType.PHONE) return null
        val normalized = identifier
        val regionCode = PhoneNumberUtils.getCountryCode(normalized)
        val country = when (regionCode) {
            "BD" -> "Bangladesh"; "IN" -> "India"; "PK" -> "Pakistan"; "US" -> "United States"
            "GB" -> "United Kingdom"; "SA" -> "Saudi Arabia"; "AE" -> "United Arab Emirates"
            "MY" -> "Malaysia"; "SG" -> "Singapore"; else -> regionCode ?: "Unknown"
        }
        return PartialResult(
            country = country,
            region = PhoneNumberUtils.getLocationInfo(normalized),
            carrier = PhoneNumberUtils.getCarrierInfo(normalized, this.context),
            confidence = 1.0f,
            source = name,
            providerId = id,
            providerVersion = version
        )
    }

    override suspend fun bulkLookup(identifiers: List<String>, type: String, context: LookupContext): Map<String, PartialResult> {
        if (type != IdentifierType.PHONE) return emptyMap()
        return emptyMap()
    }
}
