package com.infocaller.app.domain.model

import androidx.annotation.Keep

@Keep
data class Caller(
    val phoneNumber: String,
    val displayName: String?,
    val alias: String?,
    val photoUrl: String?,
    val organization: String?,
    val country: String?,
    val region: String?,
    val carrier: String?,
    val spamScore: Int = 0,
    val reportCount: Int = 0,
    val isVerified: Boolean = false,
    val spamStatus: SpamStatus = SpamStatus.UNKNOWN,
    val socialMediaLinks: List<String> = emptyList()
)

enum class SpamStatus {
    SAFE,
    SUSPICIOUS,
    SPAM,
    SCAM,
    UNKNOWN
}
