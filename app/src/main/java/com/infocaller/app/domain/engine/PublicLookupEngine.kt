package com.infocaller.app.domain.engine

import com.infocaller.app.domain.model.LookupResult
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull

class PublicLookupEngine(
    private val providerManager: ProviderManager
) {
    suspend fun performLookup(
        phoneNumber: String,
        requiredCapabilities: Set<Capability> = emptySet(),
        onPartialResult: suspend (PartialResult) -> Unit = {}
    ): LookupResult = coroutineScope {
        val providers = providerManager.getHealthyProviders().filter { 
            requiredCapabilities.isEmpty() || it.capabilities.any { cap -> cap in requiredCapabilities }
        }
        
        val deferredResults = providers.map { provider ->
            async {
                try {
                    val start = System.currentTimeMillis()
                    val res = withTimeoutOrNull(5000) {
                        provider.lookup(phoneNumber)
                    }
                    if (res != null) {
                        val finalRes = res.copy(durationMs = System.currentTimeMillis() - start)
                        providerManager.reportResult(provider.id, true, finalRes.durationMs)
                        onPartialResult(finalRes)
                        finalRes
                    } else {
                        providerManager.reportResult(provider.id, false, 5000)
                        null
                    }
                } catch (e: Exception) {
                    providerManager.reportResult(provider.id, false, 5000)
                    null
                }
            }
        }

        val partialResults = deferredResults.awaitAll().filterNotNull()
        ConfidenceEngine.merge(phoneNumber, partialResults)
    }

    suspend fun performBatchLookup(
        numbers: List<String>,
        requiredCapabilities: Set<Capability> = emptySet()
    ): Map<String, LookupResult> = coroutineScope {
        numbers.associateWith { number ->
            performLookup(number, requiredCapabilities)
        }
    }
}
