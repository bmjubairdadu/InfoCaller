package com.infocaller.app.ui.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.infocaller.app.domain.model.*
import com.infocaller.app.domain.repository.CallerRepository
import com.infocaller.app.domain.repository.DeviceDataRepository
import com.infocaller.app.data.repository.ContactEnrichmentService
import com.infocaller.app.data.local.entity.LocalContactEntity
import com.infocaller.app.data.local.database.AppDatabase
import com.infocaller.app.util.SimInfo
import com.infocaller.app.util.SimManager
import com.infocaller.app.util.PhoneNumberUtils
import com.infocaller.app.util.T9Search
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job

class CallerViewModel(
    private val repository: CallerRepository,
    private val deviceDataRepository: DeviceDataRepository,
    private val contactEnrichmentService: ContactEnrichmentService,
    private val database: AppDatabase,
    private val lookupEngine: com.infocaller.app.domain.engine.IPublicLookupEngine
) : ViewModel() {
    private val _themeMode = MutableStateFlow<Boolean?>(null)
    val themeMode: StateFlow<Boolean?> = _themeMode.asStateFlow()

    private val _simInfos = MutableStateFlow<List<SimInfo>>(emptyList())
    val simInfos: StateFlow<List<SimInfo>> = _simInfos.asStateFlow()

    private val _dialerInput = MutableStateFlow("")
    val dialerInput: StateFlow<String> = _dialerInput.asStateFlow()

    private val _searchResult = MutableStateFlow<SearchUiState>(SearchUiState.Idle)
    val searchResult: StateFlow<SearchUiState> = _searchResult.asStateFlow()

    @Volatile
    private var searchGeneration = 0
    private var activeSearchJob: Job? = null

    private val _scanSteps = MutableStateFlow<List<ScanStepUi>>(emptyList())
    val scanSteps: StateFlow<List<ScanStepUi>> = _scanSteps.asStateFlow()

    private val _scanActive = MutableStateFlow(false)
    val scanActive: StateFlow<Boolean> = _scanActive.asStateFlow()

    private val _fullLookupResult = MutableStateFlow<LookupResult?>(null)
    val fullLookupResult: StateFlow<LookupResult?> = _fullLookupResult.asStateFlow()

    private val _showSimSelection = MutableStateFlow<String?>(null)
    val showSimSelection: StateFlow<String?> = _showSimSelection.asStateFlow()

    private val _deviceDataTick = MutableStateFlow(0)
    fun refreshDeviceData() { _deviceDataTick.value += 1 }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val recentCalls: StateFlow<List<CallLogEntry>> = _deviceDataTick.flatMapLatest {
        deviceDataRepository.getRecentCalls()
    }
        .map { list ->
            list.map { it.copy(number = PhoneNumberUtils.normalize(it.number)) }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val contacts: StateFlow<List<Contact>> = _deviceDataTick.flatMapLatest {
        deviceDataRepository.getContacts()
    }
        .map { list ->
            list.map { it.copy(phoneNumber = PhoneNumberUtils.normalize(it.phoneNumber ?: "")) }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val filteredContacts: StateFlow<List<Contact>> = combine(_dialerInput, contacts) { input, list ->
        if (input.isEmpty()) emptyList()
        else list.filter {
            (it.phoneNumber?.contains(input) == true) || T9Search.matches(input, it.displayName)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun loadSimInfos(context: Context) {
        viewModelScope.launch {
            _simInfos.value = try {
                SimManager.getSimInfos(context)
            } catch (_: Exception) {
                emptyList()
            }
        }
    }

    fun setThemeMode(isDark: Boolean?, context: Context) {
        _themeMode.value = isDark
        val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        if (isDark == null) prefs.edit().remove("dark_theme").apply()
        else prefs.edit().putBoolean("dark_theme", isDark).apply()
    }

    fun updateDialerInput(input: String) {
        _dialerInput.value = input
    }

    fun searchNumber(phoneNumber: String) {
        when (com.infocaller.app.util.IdentifierRouter.routeType(phoneNumber)) {
            com.infocaller.app.domain.engine.IdentifierType.EMAIL -> {
                searchEmailManual(phoneNumber)
                return
            }
            com.infocaller.app.domain.engine.IdentifierType.USERNAME -> {
                searchUsernameManual(phoneNumber)
                return
            }
            else -> {}
        }
        val normalized = PhoneNumberUtils.normalize(phoneNumber)
        searchByIdentifier(normalized, com.infocaller.app.domain.engine.IdentifierType.PHONE)
    }

    fun searchNumberManual(phoneNumber: String) {
        searchNumber(phoneNumber)
    }

    fun searchNidManual(identifier: String) {
        return
    }

    fun searchEmailManual(email: String) {
        val cleaned = email.trim().lowercase()
        if (cleaned.isBlank() || !com.infocaller.app.util.IdentifierRouter.isEmail(cleaned)) return
        searchByIdentifier(cleaned, com.infocaller.app.domain.engine.IdentifierType.EMAIL)
    }

    fun searchUsernameManual(username: String) {
        val cleaned = username.trim().lowercase().removePrefix("@")
        if (cleaned.length !in 2..40 || cleaned.contains(" ") || cleaned.contains("@")) return
        searchByIdentifier(cleaned, com.infocaller.app.domain.engine.IdentifierType.USERNAME)
    }

    fun performFullLookup(phoneNumber: String) {
        val normalized = PhoneNumberUtils.normalize(phoneNumber)
        viewModelScope.launch {
            repository.startScan(normalized, com.infocaller.app.domain.engine.ScanPriority.CRITICAL)
                .collect { state ->
                    if (state is com.infocaller.app.domain.engine.ScanState.Progress ||
                        state is com.infocaller.app.domain.engine.ScanState.Completed) {
                        val result = if (state is com.infocaller.app.domain.engine.ScanState.Progress) state.result else (state as com.infocaller.app.domain.engine.ScanState.Completed).result
                        _fullLookupResult.value = result
                    }
                }
        }
    }

    fun searchByIdentifier(identifier: String, type: String) {
        if (identifier.isBlank()) return
        val generation = ++searchGeneration
        activeSearchJob?.cancel()
        activeSearchJob = viewModelScope.launch {
            if (type == com.infocaller.app.domain.engine.IdentifierType.PHONE) {
                val cached = repository.getFreshCachedCaller(identifier)
                if (cached != null && generation == searchGeneration) {
                    _searchResult.value = SearchUiState.Success(cached, isLive = false)
                    _scanActive.value = false
                    return@launch
                }
            }
            _searchResult.value = SearchUiState.Loading
            _scanSteps.value = emptyList()
            _scanActive.value = true
            try {
                val scanFlow = repository.startScan(identifier, com.infocaller.app.domain.engine.ScanPriority.CRITICAL, type)
                var sawProviderStep = false
                scanFlow.collect { state ->
                        if (generation != searchGeneration) return@collect
                        when (state) {
                            is com.infocaller.app.domain.engine.ScanState.ProviderStep -> {
                                sawProviderStep = true
                                applyScanStep(state)
                            }
                            is com.infocaller.app.domain.engine.ScanState.Progress -> {
                                try {
                                    repository.saveLookupResult(state.result)
                                } catch (_: Exception) { }
                                if (generation != searchGeneration) return@collect
                                _searchResult.value = SearchUiState.Success(
                                    mapToCaller(state.result),
                                    isLive = true,
                                    lastProvider = state.lastProvider,
                                    livePartial = state.result,
                                )
                            }
                            is com.infocaller.app.domain.engine.ScanState.Completed -> {
                                repository.saveLookupResult(state.result)
                                if (generation != searchGeneration) return@collect
                                _searchResult.value = SearchUiState.Success(mapToCaller(state.result), isLive = false)
                                _scanActive.value = false
                                try {
                                    autoSyncToPhonebook(state.result)
                                } catch (_: Exception) { }
                            }
                            is com.infocaller.app.domain.engine.ScanState.Error -> {
                                if (generation != searchGeneration) return@collect
                                val cached = try {
                                    repository.searchCaller(identifier)
                                } catch (_: Exception) { null }
                                if (cached != null) {
                                    _searchResult.value = SearchUiState.Success(cached, isLive = false)
                                } else {
                                    _searchResult.value = SearchUiState.Error(state.message)
                                }
                                _scanActive.value = false
                            }
                            else -> {}
                        }
                    }
            } catch (e: Exception) {
                if (generation != searchGeneration) return@launch
                try {
                    val cached = repository.searchCaller(identifier)
                    if (cached != null) {
                        _searchResult.value = SearchUiState.Success(cached, isLive = false)
                    } else {
                        _searchResult.value = SearchUiState.Error(e.message ?: "Unknown error")
                    }
                } catch (_: Exception) {
                    _searchResult.value = SearchUiState.Error(e.message ?: "Unknown error")
                }
                _scanActive.value = false
            }
        }
    }

    private fun applyScanStep(step: com.infocaller.app.domain.engine.ScanState.ProviderStep) {
        val current = _scanSteps.value.toMutableList()
        val idx = current.indexOfFirst { it.providerId == step.providerId }
        val ui = ScanStepUi(
            providerId = step.providerId,
            providerName = step.providerName.ifBlank { step.providerId },
            stepIndex = step.stepIndex,
            stepTotal = step.stepTotal,
            status = step.status.name
        )
        if (idx >= 0) current[idx] = ui else current.add(ui)
        current.sortBy { it.stepIndex }
        _scanSteps.value = current
    }

    fun dismissScanPopup() {
        _scanActive.value = false
    }

    private suspend fun autoSyncToPhonebook(result: LookupResult) {
        if (result.phoneNumber.isBlank()) return
        if (!com.infocaller.app.util.IdentifierRouter.routeType(result.phoneNumber)
            .equals(com.infocaller.app.domain.engine.IdentifierType.PHONE, ignoreCase = true)
        ) return
        val caller = mapToCaller(result)
        if (caller.displayName.isNullOrBlank() && caller.photoUrl.isNullOrBlank()) return
        contactEnrichmentService.updateExistingContact(result.phoneNumber, caller)
    }

    private fun mapToCaller(res: LookupResult): Caller {
        return Caller(
            phoneNumber = res.phoneNumber,
            displayName = res.name,
            alias = res.alternateName,
            photoUrl = res.imageUrl,
            organization = res.carrier,
            country = res.country,
            region = res.region,
            carrier = res.carrier,
            email = res.email,
            reportCount = 0,
            isVerified = false,
            socialMediaLinks = res.socialProfiles.mapNotNull { it.profileUrl }
        )
    }

    fun showSimSelection(phoneNumber: String) { _showSimSelection.value = phoneNumber }
    fun dismissSimSelection() { _showSimSelection.value = null }
    fun cancelSearch(phoneNumber: String) { repository.cancelScan(phoneNumber); _searchResult.value = SearchUiState.Idle }
    fun clearSearch() { _searchResult.value = SearchUiState.Idle; _scanSteps.value = emptyList(); _scanActive.value = false }

    fun cancelAllSearches() {
        searchGeneration++
        activeSearchJob?.cancel()
        activeSearchJob = null
        try { repository.cancelAllScans() } catch (_: Exception) { }
        _searchResult.value = SearchUiState.Idle
        _scanSteps.value = emptyList()
        _scanActive.value = false
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
            } catch (_: Exception) { _waSyncState.value = SyncState.Idle }
        }
    }

    val blocklist: StateFlow<List<String>> = repository.getBlocklist()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun blockNumber(phoneNumber: String) { viewModelScope.launch { repository.blockNumber(phoneNumber) } }
    fun unblockNumber(phoneNumber: String) { viewModelScope.launch { repository.unblockNumber(phoneNumber) } }
    fun deleteContact(phoneNumber: String) { viewModelScope.launch { contactEnrichmentService.deleteContact(phoneNumber) } }
    suspend fun saveContact(phoneNumber: String, name: String, photoUrl: String? = null): Boolean {
        return contactEnrichmentService.saveContactFast(phoneNumber, name, photoUrl)
    }

    fun deleteCallLog(number: String, date: Long) { viewModelScope.launch { deviceDataRepository.deleteCallLogEntry(number, date) } }
    fun clearAllCallLogs() { viewModelScope.launch { deviceDataRepository.clearCallLog() } }
    fun deleteSms(id: Long) { viewModelScope.launch { deviceDataRepository.deleteSms(id) } }

    val localContacts: StateFlow<List<LocalContactEntity>> = database.localContactDao().getAllContacts()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val enrichedContacts: StateFlow<List<com.infocaller.app.data.local.model.EnrichedContact>> = database.localContactDao().getAllEnrichedContacts()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun triggerThrottledSync(context: Context) {
        val workRequest = OneTimeWorkRequestBuilder<com.infocaller.app.worker.EnrichmentWorker>().build()
        WorkManager.getInstance(context).enqueueUniqueWork("ThrottledSync", androidx.work.ExistingWorkPolicy.KEEP, workRequest)
    }

    fun updateSystemContact(phoneNumber: String, caller: Caller) {
        viewModelScope.launch { contactEnrichmentService.updateExistingContact(phoneNumber, caller) }
    }

    fun setPrimaryPhoto(identifier: String, url: String, provider: String) {
        if (identifier.isBlank() || url.isBlank()) return
        val key = try {
            when {
                com.infocaller.app.util.IdentifierRouter.isEmail(identifier) -> identifier.trim().lowercase()
                com.infocaller.app.util.IdentifierRouter.routeType(identifier) ==
                    com.infocaller.app.domain.engine.IdentifierType.USERNAME ->
                    identifier.trim().lowercase().removePrefix("@")
                else -> PhoneNumberUtils.normalize(identifier)
            }
        } catch (_: Exception) { return }
        viewModelScope.launch {
            try {
                database.enrichmentDao().setPrimaryPhoto(key, url, provider)
            } catch (_: Exception) { }
            try {
                contactEnrichmentService.forcePhonebookPhoto(key, url)
            } catch (_: Exception) { }
        }
    }

    fun mapToCaller(entity: com.infocaller.app.data.local.entity.ContactEnrichmentEntity, phoneNumber: String): Caller {
        return Caller(
            phoneNumber = phoneNumber,
            displayName = entity.publicName,
            alias = entity.alternateName,
            photoUrl = entity.profileImageUrl,
            organization = entity.carrier,
            country = entity.country,
            region = entity.region,
            carrier = entity.carrier,
            email = entity.email,
            reportCount = 0,
            isVerified = false,
            socialMediaLinks = com.infocaller.app.util.SocialUtils.fromJson(entity.socialProfilesJson).mapNotNull { it.profileUrl }
        )
    }

    class Factory(
        private val repository: CallerRepository,
        private val deviceDataRepository: DeviceDataRepository,
        private val contactEnrichmentService: ContactEnrichmentService,
        private val database: AppDatabase,
        private val lookupEngine: com.infocaller.app.domain.engine.IPublicLookupEngine
    ) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(CallerViewModel::class.java)) {
                @Suppress("UNCHECKED_CAST")
                return CallerViewModel(repository, deviceDataRepository, contactEnrichmentService, database, lookupEngine) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class")
        }
    }

    fun getEnrichment(number: String): Flow<com.infocaller.app.data.local.entity.ContactEnrichmentEntity?> {
        val key = when {
            com.infocaller.app.util.IdentifierRouter.isEmail(number) -> number.trim().lowercase()
            com.infocaller.app.util.IdentifierRouter.routeType(number) == com.infocaller.app.domain.engine.IdentifierType.USERNAME -> number.trim().lowercase().removePrefix("@")
            else -> PhoneNumberUtils.normalize(number)
        }
        return database.enrichmentDao().getEnrichment(key)
    }

    fun getEnrichments(numbers: List<String>): Flow<List<com.infocaller.app.data.local.entity.ContactEnrichmentEntity>> {
        val normalized = numbers.map {
            when {
                com.infocaller.app.util.IdentifierRouter.isEmail(it) -> it.trim().lowercase()
                com.infocaller.app.util.IdentifierRouter.routeType(it) == com.infocaller.app.domain.engine.IdentifierType.USERNAME -> it.trim().lowercase().removePrefix("@")
                else -> PhoneNumberUtils.normalize(it)
            }
        }.filter { it.isNotBlank() }.distinct()
        if (normalized.isEmpty()) return kotlinx.coroutines.flow.flowOf(emptyList())
        return database.enrichmentDao().getEnrichments(normalized)
    }
}

sealed class SearchUiState {
    object Idle : SearchUiState()
    object Loading : SearchUiState()
    data class Success(
        val caller: Caller,
        val isLive: Boolean = false,
        val lastProvider: String? = null,

        val livePartial: LookupResult? = null,
    ) : SearchUiState()
    object NotFound : SearchUiState()
    data class Error(val message: String) : SearchUiState()
}

data class ScanStepUi(
    val providerId: String,
    val providerName: String,
    val stepIndex: Int,
    val stepTotal: Int,
    val status: String,
)

sealed class SyncState {
    object Idle : SyncState()
    data class Syncing(val progress: Float) : SyncState()
    object Completed : SyncState()
}
