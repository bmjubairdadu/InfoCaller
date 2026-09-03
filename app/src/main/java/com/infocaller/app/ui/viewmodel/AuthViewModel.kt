package com.infocaller.app.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.infocaller.app.domain.model.User
import com.infocaller.app.domain.repository.AuthRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class AuthViewModel(
    private val repository: AuthRepository,
    private val context: android.content.Context
) : ViewModel() {

    private val _authState = MutableStateFlow<AuthUiState>(AuthUiState.Idle)
    val authState: StateFlow<AuthUiState> = _authState.asStateFlow()

    private val _tcAuthResult = MutableStateFlow<com.infocaller.app.data.remote.TruecallerProviderImpl.AuthRequestResult?>(null)
    val tcAuthResult = _tcAuthResult.asStateFlow()

    private val _tcPhone = MutableStateFlow("")
    val tcPhone = _tcPhone.asStateFlow()

    fun setTcAuthResult(result: com.infocaller.app.data.remote.TruecallerProviderImpl.AuthRequestResult?) {
        _tcAuthResult.value = result
    }

    fun setTcPhone(phone: String) {
        _tcPhone.value = phone
    }

    fun refreshTcSession(context: android.content.Context) {
        val prefs = context.getSharedPreferences("app_prefs", android.content.Context.MODE_PRIVATE)
        prefs.edit()
            .remove("tc_device_id")
            .remove("truecaller_token")
            .apply()
    }

    init {
        checkInitialAuthState()
    }

    fun checkInitialAuthState() {
        val prefs = context.getSharedPreferences("app_prefs", android.content.Context.MODE_PRIVATE)
        val token = prefs.getString("truecaller_token", "")
        
        if (!token.isNullOrBlank()) {
            _authState.value = AuthUiState.Authenticated(User("tc-saved", null, "Verified User", null))
        } else {
            _authState.value = AuthUiState.Idle
        }
    }

    val currentUser = repository.currentUser

    fun login(email: String, password: String) {
        viewModelScope.launch {
            _authState.value = AuthUiState.Loading
            repository.signIn(email, password).onSuccess {
                _authState.value = AuthUiState.Authenticated(it)
            }.onFailure {
                _authState.value = AuthUiState.Error(it.message ?: "Login failed")
            }
        }
    }

    fun loginWithTruecaller(displayName: String?) {
        viewModelScope.launch {
            _authState.value = AuthUiState.Loading
            // Simulate successful authentication via Truecaller
            val user = User(
                id = "tc-${System.currentTimeMillis()}",
                email = null,
                displayName = displayName ?: "Truecaller User",
                photoUrl = null
            )
            _authState.value = AuthUiState.Authenticated(user)
        }
    }

    fun register(email: String, password: String) {
        viewModelScope.launch {
            _authState.value = AuthUiState.Loading
            repository.signUp(email, password).onSuccess {
                _authState.value = AuthUiState.Authenticated(it)
            }.onFailure {
                _authState.value = AuthUiState.Error(it.message ?: "Registration failed")
            }
        }
    }

    fun logout() {
        viewModelScope.launch {
            repository.signOut()
            _authState.value = AuthUiState.Idle
        }
    }

    class Factory(
        private val repository: AuthRepository,
        private val context: android.content.Context
    ) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(AuthViewModel::class.java)) {
                @Suppress("UNCHECKED_CAST")
                return AuthViewModel(repository, context.applicationContext) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class")
        }
    }
}

sealed class AuthUiState {
    object Idle : AuthUiState()
    object Loading : AuthUiState()
    data class Authenticated(val user: User) : AuthUiState()
    data class Error(val message: String) : AuthUiState()
}
