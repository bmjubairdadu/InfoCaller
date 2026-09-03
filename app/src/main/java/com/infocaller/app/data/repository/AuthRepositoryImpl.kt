package com.infocaller.app.data.repository

import com.infocaller.app.domain.model.User
import com.infocaller.app.domain.repository.AuthRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class AuthRepositoryImpl : AuthRepository {
    private val _currentUser = MutableStateFlow<User?>(null)
    override val currentUser = _currentUser.asStateFlow()

    override suspend fun signIn(email: String, password: String): Result<User> {
        // Mock success for legacy auth
        val user = User(
            id = "mock-user-id",
            email = email,
            displayName = email.split("@").first(),
            photoUrl = null
        )
        _currentUser.value = user
        return Result.success(user)
    }

    override suspend fun signUp(email: String, password: String): Result<User> {
        // Mock success for legacy auth
        val user = User(
            id = "mock-user-id",
            email = email,
            displayName = email.split("@").first(),
            photoUrl = null
        )
        _currentUser.value = user
        return Result.success(user)
    }

    override suspend fun signOut() {
        _currentUser.value = null
    }
}
