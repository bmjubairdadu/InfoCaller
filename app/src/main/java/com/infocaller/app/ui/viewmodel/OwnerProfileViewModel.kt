package com.infocaller.app.ui.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.infocaller.app.data.remote.OwnerClaimRepository
import com.infocaller.app.data.remote.OwnerProfileRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface OwnerUiState {
    data object Idle : OwnerUiState
    data object Loading : OwnerUiState
    data class Message(val text: String, val error: Boolean = false) : OwnerUiState
}

class OwnerProfileViewModel(private val repo: OwnerClaimRepository) : ViewModel() {
    private val _state = MutableStateFlow<OwnerUiState>(OwnerUiState.Idle)
    val state = _state.asStateFlow()

    val backendUrl = MutableStateFlow(repo.backendBaseUrl())
    val verifiedPhone = MutableStateFlow(repo.verifiedPhone())
    val isVerified = MutableStateFlow(repo.isVerified())
    val myProfileJson = MutableStateFlow<String?>(null)

    var phone = MutableStateFlow("")
    var code = MutableStateFlow("")
    var displayName = MutableStateFlow("")
    var photoUrl = MutableStateFlow("")
    var businessName = MutableStateFlow("")
    var businessCategory = MutableStateFlow("")
    var country = MutableStateFlow("")
    var isBusiness = MutableStateFlow(false)
    var visibility = MutableStateFlow("public")
    var consentChecked = MutableStateFlow(false)

    fun refresh() {
        backendUrl.value = repo.backendBaseUrl()
        verifiedPhone.value = repo.verifiedPhone()
        isVerified.value = repo.isVerified()
    }

    fun setBackend(url: String) {
        repo.setBackendBaseUrl(url)
        backendUrl.value = repo.backendBaseUrl()
    }

    fun requestOtp() = viewModelScope.launch {
        _state.value = OwnerUiState.Loading
        val r = repo.requestOtp(phone.value)
        _state.value = r.fold(
            onSuccess = { OwnerUiState.Message("OTP sent. Check SMS.") },
            onFailure = { OwnerUiState.Message(it.message ?: "OTP failed", true) }
        )
    }

    fun verify() = viewModelScope.launch {
        _state.value = OwnerUiState.Loading
        val r = repo.verifyOtp(code.value)
        _state.value = r.fold(
            onSuccess = {
                refresh()
                loadProfile()
                OwnerUiState.Message("Number verified: $it")
            },
            onFailure = { OwnerUiState.Message(it.message ?: "Verify failed", true) }
        )
    }

    fun loadProfile() = viewModelScope.launch {
        val r = repo.loadMyProfile()
        r.onSuccess { myProfileJson.value = it }
        r.onFailure { _state.value = OwnerUiState.Message(it.message ?: "Load failed", true) }
    }

    fun publish() = viewModelScope.launch {
        _state.value = OwnerUiState.Loading
        val r = repo.publishOwnProfile(
            OwnerProfileRequest(
                phone = verifiedPhone.value ?: phone.value,
                displayName = displayName.value.trim(),
                photoUrl = photoUrl.value.trim().takeIf { it.isNotBlank() },
                businessName = businessName.value.trim().takeIf { it.isNotBlank() },
                businessCategory = businessCategory.value.trim().takeIf { it.isNotBlank() },
                country = country.value.trim().takeIf { it.isNotBlank() },
                isBusiness = isBusiness.value,
                visibility = visibility.value,
                consentGranted = consentChecked.value
            )
        )
        _state.value = r.fold(
            onSuccess = {
                loadProfile()
                OwnerUiState.Message("Profile published. Only public+verified info is shown to others.")
            },
            onFailure = { OwnerUiState.Message(it.message ?: "Publish failed", true) }
        )
    }

    fun setVisibility(v: String) = viewModelScope.launch {
        val r = repo.updateVisibility(v)
        _state.value = r.fold(
            onSuccess = {
                visibility.value = v
                loadProfile()
                OwnerUiState.Message("Visibility: $v")
            },
            onFailure = { OwnerUiState.Message(it.message ?: "Update failed", true) }
        )
    }

    fun revoke() = viewModelScope.launch {
        val r = repo.revokeConsent()
        _state.value = r.fold(
            onSuccess = {
                consentChecked.value = false
                loadProfile()
                OwnerUiState.Message("Consent revoked. Your info is now private.")
            },
            onFailure = { OwnerUiState.Message(it.message ?: "Revoke failed", true) }
        )
    }

    fun delete() = viewModelScope.launch {
        val r = repo.deleteProfile()
        _state.value = r.fold(
            onSuccess = {
                refresh()
                myProfileJson.value = null
                OwnerUiState.Message("Profile deleted.")
            },
            onFailure = { OwnerUiState.Message(it.message ?: "Delete failed", true) }
        )
    }

    fun report(phone: String, reason: String) = viewModelScope.launch {
        val r = repo.reportSpam(phone, reason)
        _state.value = r.fold(
            onSuccess = { OwnerUiState.Message("Report submitted.") },
            onFailure = { OwnerUiState.Message(it.message ?: "Report failed", true) }
        )
    }

    class Factory(private val context: Context) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return OwnerProfileViewModel(OwnerClaimRepository(context.applicationContext)) as T
        }
    }
}
