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
    FOREGROUND,
    CRITICAL
}

sealed class ScanState {
    object Idle : ScanState()
    data class Started(val phoneNumber: String) : ScanState()
    data class Progress(val phoneNumber: String, val result: LookupResult, val lastProvider: String) : ScanState()
    data class Completed(val phoneNumber: String, val result: LookupResult) : ScanState()
    data class Error(val phoneNumber: String, val message: String) : ScanState()
}


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

    private val pausedBackgroundScans =
        java.util.Collections.synchronizedSet(mutableSetOf<String>())
    
    data class ScanJobInfo(val job: Job, val priority: ScanPriority)

    
    override fun startScan(identifier: String, priority: ScanPriority, type: String): Flow<ScanState> {
        val normalized = if (type == IdentifierType.PHONE) PhoneNumberUtils.normalize(identifier) else identifier

        // Atomic check-and-reserve to prevent duplicate concurrent scans.
        val placeholder = ScanJobInfo(Job(), priority)
        val raced = activeScans.putIfAbsent(normalized, placeholder)
        if (raced?.job?.isActive == true) {
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

                lookupEngine.performLookup(
                    normalized, 
                    type = type,
                    alreadyCompletedProviders = completedProviders,
                    requiredCapabilities = Capability.entries.toSet() - satisfiedCaps
                ) { partial ->
                    partial.providerId?.let { id ->
                        completedProviders.add(id)
                    }

                    val analyzedPartials = if (partial.photoCandidates.isNotEmpty()) {
                        // Cap + timeout photo analysis: slow ML must never stall the whole scan.
                        val analyzed = partial.photoCandidates.take(4).mapNotNull { candidate ->
                            withTimeoutOrNull(8000) {
                                ensureActive()
                                imageAnalysisService.analyze(candidate)
                            }
                        }
                        val faceClear = analyzed.filter { c ->
                            c.faceCount > 0 && c.faceConfidence >= 0.7f && c.faceCoverage >= 0.02f && c.imageQuality >= 0.01f && c.width >= 80 && c.height >= 80
                        }
                        if (faceClear.isEmpty()) {
                            partial.copy(photoCandidates = emptyList(), imageUrl = null)
                        } else {
                            val bestFirst = faceClear.sortedByDescending { it.faceCoverage * (0.5f + it.imageQuality) }
                            partial.copy(photoCandidates = bestFirst, imageUrl = bestFirst.first().url)
                        }
                    } else {
                        partial
                    }

                    currentResult = IntelligenceResultMerger.merge(currentResult, analyzedPartials)
                    
                    resultSaver?.invoke(currentResult)

                    updateLocalSatisfiedCaps(currentResult, satisfiedCaps)

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
                // Remove only if our own job entry is still present (compare by Job instance
                // since ScanJobInfo is a data class whose placeholder Job() never equals ours).
                val thisJob = coroutineContext[Job]
                val current = activeScans[normalized]
                if (current?.job === thisJob) activeScans.remove(normalized)
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

        // Replace the placeholder reservation with the real job.
        activeScans[normalized] = ScanJobInfo(job, priority)
        return scanFlow
    }

    private fun updateLocalSatisfiedCaps(res: LookupResult, satisfied: MutableSet<Capability>) {
        if (res.name != null && !ContactUtils.isPlaceholderName(res.name)) {
            satisfied.add(Capability.PUBLIC_SEARCH)
        }
        if (res.imageUrl != null) {
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

        if (!hasActiveForeground) {
            // Atomic drain of the synchronized set — no lost or duplicate resumes.
            val toResume: List<String> = synchronized(pausedBackgroundScans) {
                if (pausedBackgroundScans.isEmpty()) return
                val copy = pausedBackgroundScans.toList()
                pausedBackgroundScans.clear()
                copy
            }
            toResume.forEach { number ->
                scope.launch {
                    try { startScan(number, ScanPriority.BACKGROUND).collect() } catch (_: Exception) { }
                }
            }
        }
    }

    private fun updateGlobalState(number: String, state: ScanState) {
        val currentMap = _scanStates.value.toMutableMap()
        currentMap[number] = state
        // Bound map growth: terminal states for old numbers are dropped.
        if (currentMap.size > MAX_TRACKED_STATES) {
            val terminal = currentMap.entries
                .filter { it.value is ScanState.Completed || it.value is ScanState.Error || it.value is ScanState.Idle }
                .map { it.key }
                .take(currentMap.size - MAX_TRACKED_STATES)
            terminal.forEach { currentMap.remove(it) }
        }
        _scanStates.value = currentMap
    }

    companion object {
        private const val MAX_TRACKED_STATES = 100
    }

    override fun getScanState(identifier: String): ScanState {
        val normalized = try { PhoneNumberUtils.normalize(identifier) } catch (_: Exception) { identifier }
        return _scanStates.value[normalized] ?: _scanStates.value[identifier] ?: ScanState.Idle
    }

    override fun cancelScan(identifier: String) {
        val normalized = try { PhoneNumberUtils.normalize(identifier) } catch (_: Exception) { identifier }
        activeScans[normalized]?.job?.cancel()
        activeScans.remove(normalized)
        if (normalized != identifier) {
            activeScans[identifier]?.job?.cancel()
            activeScans.remove(identifier)
        }
    }
}
