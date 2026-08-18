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
        val partials = lookupPartials(phoneNumber, requiredCapabilities, onPartialResult)
        ConfidenceEngine.merge(phoneNumber, partials)
    }

    suspend fun lookupPartials(
        phoneNumber: String,
        requiredCapabilities: Set<Capability> = emptySet(),
        onPartialResult: suspend (PartialResult) -> Unit = {}
    ): List<PartialResult> = coroutineScope {
        val providers = providerManager.getHealthyProviders().filter { 
            requiredCapabilities.isEmpty() || it.capabilities.any { cap -> cap in requiredCapabilities }
        }
        
        val deferredResults = providers.map { provider ->
            async {
                try {
                    val start = System.currentTimeMillis()
                    val res = withTimeoutOrNull(8000) { // Slightly longer timeout
                        provider.lookup(phoneNumber)
                    }
                    if (res != null) {
                        val finalRes = res.copy(durationMs = System.currentTimeMillis() - start)
                        providerManager.reportResult(provider.id, true, finalRes.durationMs)
                        onPartialResult(finalRes)
                        finalRes
                    } else {
                        providerManager.reportResult(provider.id, false, 8000)
                        null
                    }
                } catch (e: Exception) {
                    providerManager.reportResult(provider.id, false, 8000)
                    null
                }
            }
        }

        deferredResults.awaitAll().filterNotNull()
    }
}
