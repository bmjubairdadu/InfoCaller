package com.infocaller.app.domain.repository

import com.infocaller.app.domain.model.User
import kotlinx.coroutines.flow.Flow

interface AuthRepository {
    val currentUser: Flow<User?>
    suspend fun signOut()
}
