package com.infocaller.app.domain.repository

import com.infocaller.app.domain.model.Caller
import com.infocaller.app.domain.model.LookupResult
import com.infocaller.app.domain.engine.ScanState
import com.infocaller.app.domain.engine.ScanPriority
import com.infocaller.app.domain.engine.IdentifierType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

interface ICallerRepository {
    fun getCaller(phoneNumber: String): Flow<Caller?>
    suspend fun searchCaller(phoneNumber: String): Caller?
    suspend fun saveCaller(caller: Caller)
    suspend fun saveLookupResult(result: LookupResult)
    suspend fun contributeCallerInfo(caller: Caller)
    
    fun getScanStates(): StateFlow<Map<String, ScanState>>
    fun startScan(identifier: String, priority: ScanPriority, type: String = IdentifierType.PHONE): Flow<ScanState>
    fun cancelScan(identifier: String)

    fun getBlocklist(): Flow<List<String>>
    suspend fun blockNumber(phoneNumber: String)
    suspend fun unblockNumber(phoneNumber: String)
    suspend fun isBlocked(phoneNumber: String): Boolean
}
