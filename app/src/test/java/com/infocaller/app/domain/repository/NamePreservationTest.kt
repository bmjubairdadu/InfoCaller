package com.infocaller.app.domain.repository

import com.infocaller.app.data.local.dao.*
import com.infocaller.app.data.local.entity.CallerEntity
import com.infocaller.app.data.repository.CallerRepositoryImpl
import com.infocaller.app.domain.engine.*
import com.infocaller.app.domain.model.*
import com.infocaller.app.util.IContextResolver
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.kotlin.*

class NamePreservationTest {

    private val callerDao: CallerDao = mock()
    private val enrichmentDao: EnrichmentDao = mock()
    
    private class SimpleMockLookupEngine : IPublicLookupEngine {
        override suspend fun performLookup(identifier: String, type: String, requiredCapabilities: Set<Capability>, alreadyCompletedProviders: Set<String>, onPartialResult: suspend (PartialResult) -> Unit): LookupResult = LookupResult(phoneNumber = identifier)
    }
    
    private class SimpleMockOrchestrator : IScanOrchestrator {
        override val scanStates: StateFlow<Map<String, ScanState>> = MutableStateFlow(emptyMap())
        override fun startScan(phoneNumber: String, priority: ScanPriority): Flow<ScanState> = kotlinx.coroutines.flow.emptyFlow()
        override fun getScanState(phoneNumber: String): ScanState = ScanState.Idle
        override fun cancelScan(phoneNumber: String) {}
    }
    
    private class MockContextResolver : IContextResolver {
        override fun query(uri: android.net.Uri, projection: Array<String>?, selection: String?, selectionArgs: Array<String>?, sortOrder: String?): android.database.Cursor? = null
    }

    private val lookupEngine = SimpleMockLookupEngine()
    private val orchestrator = SimpleMockOrchestrator()
    private val contextResolver = MockContextResolver()

    @Test
    fun testLocalNameSurvivability(): Unit = runBlocking {
        val repo = CallerRepositoryImpl(
            callerDao, mock(), enrichmentDao, lookupEngine, orchestrator, contextResolver
        )

        val number = "+123"
        val existing = CallerEntity(
            phoneNumber = number,
            localName = "Private",
            displayName = "Old",
            alias = null, photoUrl = null, organization = null, country = null, region = null, carrier = null, reportCount = 0, isVerified = false
        )

        whenever(callerDao.getCallerSync(number)).thenReturn(existing)
        
        val result = LookupResult(phoneNumber = number, name = "Public")
        repo.saveLookupResult(result)

        val captor = argumentCaptor<CallerEntity>()
        verify(callerDao).insertCaller(captor.capture())
        
        assertEquals("Private", captor.firstValue.localName)
        assertEquals("Public", captor.firstValue.displayName)
    }
}
