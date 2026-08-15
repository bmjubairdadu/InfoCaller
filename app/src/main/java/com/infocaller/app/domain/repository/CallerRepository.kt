package com.infocaller.app.domain.repository

import com.infocaller.app.domain.model.Caller
import kotlinx.coroutines.flow.Flow

interface CallerRepository {
    fun getCaller(phoneNumber: String): Flow<Caller?>
    suspend fun searchCaller(phoneNumber: String): Caller?
    suspend fun reportSpam(phoneNumber: String, reason: String)
    suspend fun saveCaller(caller: Caller)
    suspend fun submitReport(phoneNumber: String, category: String, note: String)
    suspend fun contributeCallerInfo(caller: Caller)
    suspend fun syncSpamDatabase()
    suspend fun isSpam(phoneNumber: String): Boolean
    
    // Blocklist
    fun getBlocklist(): Flow<List<String>>
    suspend fun blockNumber(phoneNumber: String)
    suspend fun unblockNumber(phoneNumber: String)
    suspend fun isBlocked(phoneNumber: String): Boolean
}
