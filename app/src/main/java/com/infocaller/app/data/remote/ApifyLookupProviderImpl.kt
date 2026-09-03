package com.infocaller.app.data.remote

import android.util.Log
import com.infocaller.app.domain.engine.*
import com.infocaller.app.domain.model.SocialLookupStatus
import com.infocaller.app.domain.model.SocialProfile
import com.infocaller.app.domain.engine.IdentifierType
import com.infocaller.app.util.SocialUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Authorized intelligence provider using Backend Relay.
 * Direct third-party secrets and rotation logic removed.
 */
class ApifyLookupProviderImpl(
    private val backendApiService: BackendApiService
) : LookupProvider {
    override val id: String = "authorized_backend_relay"
    override val name: String = "InfoCaller Intelligence"
    override val version: String = "3.2.0"
    override val capabilities: Set<Capability> = setOf(
        Capability.WHATSAPP, Capability.TELEGRAM, Capability.PROFILE_PHOTO, 
        Capability.ABOUT, Capability.CARRIER, Capability.BUSINESS, Capability.PUBLIC_SEARCH,
        Capability.CITY, Capability.COUNTRY, Capability.ALTERNATE_NAME
    )
    override val priority: Int = 10
    override val costClass: CostClass = CostClass.HIGH

    override suspend fun lookup(identifier: String, type: String, context: LookupContext): PartialResult? = withContext(Dispatchers.IO) {
        if (type != IdentifierType.PHONE) return@withContext null
        
        try {
            val response = backendApiService.lookupPhone(PhoneLookupRequest(identifier))
            
            if (response.isSuccessful) {
                val data = response.body() ?: return@withContext null
                
                val socialProfiles = mutableListOf<SocialProfile>()
                if (!data.socialProfilesJson.isNullOrBlank()) {
                    socialProfiles.addAll(SocialUtils.fromJson(data.socialProfilesJson))
                }
                
                if (data.whatsappStatus == "CONFIRMED" && socialProfiles.none { it.platform == "WhatsApp" }) {
                    socialProfiles.add(SocialProfile("WhatsApp", identifier, "https://wa.me/${identifier.filter { it.isDigit() }}", SocialLookupStatus.CONFIRMED))
                }

                val photoCandidates = mutableListOf<com.infocaller.app.domain.model.PhotoCandidate>()
                if (!data.profileImageUrl.isNullOrBlank()) {
                    photoCandidates.add(
                        com.infocaller.app.domain.model.PhotoCandidate(
                            provider = "Backend Intelligence",
                            url = data.profileImageUrl,
                            sourcePriority = 70,
                            timestamp = System.currentTimeMillis()
                        )
                    )
                }

                return@withContext PartialResult(
                    name = data.publicName,
                    alternateName = data.alternateName,
                    imageUrl = data.profileImageUrl,
                    photoCandidates = photoCandidates,
                    about = data.about,
                    carrier = data.carrier,
                    country = data.country?.takeIf { it.isNotBlank() && it.lowercase() != "bangladesh" } ?: data.country,
                    region = data.region,
                    city = data.city,
                    isBusiness = data.isBusiness == true,
                    socialProfiles = socialProfiles,
                    confidence = if (data.confidence == "HIGH") 0.9f else 0.7f,
                    source = data.source ?: "Authorized Relay",
                    providerId = id,
                    providerVersion = version
                )
            }
        } catch (e: Exception) {
            Log.e("BackendRelay", "Authorized lookup failed: ${e.message}")
        }
        null
    }

    override suspend fun bulkLookup(identifiers: List<String>, type: String, context: LookupContext): Map<String, PartialResult> = withContext(Dispatchers.IO) {
        emptyMap()
    }
}
