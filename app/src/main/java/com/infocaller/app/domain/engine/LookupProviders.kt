package com.infocaller.app.domain.engine

import com.infocaller.app.domain.model.SocialProfile
import com.infocaller.app.domain.model.PhotoCandidate


interface LookupProvider {
    val id: String
    val name: String
    val version: String
    val capabilities: Set<Capability>
    val priority: Int
    val costClass: CostClass
    
    suspend fun lookup(
        identifier: String,
        type: String,
        context: LookupContext = LookupContext()
    ): PartialResult?

    suspend fun bulkLookup(
        identifiers: List<String>,
        type: String,
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
    ALTERNATE_NAME,
    CITY,
    COUNTRY,
    TIMEZONE,
    EMAIL,
    PUBLIC_PROFILE,
    LINE_TYPE,
    INFOSTEALER_LEAK,
    SERVICE_PRESENCE,
    PORTING_HISTORY,
    TELEGRAM_LINK,
    DISPOSABLE_CHECK,
    IP_RECON,
    DOMAIN_INTEL,
    CRYPTO_RECON,
    SOCIAL_UID_MATCH,
    DEEP_PII,
    VEHICLE_INTEL,
    FINANCIAL_RECON,
    CORPORATE_SEARCH,
    NETWORK_PIVOT,
    DARK_WEB_MENTION
}


enum class CostClass {
    FREE,
    LOW,
    MEDIUM,
    HIGH
}

object IdentifierType {
    const val PHONE = "PHONE"
    const val EMAIL = "EMAIL"
    const val USERNAME = "USERNAME"
    const val IP_ADDRESS = "IP_ADDRESS"
    const val DOMAIN = "DOMAIN"
    const val SOCIAL_UID = "SOCIAL_UID"
    const val CRYPTO_WALLET = "CRYPTO_WALLET"
    const val FULL_NAME = "FULL_NAME"
    const val PLATE_NUMBER = "PLATE_NUMBER"
    const val IBAN = "IBAN"
    const val VAT_ID = "VAT_ID"
    const val MAC_ADDRESS = "MAC_ADDRESS"
    const val NID = "NID"
    const val DOB = "DOB"
}

data class LookupContext(
    val forceRefresh: Boolean = false,
    val priority: Int = 0
)

data class PartialResult(
    val identifier: String? = null,
    val identifierType: String = IdentifierType.PHONE,
    val name: String? = null,
    val alternateName: String? = null,
    val imageUrl: String? = null,
    val photoCandidates: List<PhotoCandidate> = emptyList(),
    val about: String? = null,
    val city: String? = null,
    val country: String? = null,
    val region: String? = null,
    val timezone: String? = null,
    val email: String? = null,
    val carrier: String? = null,
    val lineType: String? = null,
    val socialProfiles: List<SocialProfile> = emptyList(),
    val isDisposable: Boolean? = null,
    val isBusiness: Boolean? = null,
    val plateNumber: String? = null,
    val iban: String? = null,
    val vatId: String? = null,
    val macAddress: String? = null,
    val nid: String? = null,
    val dob: String? = null,
    val confidence: Float = 0f,
    val source: String? = null,
    val durationMs: Long = 0,
    val providerId: String? = null,
    val providerVersion: String? = null
)

enum class ProviderStatus {
    HEALTHY,
    DEGRADED,
    RATE_LIMITED,
    OFFLINE,
    BROKEN,
    DISABLED,
    UNAVAILABLE,
    NOT_CONFIGURED,
    AUTHORIZED
}

interface PhoneMetadataProvider : LookupProvider
interface SocialProvider : LookupProvider
interface SearchProvider : LookupProvider
interface ImageProvider : LookupProvider
interface BusinessProvider : LookupProvider
interface ReputationProvider : LookupProvider
