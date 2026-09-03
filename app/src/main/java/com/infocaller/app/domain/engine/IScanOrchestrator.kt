package com.infocaller.app.domain.engine

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

interface IScanOrchestrator {
    val scanStates: StateFlow<Map<String, ScanState>>
    fun startScan(identifier: String, priority: ScanPriority = ScanPriority.FOREGROUND, type: String = IdentifierType.PHONE): Flow<ScanState>
    fun getScanState(identifier: String): ScanState
    fun cancelScan(identifier: String)
}
