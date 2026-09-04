package com.infocaller.app.domain.engine

import android.util.Log
import com.infocaller.app.domain.model.LookupResult
import com.infocaller.app.util.PhoneNumberUtils
import kotlinx.coroutines.*


class PublicLookupEngine(
    private val providerManager: ProviderManager
) : IPublicLookupEngine {
    override suspend fun performLookup(
        identifier: String,
        type: String,
        requiredCapabilities: Set<Capability>,
        alreadyCompletedProviders: Set<String>,
        onPartialResult: suspend (PartialResult) -> Unit
    ): LookupResult = coroutineScope {
        val finalResults = mutableListOf<PartialResult>()
        val normalized = if (type == IdentifierType.PHONE) PhoneNumberUtils.normalize(identifier) else identifier
        
        val deepScanned = mutableSetOf<String>()
        deepScanned.add(normalized)

        val remainingCapabilities = if (requiredCapabilities.isEmpty()) {
            Capability.entries.toMutableSet()
        } else {
            requiredCapabilities.toMutableSet()
        }

        val allProviders = providerManager.getAllProviders()
            .filter { p ->
                // Only FREE/LOW providers run on-device. HIGH/MEDIUM are
                // opt-in via registration and never auto-run.
                p.costClass == CostClass.FREE || p.costClass == CostClass.LOW
            }
        val tc = allProviders.find { it.id.contains("truecaller", ignoreCase = true) }
        val eyecon = allProviders.find { it.id.contains("eyecon", ignoreCase = true) }
        val others = allProviders.filter { it != tc && it != eyecon }
            .sortedWith(compareBy<LookupProvider> { it.costClass }.thenByDescending { it.priority })

        val executionPlan = mutableListOf<LookupProvider>()
        tc?.let { executionPlan.add(it) }
        eyecon?.let { executionPlan.add(it) }
        executionPlan.addAll(others)

        var photoFound = false
        var nameFound = false
        for (provider in executionPlan) {
            if (alreadyCompletedProviders.contains(provider.id)) continue
            val caps = provider.capabilities.toMutableSet()
            if (photoFound) caps.remove(Capability.PROFILE_PHOTO)
            if (nameFound) { caps.remove(Capability.PUBLIC_SEARCH); caps.remove(Capability.ALTERNATE_NAME); caps.remove(Capability.PUBLIC_PROFILE) }
            val usefulCapabilities = caps.intersect(remainingCapabilities)
            if (usefulCapabilities.isEmpty()) {
                if (caps.isEmpty()) continue
                continue
            }
            
            try {
                val start = System.currentTimeMillis()
                val result = withTimeoutOrNull(15000) {
                    provider.lookup(normalized, type = type)
                }

                if (result != null) {
                    val duration = System.currentTimeMillis() - start
                    val finalRes = result.copy(durationMs = duration, identifier = normalized, identifierType = type)
                    providerManager.reportResult(provider.id, true, duration)

                    finalResults.add(finalRes)
                    onPartialResult(finalRes)
                    if (!finalRes.name.isNullOrBlank()) nameFound = true
                    if (!finalRes.imageUrl.isNullOrBlank() || finalRes.photoCandidates.isNotEmpty()) photoFound = true

                    // Deep-discovery pivots run under the same cooperative cancellation
                    // so a cancelled call-path scan stops promptly.
                    ensureActive()
                    performDeepDiscovery(finalRes, deepScanned, onPartialResult, finalResults)

                    updateRemainingCapabilities(finalRes, remainingCapabilities)

                    if (isSufficientlyDetailed(finalResults)) break

                    if (remainingCapabilities.isEmpty()) break
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                // Never swallow cancellation — the call path must stay cancellable.
                throw e
            } catch (e: Exception) {
                providerManager.reportResult(provider.id, false, 15000)
            }
        }

        ConfidenceEngine.merge(normalized, finalResults)
    }

    private suspend fun performDeepDiscovery(
        result: PartialResult,
        scanned: MutableSet<String>,
        onPartialResult: suspend (PartialResult) -> Unit,
        accumulator: MutableList<PartialResult>
    ) {
        if (scanned.size > 15) return
        var pivots = 0
        val maxPivots = 5
        for (profile in result.socialProfiles) {
            if (pivots >= maxPivots) break
            val username = profile.username?.trim()
            if (!username.isNullOrBlank() && !scanned.contains(username) && username.length in 3..30 && !username.contains(" ")) {
                scanned.add(username); pivots++
                lookupPartials(username, IdentifierType.USERNAME) { partial -> accumulator.add(partial); onPartialResult(partial) }
            }
            val handle = profile.profileUrl?.substringAfterLast("/")?.substringBefore("?")?.trim()
            if (pivots < maxPivots && !handle.isNullOrBlank() && handle != username && handle.length in 3..30 && !scanned.contains(handle) && !handle.contains(".")) {
                scanned.add(handle); pivots++
                lookupPartials(handle, IdentifierType.USERNAME) { partial -> accumulator.add(partial); onPartialResult(partial) }
            }
        }
        if (pivots >= maxPivots) return
        val email = result.email?.trim()
        if (!email.isNullOrBlank() && !scanned.contains(email) && email.contains("@")) {
            scanned.add(email); pivots++

            lookupPartials(email, IdentifierType.EMAIL) { partial -> accumulator.add(partial); onPartialResult(partial) }
        }
        if (pivots >= maxPivots) return
        val name = result.name?.trim()
        if (!name.isNullOrBlank() && pivots < maxPivots) {
            val slug = name.lowercase().replace(Regex("[^a-z0-9]"), "")
            if (slug.length in 4..30 && !scanned.contains(slug)) {
                scanned.add(slug); pivots++
                lookupPartials(slug, IdentifierType.USERNAME) { partial -> accumulator.add(partial); onPartialResult(partial) }
            }
        }
        if (pivots >= maxPivots) return
        if (!name.isNullOrBlank() && name.split(" ").size in 2..4) {
            if (!scanned.contains("name:$name") && pivots < maxPivots) {
                scanned.add("name:$name"); pivots++
                lookupPartials(name, IdentifierType.FULL_NAME){ partial -> accumulator.add(partial); onPartialResult(partial) }
            }
        }
        if (pivots >= maxPivots) return
        val img = result.imageUrl
        if (!img.isNullOrBlank() && img.startsWith("http") && !scanned.contains(img) && pivots < maxPivots) {
            scanned.add(img); pivots++

            lookupPartials(img, "IMAGE_URL"){ partial -> accumulator.add(partial); onPartialResult(partial) }
        }
        if (pivots >= maxPivots) return
        result.about?.let { about ->
            Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}").find(about)?.value?.let { foundEmail ->
                if (!scanned.contains(foundEmail) && pivots < maxPivots) { scanned.add(foundEmail); pivots++; lookupPartials(foundEmail, IdentifierType.EMAIL){ accumulator.add(it); onPartialResult(it)} }
            }
            Regex("@([A-Za-z0-9_.]{3,30})").find(about)?.groupValues?.getOrNull(1)?.let { foundUser ->
                if (!scanned.contains(foundUser) && pivots < maxPivots && foundUser.length in 3..30) { scanned.add(foundUser); pivots++; lookupPartials(foundUser, IdentifierType.USERNAME){ accumulator.add(it); onPartialResult(it)} }
            }
        }
        if (pivots >= maxPivots) return
        val nid = result.nid?.trim()
        if (!nid.isNullOrBlank() && !scanned.contains(nid)) { scanned.add(nid); lookupPartials(nid, IdentifierType.NID){ accumulator.add(it); onPartialResult(it)} }
    }

    private fun isSufficientlyDetailed(results: List<PartialResult>): Boolean {
        val hasName = results.any { it.name != null && it.confidence >= 0.85f }
        val hasPhoto = results.any { it.imageUrl != null && it.confidence >= 0.8f }
        return hasName && hasPhoto
    }

    private fun updateRemainingCapabilities(result: PartialResult, remaining: MutableSet<Capability>) {
        if (result.name != null && result.confidence >= 0.85f) {
            remaining.remove(Capability.PUBLIC_SEARCH)
            remaining.remove(Capability.ALTERNATE_NAME)
        }
        
        if (result.imageUrl != null && result.confidence >= 0.9f) {
            remaining.remove(Capability.PROFILE_PHOTO)
        }
        
        if (result.city != null) remaining.remove(Capability.CITY)
        if (result.country != null) remaining.remove(Capability.COUNTRY)
        if (result.carrier != null) remaining.remove(Capability.CARRIER)
        
        if (result.socialProfiles.any { it.platform == "WhatsApp" }) remaining.remove(Capability.WHATSAPP)
        if (result.socialProfiles.any { it.platform == "Telegram" }) remaining.remove(Capability.TELEGRAM)
        if (result.socialProfiles.size >= 3) remaining.remove(Capability.SOCIAL_MATCH)
        
        if (result.email != null && result.confidence >= 0.8f) remaining.remove(Capability.EMAIL)
        
        if (result.isBusiness != null) remaining.remove(Capability.BUSINESS)
    }

    suspend fun lookupPartials(
        identifier: String,
        type: String = IdentifierType.PHONE,
        requiredCapabilities: Set<Capability> = emptySet(),
        onPartialResult: suspend (PartialResult) -> Unit = {}
    ): List<PartialResult> = coroutineScope {
        val results = mutableListOf<PartialResult>()
        performLookup(identifier, type, requiredCapabilities) {
            results.add(it)
            onPartialResult(it)
        }
        results
    }
}
