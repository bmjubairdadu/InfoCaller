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
            try { list.take(300).map { it.copy(number = PhoneNumberUtils.normalize(it.number)) } } catch (_: Exception) { emptyList() } catch (_: Error) { emptyList() }
        }
        .catch { emit(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val contacts: StateFlow<List<Contact>> = _deviceDataTick.flatMapLatest {
        deviceDataRepository.getContacts()
    }
        .map { list ->
            try { list.take(5000).map { it.copy(phoneNumber = PhoneNumberUtils.normalize(it.phoneNumber ?: "")) } } catch (_: Exception) { emptyList() } catch (_: Error) { emptyList() }
        }
        .catch { emit(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Dialer filter: debounced + capped, never blocks the UI thread on huge contact lists.
    val filteredContacts: StateFlow<List<Contact>> = combine(_dialerInput.debounce(120L), contacts) { input, list ->
        try {
            val q = input.trim().take(20)
            if (q.isEmpty()) emptyList()
            else if (q.length < 2) emptyList()
            else list.asSequence().filter {
                try { (it.phoneNumber?.contains(q) == true) || T9Search.matches(q, it.displayName) } catch (_: Exception) { false } catch (_: Error) { false }
            }.take(25).toList()
        } catch (_: Exception) { emptyList() } catch (_: Error) { emptyList() }
    }.catch { emit(emptyList()) }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

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
        // Cap length: unbounded paste otherwise thrashes filter + scan on every keystroke.
        _dialerInput.value = try { input.take(20) } catch (_: Exception) { "" } catch (_: Error) { "" }
    }

    fun searchNumber(phoneNumber: String) {
        val raw = try { phoneNumber.trim().take(120) } catch (_: Exception) { "" } catch (_: Error) { "" }
        if (raw.isBlank()) return
        // Never scan USSD/MMI codes or garbage: instant no-op keeps dialer smooth.
        if (raw.contains("*") || raw.contains("#")) {
            try {
                activeSearchJob?.cancel()
                _scanActive.value = false
                _searchResult.value = SearchUiState.Idle
            } catch (_: Exception) { } catch (_: Error) { }
            return
        }
        val routeType = try { com.infocaller.app.util.IdentifierRouter.routeType(raw) } catch (_: Exception) { com.infocaller.app.domain.engine.IdentifierType.PHONE } catch (_: Error) { com.infocaller.app.domain.engine.IdentifierType.PHONE }
        when (routeType) {
            com.infocaller.app.domain.engine.IdentifierType.EMAIL -> {
                searchEmailManual(raw)
                return
            }
            com.infocaller.app.domain.engine.IdentifierType.USERNAME -> {
                // Bare short handles typed in dialer are usually not usernames (e.g. "8801").
                // Only treat as username if it looks like a real handle; else phone path.
                val handle = raw.removePrefix("@")
                val looksLikeHandle = handle.length >= 3 && handle.any { it.isLetter() } &&
                    handle.all { it.isLetterOrDigit() || it == '.' || it == '_' || it == '-' }
                if (looksLikeHandle) {
                    searchUsernameManual(raw)
                    return
                }
            }
            else -> {}
        }
        val normalized = try { PhoneNumberUtils.normalize(raw) } catch (_: Exception) { raw } catch (_: Error) { raw }
        // Phone sanity: digits 7..15, else instant idle (no provider fan-out, no crash).
        try {
            val digits = normalized.filter { it.isDigit() }
            if (digits.length < 7 || digits.length > 15) {
                try {
                    activeSearchJob?.cancel()
                    _scanActive.value = false
                    // Keep UX calm: bad input = idle, not a red error.
                    if (_searchResult.value !is SearchUiState.Success) _searchResult.value = SearchUiState.Idle
                } catch (_: Exception) { } catch (_: Error) { }
                return
            }
        } catch (_: Exception) { } catch (_: Error) { }
        searchByIdentifier(normalized, com.infocaller.app.domain.engine.IdentifierType.PHONE)
    }

    fun searchNumberManual(phoneNumber: String) {
        searchNumber(phoneNumber)
    }

    // NID / NID|DOB / DOB manual search. Dead no-op before — now routes to the
    // on-device NID database provider via the normal scan pipeline.
    fun searchNidManual(identifier: String) {
        val cleaned = try { identifier.trim().take(60) } catch (_: Exception) { return } catch (_: Error) { return }
        if (cleaned.isBlank()) return
        val nidType = com.infocaller.app.domain.engine.IdentifierType.NID
        val dobType = com.infocaller.app.domain.engine.IdentifierType.DOB
        when {
            cleaned.contains("|") -> {
                val parts = cleaned.split("|").map { it.trim() }.filter { it.isNotBlank() }
                if (parts.size != 2) return
                if (!parts[0].all { it.isDigit() } || parts[0].length !in 10..17) return
                searchByIdentifier(parts[0] + "|" + parts[1].take(12), nidType)
            }
            cleaned.all { it.isDigit() } && cleaned.length in 10..17 ->
                searchByIdentifier(cleaned, nidType)
            Regex("^\\d{4}-\\d{2}-\\d{2}$").matches(cleaned) ||
                Regex("^\\d{2}/\\d{2}/\\d{4}$").matches(cleaned) ->
                searchByIdentifier(cleaned, dobType)
            else -> return
        }
    }

    fun searchEmailManual(email: String) {
        val cleaned = try { email.trim().lowercase().take(120) } catch (_: Exception) { "" } catch (_: Error) { "" }
        if (cleaned.isBlank() || cleaned.length > 120) return
        val ok = try { com.infocaller.app.util.IdentifierRouter.isEmail(cleaned) } catch (_: Exception) { false } catch (_: Error) { false }
        if (!ok) return
        searchByIdentifier(cleaned, com.infocaller.app.domain.engine.IdentifierType.EMAIL)
    }

    fun searchUsernameManual(username: String) {
        val cleaned = try { username.trim().lowercase().removePrefix("@").take(40) } catch (_: Exception) { "" } catch (_: Error) { "" }
        if (cleaned.length !in 2..40 || cleaned.contains(" ") || cleaned.contains("@")) return
        if (cleaned.all { it.isDigit() }) return
        searchByIdentifier(cleaned, com.infocaller.app.domain.engine.IdentifierType.USERNAME)
    }

    fun performFullLookup(phoneNumber: String) {
        val raw = try { phoneNumber.trim().take(120) } catch (_: Exception) { return } catch (_: Error) { return }
        if (raw.isBlank() || raw.contains("*") || raw.contains("#")) return
        val normalized = try { PhoneNumberUtils.normalize(raw) } catch (_: Exception) { return } catch (_: Error) { return }
        try {
            if (normalized.filter { it.isDigit() }.length !in 7..15) return
        } catch (_: Exception) { return } catch (_: Error) { return }
        viewModelScope.launch {
            try {
                // Timeout guard: never hang the sheet forever.
                kotlinx.coroutines.withTimeoutOrNull(45_000L) {
                    repository.startScan(normalized, com.infocaller.app.domain.engine.ScanPriority.CRITICAL)
                        .collect { state ->
                            if (state is com.infocaller.app.domain.engine.ScanState.Progress ||
                                state is com.infocaller.app.domain.engine.ScanState.Completed) {
                                val result = if (state is com.infocaller.app.domain.engine.ScanState.Progress) state.result else (state as com.infocaller.app.domain.engine.ScanState.Completed).result
                                try { _fullLookupResult.value = result } catch (_: Exception) { } catch (_: Error) { }
                            }
                        }
                }
            } catch (_: Exception) { } catch (_: Error) { }
        }
    }

    fun searchByIdentifier(identifier: String, type: String) {
        val safeId = try { identifier.trim().take(120) } catch (_: Exception) { return } catch (_: Error) { return }
        if (safeId.isBlank()) return
        if (type == com.infocaller.app.domain.engine.IdentifierType.PHONE) {
            try {
                if (safeId.contains("*") || safeId.contains("#")) return
                if (safeId.filter { it.isDigit() }.length !in 7..15) return
            } catch (_: Exception) { return } catch (_: Error) { return }
        }
        val generation = ++searchGeneration
        try { activeSearchJob?.cancel() } catch (_: Exception) { } catch (_: Error) { }
        activeSearchJob = viewModelScope.launch {
            if (type == com.infocaller.app.domain.engine.IdentifierType.PHONE) {
                try {
                    val cached = repository.getFreshCachedCaller(safeId)
                    if (cached != null && generation == searchGeneration) {
                        _searchResult.value = SearchUiState.Success(cached, isLive = false)
                        _scanActive.value = false
                        return@launch
                    }
                } catch (_: Exception) { } catch (_: Error) { }
            }
            try { _searchResult.value = SearchUiState.Loading } catch (_: Exception) { } catch (_: Error) { }
            try { _scanSteps.value = emptyList() } catch (_: Exception) { } catch (_: Error) { }
            try { _scanActive.value = true } catch (_: Exception) { } catch (_: Error) { }
            try {
                val scanFlow = try {
                    repository.startScan(safeId, com.infocaller.app.domain.engine.ScanPriority.CRITICAL, type)
                } catch (_: Exception) { null } catch (_: Error) { null }
                if (scanFlow == null) {
                    if (generation != searchGeneration) return@launch
                    try {
                        val cached = repository.searchCaller(safeId)
                        if (cached != null) _searchResult.value = SearchUiState.Success(cached, isLive = false)
                        else _searchResult.value = SearchUiState.NotFound
                    } catch (_: Exception) { try { _searchResult.value = SearchUiState.NotFound } catch (_: Exception) { } catch (_: Error) { } }
                    catch (_: Error) { try { _searchResult.value = SearchUiState.NotFound } catch (_: Exception) { } catch (_: Error) { } }
                    try { _scanActive.value = false } catch (_: Exception) { } catch (_: Error) { }
                    return@launch
                }
                var sawProviderStep = false
                try {
                    kotlinx.coroutines.withTimeoutOrNull(50_000L) {
                        scanFlow.collect { state ->
                            if (generation != searchGeneration) return@collect
                            when (state) {
                                is com.infocaller.app.domain.engine.ScanState.ProviderStep -> {
                                    sawProviderStep = true
                                    try { applyScanStep(state) } catch (_: Exception) { } catch (_: Error) { }
                                }
                                is com.infocaller.app.domain.engine.ScanState.Progress -> {
                                    try {
                                        repository.saveLookupResult(state.result)
                                    } catch (_: Exception) { } catch (_: Error) { }
                                    if (generation != searchGeneration) return@collect
                                    try {
                                        _searchResult.value = SearchUiState.Success(
                                            mapToCaller(state.result),
                                            isLive = true,
                                            lastProvider = try { state.lastProvider.take(64) } catch (_: Exception) { null } catch (_: Error) { null },
                                            livePartial = state.result,
                                        )
                                    } catch (_: Exception) { } catch (_: Error) { }
                                }
                                is com.infocaller.app.domain.engine.ScanState.Completed -> {
                                    try { repository.saveLookupResult(state.result) } catch (_: Exception) { } catch (_: Error) { }
                                    if (generation != searchGeneration) return@collect
                                    try {
                                        // Empty result = NotFound UI (not a crash, not a red error).
                                        val mapped = try { mapToCaller(state.result) } catch (_: Exception) { null } catch (_: Error) { null }
                                        val hasAnything = mapped != null && (!mapped.displayName.isNullOrBlank() || !mapped.photoUrl.isNullOrBlank())
                                        if (hasAnything) _searchResult.value = SearchUiState.Success(mapped!!, isLive = false)
                                        else {
                                            val cached = try { repository.searchCaller(safeId) } catch (_: Exception) { null } catch (_: Error) { null }
                                            if (cached != null) _searchResult.value = SearchUiState.Success(cached, isLive = false)
                                            else _searchResult.value = SearchUiState.NotFound
                                        }
                                    } catch (_: Exception) { } catch (_: Error) { }
                                    try { _scanActive.value = false } catch (_: Exception) { } catch (_: Error) { }
                                    try {
                                        autoSyncToPhonebook(state.result)
                                    } catch (_: Exception) { } catch (_: Error) { }
                                }
                                is com.infocaller.app.domain.engine.ScanState.Error -> {
                                    if (generation != searchGeneration) return@collect
                                    val cached = try {
                                        repository.searchCaller(safeId)
                                    } catch (_: Exception) { null } catch (_: Error) { null }
                                    try {
                                        if (cached != null) {
                                            _searchResult.value = SearchUiState.Success(cached, isLive = false)
                                        } else {
                                            // Soft-fail: show NotFound instead of scary error for manual scans.
                                            _searchResult.value = SearchUiState.NotFound
                                        }
                                    } catch (_: Exception) { } catch (_: Error) { }
                                    try { _scanActive.value = false } catch (_: Exception) { } catch (_: Error) { }
                                }
                                else -> {}
                            }
                        }
                    }
                } catch (_: Exception) { } catch (_: Error) { }
                // Timeout path: if still loading, fall back to cache/NotFound (never hang).
                if (generation == searchGeneration) {
                    val cur = try { _searchResult.value } catch (_: Exception) { null } catch (_: Error) { null }
                    if (cur is SearchUiState.Loading) {
                        try {
                            val cached = try { repository.searchCaller(safeId) } catch (_: Exception) { null } catch (_: Error) { null }
                            if (cached != null) _searchResult.value = SearchUiState.Success(cached, isLive = false)
                            else _searchResult.value = SearchUiState.NotFound
                        } catch (_: Exception) { } catch (_: Error) { }
                        try { _scanActive.value = false } catch (_: Exception) { } catch (_: Error) { }
                    } else {
                        try { _scanActive.value = false } catch (_: Exception) { } catch (_: Error) { }
                    }
                }
            } catch (e: Exception) {
                if (generation != searchGeneration) return@launch
                try {
                    val cached = try { repository.searchCaller(safeId) } catch (_: Exception) { null } catch (_: Error) { null }
                    if (cached != null) {
                        _searchResult.value = SearchUiState.Success(cached, isLive = false)
                    } else {
                        _searchResult.value = SearchUiState.NotFound
                    }
                } catch (_: Exception) {
                    try { _searchResult.value = SearchUiState.NotFound } catch (_: Exception) { } catch (_: Error) { }
                } catch (_: Error) {
                    try { _searchResult.value = SearchUiState.NotFound } catch (_: Exception) { } catch (_: Error) { }
                }
                try { _scanActive.value = false } catch (_: Exception) { } catch (_: Error) { }
            } catch (_: Error) {
                if (generation != searchGeneration) return@launch
                try { _searchResult.value = SearchUiState.NotFound } catch (_: Exception) { } catch (_: Error) { }
                try { _scanActive.value = false } catch (_: Exception) { } catch (_: Error) { }
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

    // User tap = explicit choice: stamped as user:<provider> so mergers and
    // persistence never overwrite it with a Telegram/Twitch/logo photo.
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
        val cleanUrl = try { url.trim().take(2000) } catch (_: Exception) { url } catch (_: Error) { url }
        try { if (!com.infocaller.app.util.PhotoPolicy.isUsablePhotoUrl(cleanUrl)) return } catch (_: Exception) { return } catch (_: Error) { return }
        val userSource = try { com.infocaller.app.util.PhotoPolicy.userSourceOf(provider) } catch (_: Exception) { "user:manual" } catch (_: Error) { "user:manual" }
        viewModelScope.launch {
            try {
                database.enrichmentDao().setPrimaryPhoto(key, cleanUrl, userSource)
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
