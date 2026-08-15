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
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class ContinuousEnrichmentEngine(
    private val context: Context,
    private val queueDao: EnrichmentQueueDao,
    private val enrichmentDao: EnrichmentDao,
    private val lookupEngine: PublicLookupEngine
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var job: Job? = null

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
                startProcessing()
            }

            override fun onLost(network: Network) {
                _isOnline.value = false
                stopProcessing()
            }
        })
    }

    fun startProcessing() {
        if (job?.isActive == true) return
        
        job = scope.launch {
            while (isActive && _isOnline.value) {
                val items = queueDao.getEligibleItems(System.currentTimeMillis(), limit = 5)
                if (items.isEmpty()) {
                    delay(30000) // Wait for 30s if queue empty
                    continue
                }

                items.forEach { item ->
                    if (!isActive || !_isOnline.value) return@forEach
                    processItem(item)
                }
                
                delay(5000) // Small delay between batches to respect rate limits
            }
        }
    }

    fun stopProcessing() {
        job?.cancel()
        job = null
    }

    private suspend fun processItem(item: EnrichmentQueueEntity) {
        try {
            queueDao.insertOrUpdate(item.copy(status = QueueStatus.PROCESSING, lastAttemptAt = System.currentTimeMillis()))
            
            // Determine required capabilities based on existing cache
            val existing = enrichmentDao.getEnrichmentSync(item.normalizedPhoneNumber)
            val required = mutableSetOf<Capability>()
            
            if (existing == null || existing.publicName == null) required.add(Capability.PHONE_METADATA)
            if (existing == null || existing.profileImageUrl == null) required.add(Capability.PROFILE_PHOTO)
            if (existing == null || existing.carrier == null) required.add(Capability.CARRIER)
            if (existing == null || existing.whatsappStatus == null) required.add(Capability.WHATSAPP)
            if (existing == null || existing.telegramStatus == null) required.add(Capability.TELEGRAM)
            if (existing == null || existing.spamStatus == null) required.add(Capability.SPAM_CHECK)

            // Perform lookup with real-time saving and selective capabilities
            val finalResult = lookupEngine.performLookup(item.normalizedPhoneNumber, requiredCapabilities = required) { partial ->
                // STAGE 4: Real-time result saving
                savePartialToCache(item.normalizedPhoneNumber, item.contactId, partial)
            }
            
            // Final save to ensure consistency and set expiration
            saveLookupResultToCache(finalResult, item.contactId)

            queueDao.insertOrUpdate(item.copy(status = QueueStatus.COMPLETED, attemptCount = item.attemptCount + 1))
        } catch (e: Exception) {
            Log.e("EnrichmentEngine", "Failed to process ${item.normalizedPhoneNumber}", e)
            val nextAttempt = System.currentTimeMillis() + (Math.pow(2.0, item.attemptCount.toDouble()).toLong() * 60000)
            queueDao.insertOrUpdate(item.copy(
                status = QueueStatus.RETRY_WAIT,
                nextAttemptAt = nextAttempt,
                attemptCount = item.attemptCount + 1,
                reason = e.message
            ))
        }
    }

    private suspend fun savePartialToCache(number: String, contactId: Long?, partial: PartialResult) {
        val existing = enrichmentDao.getEnrichmentSync(number)
        
        val updated = ContactEnrichmentEntity(
            normalizedPhoneNumber = number,
            contactId = contactId ?: existing?.contactId,
            publicName = partial.name ?: existing?.publicName,
            profileImageUrl = partial.imageUrl ?: existing?.profileImageUrl,
            about = partial.about ?: existing?.about,
            carrier = partial.carrier ?: existing?.carrier,
            country = partial.country ?: existing?.country,
            region = partial.region ?: existing?.region,
            whatsappStatus = partial.socialProfiles.find { it.platform == "WhatsApp" }?.status?.name ?: existing?.whatsappStatus,
            telegramStatus = partial.socialProfiles.find { it.platform == "Telegram" }?.status?.name ?: existing?.telegramStatus,
            spamScore = if (partial.spamScore > 0) partial.spamScore else (existing?.spamScore ?: 0),
            spamType = partial.spamType ?: existing?.spamType,
            spamStatus = if (partial.spamScore > 50) "SPAM" else existing?.spamStatus,
            source = (existing?.source?.split(",")?.toMutableSet() ?: mutableSetOf()).apply { partial.source?.let { add(it) } }.joinToString(","),
            confidence = maxOf(existing?.confidence?.toFloatOrNull() ?: 0f, partial.confidence).toString(),
            lastChecked = System.currentTimeMillis(),
            expiresAt = existing?.expiresAt ?: (System.currentTimeMillis() + 3600000) // Temporary short TTL for partials
        )
        enrichmentDao.insertEnrichment(updated)
    }

    private suspend fun saveLookupResultToCache(result: com.infocaller.app.domain.model.LookupResult, contactId: Long?) {
        enrichmentDao.insertEnrichment(
            ContactEnrichmentEntity(
                normalizedPhoneNumber = result.phoneNumber,
                contactId = contactId,
                publicName = result.name,
                profileImageUrl = result.imageUrl,
                about = result.about,
                carrier = result.carrier,
                country = result.country,
                region = result.region,
                whatsappStatus = result.socialProfiles.find { it.platform == "WhatsApp" }?.status?.name,
                telegramStatus = result.socialProfiles.find { it.platform == "Telegram" }?.status?.name,
                spamScore = result.spamScore,
                spamType = result.spamType,
                spamStatus = result.spamStatus.name,
                source = result.sources.joinToString(","),
                confidence = result.confidence.toString(),
                lastChecked = System.currentTimeMillis(),
                expiresAt = System.currentTimeMillis() + (7 * 24 * 60 * 60 * 1000L)
            )
        )
    }

    suspend fun enqueue(number: String, priority: Int = QueuePriority.MEDIUM, contactId: Long? = null) {
        val existing = queueDao.getQueueItemSync(number)
        if (existing != null && existing.status == QueueStatus.COMPLETED) {
            // If already completed, check if it's stale? 
            // For now, only re-enqueue if specifically requested or priority higher
            if (priority > existing.priority) {
                queueDao.insertOrUpdate(existing.copy(priority = priority, status = QueueStatus.PENDING, nextAttemptAt = 0))
            }
            return
        }
        
        queueDao.insertOrUpdate(
            EnrichmentQueueEntity(
                normalizedPhoneNumber = number,
                contactId = contactId,
                priority = priority,
                status = QueueStatus.PENDING
            )
        )
        
        if (_isOnline.value) {
            startProcessing()
        }
    }

    fun getEnrichment(number: String): Flow<ContactEnrichmentEntity?> {
        return enrichmentDao.getEnrichment(com.infocaller.app.util.PhoneNumberUtils.normalize(number))
    }
}
