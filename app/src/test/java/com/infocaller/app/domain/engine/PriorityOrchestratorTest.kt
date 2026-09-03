package com.infocaller.app.domain.engine

import com.infocaller.app.data.local.dao.ScanJobDao
import com.infocaller.app.data.local.entity.ScanJobStateEntity
import com.infocaller.app.domain.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PriorityOrchestratorTest {

    private class MockLookupEngine : IPublicLookupEngine {
        var lastAlreadyCompleted: Set<String> = emptySet()
        var delayMs = 0L

        override suspend fun performLookup(
            identifier: String,
            type: String,
            requiredCapabilities: Set<Capability>,
            alreadyCompletedProviders: Set<String>,
            onPartialResult: suspend (PartialResult) -> Unit
        ): LookupResult {
            lastAlreadyCompleted = alreadyCompletedProviders
            if (delayMs > 0) delay(delayMs)
            return LookupResult(phoneNumber = identifier)
        }
    }

    private class MockScanJobDao : ScanJobDao {
        var savedState: ScanJobStateEntity? = null
        override suspend fun getState(number: String): ScanJobStateEntity? = savedState
        override suspend fun insertState(state: ScanJobStateEntity) { savedState = state }
        override suspend fun deleteState(number: String) { savedState = null }
    }

    private class MockImageAnalysisService : IImageAnalysisService {
        override suspend fun analyze(candidate: PhotoCandidate): PhotoCandidate = candidate
    }

    private val lookupEngine = MockLookupEngine()
    private val scanJobDao = MockScanJobDao()
    private val imageAnalysisService = MockImageAnalysisService()
    private lateinit var orchestrator: ScanOrchestrator

    @Before
    fun setup() {
        // Use a real scope for simplicity in this final test pass
        orchestrator = ScanOrchestrator(lookupEngine, imageAnalysisService, scanJobDao, CoroutineScope(Dispatchers.Unconfined))
    }

    @Test
    fun testPriorityPause(): Unit = runBlocking {
        val backgroundNumber = "+11"
        val criticalNumber = "+22"

        lookupEngine.delayMs = 1000

        val bgFlow = orchestrator.startScan(backgroundNumber, ScanPriority.BACKGROUND)
        val bgJob = launch { bgFlow.collect() }
        
        delay(100)
        
        orchestrator.startScan(criticalNumber, ScanPriority.CRITICAL).first()

        assertTrue(orchestrator.getScanState(backgroundNumber) is ScanState.Idle)
        bgJob.cancel()
    }

    @Test
    fun testProviderStateResume(): Unit = runBlocking {
        val number = "+123"
        scanJobDao.savedState = ScanJobStateEntity(
            phoneNumber = number,
            completedProviders = "A",
            satisfiedCapabilities = ""
        )

        orchestrator.startScan(number, ScanPriority.FOREGROUND).first()
        
        assertEquals(setOf("A"), lookupEngine.lastAlreadyCompleted)
    }
}
