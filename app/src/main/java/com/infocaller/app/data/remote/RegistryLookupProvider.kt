package com.infocaller.app.data.remote

import com.infocaller.app.domain.engine.*
import com.infocaller.app.domain.model.SocialLookupStatus
import com.infocaller.app.domain.model.SocialProfile
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
        Capability.CARRIER, Capability.WHATSAPP, Capability.TELEGRAM, Capability.SPAM_CHECK
    )

    override suspend fun lookup(normalizedPhoneNumber: String, context: LookupContext): PartialResult? = withContext(Dispatchers.IO) {
        try {
            val response = registryService.lookupInRegistry(normalizedPhoneNumber)
            if (response.isSuccessful) {
                val data = response.body() ?: return@withContext null
                
                val socialProfiles = mutableListOf<SocialProfile>()
                data.whatsappStatus?.let {
                    socialProfiles.add(SocialProfile("WhatsApp", normalizedPhoneNumber, "https://wa.me/${normalizedPhoneNumber.filter { it.isDigit() }}", SocialLookupStatus.valueOf(it)))
                }
                data.telegramStatus?.let {
                    socialProfiles.add(SocialProfile("Telegram", normalizedPhoneNumber, null, SocialLookupStatus.valueOf(it)))
                }

                return@withContext PartialResult(
                    name = data.publicName,
                    imageUrl = data.profileImageUrl,
                    about = data.about,
                    city = data.city,
                    country = data.country,
                    region = data.region,
                    carrier = data.carrier,
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
}
