package com.infocaller.app.data.remote.model

import androidx.annotation.Keep
import com.google.gson.annotations.SerializedName

@Keep
data class ApifyLookupRequest(
    val numbers: List<String>,
    val concurrency: Int = 10,
    val fbProfilePic: Boolean = true,
    val includeAbout: Boolean = true,
    val includeCarrier: Boolean = true,
    val includeGoogle: Boolean = true,
    val includeLeaks: Boolean = false,
    val includeLookup: Boolean = true,
    val includeProfilePic: Boolean = true,
    val includeTelegram: Boolean = true,
    val onlyCache: Boolean = false,
    val preferCache: Boolean = true
)

@Keep
data class ApifyLookupItem(
    val number: String,
    val exists: Boolean? = null,
    val isBusiness: Boolean? = null,
    val urlImage: String? = null,
    val profilePicture: String? = null,
    val about: String? = null,
    val source: String? = null,
    val fetchedAt: String? = null,
    val carrier: String? = null,
    val country: String? = null,
    val region: String? = null,
    val category: String? = null,
    val description: String? = null,
    val website: String? = null,
    val location: String? = null,
    val telegram: TelegramResult? = null,
    val google: GoogleResult? = null,
    val lookup: Map<String, Any>? = null
)

@Keep
data class TelegramResult(
    val error: String? = null,
    val phone: String? = null,
    val username: String? = null,
    val name: String? = null,
    val bio: String? = null,
    val photo: String? = null
)

@Keep
data class GoogleResult(
    val error: String? = null,
    val success: Any? = null,
    val results: List<GoogleSearchItem>? = null
)

@Keep
data class GoogleSearchItem(
    val title: String? = null,
    val link: String? = null,
    val snippet: String? = null
)

@Keep
data class NumverifyResponse(
    val valid: Boolean? = null,
    val number: String? = null,
    val local_format: String? = null,
    val international_format: String? = null,
    val country_prefix: String? = null,
    val country_code: String? = null,
    val country_name: String? = null,
    val location: String? = null,
    val carrier: String? = null,
    val line_type: String? = null
)

// InfoCaller Normalized Format for Backend
@Keep
data class InfoCallerLookupResponse(
    val phoneNumber: String,
    val contactName: String? = null,
    val publicName: String? = null,
    val alternateName: String? = null,
    val profileImageUrl: String? = null,
    val about: String? = null,
    val carrier: String? = null,
    val country: String? = null,
    val city: String? = null,
    val region: String? = null,
    val timezone: String? = null,
    val email: String? = null,
    val whatsappStatus: String? = null,
    val telegramStatus: String? = null,
    val googleResult: Any? = null,
    val isBusiness: Boolean? = null,
    val socialProfilesJson: String? = null,
    val lineType: String? = null,
    val source: String? = null,
    val confidence: String? = null,
    val lastChecked: Long? = null
)
