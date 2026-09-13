package com.infocaller.app.domain.engine

import android.util.Log
import com.infocaller.app.data.local.dao.ScanJobDao
import com.infocaller.app.data.local.entity.ScanJobStateEntity
import com.infocaller.app.domain.model.LookupResult
import com.infocaller.app.util.PhoneNumberUtils
import com.infocaller.app.util.ContactUtils
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
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
    data class ProviderStep(
        val phoneNumber: String,
        val providerId: String,
        val providerName: String,
        val stepIndex: Int,
        val stepTotal: Int,
        val status: StepStatus,
    ) : ScanState()
    data class Completed(val phoneNumber: String, val result: LookupResult) : ScanState()
    data class Error(val phoneNumber: String, val message: String) : ScanState()
}

enum class StepStatus {
    RUNNING,
    SUCCESS,
    FAILED,
    SKIPPED,
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
        val rawId = try { identifier.trim().take(120) } catch (_: Exception) { "" } catch (_: Error) { "" }
        if (rawId.isBlank()) {
            return flowOf(ScanState.Error(identifier.take(40), "Empty number"))
        }
        val normalized = try {
            if (type == IdentifierType.PHONE) PhoneNumberUtils.normalize(rawId) else rawId
        } catch (_: Exception) { rawId } catch (_: Error) { rawId }
        if (normalized.isBlank()) {
            return flowOf(ScanState.Error(rawId.take(40), "Invalid number"))
        }
        // Phone sanity: avoid scanning garbage that fans out 16 providers for nothing.
        if (type == IdentifierType.PHONE) {
            try {
                val digits = normalized.filter { it.isDigit() }
                if (digits.length < 7 || digits.length > 15) {
                    return flowOf(ScanState.Error(normalized.take(40), "Invalid number"))
                }
            } catch (_: Exception) { } catch (_: Error) { }
        }
        val scanKey = "$type:$normalized"

        if (priority == ScanPriority.CRITICAL || priority == ScanPriority.FOREGROUND) {
            val stale = activeScans.entries.filter { (k, v) ->
                k != scanKey && (v.priority == ScanPriority.CRITICAL || v.priority == ScanPriority.FOREGROUND) && v.job.isActive
            }
            stale.forEach { (k, v) ->
                try { v.job.cancel("Superseded by newer search") } catch (_: Exception) { }
                activeScans.remove(k)
            }
        }

        val placeholder = ScanJobInfo(Job(), priority)
        val raced = activeScans.putIfAbsent(scanKey, placeholder)
        if (raced?.job?.isActive == true) {
            return scanStates.map { it[scanKey] ?: ScanState.Idle }
                .filter { it !is ScanState.Idle }
        }

        if (priority == ScanPriority.CRITICAL || priority == ScanPriority.FOREGROUND) {
            _isPriorityScanActive.value = true
            cancelBackgroundScans()
        }

        val scanChannel = Channel<ScanState>(Channel.UNLIMITED)
        try { scanChannel.trySend(ScanState.Started(normalized)) } catch (_: Exception) { } catch (_: Error) { }

        val job = scope.launch {
            var finished: ScanState? = null
            try {
                updateGlobalState(scanKey, ScanState.Started(normalized))

                var currentResult = LookupResult(phoneNumber = normalized)

                fun parseProviders(s: String?): MutableSet<String> {
                    if (s.isNullOrBlank()) return mutableSetOf()
                    return try {
                        if (s.trim().startsWith("[")) {
                            val arr = try { com.google.gson.Gson().fromJson(s, Array<String>::class.java) } catch (_: Exception) { null } catch (_: Error) { null }
                            arr?.mapNotNull { try { it.trim().take(64).takeIf { t -> t.isNotBlank() } } catch (_: Exception) { null } catch (_: Error) { null } }?.take(64)?.toMutableSet() ?: mutableSetOf()
                        } else s.split(",").mapNotNull { try { it.trim().take(64).takeIf { t -> t.isNotBlank() } } catch (_: Exception) { null } catch (_: Error) { null } }.take(64).toMutableSet()
                    } catch (_: Exception) { mutableSetOf() } catch (_: Error) { mutableSetOf() }
                }
                fun parseCaps(s: String?): MutableSet<Capability> {
                    if (s.isNullOrBlank()) return mutableSetOf()
                    val names: List<String> = try {
                        if (s.trim().startsWith("[")) {
                            try { com.google.gson.Gson().fromJson(s, Array<String>::class.java)?.toList() ?: emptyList() } catch (_: Exception) { emptyList() } catch (_: Error) { emptyList() }
                        } else s.split(",").map { try { it.trim() } catch (_: Exception) { "" } catch (_: Error) { "" } }
                    } catch (_: Exception) { emptyList() } catch (_: Error) { emptyList() }
                    return try {
                        names.take(32).mapNotNull { try { Capability.valueOf(it) } catch (_: Exception) { null } catch (_: Error) { null } }.toMutableSet()
                    } catch (_: Exception) { mutableSetOf() } catch (_: Error) { mutableSetOf() }
                }
                val savedState = try {
                    if (type == IdentifierType.PHONE) scanJobDao.getState(normalized) else null
                } catch (_: Exception) { null } catch (_: Error) { null }
                val completedProviders = parseProviders(savedState?.completedProviders)
                val satisfiedCaps = parseCaps(savedState?.satisfiedCapabilities)

                fun currentResultSnapshot(): LookupResult = try { currentResult.copy() } catch (_: Exception) { currentResult } catch (_: Error) { currentResult }
                // Watchdog: never let a manual scan hang the UI forever.
                val watchdog = launch {
                    try {
                        delay(SCAN_TIMEOUT_MS)
                        try { scanChannel.trySend(ScanState.Completed(normalized, currentResultSnapshot())) } catch (_: Exception) { } catch (_: Error) { }
                    } catch (_: Exception) { } catch (_: Error) { }
                }

                try {
                lookupEngine.performLookup(
                    normalized,
                    type = type,
                    alreadyCompletedProviders = completedProviders,
                    requiredCapabilities = try { Capability.entries.toSet() - satisfiedCaps } catch (_: Exception) { emptySet() } catch (_: Error) { emptySet() },
                    onPartialResult = { partial ->
                    val safePartial = try {
                        // Clamp unbounded provider payloads before merge (OOM-safe).
                        // Logo-safe: drop official logos before they ever reach the merger/DB.
                        val rawCandidates = try { partial.photoCandidates.take(6) } catch (_: Exception) { emptyList() } catch (_: Error) { emptyList() }
                        val cleanCandidates = try {
                            rawCandidates.filter { c ->
                                try { com.infocaller.app.util.PhotoPolicy.isUsablePhotoUrl(c.url) } catch (_: Exception) { false } catch (_: Error) { false }
                            }
                        } catch (_: Exception) { emptyList() } catch (_: Error) { emptyList() }
                        val cappedSocials = try { partial.socialProfiles.take(12) } catch (_: Exception) { emptyList() } catch (_: Error) { emptyList() }
                        val cleanSocials = try {
                            cappedSocials.map { s ->
                                try {
                                    val av = s.avatarUrl
                                    if (av != null && !com.infocaller.app.util.PhotoPolicy.isUsablePhotoUrl(av)) s.copy(avatarUrl = null) else s
                                } catch (_: Exception) { s } catch (_: Error) { s }
                            }
                        } catch (_: Exception) { cappedSocials } catch (_: Error) { cappedSocials }
                        val rawImage = try { partial.imageUrl?.trim()?.take(2000)?.takeIf { it.startsWith("http") } } catch (_: Exception) { null } catch (_: Error) { null }
                        val cleanImage = try { if (com.infocaller.app.util.PhotoPolicy.isUsablePhotoUrl(rawImage)) rawImage else null } catch (_: Exception) { null } catch (_: Error) { null }
                        partial.copy(
                            name = try { partial.name?.trim()?.take(80)?.takeIf { it.isNotBlank() } } catch (_: Exception) { null } catch (_: Error) { null },
                            alternateName = try { partial.alternateName?.trim()?.take(80) } catch (_: Exception) { null } catch (_: Error) { null },
                            imageUrl = cleanImage,
                            about = try { partial.about?.take(500) } catch (_: Exception) { null } catch (_: Error) { null },
                            photoCandidates = cleanCandidates,
                            socialProfiles = cleanSocials,
                        )
                    } catch (_: Exception) { partial } catch (_: Error) { partial }
                    val partial = safePartial
                    try { partial.providerId?.let { id -> if (id.length <= 64) completedProviders.add(id) } } catch (_: Exception) { } catch (_: Error) { }

                    val photoPool = try {
                        // Logo-safe pool: official logos never enter the bitmap decoder.
                        val cleanCands = try {
                            partial.photoCandidates.filter { c ->
                                try { com.infocaller.app.util.PhotoPolicy.isUsablePhotoUrl(c.url) } catch (_: Exception) { false } catch (_: Error) { false }
                            }
                        } catch (_: Exception) { emptyList() } catch (_: Error) { emptyList() }
                        val cleanImage = try { if (com.infocaller.app.util.PhotoPolicy.isUsablePhotoUrl(partial.imageUrl)) partial.imageUrl else null } catch (_: Exception) { null } catch (_: Error) { null }
                        when {
                            cleanCands.isNotEmpty() -> cleanCands.take(2)
                            !cleanImage.isNullOrBlank() && cleanImage.startsWith("http") -> listOf(
                                com.infocaller.app.domain.model.PhotoCandidate(
                                    provider = (try { partial.source ?: partial.providerId } catch (_: Exception) { null } catch (_: Error) { null }) ?: "photo",
                                    url = cleanImage
                                )
                            )
                            else -> emptyList()
                        }
                    } catch (_: Exception) { emptyList() } catch (_: Error) { emptyList() }
                    val analyzedPartials = if (photoPool.isNotEmpty()) {
                        val analyzed = try {
                            photoPool.take(1).mapNotNull { candidate ->
                                try {
                                    withTimeoutOrNull(3000) {
                                        ensureActive()
                                        try { imageAnalysisService.analyze(candidate) } catch (_: Exception) { null } catch (_: Error) { null }
                                    }
                                } catch (_: Exception) { null } catch (_: Error) { null }
                            }
                        } catch (_: Exception) { emptyList() } catch (_: Error) { emptyList() }
                        try {
                            if (analyzed.isEmpty()) {
                                // Keep name result even if photo failed; never crash on empty pool.
                                if (partial.photoCandidates.isNotEmpty()) partial
                                else {
                                    val firstUrl = try { photoPool.firstOrNull()?.url?.takeIf { it.startsWith("http") } } catch (_: Exception) { null } catch (_: Error) { null }
                                    partial.copy(photoCandidates = photoPool, imageUrl = partial.imageUrl ?: firstUrl)
                                }
                            } else {
                                val faceClear = analyzed.filter { c ->
                                    try { c.faceCount > 0 && c.faceConfidence >= 0.7f && c.faceCoverage >= 0.02f && c.imageQuality >= 0.01f && c.width >= 80 && c.height >= 80 } catch (_: Exception) { false } catch (_: Error) { false }
                                }
                                if (faceClear.isEmpty()) {
                                    partial.copy(photoCandidates = emptyList(), imageUrl = null)
                                } else {
                                    val bestFirst = try { faceClear.sortedByDescending { it.faceCoverage * (0.5f + it.imageQuality) } } catch (_: Exception) { faceClear } catch (_: Error) { faceClear }
                                    val bestUrl = try { bestFirst.firstOrNull()?.url } catch (_: Exception) { null } catch (_: Error) { null }
                                    partial.copy(photoCandidates = bestFirst, imageUrl = bestUrl)
                                }
                            }
                        } catch (_: Exception) { partial } catch (_: Error) { partial }
                    } else {
                        partial
                    }

                    try {
                        currentResult = IntelligenceResultMerger.merge(currentResult, analyzedPartials)
                    } catch (_: Exception) { } catch (_: Error) { }

                    try { resultSaver?.invoke(currentResult) } catch (_: Exception) { } catch (_: Error) { }

                    try { updateLocalSatisfiedCaps(currentResult, satisfiedCaps) } catch (_: Exception) { } catch (_: Error) { }

                    try {
                        val gson = com.google.gson.Gson()
                        scanJobDao.insertState(ScanJobStateEntity(
                            phoneNumber = normalized,
                            completedProviders = gson.toJson(completedProviders.toList().take(64)),
                            satisfiedCapabilities = gson.toJson(satisfiedCaps.map { it.name }.take(32))
                        ))
                    } catch (_: Exception) { } catch (_: Error) { }

                    val progress = try { ScanState.Progress(normalized, currentResultSnapshot(), (try { partial.providerId } catch (_: Exception) { null } catch (_: Error) { null }) ?: "unknown") } catch (_: Exception) { null } catch (_: Error) { null }
                    if (progress != null) {
                        try { scanChannel.trySend(progress) } catch (_: Exception) { } catch (_: Error) { }
                        try { updateGlobalState(scanKey, progress) } catch (_: Exception) { } catch (_: Error) { }
                    }
                    },
                    onProviderStep = { providerId, providerName, stepIndex, stepTotal, status ->
                        try {
                            val pid = providerId.take(64)
                            val pname = providerName.take(64).ifBlank { pid }
                            val step = ScanState.ProviderStep(
                                normalized, pid, pname, stepIndex.coerceIn(0, 999), stepTotal.coerceIn(1, 999), status
                            )
                            try { scanChannel.trySend(step) } catch (_: Exception) { } catch (_: Error) { }
                            updateGlobalState(scanKey, step)
                        } catch (_: Exception) { } catch (_: Error) { }
                    }
                )
                } catch (e: CancellationException) { throw e }
                catch (_: Error) {
                    // OOM inside provider fan-out: still return whatever we gathered.
                } catch (_: Exception) { }

                try { watchdog.cancel() } catch (_: Exception) { } catch (_: Error) { }

                try { scanJobDao.deleteState(normalized) } catch (_: Exception) { } catch (_: Error) { }

                val finalState = ScanState.Completed(normalized, currentResultSnapshot())
                finished = finalState
                try { scanChannel.trySend(finalState) } catch (_: Exception) { } catch (_: Error) { }
                try { updateGlobalState(scanKey, finalState) } catch (_: Exception) { } catch (_: Error) { }
            } catch (e: CancellationException) {
                if (e is CancellationException) {
                    if (priority == ScanPriority.BACKGROUND) {
                        try { updateGlobalState(scanKey, ScanState.Idle) } catch (_: Exception) { } catch (_: Error) { }
                    }
                    throw e
                }
                val errorState = ScanState.Error(normalized, e.message ?: "Unknown error")
                try { scanChannel.trySend(errorState) } catch (_: Exception) { } catch (_: Error) { }
                try { updateGlobalState(scanKey, errorState) } catch (_: Exception) { } catch (_: Error) { }
            } catch (_: Error) {
                // Never crash the app from a scan: surface current partials as Completed.
                try {
                    val fallback = ScanState.Completed(normalized, LookupResult(phoneNumber = normalized))
                    finished = fallback
                    try { scanChannel.trySend(fallback) } catch (_: Exception) { } catch (_: Error) { }
                    try { updateGlobalState(scanKey, fallback) } catch (_: Exception) { } catch (_: Error) { }
                } catch (_: Exception) { } catch (_: Error) { }
            } catch (e: Exception) {
                val msg = try { (e.message ?: "Unknown error").take(300) } catch (_: Exception) { "Unknown error" } catch (_: Error) { "Unknown error" }
                val errorState = ScanState.Error(normalized, msg)
                try { scanChannel.trySend(errorState) } catch (_: Exception) { } catch (_: Error) { }
                try { updateGlobalState(scanKey, errorState) } catch (_: Exception) { } catch (_: Error) { }
            } finally {
                try { scanChannel.close() } catch (_: Exception) { } catch (_: Error) { }
                val thisJob = try { coroutineContext[Job] } catch (_: Exception) { null } catch (_: Error) { null }
                try {
                    val current = activeScans[scanKey]
                    if (current?.job === thisJob) activeScans.remove(scanKey)
                } catch (_: Exception) { } catch (_: Error) { }
                if (priority == ScanPriority.CRITICAL || priority == ScanPriority.FOREGROUND) {
                    try {
                        val stillHasPriority = activeScans.values.any {
                            try { (it.priority == ScanPriority.CRITICAL || it.priority == ScanPriority.FOREGROUND) && it.job.isActive } catch (_: Exception) { false } catch (_: Error) { false }
                        }
                        if (!stillHasPriority) {
                            _isPriorityScanActive.value = false
                            resumeBackgroundScans()
                        }
                    } catch (_: Exception) { } catch (_: Error) { }
                }
            }
        }

        activeScans[scanKey] = ScanJobInfo(job, priority)
        return scanChannel.receiveAsFlow()
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
        private const val SCAN_TIMEOUT_MS = 45_000L
    }

    override fun getScanState(identifier: String): ScanState {
        val normalized = try { PhoneNumberUtils.normalize(identifier) } catch (_: Exception) { identifier }
        return _scanStates.value["PHONE:$normalized"]
            ?: _scanStates.value["EMAIL:$normalized"]
            ?: _scanStates.value["USERNAME:$normalized"]
            ?: _scanStates.value[normalized]
            ?: _scanStates.value[identifier]
            ?: ScanState.Idle
    }

    override fun cancelScan(identifier: String) {
        val normalized = try { PhoneNumberUtils.normalize(identifier) } catch (_: Exception) { identifier }
        val keys = (activeScans.keys.filter {
            it == normalized || it == identifier || it.endsWith(":$normalized") || it.endsWith(":$identifier")
        }).toList()
        keys.forEach { k ->
            try { activeScans[k]?.job?.cancel() } catch (_: Exception) { }
            activeScans.remove(k)
        }
        if (keys.isEmpty()) {
            activeScans[normalized]?.job?.cancel()
            activeScans.remove(normalized)
        }
    }

    override fun cancelAllScans() {
        val keys = activeScans.keys.toList()
        keys.forEach { k ->
            try { activeScans[k]?.job?.cancel("cancelAllScans") } catch (_: Exception) { }
            activeScans.remove(k)
        }
        try {
            _isPriorityScanActive.value = activeScans.values.any {
                (it.priority == ScanPriority.CRITICAL || it.priority == ScanPriority.FOREGROUND) && it.job.isActive
            }
        } catch (_: Exception) { }
    }
}
