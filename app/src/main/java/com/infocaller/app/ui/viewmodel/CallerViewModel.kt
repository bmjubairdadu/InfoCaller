package com.infocaller.app.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.infocaller.app.domain.model.CallLogEntry
import com.infocaller.app.domain.model.Caller
import com.infocaller.app.domain.model.Contact
import com.infocaller.app.domain.repository.CallerRepository
import com.infocaller.app.domain.repository.DeviceDataRepository
import com.infocaller.app.data.repository.ContactEnrichmentService
import com.infocaller.app.data.local.entity.LocalContactEntity
import com.infocaller.app.data.local.database.AppDatabase
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class CallerViewModel(
    private val repository: CallerRepository,
    private val deviceDataRepository: DeviceDataRepository,
    private val contactEnrichmentService: ContactEnrichmentService,
    private val database: AppDatabase,
    private val lookupEngine: com.infocaller.app.domain.engine.PublicLookupEngine
) : ViewModel() {

    private val _themeMode = MutableStateFlow<Boolean?>(true) // Default to Dark, null means System
    val themeMode: StateFlow<Boolean?> = _themeMode.asStateFlow()

    fun setThemeMode(isDark: Boolean?, context: android.content.Context) {
        _themeMode.value = isDark
        val prefs = context.getSharedPreferences("app_prefs", android.content.Context.MODE_PRIVATE)
        if (isDark == null) {
            prefs.edit().remove("dark_theme").apply()
        } else {
            prefs.edit().putBoolean("dark_theme", isDark).apply()
        }
    }

    private val _searchResult = MutableStateFlow<SearchUiState>(SearchUiState.Idle)
    val searchResult: StateFlow<SearchUiState> = _searchResult.asStateFlow()

    private val _dialerInput = MutableStateFlow("")
    val dialerInput: StateFlow<String> = _dialerInput.asStateFlow()

    private val _showSimSelection = MutableStateFlow<String?>(null)
    val showSimSelection: StateFlow<String?> = _showSimSelection.asStateFlow()

    fun showSimSelection(phoneNumber: String) {
        _showSimSelection.value = phoneNumber
    }

    fun dismissSimSelection() {
        _showSimSelection.value = null
    }

    val recentCalls: StateFlow<List<CallLogEntry>> = deviceDataRepository.getRecentCalls()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val contacts: StateFlow<List<Contact>> = deviceDataRepository.getContacts()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun updateDialerInput(input: String) {
        _dialerInput.value = input
    }

    fun searchNumber(phoneNumber: String) {
        if (phoneNumber.isBlank()) return

        viewModelScope.launch {
            _searchResult.value = SearchUiState.Loading
            try {
                val caller = repository.searchCaller(phoneNumber)
                if (caller != null) {
                    _searchResult.value = SearchUiState.Success(caller)
                } else {
                    _searchResult.value = SearchUiState.NotFound
                }
            } catch (e: Exception) {
                _searchResult.value = SearchUiState.Error(e.message ?: "Unknown error")
            }
        }
    }


    fun clearSearch() {
        _searchResult.value = SearchUiState.Idle
    }

    fun updateCallerInfo(caller: Caller) {
        viewModelScope.launch {
            repository.saveCaller(caller)
            contactEnrichmentService.updateExistingContact(caller.phoneNumber, caller)
            _searchResult.value = SearchUiState.Success(caller)
        }
    }

    private val _syncState = MutableStateFlow<SyncState>(SyncState.Idle)
    val syncState: StateFlow<SyncState> = _syncState.asStateFlow()

    fun performMasterSync() {
        viewModelScope.launch {
            _syncState.value = SyncState.Syncing(0f)
            try {
                contactEnrichmentService.enrichAllContactsInBg()
                _syncState.value = SyncState.Completed
            } catch (_: Exception) {
                _syncState.value = SyncState.Idle
            }
        }
    }

    private val _waSyncState = MutableStateFlow<SyncState>(SyncState.Idle)

    fun syncWhatsAppPhotos() {
        if (_waSyncState.value is SyncState.Syncing) return
        
        viewModelScope.launch {
            _waSyncState.value = SyncState.Syncing(0f)
            try {
                contactEnrichmentService.syncAllWhatsAppPhotos { _, _ -> }
                contactEnrichmentService.enrichAllContactsInBg()
                _waSyncState.value = SyncState.Completed
            } catch (_: Exception) {
                _waSyncState.value = SyncState.Idle
            }
        }
    }

    val blocklist: StateFlow<List<String>> = repository.getBlocklist()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun blockNumber(phoneNumber: String) {
        viewModelScope.launch {
            repository.blockNumber(phoneNumber)
        }
    }

    fun unblockNumber(phoneNumber: String) {
        viewModelScope.launch {
            repository.unblockNumber(phoneNumber)
        }
    }

    fun deleteContact(phoneNumber: String) {
        viewModelScope.launch {
            contactEnrichmentService.deleteContact(phoneNumber)
        }
    }

    fun deleteCallLog(number: String, date: Long) {
        viewModelScope.launch {
            deviceDataRepository.deleteCallLogEntry(number, date)
        }
    }

    fun clearAllCallLogs() {
        viewModelScope.launch {
            deviceDataRepository.clearCallLog()
        }
    }

    val localContacts: StateFlow<List<LocalContactEntity>> = database.localContactDao().getAllContacts()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun triggerThrottledSync(context: android.content.Context) {
        val workRequest = OneTimeWorkRequestBuilder<com.infocaller.app.worker.EnrichmentWorker>().build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            "ThrottledSync",
            androidx.work.ExistingWorkPolicy.KEEP,
            workRequest
        )
    }

    private val _recoveryState = MutableStateFlow<String?>(null)
    val recoveryState: StateFlow<String?> = _recoveryState.asStateFlow()

    fun runEmergencyCleanup() {
        viewModelScope.launch {
            _recoveryState.value = "Starting cleanup..."
            val count = contactEnrichmentService.emergencyCleanup { progress ->
                _recoveryState.value = progress
            }
            _recoveryState.value = "Cleaned $count placeholder names."
        }
    }

    class Factory(
        private val repository: CallerRepository,
        private val deviceDataRepository: DeviceDataRepository,
        private val contactEnrichmentService: ContactEnrichmentService,
        private val database: AppDatabase,
        private val lookupEngine: com.infocaller.app.domain.engine.PublicLookupEngine
    ) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(CallerViewModel::class.java)) {
                @Suppress("UNCHECKED_CAST")
                return CallerViewModel(repository, deviceDataRepository, contactEnrichmentService, database, lookupEngine) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class")
        }
    }

    private val _lookupResult = MutableStateFlow<com.infocaller.app.domain.model.LookupResult?>(null)
    val fullLookupResult: StateFlow<com.infocaller.app.domain.model.LookupResult?> = _lookupResult.asStateFlow()

    fun performFullLookup(phoneNumber: String) {
        viewModelScope.launch {
            _lookupResult.value = lookupEngine.performLookup(phoneNumber)
        }
    }

    fun getEnrichment(number: String): Flow<com.infocaller.app.data.local.entity.ContactEnrichmentEntity?> {
        return database.enrichmentDao().getEnrichment(com.infocaller.app.util.PhoneNumberUtils.normalize(number))
    }
}

sealed class SearchUiState {
    object Idle : SearchUiState()
    object Loading : SearchUiState()
    data class Success(val caller: Caller) : SearchUiState()
    object NotFound : SearchUiState()
    data class Error(val message: String) : SearchUiState()
}

sealed class SyncState {
    object Idle : SyncState()
    data class Syncing(val progress: Float) : SyncState()
    object Completed : SyncState()
}
