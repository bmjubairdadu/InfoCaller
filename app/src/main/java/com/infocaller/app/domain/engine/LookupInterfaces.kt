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
        onPartialResult: suspend (PartialResult) -> Unit = {}
    ): LookupResult
}

interface IImageAnalysisService {
    suspend fun analyze(candidate: PhotoCandidate): PhotoCandidate
}
