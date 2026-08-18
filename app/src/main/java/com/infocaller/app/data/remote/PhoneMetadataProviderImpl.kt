package com.infocaller.app.data.remote

import android.content.Context
import com.infocaller.app.domain.engine.*
import com.infocaller.app.util.PhoneNumberUtils

class PhoneMetadataProviderImpl(private val context: Context) : PhoneMetadataProvider {
    override val id: String = "offline_metadata"
    override val name: String = "Offline Metadata"
    override val version: String = "1.0.0"
    override val capabilities: Set<Capability> = setOf(Capability.PHONE_METADATA, Capability.CARRIER)

    override suspend fun lookup(normalizedPhoneNumber: String, context: LookupContext): PartialResult? {
        return PartialResult(
            country = "Bangladesh",
            region = PhoneNumberUtils.getLocationInfo(normalizedPhoneNumber),
            carrier = PhoneNumberUtils.getCarrierInfo(normalizedPhoneNumber, this.context),
            confidence = 1.0f,
            source = name,
            providerId = id,
            providerVersion = version
        )
    }
}
