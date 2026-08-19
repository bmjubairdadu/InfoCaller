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
        val satisfiedCapabilities = mutableSetOf<Capability>()
        
        // If no capabilities specified, we want "everything"
        val remainingCapabilities = if (requiredCapabilities.isEmpty()) {
            Capability.values().toMutableSet()
        } else {
            requiredCapabilities.toMutableSet()
        }

        // Get healthy providers sorted by priority (Highest first)
        val sortedProviders = providerManager.getHealthyProviders().sortedByDescending { it.priority }

        // Planner: Execute providers in priority tiers or based on missing capabilities
        for (provider in sortedProviders) {
            val usefulCapabilities = provider.capabilities.intersect(remainingCapabilities)
            if (usefulCapabilities.isEmpty()) continue

            try {
                val start = System.currentTimeMillis()
                val result = withTimeoutOrNull(8000) {
                    provider.lookup(phoneNumber)
                }
                
                if (result != null) {
                    val duration = System.currentTimeMillis() - start
                    val finalRes = result.copy(durationMs = duration)
                    providerManager.reportResult(provider.id, true, duration)
                    
                    finalResults.add(finalRes)
                    onPartialResult(finalRes)
                    
                    // Update remaining capabilities
                    // A field is considered satisfied if the result contains non-null value for it
                    if (finalRes.name != null) remainingCapabilities.remove(Capability.PUBLIC_SEARCH)
                    if (finalRes.imageUrl != null) remainingCapabilities.remove(Capability.PROFILE_PHOTO)
                    if (finalRes.city != null || finalRes.country != null) remainingCapabilities.remove(Capability.CITY)
                    if (finalRes.carrier != null) remainingCapabilities.remove(Capability.CARRIER)
                    if (finalRes.isBusiness != null) remainingCapabilities.remove(Capability.BUSINESS)
                    if (finalRes.socialProfiles.any { it.platform == "WhatsApp" }) remainingCapabilities.remove(Capability.WHATSAPP)
                    if (finalRes.socialProfiles.any { it.platform == "Telegram" }) remainingCapabilities.remove(Capability.TELEGRAM)
                    if (finalRes.spamScore > 0) remainingCapabilities.remove(Capability.SPAM_CHECK)
                    
                    // If we have enough info, we can stop
                    if (remainingCapabilities.isEmpty()) break
                } else {
                    providerManager.reportResult(provider.id, false, 8000)
                }
            } catch (e: Exception) {
                Log.e("LookupEngine", "Provider ${provider.id} failed", e)
                providerManager.reportResult(provider.id, false, 8000)
            }
        }

        ConfidenceEngine.merge(phoneNumber, finalResults)
    }

    suspend fun lookupPartials(
        phoneNumber: String,
        requiredCapabilities: Set<Capability> = emptySet(),
        onPartialResult: suspend (PartialResult) -> Unit = {}
    ): List<PartialResult> = coroutineScope {
        // This is a legacy method used by some workers, redirecting to performLookup style logic but returning list
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
        
        // For bulk, we currently run all healthy providers that match capabilities
        // as planning is more complex for multiple numbers
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
