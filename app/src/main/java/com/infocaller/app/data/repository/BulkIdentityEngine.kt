package com.infocaller.app.data.repository

import android.content.Context
import android.util.Log
import com.infocaller.app.InfoCallerApplication
import com.infocaller.app.data.remote.EyeconProviderImpl
import com.infocaller.app.data.remote.TruecallerProviderImpl
import com.infocaller.app.domain.engine.ConfidenceEngine
import com.infocaller.app.domain.engine.IdentifierType
import com.infocaller.app.domain.engine.LookupContext
import com.infocaller.app.domain.engine.PartialResult
import com.infocaller.app.domain.model.LookupResult
import com.infocaller.app.permissions.PermissionManager
import com.infocaller.app.util.ContactUtils
import com.infocaller.app.util.EnrichmentGapChecker
import com.infocaller.app.util.PhoneNumberUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Parallel bulk identity engine: scans many contacts at once using the
 * Truecaller bulk endpoint (30 numbers per request) plus Eyecon lookups run
 * concurrently across numbers, then merges, caches and permanently mirrors
 * every hit into the phonebook.
 *
 * Runs 24/7 via [com.infocaller.app.worker.EnrichmentWorker] (hourly,
 * connectivity-gated), on boot ([com.infocaller.app.receiver.BootReceiver]
 * re-arms the worker) and once at app launch from MainActivity. Rows that
 * already have a name + photo are skipped, so repeat passes are cheap.
 */
object BulkIdentityEngine {

    data class Progress(
        val total: Int = 0,
        val done: Int = 0,
        val lastLabel: String? = null,
        val running: Boolean = false,
    )

    private val _progress = MutableStateFlow(Progress())
    val progress = _progress.asStateFlow()

    @Volatile
    private var running = false

    /** Max numbers enriched per pass (rate-limit + battery friendly). */
    private const val MAX_NUMBERS_PER_PASS = 300

    fun isRunning(): Boolean = running

    /**
     * Full pass over call-log numbers + all contacts. Returns how many
     * numbers gained new identity data, or -1 when a pass is already running.
     */
    suspend fun runFullPass(context: Context): Int = withContext(Dispatchers.IO) {
        if (running) return@withContext -1
        val app = context.applicationContext as InfoCallerApplication
        if (!PermissionManager.hasPermissions(app, PermissionManager.CONTACTS_PERMISSIONS)) {
            return@withContext 0
        }
        running = true
        _progress.value = Progress(running = true)
        try {
            val numbers = collectNumbers(app)
            if (numbers.isEmpty()) {
                _progress.value = Progress()
                return@withContext 0
            }
            scanNumbers(app, numbers)
        } finally {
            running = false
            _progress.value = Progress()
        }
    }

    /** Recents + contacts, normalized, de-duplicated, dialable only. */
    private suspend fun collectNumbers(app: InfoCallerApplication): List<String> {
        return try {
            val recents = try {
                app.deviceDataRepository.fetchRecentCallsSync().map { it.number }
            } catch (_: Exception) { emptyList() }
            val contacts = try {
                app.deviceDataRepository.fetchContactsSync().mapNotNull { it.phoneNumber }
            } catch (_: Exception) { emptyList() }
            (recents + contacts)
                .map { PhoneNumberUtils.normalize(it) }
                .filter { n ->
                    n.isNotBlank() && !n.startsWith("*") && !n.startsWith("#") &&
                        n.filter { it.isDigit() }.length >= 7
                }
                .distinct()
        } catch (_: Exception) { emptyList() }
    }

    /**
     * Core fan-out: Truecaller bulk first (1 request per 30 numbers), then
     * Eyecon concurrently for whatever still lacks a name or photo.
     */
    suspend fun scanNumbers(app: Context, numbers: List<String>): Int = supervisorScope {
        val application = app.applicationContext as InfoCallerApplication
        val dao = application.database.enrichmentDao()
        // Chunked cache reads: Room generates one bind var per number and
        // SQLite caps variables (~999), so a 2k-contact list must be read
        // in pages, never one giant IN (...) query.
        val cached = mutableMapOf<String, com.infocaller.app.data.local.entity.ContactEnrichmentEntity>()
        for (page in numbers.chunked(400)) {
            try {
                dao.getEnrichmentsSync(page).forEach { cached[it.normalizedPhoneNumber] = it }
            } catch (_: Exception) { }
        }
        val todo = numbers.filter { n ->
            val e = cached[n] ?: return@filter true
            val gaps = EnrichmentGapChecker.check(e)
            gaps.missingName || gaps.missingPhoto
        }.take(MAX_NUMBERS_PER_PASS)
        if (todo.isEmpty()) return@supervisorScope 0
        _progress.value = Progress(total = todo.size, done = 0, running = true)

        val providers = application.providerManager.getAllProviders()
        val tc = providers.firstOrNull { it.id == "truecaller_authorized" } as? TruecallerProviderImpl
        val eyecon = providers.firstOrNull { it.id == "eyecon_authorized" } as? EyeconProviderImpl

        val partialsByNumber = mutableMapOf<String, MutableList<PartialResult>>()
        fun addPartial(number: String, p: PartialResult) {
            partialsByNumber.getOrPut(number) { mutableListOf() }.add(p)
        }

        // ---- Phase 1: Truecaller bulk, 30 per request, sequential (rate limits).
        if (tc != null) {
            for (chunk in todo.chunked(30)) {
                try {
                    val bulk = withTimeoutOrNull(25_000L) {
                        tc.bulkLookup(chunk, IdentifierType.PHONE, LookupContext())
                    } ?: emptyMap()
                    for ((respKey, partial) in bulk) {
                        val match = matchKey(respKey, chunk) ?: continue
                        addPartial(match, partial)
                    }
                } catch (_: Exception) { }
                try { kotlinx.coroutines.delay(500L) } catch (_: Exception) { }
            }
        }

        // ---- Phase 2: Eyecon in parallel for numbers still missing name/photo.
        if (eyecon != null) {
            val eyeconTargets = todo.filter { n ->
                val have = partialsByNumber[n].orEmpty()
                val hasName = have.any { !it.name.isNullOrBlank() && !ContactUtils.isPlaceholderName(it.name) }
                val hasPhoto = have.any { !it.imageUrl.isNullOrBlank() || it.photoCandidates.isNotEmpty() }
                !hasName || !hasPhoto
            }
            val semaphore = Semaphore(5)
            coroutineScope {
                eyeconTargets.map { n ->
                    async {
                        semaphore.withPermit {
                            try {
                                val r = withTimeoutOrNull(9_000L) {
                                    eyecon.lookup(n, IdentifierType.PHONE, LookupContext())
                                }
                                if (r != null) synchronized(partialsByNumber) { addPartial(n, r) }
                            } catch (_: Exception) { }
                        }
                    }
                }.awaitAll()
            }
        }

        // ---- Phase 3: merge + cache + permanent phonebook mirror.
        val service = ContactEnrichmentService(
            application, application.lookupEngine, application.repository, application.database
        )
        var enriched = 0
        var done = 0
        for (n in todo) {
            try {
                val partials = partialsByNumber[n].orEmpty()
                if (partials.isNotEmpty()) {
                    val merged: LookupResult = ConfidenceEngine.merge(n, partials)
                    val hasName = !merged.name.isNullOrBlank() && !ContactUtils.isPlaceholderName(merged.name)
                    val hasPhoto = !merged.imageUrl.isNullOrBlank() || merged.photoCandidates.isNotEmpty()
                    if (hasName || hasPhoto) {
                        try { application.repository.saveLookupResult(merged) } catch (_: Exception) { }
                        try { service.mirrorLookupResult(n, merged) } catch (_: Exception) { }
                        enriched++
                        _progress.value = Progress(
                            total = todo.size, done = done + 1,
                            lastLabel = merged.name ?: n, running = true
                        )
                    }
                }
            } catch (e: Exception) {
                Log.w("BulkIdentity", "scan failed for $n", e)
            }
            done++
            if (done % 5 == 0 || done == todo.size) {
                _progress.value = Progress(total = todo.size, done = done, running = true)
            }
        }
        enriched
    }

    /** Matches a bulk-response key back to one of our normalized numbers. */
    private fun matchKey(respKey: String, candidates: List<String>): String? {
        val norm = try { PhoneNumberUtils.normalize(respKey) } catch (_: Exception) { "" }
        if (norm.isNotBlank() && candidates.contains(norm)) return norm
        val respDigits = respKey.filter { it.isDigit() }.takeLast(10)
        if (respDigits.length < 7) return null
        return candidates.firstOrNull { c ->
            c.filter { it.isDigit() }.takeLast(10) == respDigits
        }
    }
}
