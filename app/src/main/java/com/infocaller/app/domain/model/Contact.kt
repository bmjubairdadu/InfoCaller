package com.infocaller.app.domain.model

import androidx.annotation.Keep

@Keep
data class Contact(
    val id: String,
    val displayName: String,
    val phoneNumber: String?,
    val photoUri: String?,
    val email: String? = null,
    val emails: List<String> = emptyList(),
    val bio: String? = null,
    val notes: String? = null,
    val organization: String? = null,
    val jobTitle: String? = null,
    val address: String? = null,
    val website: String? = null,
    val birthday: String? = null,
    val nickname: String? = null,
    val alternateNumbers: List<String> = emptyList(),
    val photoThumbnailUri: String? = null,
    val isFavorite: Boolean = false,
    val lastContacted: Long = 0L,
    val timesContacted: Int = 0
)
