package com.infocaller.app.domain.model

import androidx.annotation.Keep

@Keep
data class LookupResult(
    val phoneNumber: String,
    val name: String? = null,
    val imageUrl: String? = null,
    val about: String? = null,
    val city: String? = null,
    val country: String? = null,
    val region: String? = null,
    val carrier: String? = null,
    val socialProfiles: List<SocialProfile> = emptyList(),
    val spamScore: Int = 0,
    val spamType: String? = null,
    val isBusiness: Boolean? = null,
    val spamStatus: SpamStatus = SpamStatus.UNKNOWN,
    val sources: List<String> = emptyList(),
    val confidence: Float = 0f,
    val performance: List<ProviderPerformance> = emptyList(),
    val timestamp: Long = System.currentTimeMillis()
)

@Keep
data class ProviderPerformance(
    val providerName: String,
    val durationMs: Long
)

@Keep
data class SocialProfile(
    val platform: String,
    val username: String? = null,
    val profileUrl: String? = null,
    val status: SocialLookupStatus = SocialLookupStatus.UNKNOWN
)

enum class SocialLookupStatus {
    CONFIRMED,
    PUBLIC_MATCH,
    POSSIBLE_MATCH,
    NOT_FOUND,
    UNKNOWN,
    UNSUPPORTED
}

enum class SpamLevel {
    SAFE,
    LOW,
    MEDIUM,
    HIGH,
    SEVERE,
    UNKNOWN
}
