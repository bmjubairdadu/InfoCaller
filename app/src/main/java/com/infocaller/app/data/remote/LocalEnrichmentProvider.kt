package com.infocaller.app.data.remote

import com.infocaller.app.domain.engine.*
import com.infocaller.app.data.local.dao.EnrichmentDao
import com.infocaller.app.domain.model.SocialLookupStatus
import com.infocaller.app.domain.model.SocialProfile
import com.infocaller.app.util.SocialUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class LocalEnrichmentProvider(
    private val enrichmentDao: EnrichmentDao
) : LookupProvider {
    override val id: String = "local_cache"
    override val name: String = "Local Intelligence Cache"
    override val version: String = "1.0.0"
    override val capabilities: Set<Capability> = Capability.entries.toSet()
    override val priority: Int = 1000 // Highest priority
    override val costClass: CostClass = CostClass.FREE

    override suspend fun lookup(identifier: String, type: String, context: LookupContext): PartialResult? = withContext(Dispatchers.IO) {
        if (type != IdentifierType.PHONE) return@withContext null
        val normalizedPhoneNumber = identifier
        val entity = enrichmentDao.getEnrichmentSync(normalizedPhoneNumber) ?: return@withContext null
        
        val isStale = entity.expiresAt < System.currentTimeMillis()
        val baseConfidence = entity.confidence?.toFloatOrNull() ?: 0.5f
        
        // If stale, we report lower confidence so the planner continues to other providers.
        // If fresh and high confidence, it will satisfy the capabilities.
        val finalConfidence = if (isStale) minOf(0.4f, baseConfidence) else baseConfidence
        
        PartialResult(
            name = entity.publicName,
            alternateName = entity.alternateName,
            imageUrl = entity.profileImageUrl,
            about = entity.about,
            city = entity.city,
            country = entity.country,
            region = entity.region,
            timezone = entity.timezone,
            email = entity.email,
            carrier = entity.carrier,
            lineType = entity.lineType,
            isBusiness = entity.isBusiness,
            socialProfiles = SocialUtils.fromJson(entity.socialProfilesJson),
            confidence = finalConfidence,
            source = "Local Cache",
            providerId = id,
            providerVersion = version
        )
    }

    override suspend fun bulkLookup(identifiers: List<String>, type: String, context: LookupContext): Map<String, PartialResult> = withContext(Dispatchers.IO) {
        if (type != IdentifierType.PHONE) return@withContext emptyMap()
        emptyMap()
    }
}
