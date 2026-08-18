package com.infocaller.app.domain.engine

import com.infocaller.app.domain.model.SocialProfile

interface LookupProvider {
    val id: String
    val name: String
    val version: String
    val capabilities: Set<Capability>
    
    suspend fun lookup(
        normalizedPhoneNumber: String,
        context: LookupContext = LookupContext()
    ): PartialResult?

    suspend fun bulkLookup(
        normalizedPhoneNumbers: List<String>,
        context: LookupContext = LookupContext()
    ): Map<String, PartialResult> = emptyMap()
}

enum class Capability {
    PHONE_METADATA,
    WHATSAPP,
    TELEGRAM,
    PROFILE_PHOTO,
    ABOUT,
    CARRIER,
    BUSINESS,
    PUBLIC_SEARCH,
    SOCIAL_MATCH,
    SPAM_CHECK,
    ALTERNATE_NAME,
    CITY,
    COUNTRY,
    TIMEZONE,
    EMAIL,
    PUBLIC_PROFILE
}

data class LookupContext(
    val forceRefresh: Boolean = false,
    val priority: Int = 0
)

data class PartialResult(
    val name: String? = null,
    val alternateName: String? = null,
    val imageUrl: String? = null,
    val about: String? = null,
    val city: String? = null,
    val country: String? = null,
    val region: String? = null,
    val timezone: String? = null,
    val email: String? = null,
    val carrier: String? = null,
    val socialProfiles: List<SocialProfile> = emptyList(),
    val spamScore: Int = 0,
    val spamType: String? = null,
    val isBusiness: Boolean? = null,
    val confidence: Float = 0f,
    val source: String? = null,
    val durationMs: Long = 0,
    val providerId: String? = null,
    val providerVersion: String? = null
)

interface PhoneMetadataProvider : LookupProvider
interface SpamProvider : LookupProvider
interface SocialProvider : LookupProvider
interface SearchProvider : LookupProvider
interface ImageProvider : LookupProvider
interface BusinessProvider : LookupProvider

enum class ProviderStatus {
    HEALTHY,
    DEGRADED,
    RATE_LIMITED,
    OFFLINE,
    BROKEN,
    DISABLED
}
