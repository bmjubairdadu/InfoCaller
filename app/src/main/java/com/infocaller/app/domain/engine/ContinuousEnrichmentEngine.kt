package com.infocaller.app.domain.engine

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log
import com.infocaller.app.data.local.dao.EnrichmentDao
import com.infocaller.app.data.local.dao.EnrichmentQueueDao
import com.infocaller.app.data.local.entity.ContactEnrichmentEntity
import com.infocaller.app.data.local.entity.EnrichmentQueueEntity
import com.infocaller.app.data.local.entity.QueuePriority
import com.infocaller.app.data.local.entity.QueueStatus
import com.infocaller.app.data.repository.ContactEnrichmentService
import com.infocaller.app.data.remote.BackendApiService
import com.infocaller.app.domain.model.Caller
import com.infocaller.app.domain.repository.CallerRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

class ContinuousEnrichmentEngine(
    private val context: Context,
    private val queueDao: EnrichmentQueueDao,
    private val enrichmentDao: EnrichmentDao,
    private val lookupEngine: IPublicLookupEngine,
    private val orchestrator: IScanOrchestrator,
    private val repository: CallerRepository,
    private val enrichmentService: ContactEnrichmentService? = null,
    private val backendService: BackendApiService? = null
) {
    private val _isOnline = MutableStateFlow(isCurrentlyOnline())
    val isOnline = _isOnline.asStateFlow()

    init {
        monitorConnectivity()
    }

    private fun isCurrentlyOnline(): Boolean {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun monitorConnectivity() {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        connectivityManager.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                _isOnline.value = true
            }

            override fun onLost(network: Network) {
                _isOnline.value = false
            }
        })
    }

    fun startProcessing() {
        // Handled by Worker
    }

    // Professional throttling: global rate limit + per-provider backoff
    private var lastProcessMs = 0L
    private val MIN_INTERVAL_MS = 3500L // 3.5s between numbers -> ~17/min, safe for free APIs
    private val BURST_DAILY_CAP = 800 // soft cap per day (prefs-tracked)

    suspend fun processNextOneByOne() = withContext(Dispatchers.IO) {
        if (!_isOnline.value) return@withContext
        // Enforce global throttle
        val now = System.currentTimeMillis()
        val wait = MIN_INTERVAL_MS - (now - lastProcessMs)
        if (wait > 0) kotlinx.coroutines.delay(wait)
        if (!_isOnline.value || !isActive) return@withContext
        // Daily cap check (professional: prevent runaway costs/blocks)
        val prefs = context.getSharedPreferences("enrichment_limits", Context.MODE_PRIVATE)
        val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
        val keyCount = "count_$today"
        val keyDate = "date"
        if (prefs.getString(keyDate, "") != today) prefs.edit().putString(keyDate, today).putInt(keyCount, 0).apply()
        val todayCount = prefs.getInt(keyCount, 0)
        if (todayCount >= BURST_DAILY_CAP) {
            android.util.Log.i("EnrichmentEngine", "Daily cap $BURST_DAILY_CAP reached - pausing until tomorrow")
            return@withContext
        }
        val items = queueDao.getEligibleItems(System.currentTimeMillis(), limit = 1)
        if (items.isNotEmpty()) {
            val item = items[0]
            if (isActive && _isOnline.value) {
                lastProcessMs = System.currentTimeMillis()
                processItem(item)
                prefs.edit().putInt(keyCount, todayCount + 1).apply()
            }
        }
    }

    private suspend fun processItem(item: EnrichmentQueueEntity) {
        try {
            val identifier = item.identifier
            if (item.type != IdentifierType.PHONE) {
                queueDao.insertOrUpdate(item.copy(status = QueueStatus.FAILED, reason = "Non-phone type not supported"))
                return
            }
            // Gap-aware: skip if contact is already enriched (name+photo present)
            val existingEnrichment = enrichmentDao.getEnrichmentSync(com.infocaller.app.util.PhoneNumberUtils.normalize(identifier))
            val gaps = com.infocaller.app.util.EnrichmentGapChecker.check(existingEnrichment)
            if (gaps.isComplete) {
                Log.d("EnrichmentEngine", "Skipping ${item.identifier} - already complete (name+photo present)")
                queueDao.insertOrUpdate(item.copy(status = QueueStatus.COMPLETED, lastAttemptAt = System.currentTimeMillis()))
                return
            }

            queueDao.insertOrUpdate(item.copy(status = QueueStatus.PROCESSING, lastAttemptAt = System.currentTimeMillis()))

            // Perform scan via prioritized orchestrator - pass missing capabilities as hint
            val requiredCaps = if (existingEnrichment != null) {
                // Convert missing capability names to enum via gap checker
                val missing = com.infocaller.app.util.EnrichmentGapChecker.missingCapabilities(gaps)
                missing.mapNotNull { try { Capability.valueOf(it) } catch (_:Exception) { null } }.toSet()
            } else emptySet()
            orchestrator.startScan(identifier, ScanPriority.BACKGROUND).collect { state ->
                if (state is ScanState.Completed) {
                    val res = state.result
                    repository.saveLookupResult(res)

                    // PROACTIVE SYNC: Fill only missing fields (gap-aware)
                    val beforeGaps = gaps
                    val newGaps = com.infocaller.app.util.EnrichmentGapChecker.check(
                        enrichmentDao.getEnrichmentSync(com.infocaller.app.util.PhoneNumberUtils.normalize(identifier))
                    )
                    // Name is locked to user's saved name - never overwrite. Save discovered name to alternateName only.
                    // Throttled one-by-one already handled by MIN_INTERVAL; update only missing photo/other fields.
                    val shouldSync = res.confidence >= 0.6f && (!res.imageUrl.isNullOrBlank() || !res.name.isNullOrBlank() || !res.city.isNullOrBlank() || !res.about.isNullOrBlank())
                    if (shouldSync) {
                        // displayName is null here to honor "name never change" - ContactEnrichmentService will skip DISPLAY_NAME op
                        enrichmentService?.updateExistingContact(
                            phoneNumber = identifier,
                            caller = Caller(
                                phoneNumber = identifier,
                                displayName = null, // locked - alternateName carries WhatsApp/Truecaller name instead
                                alias = res.name ?: res.alternateName,
                                photoUrl = if (beforeGaps.missingPhoto) res.imageUrl else null,
                                organization = res.carrier,
                                carrier = res.carrier,
                                country = res.country,
                                region = res.region,
                                reportCount = 0,
                                isVerified = false,
                                socialMediaLinks = res.socialProfiles.mapNotNull { it.profileUrl }
                            )
                        )
                    }
                }
            }

            queueDao.insertOrUpdate(item.copy(status = QueueStatus.COMPLETED, attemptCount = item.attemptCount + 1))
        } catch (e: Exception) {
            Log.e("EnrichmentEngine", "Failed to process ${item.identifier}", e)
            // capped exponential backoff 1m,2m,4m.. max 24h + jitter 0..30s to avoid thundering herd
            val exp = (1L shl minOf(item.attemptCount, 10)) * 60_000L
            val capped = minOf(exp, 86_400_000L)
            val jitter = (0..30_000L).random()
            val nextAttempt = System.currentTimeMillis() + capped + jitter
            queueDao.insertOrUpdate(item.copy(status = QueueStatus.RETRY_WAIT, nextAttemptAt = nextAttempt, attemptCount = item.attemptCount + 1, reason = e.message))
        }
    }

    suspend fun enqueue(id: String, type: String = IdentifierType.PHONE, priority: Int = QueuePriority.MEDIUM, contactId: Long? = null) {
        val existing = queueDao.getQueueItemSync(id)
        if (existing != null && existing.status == QueueStatus.COMPLETED) {
            if (priority > existing.priority) {
                queueDao.insertOrUpdate(existing.copy(priority = priority, status = QueueStatus.PENDING, nextAttemptAt = 0))
            }
            return
        }
        queueDao.insertOrUpdate(EnrichmentQueueEntity(identifier = id, type = type, contactId = contactId, priority = priority, status = QueueStatus.PENDING))
    }

    fun getEnrichment(number: String): Flow<ContactEnrichmentEntity?> {
        return enrichmentDao.getEnrichment(com.infocaller.app.util.PhoneNumberUtils.normalize(number))
    }
}
