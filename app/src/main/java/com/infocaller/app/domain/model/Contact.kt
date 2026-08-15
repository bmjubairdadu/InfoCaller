package com.infocaller.app.domain.model

data class Contact(
    val id: String,
    val displayName: String,
    val phoneNumber: String?,
    val photoUri: String?
)
