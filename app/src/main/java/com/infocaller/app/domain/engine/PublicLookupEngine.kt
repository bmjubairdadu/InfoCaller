package com.infocaller.app.domain.engine

import android.util.Log
import com.infocaller.app.domain.model.LookupResult
import kotlinx.coroutines.*

class PublicLookupEngine(
    private val providerManager: ProviderManager
) {
    suspend fun performLookup(
        phoneNumber: String,
        requiredCapabilities: Set<Capability> = emptySet(),
        onPartialResult: suspend (PartialResult) -> Unit = {}
    ): LookupResult = coroutineScope {
        val finalResults = mutableListOf<PartialResult>()
        
        // 1. Single reliable E.164 normalization
        val normalized = com.infocaller.app.util.PhoneNumberUtils.normalize(phoneNumber)
        
        // 2. Define what we are looking for
        val remainingCapabilities = if (requiredCapabilities.isEmpty()) {
            Capability.values().toMutableSet()
        } else {
            requiredCapabilities.toMutableSet()
        }

        // 3. Get healthy providers sorted by priority (Highest first)
        val sortedProviders = providerManager.getHealthyProviders().sortedByDescending { it.priority }

        // 4. Planner: Execute providers and stop when info is sufficient
        for (provider in sortedProviders) {
            // Check if this provider has any overlap with remaining needs
            val usefulCapabilities = provider.capabilities.intersect(remainingCapabilities)
            if (usefulCapabilities.isEmpty()) continue

            // Strategy: Run independent providers concurrently if possible (not implemented here for simplicity, 
            // but we use sequential tiers to save costs/credits)
            
            try {
                val start = System.currentTimeMillis()
                val result = withTimeoutOrNull(8000) {
                    provider.lookup(normalized)
                }
                
                if (result != null) {
                    val duration = System.currentTimeMillis() - start
                    val finalRes = result.copy(durationMs = duration)
                    providerManager.reportResult(provider.id, true, duration)
                    
                    finalResults.add(finalRes)
                    onPartialResult(finalRes)
                    
                    // Mark capabilities as satisfied if we got real data
                    if (finalRes.name != null && !com.infocaller.app.util.ContactUtils.isPlaceholderName(finalRes.name)) {
                        remainingCapabilities.remove(Capability.PUBLIC_SEARCH)
                        remainingCapabilities.remove(Capability.ALTERNATE_NAME)
                    }
                    if (finalRes.imageUrl != null) remainingCapabilities.remove(Capability.PROFILE_PHOTO)
                    if (finalRes.city != null) remainingCapabilities.remove(Capability.CITY)
                    if (finalRes.country != null) remainingCapabilities.remove(Capability.COUNTRY)
                    if (finalRes.carrier != null) remainingCapabilities.remove(Capability.CARRIER)
                    if (finalRes.isBusiness != null) remainingCapabilities.remove(Capability.BUSINESS)
                    if (finalRes.email != null) remainingCapabilities.remove(Capability.EMAIL)
                    if (finalRes.spamScore > 0) remainingCapabilities.remove(Capability.SPAM_CHECK)
                    
                    // If we satisfied all requested or critical info, stop.
                    if (remainingCapabilities.isEmpty()) break
                } else {
                    providerManager.reportResult(provider.id, false, 8000)
                }
            } catch (e: Exception) {
                Log.e("LookupEngine", "Provider ${provider.id} failed", e)
                providerManager.reportResult(provider.id, false, 8000)
            }
        }

        ConfidenceEngine.merge(normalized, finalResults)
    }

    suspend fun lookupPartials(
        phoneNumber: String,
        requiredCapabilities: Set<Capability> = emptySet(),
        onPartialResult: suspend (PartialResult) -> Unit = {}
    ): List<PartialResult> = coroutineScope {
        val results = mutableListOf<PartialResult>()
        performLookup(phoneNumber, requiredCapabilities) {
            results.add(it)
            onPartialResult(it)
        }
        results
    }

    suspend fun performBulkLookup(
        phoneNumbers: List<String>,
        requiredCapabilities: Set<Capability> = emptySet()
    ): Map<String, LookupResult> = coroutineScope {
        val providers = providerManager.getHealthyProviders().filter { 
            requiredCapabilities.isEmpty() || it.capabilities.any { cap -> cap in requiredCapabilities }
        }.sortedByDescending { it.priority }

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
