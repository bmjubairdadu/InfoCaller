package com.infocaller.app.domain.engine

import android.util.Log
import com.infocaller.app.data.local.dao.ScanJobDao
import com.infocaller.app.data.local.entity.ScanJobStateEntity
import com.infocaller.app.domain.model.LookupResult
import com.infocaller.app.util.PhoneNumberUtils
import com.infocaller.app.util.ContactUtils
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.concurrent.ConcurrentHashMap

enum class ScanPriority {
    BACKGROUND,
    FOREGROUND, // manual list click
    CRITICAL    // dialer / search box
}

sealed class ScanState {
    object Idle : ScanState()
    data class Started(val phoneNumber: String) : ScanState()
    data class Progress(val phoneNumber: String, val result: LookupResult, val lastProvider: String) : ScanState()
    data class Completed(val phoneNumber: String, val result: LookupResult) : ScanState()
    data class Error(val phoneNumber: String, val message: String) : ScanState()
}

/**
 * Centralized Orchestrator for all scanning operations.
 * Handles priority, cancellation, and persistent provider-level resumption.
 */
class ScanOrchestrator(
    private val lookupEngine: IPublicLookupEngine,
    private val imageAnalysisService: IImageAnalysisService,
    private val scanJobDao: ScanJobDao,
    private var resultSaver: (suspend (LookupResult) -> Unit)? = null,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
) : IScanOrchestrator {

    fun setResultSaver(saver: suspend (LookupResult) -> Unit) {
        this.resultSaver = saver
    }
    private val activeScans = ConcurrentHashMap<String, ScanJobInfo>()
    private val _scanStates = MutableStateFlow<Map<String, ScanState>>(emptyMap())
    override val scanStates = _scanStates.asStateFlow()

    private val _isPriorityScanActive = MutableStateFlow(false)
    val isPriorityScanActive = _isPriorityScanActive.asStateFlow()

    private val pausedBackgroundScans = mutableSetOf<String>()
    
    data class ScanJobInfo(val job: Job, val priority: ScanPriority)

    /**
     * Starts a scan for a given identifier with a specific priority and type.
     */
    override fun startScan(identifier: String, priority: ScanPriority, type: String): Flow<ScanState> {
        val normalized = if (type == IdentifierType.PHONE) PhoneNumberUtils.normalize(identifier) else identifier
        
        val existing = activeScans[normalized]
        if (existing?.job?.isActive == true) {
            return scanStates.map { it[normalized] ?: ScanState.Idle }
                .filter { it !is ScanState.Idle }
        }

        if (priority == ScanPriority.CRITICAL || priority == ScanPriority.FOREGROUND) {
            _isPriorityScanActive.value = true
            cancelBackgroundScans()
        }

        val scanFlow = MutableStateFlow<ScanState>(ScanState.Started(normalized))
        
        val job = scope.launch {
            try {
                updateGlobalState(normalized, ScanState.Started(normalized))
                
                var currentResult = LookupResult(phoneNumber = normalized)
                
                // 1. Load persistent state (supports both JSON array and legacy CSV)
                fun parseProviders(s: String?): MutableSet<String> {
                    if (s.isNullOrBlank()) return mutableSetOf()
                    return try {
                        if (s.trim().startsWith("[")) com.google.gson.Gson().fromJson(s, Array<String>::class.java).toMutableSet()
                        else s.split(",").filter { it.isNotBlank() }.map { it.trim() }.toMutableSet()
                    } catch (_: Exception) { s.split(",").filter { it.isNotBlank() }.map { it.trim() }.toMutableSet() }
                }
                fun parseCaps(s: String?): MutableSet<Capability> {
                    if (s.isNullOrBlank()) return mutableSetOf()
                    val names: List<String> = try {
                        if (s.trim().startsWith("[")) com.google.gson.Gson().fromJson(s, Array<String>::class.java).toList()
                        else s.split(",").filter { it.isNotBlank() }.map { it.trim() }
                    } catch (_: Exception) { s.split(",").filter { it.isNotBlank() }.map { it.trim() } }
                    return names.mapNotNull { try { Capability.valueOf(it) } catch (_: Exception) { null } }.toMutableSet()
                }
                val savedState = if (type == IdentifierType.PHONE) scanJobDao.getState(normalized) else null
                val completedProviders = parseProviders(savedState?.completedProviders)
                val satisfiedCaps = parseCaps(savedState?.satisfiedCapabilities)

                // 2. Perform Lookup with independent capability tracking
                lookupEngine.performLookup(
                    normalized, 
                    type = type,
                    alreadyCompletedProviders = completedProviders,
                    requiredCapabilities = Capability.entries.toSet() - satisfiedCaps
                ) { partial ->
                    // Mark provider as completed
                    partial.providerId?.let { id ->
                        completedProviders.add(id)
                    }

                    // Analyze and keep ONLY clearly visible face photos
                    val analyzedPartials = if (partial.photoCandidates.isNotEmpty()) {
                        val analyzed = partial.photoCandidates.map { imageAnalysisService.analyze(it) }
                        // Face-only filter: face detected, confidence & coverage thresholds, min quality
                        val faceClear = analyzed.filter { c ->
                            c.faceCount > 0 && c.faceConfidence >= 0.7f && c.faceCoverage >= 0.02f && c.imageQuality >= 0.01f && c.width >= 80 && c.height >= 80
                        }
                        if (faceClear.isEmpty()) {
                            // No clear face - drop all candidates, keep PartialResult but with empty photos (will not be saved/shown)
                            partial.copy(photoCandidates = emptyList(), imageUrl = null)
                        } else {
                            // Keep only best clear-face, sorted by faceCoverage*quality
                            val bestFirst = faceClear.sortedByDescending { it.faceCoverage * (0.5f + it.imageQuality) }
                            partial.copy(photoCandidates = bestFirst, imageUrl = bestFirst.first().url)
                        }
                    } else {
                        partial
                    }

                    currentResult = IntelligenceResultMerger.merge(currentResult, analyzedPartials)
                    
                    // SAVE IMMEDIATELY to avoid losing data and for better UX
                    resultSaver?.invoke(currentResult)

                    // Update satisfied capabilities locally for next providers in this run
                    updateLocalSatisfiedCaps(currentResult, satisfiedCaps)

                    // Persist state after each provider to support resumption (JSON arrays, safe for commas)
                    val gson = com.google.gson.Gson()
                    scanJobDao.insertState(ScanJobStateEntity(
                        phoneNumber = normalized,
                        completedProviders = gson.toJson(completedProviders.toList()),
                        satisfiedCapabilities = gson.toJson(satisfiedCaps.map { it.name })
                    ))

                    val progress = ScanState.Progress(normalized, currentResult, partial.providerId ?: "unknown")
                    scanFlow.value = progress
                    updateGlobalState(normalized, progress)
                }
                
                // If we finished successfully, clear the state
                scanJobDao.deleteState(normalized)
                
                val finalState = ScanState.Completed(normalized, currentResult)
                scanFlow.value = finalState
                updateGlobalState(normalized, finalState)
            } catch (e: Exception) {
                if (e is CancellationException) {
                    if (priority == ScanPriority.BACKGROUND) {
                        updateGlobalState(normalized, ScanState.Idle)
                    }
                    throw e
                }
                val errorState = ScanState.Error(normalized, e.message ?: "Unknown error")
                scanFlow.value = errorState
                updateGlobalState(normalized, errorState)
            } finally {
                activeScans.remove(normalized)
                if (priority == ScanPriority.CRITICAL || priority == ScanPriority.FOREGROUND) {
                    val stillHasPriority = activeScans.values.any { 
                        (it.priority == ScanPriority.CRITICAL || it.priority == ScanPriority.FOREGROUND) && it.job.isActive 
                    }
                    if (!stillHasPriority) {
                        _isPriorityScanActive.value = false
                        resumeBackgroundScans()
                    }
                }
            }
        }
        
        activeScans[normalized] = ScanJobInfo(job, priority)
        return scanFlow
    }

    private fun updateLocalSatisfiedCaps(res: LookupResult, satisfied: MutableSet<Capability>) {
        if (res.name != null && !ContactUtils.isPlaceholderName(res.name)) {
            satisfied.add(Capability.PUBLIC_SEARCH)
        }
        if (res.imageUrl != null) {
            // Only mark photo satisfied if we have a high-confidence candidate
            if (res.photoCandidates.any { it.faceCount > 0 && it.faceConfidence > 0.8f }) {
                satisfied.add(Capability.PROFILE_PHOTO)
            }
        }
        if (res.email != null) satisfied.add(Capability.EMAIL)
        if (res.city != null) satisfied.add(Capability.CITY)
        if (res.country != null) satisfied.add(Capability.COUNTRY)
        if (res.carrier != null) satisfied.add(Capability.CARRIER)
    }

    private fun cancelBackgroundScans() {
        activeScans.forEach { (number, info) ->
            if (info.priority == ScanPriority.BACKGROUND && info.job.isActive) {
                info.job.cancel("Foreground request priority")
                pausedBackgroundScans.add(number)
            }
        }
    }

    private fun resumeBackgroundScans() {
        val hasActiveForeground = activeScans.values.any { 
            (it.priority == ScanPriority.CRITICAL || it.priority == ScanPriority.FOREGROUND) && it.job.isActive 
        }
        
        if (!hasActiveForeground && pausedBackgroundScans.isNotEmpty()) {
            val toResume = pausedBackgroundScans.toList()
            pausedBackgroundScans.clear()
            toResume.forEach { number ->
                scope.launch {
                    startScan(number, ScanPriority.BACKGROUND).collect()
                }
            }
        }
    }

    private fun updateGlobalState(number: String, state: ScanState) {
        val currentMap = _scanStates.value.toMutableMap()
        currentMap[number] = state
        _scanStates.value = currentMap
    }

    override fun getScanState(identifier: String): ScanState {
        return _scanStates.value[identifier] ?: ScanState.Idle
    }

    override fun cancelScan(identifier: String) {
        activeScans[identifier]?.job?.cancel()
        activeScans.remove(identifier)
    }
}
