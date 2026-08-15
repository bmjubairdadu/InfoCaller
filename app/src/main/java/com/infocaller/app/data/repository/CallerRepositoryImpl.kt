package com.infocaller.app.data.repository

import android.content.Context
import com.infocaller.app.data.local.dao.CallerDao
import com.infocaller.app.data.local.dao.BlocklistDao
import com.infocaller.app.data.local.entity.BlocklistEntity
import com.infocaller.app.data.local.entity.CallerEntity
import com.infocaller.app.domain.model.Caller
import com.infocaller.app.domain.model.SpamStatus
import com.infocaller.app.domain.repository.CallerRepository
import com.infocaller.app.util.PhoneNumberUtils
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class CallerRepositoryImpl(
    private val callerDao: CallerDao,
    private val blocklistDao: BlocklistDao,
    private val callerScraper: com.infocaller.app.data.remote.CallerScraper,
    private val context: Context
) : CallerRepository {

    override fun getCaller(phoneNumber: String): Flow<Caller?> {
        val normalized = PhoneNumberUtils.normalize(phoneNumber)
        return callerDao.getCaller(normalized).map { it?.toDomain() }
    }

    override suspend fun searchCaller(phoneNumber: String): Caller? {
        val normalized = PhoneNumberUtils.normalize(phoneNumber)
        
        // 1. Check local DB with specific TTLs
        val cached = callerDao.getCallerSync(normalized)
        if (cached != null) {
            val age = System.currentTimeMillis() - cached.lastUpdated
            
            // Suggested TTL: 24h for general lookup, but we'll use a safer 7 days for name/metadata
            val isFresh = age < 7 * 24 * 60 * 60 * 1000L
            if (isFresh) return cached.toDomain()
        }

        // 2. Offline-First Scraper (No Paid APIs)
        val result = callerScraper.fetchCallerInfo(phoneNumber)
        if (result != null) {
            saveCaller(result)
        }
        
        return result ?: cached?.toDomain()
    }

    override suspend fun reportSpam(phoneNumber: String, reason: String) {
        val existing = callerDao.getCallerSync(phoneNumber)
        if (existing != null) {
            val updated = existing.copy(
                reportCount = existing.reportCount + 1,
                spamStatus = SpamStatus.SPAM.name
            )
            callerDao.insertCaller(updated)
        } else {
            val newSpam = Caller(
                phoneNumber = phoneNumber,
                displayName = "Reported Spam",
                alias = null,
                photoUrl = null,
                organization = null,
                country = "Unknown",
                region = null,
                carrier = null,
                reportCount = 1,
                spamStatus = SpamStatus.SPAM
            )
            saveCaller(newSpam)
        }
    }

    override suspend fun submitReport(phoneNumber: String, category: String, note: String) {
        // No-OP without API
    }

    override suspend fun contributeCallerInfo(caller: Caller) {
        saveCaller(caller)
    }

    override suspend fun saveCaller(caller: Caller) {
        callerDao.insertCaller(CallerEntity.fromDomain(caller))
    }

    override suspend fun syncSpamDatabase() {
        // No-OP without API
    }

    override suspend fun isSpam(phoneNumber: String): Boolean {
        return callerDao.isSpam(phoneNumber)
    }

    override fun getBlocklist(): Flow<List<String>> {
        return blocklistDao.getAllBlocked().map { list -> list.map { it.phoneNumber } }
    }

    override suspend fun blockNumber(phoneNumber: String) {
        blocklistDao.block(BlocklistEntity(PhoneNumberUtils.normalize(phoneNumber)))
    }

    override suspend fun unblockNumber(phoneNumber: String) {
        blocklistDao.unblock(PhoneNumberUtils.normalize(phoneNumber))
    }

    override suspend fun isBlocked(phoneNumber: String): Boolean {
        return blocklistDao.isBlocked(PhoneNumberUtils.normalize(phoneNumber))
    }
}
