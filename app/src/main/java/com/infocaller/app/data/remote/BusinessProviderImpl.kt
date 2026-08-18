package com.infocaller.app.data.remote

import com.infocaller.app.domain.engine.*

class BusinessProviderImpl : BusinessProvider {
    override val id: String = "business_index"
    override val name: String = "Public Business Index"
    override val version: String = "1.0.0"
    override val capabilities: Set<Capability> = setOf(Capability.BUSINESS)

    override suspend fun lookup(normalizedPhoneNumber: String, context: LookupContext): PartialResult? {
        if (normalizedPhoneNumber.endsWith("000")) {
            return PartialResult(
                name = "InfoCaller Business Support",
                confidence = 0.9f,
                source = name,
                providerId = id,
                providerVersion = version
            )
        }
        return PartialResult()
    }
}
