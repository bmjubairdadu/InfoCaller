package com.infocaller.app.data.remote

import com.infocaller.app.domain.engine.*

class BusinessProviderImpl : BusinessProvider {
    override val id: String = "business_index"
    override val name: String = "Public Business Index"
    override val version: String = "1.0.0"
    override val capabilities: Set<Capability> = setOf(Capability.BUSINESS)
    override val priority: Int = 20
    override val costClass: CostClass = CostClass.FREE

    override suspend fun lookup(identifier: String, type: String, context: LookupContext): PartialResult? {
        // Business lookup requires real business registry API (e.g., Google Places / OpenCorporates).
        // Stub removed - previously returned fake "InfoCaller Business Support" for numbers ending 000.
        // Keep provider as no-op until real business API wired; prevents false caller ID.
        return null
    }

    override suspend fun bulkLookup(identifiers: List<String>, type: String, context: LookupContext): Map<String, PartialResult> {
        if (type != IdentifierType.PHONE) return emptyMap()
        return emptyMap()
    }
}
