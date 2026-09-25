package com.infocaller.app.domain.model

data class User(
    val id: String,
    val email: String?,
    val displayName: String?,
    val photoUrl: String?,
    val role: UserRole = UserRole.USER
)

enum class UserRole {
    USER,
    INVESTIGATOR,
    ADMIN
}
