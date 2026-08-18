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

    suspend fun performBulkLookup(
        phoneNumbers: List<String>,
        requiredCapabilities: Set<Capability> = emptySet()
    ): Map<String, LookupResult> = coroutineScope {
        val providers = providerManager.getHealthyProviders().filter { 
            requiredCapabilities.isEmpty() || it.capabilities.any { cap -> cap in requiredCapabilities }
        }

        val resultsMap = mutableMapOf<String, MutableList<PartialResult>>()
        
        providers.forEach { provider ->
            try {
                val start = System.currentTimeMillis()
                val bulkResults = withTimeoutOrNull(15000) {
                    provider.bulkLookup(phoneNumbers)
                }
                if (bulkResults != null && bulkResults.isNotEmpty()) {
                    providerManager.reportResult(provider.id, true, (System.currentTimeMillis() - start) / bulkResults.size)
                    bulkResults.forEach { (number, partial) ->
                        resultsMap.getOrPut(number) { mutableListOf() }.add(partial)
                    }
                } else {
                    // Fallback to individual lookups if bulk fails or not supported
                    phoneNumbers.forEach { number ->
                        val res = provider.lookup(number)
                        if (res != null) {
                            resultsMap.getOrPut(number) { mutableListOf() }.add(res)
                        }
                    }
                }
            } catch (e: Exception) {
                providerManager.reportResult(provider.id, false, 15000)
            }
        }

        resultsMap.mapValues { (number, partials) ->
            ConfidenceEngine.merge(number, partials)
        }
    }
}
