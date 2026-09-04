package com.infocaller.app.data.repository

import com.infocaller.app.domain.model.User
import com.infocaller.app.domain.repository.AuthRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class AuthRepositoryImpl : AuthRepository {
    private val _currentUser = MutableStateFlow<User?>(null)
    override val currentUser = _currentUser.asStateFlow()

    override suspend fun signOut() {
        _currentUser.value = null
    }
}
