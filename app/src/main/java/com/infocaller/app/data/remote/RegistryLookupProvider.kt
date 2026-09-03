package com.infocaller.app.data.remote

import com.infocaller.app.domain.engine.*
import com.infocaller.app.domain.model.SocialLookupStatus
import com.infocaller.app.domain.model.SocialProfile
import com.infocaller.app.util.SocialUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class RegistryLookupProvider(
    private val registryService: RegistryApiService
) : LookupProvider {
    override val id: String = "shared_registry"
    override val name: String = "InfoCaller Shared Registry"
    override val version: String = "1.0.0"
    override val capabilities: Set<Capability> = setOf(
        Capability.PUBLIC_SEARCH, Capability.PROFILE_PHOTO, Capability.PHONE_METADATA,
        Capability.CARRIER, Capability.WHATSAPP, Capability.TELEGRAM,
        Capability.BUSINESS, Capability.CITY, Capability.COUNTRY, Capability.TIMEZONE,
        Capability.EMAIL, Capability.LINE_TYPE, Capability.SOCIAL_MATCH
    )
    override val priority: Int = 900
    override val costClass: CostClass = CostClass.LOW

    override suspend fun lookup(identifier: String, type: String, context: LookupContext): PartialResult? = withContext(Dispatchers.IO) {
        if (type != com.infocaller.app.domain.engine.IdentifierType.PHONE) return@withContext null
        val normalizedPhoneNumber = identifier
        try {
            val response = registryService.lookupInRegistry(normalizedPhoneNumber)
            if (response.isSuccessful) {
                val data = response.body() ?: return@withContext null
                
                val socialProfiles = SocialUtils.fromJson(data.socialProfilesJson).toMutableList()
                
                if (socialProfiles.none { it.platform == "WhatsApp" }) {
                    data.whatsappStatus?.let {
                        socialProfiles.add(SocialProfile("WhatsApp", normalizedPhoneNumber, "https://wa.me/${normalizedPhoneNumber.filter { it.isDigit() }}", SocialLookupStatus.valueOf(it)))
                    }
                }
                
                if (socialProfiles.none { it.platform == "Telegram" }) {
                    data.telegramStatus?.let {
                        socialProfiles.add(SocialProfile("Telegram", normalizedPhoneNumber, null, SocialLookupStatus.valueOf(it)))
                    }
                }

                return@withContext PartialResult(
                    name = data.publicName,
                    alternateName = data.alternateName,
                    imageUrl = data.profileImageUrl,
                    about = data.about,
                    city = data.city,
                    country = data.country,
                    region = data.region,
                    timezone = data.timezone,
                    email = data.email,
                    carrier = data.carrier,
                    lineType = data.lineType,
                    isBusiness = data.isBusiness,
                    socialProfiles = socialProfiles,
                    confidence = if (data.confidence == "HIGH") 0.9f else 0.5f,
                    source = name,
                    providerId = id,
                    providerVersion = version
                )
            }
        } catch (_: Exception) {}
        null
    }

    override suspend fun bulkLookup(identifiers: List<String>, type: String, context: LookupContext): Map<String, PartialResult> = withContext(Dispatchers.IO) {
        if (type != com.infocaller.app.domain.engine.IdentifierType.PHONE) return@withContext emptyMap()
        emptyMap()
    }
}
