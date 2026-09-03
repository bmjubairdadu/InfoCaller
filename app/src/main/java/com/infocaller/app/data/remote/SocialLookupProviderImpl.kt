package com.infocaller.app.data.remote

import com.infocaller.app.domain.engine.*
import com.infocaller.app.domain.model.SocialProfile
import com.infocaller.app.domain.model.SocialLookupStatus

class SocialLookupProviderImpl : SocialProvider {
    override val id: String = "social_presence"
    override val name: String = "Social Presence"
    override val version: String = "1.0.0"
    override val capabilities: Set<Capability> = setOf(Capability.SOCIAL_MATCH)
    override val priority: Int = 30
    override val costClass: CostClass = CostClass.FREE

    // DEPRECATED stub - real check moved to SocialEnumProviderImpl/WhatsappApifyProvider; keep as no-op to avoid false positives
    override suspend fun lookup(identifier: String, type: String, context: LookupContext): PartialResult? {
        return null
    }
    }

    override suspend fun bulkLookup(identifiers: List<String>, type: String, context: LookupContext): Map<String, PartialResult> {
        if (type != IdentifierType.PHONE) return emptyMap()
        return emptyMap()
    }
}
