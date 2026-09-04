package com.infocaller.app.data.remote.model

import androidx.annotation.Keep

/** Shared-registry record shape (RegistryApiService + RegistryLookupProvider). */
@Keep
data class RegistryLookupResponse(
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
