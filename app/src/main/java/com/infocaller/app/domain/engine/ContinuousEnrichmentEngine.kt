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
import com.infocaller.app.data.remote.model.InfoCallerLookupResponse
import com.infocaller.app.domain.model.Caller
import com.infocaller.app.util.SocialUtils
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class ContinuousEnrichmentEngine(
    private val context: Context,
    private val queueDao: EnrichmentQueueDao,
    private val enrichmentDao: EnrichmentDao,
    private val lookupEngine: PublicLookupEngine,
    private val enrichmentService: ContactEnrichmentService? = null,
    private val backendService: BackendApiService? = null
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
                    delay(30000)
                    continue
                }
                items.forEach { item ->
                    if (!isActive || !_isOnline.value) return@forEach
                    processItem(item)
                }
                delay(5000)
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
            
            val existing = enrichmentDao.getEnrichmentSync(item.normalizedPhoneNumber)
            val isStale = existing == null || existing.expiresAt < System.currentTimeMillis()
            
            val required = mutableSetOf<Capability>()
            if (existing?.publicName == null || isStale) required.add(Capability.PUBLIC_SEARCH)
            if (existing?.profileImageUrl == null || isStale) required.add(Capability.PROFILE_PHOTO)
            if (existing?.city == null || isStale) required.add(Capability.PHONE_METADATA)
            if (existing?.carrier == null || isStale) required.add(Capability.CARRIER)
            if (existing?.isBusiness == null || isStale) required.add(Capability.BUSINESS)
            if (existing?.whatsappStatus == null || isStale) required.add(Capability.WHATSAPP)
            if (existing?.telegramStatus == null || isStale) required.add(Capability.TELEGRAM)
            if (existing?.spamStatus == null || isStale) required.add(Capability.SPAM_CHECK)

            if (required.isEmpty()) {
                queueDao.insertOrUpdate(item.copy(status = QueueStatus.COMPLETED, attemptCount = item.attemptCount + 1))
                return
            }

            val partials = lookupEngine.lookupPartials(item.normalizedPhoneNumber, requiredCapabilities = required) { partial ->
                savePartialToCache(item.normalizedPhoneNumber, item.contactId, partial)
            }
            
            val finalResult = ConfidenceEngine.merge(item.normalizedPhoneNumber, partials)
            saveLookupResultToCache(finalResult, item.contactId)

            // Privacy Safe Publish
            val registryResult = ConfidenceEngine.mergeForRegistry(item.normalizedPhoneNumber, partials)
            if (registryResult.confidence > 0.6f) {
                publishToSharedRegistry(registryResult)
            }

            // Sync back to system contacts
            enrichmentService?.updateExistingContact(
                phoneNumber = item.normalizedPhoneNumber,
                caller = Caller(
                    phoneNumber = item.normalizedPhoneNumber,
                    displayName = finalResult.name,
                    alias = finalResult.sources.firstOrNull(),
                    photoUrl = finalResult.imageUrl,
                    organization = finalResult.carrier,
                    carrier = finalResult.carrier,
                    country = finalResult.country,
                    region = finalResult.region,
                    spamScore = finalResult.spamScore,
                    spamStatus = finalResult.spamStatus
                )
            )

            queueDao.insertOrUpdate(item.copy(status = QueueStatus.COMPLETED, attemptCount = item.attemptCount + 1))
        } catch (e: Exception) {
            Log.e("EnrichmentEngine", "Failed to process ${item.normalizedPhoneNumber}", e)
            val nextAttempt = System.currentTimeMillis() + (Math.pow(2.0, item.attemptCount.toDouble()).toLong() * 60000)
            queueDao.insertOrUpdate(item.copy(status = QueueStatus.RETRY_WAIT, nextAttemptAt = nextAttempt, attemptCount = item.attemptCount + 1, reason = e.message))
        }
    }

    private suspend fun publishToSharedRegistry(result: com.infocaller.app.domain.model.LookupResult) {
        if (backendService == null) return
        try {
            val record = InfoCallerLookupResponse(
                phoneNumber = result.phoneNumber,
                publicName = result.name,
                profileImageUrl = result.imageUrl,
                about = result.about,
                carrier = result.carrier,
                country = result.country,
                city = result.city,
                region = result.region,
                whatsappStatus = result.socialProfiles.find { it.platform == "WhatsApp" }?.status?.name,
                telegramStatus = result.socialProfiles.find { it.platform == "Telegram" }?.status?.name,
                isBusiness = result.isBusiness,
                source = "user_contributed",
                confidence = if (result.confidence > 0.8f) "HIGH" else "MEDIUM"
            )
            backendService.publishToRegistry(record)
        } catch (e: Exception) {
            Log.e("EnrichmentEngine", "Registry publish failed", e)
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
            city = partial.city ?: existing?.city,
            carrier = partial.carrier ?: existing?.carrier,
            country = partial.country ?: existing?.country,
            region = partial.region ?: existing?.region,
            isBusiness = partial.isBusiness ?: existing?.isBusiness,
            whatsappStatus = partial.socialProfiles.find { it.platform == "WhatsApp" }?.status?.name ?: existing?.whatsappStatus,
            telegramStatus = partial.socialProfiles.find { it.platform == "Telegram" }?.status?.name ?: existing?.telegramStatus,
            socialProfilesJson = if (partial.socialProfiles.isNotEmpty()) SocialUtils.toJson(partial.socialProfiles) else existing?.socialProfilesJson,
            spamScore = if (partial.spamScore > 0) partial.spamScore else (existing?.spamScore ?: 0),
            spamType = partial.spamType ?: existing?.spamType,
            spamStatus = if (partial.spamScore > 50) "SPAM" else existing?.spamStatus,
            source = (existing?.source?.split(",")?.toMutableSet() ?: mutableSetOf()).apply { partial.source?.let { add(it) } }.joinToString(","),
            confidence = maxOf(existing?.confidence?.toFloatOrNull() ?: 0f, partial.confidence).toString(),
            lastChecked = System.currentTimeMillis(),
            expiresAt = existing?.expiresAt ?: (System.currentTimeMillis() + 3600000)
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
                city = result.city,
                carrier = result.carrier,
                country = result.country,
                region = result.region,
                isBusiness = result.isBusiness,
                whatsappStatus = result.socialProfiles.find { it.platform == "WhatsApp" }?.status?.name,
                telegramStatus = result.socialProfiles.find { it.platform == "Telegram" }?.status?.name,
                socialProfilesJson = SocialUtils.toJson(result.socialProfiles),
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
            if (priority > existing.priority) {
                queueDao.insertOrUpdate(existing.copy(priority = priority, status = QueueStatus.PENDING, nextAttemptAt = 0))
            }
            return
        }
        queueDao.insertOrUpdate(EnrichmentQueueEntity(normalizedPhoneNumber = number, contactId = contactId, priority = priority, status = QueueStatus.PENDING))
        if (_isOnline.value) startProcessing()
    }

    fun getEnrichment(number: String): Flow<ContactEnrichmentEntity?> {
        return enrichmentDao.getEnrichment(com.infocaller.app.util.PhoneNumberUtils.normalize(number))
    }
}
