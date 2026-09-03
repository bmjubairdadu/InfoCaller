package com.infocaller.app.domain.model

import androidx.annotation.Keep

@Keep
data class Caller(
    val phoneNumber: String,
    val localName: String? = null,
    val displayName: String?,
    val alias: String?,
    val photoUrl: String?,
    val organization: String?,
    val country: String?,
    val region: String?,
    val carrier: String?,
    val reportCount: Int = 0,
    val isVerified: Boolean = false,
    val socialMediaLinks: List<String> = emptyList()
)
