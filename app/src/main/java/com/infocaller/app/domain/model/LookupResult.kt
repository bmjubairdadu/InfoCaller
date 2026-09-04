package com.infocaller.app.domain.model

import androidx.annotation.Keep

@Keep
data class LookupResult(
    val phoneNumber: String,
    val name: String? = null,
    val nameSource: String? = null,
    val alternateName: String? = null,
    val alternateNames: Map<String, List<String>> = emptyMap(),
    val imageUrl: String? = null,
    val imageSource: String? = null,
    val photoCandidates: List<PhotoCandidate> = emptyList(),
    val about: String? = null,
    val city: String? = null,
    val country: String? = null,
    val region: String? = null,
    val timezone: String? = null,
    val email: String? = null,
    val emailSource: String? = null,
    val carrier: String? = null,
    val lineType: String? = null,
    
    val plateNumber: String? = null,
    val iban: String? = null,
    val vatId: String? = null,
    val macAddress: String? = null,
    val nid: String? = null,
    val dob: String? = null,

    val socialProfiles: List<SocialProfile> = emptyList(),
    val isBusiness: Boolean? = null,
    val sources: List<String> = emptyList(),
    val confidence: Float = 0f,
    val performance: List<ProviderPerformance> = emptyList(),
    val timestamp: Long = System.currentTimeMillis()
)

@Keep
data class PhotoCandidate(
    val provider: String,
    val url: String,
    val width: Int = 0,
    val height: Int = 0,
    val fileSize: Long = 0,
    val faceCount: Int = -1,
    val faceConfidence: Float = 0f,
    val faceCoverage: Float = 0f,
    val imageQuality: Float = 0f,
    val sourcePriority: Int = 0,
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
    val status: SocialLookupStatus = SocialLookupStatus.UNKNOWN,
    val displayName: String? = null,
    val avatarUrl: String? = null,
    val source: String? = null,
    val confidence: Float = 0f,
    val foundAt: Long = System.currentTimeMillis()
)

enum class SocialLookupStatus {
    CONFIRMED,
    PUBLIC_MATCH,
    POSSIBLE_MATCH,
    NOT_FOUND,
    UNKNOWN,
    UNSUPPORTED
}
