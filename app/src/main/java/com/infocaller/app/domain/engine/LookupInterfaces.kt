package com.infocaller.app.domain.engine

import com.infocaller.app.domain.model.LookupResult
import com.infocaller.app.domain.model.PhotoCandidate
import kotlinx.coroutines.flow.Flow

interface IPublicLookupEngine {
    suspend fun performLookup(
        identifier: String,
        type: String = IdentifierType.PHONE,
        requiredCapabilities: Set<Capability> = emptySet(),
        alreadyCompletedProviders: Set<String> = emptySet(),
        onPartialResult: suspend (PartialResult) -> Unit = {},
        onProviderStep: suspend (providerId: String, providerName: String, stepIndex: Int, stepTotal: Int, status: StepStatus) -> Unit = { _, _, _, _, _ -> }
    ): LookupResult
}

interface IImageAnalysisService {
    suspend fun analyze(candidate: PhotoCandidate): PhotoCandidate
}
