package com.infocaller.app.domain.engine

import android.util.Log
import com.infocaller.app.domain.model.LookupResult
import com.infocaller.app.util.PhoneNumberUtils
import kotlinx.coroutines.*

/**
 * The PublicLookupEngine coordinates multiple intelligence providers
 * using an independent capability-based scheduler.
 */
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
        
        // Use a set to track which identifiers (usernames, emails) we've already deep-scanned to avoid cycles
        val deepScanned = mutableSetOf<String>()
        deepScanned.add(normalized)

        val remainingCapabilities = if (requiredCapabilities.isEmpty()) {
            Capability.entries.toMutableSet()
        } else {
            requiredCapabilities.toMutableSet()
        }

        // Apify WhatsApp is HIGH but user provided key -> allow it. Keep other HIGH/MEDIUM low-prio.
        val hasApify = try { com.infocaller.app.BuildConfig.APIFY_TOKEN_1.isNotBlank() } catch(_:Exception){ false }
        val allowedHighIds = setOf("whatsapp_apify_direct")
        val allProviders = providerManager.getHealthyProviders()
            .let { list -> list.filter { p ->
                when (p.costClass) {
                    CostClass.FREE, CostClass.LOW -> true
                    CostClass.HIGH, CostClass.MEDIUM -> p.id in allowedHighIds || p.costClass == CostClass.MEDIUM
                    else -> true
                }
            }}
        // De-prioritize HIGH after FREE/LOW but still run if cap not hit
        val _hasApify = hasApify
        // Keep Truecaller/Eyecon as priority lane but still respect FREE-first ordering afterwards
        val tc = allProviders.find { it.id.contains("truecaller", ignoreCase = true) }
        val eyecon = allProviders.find { it.id.contains("eyecon", ignoreCase = true) }
        val others = allProviders.filter { it != tc && it != eyecon }
            .sortedWith(compareBy<LookupProvider> { it.costClass }.thenByDescending { it.priority })

        // Order rule: FIRST = Truecaller, MIDDLE = remaining tools/APIs, LAST = Apify
        // Except Truecaller->Eyecon stays early for name+photo breadth
        val whats = allProviders.find { it.id == "whatsapp_apify_direct" }
        val apifyRelay = allProviders.find { it.id == "authorized_backend_relay" }
        val middle = others.filter { it.id != "whatsapp_apify_direct" && it.id != "authorized_backend_relay" }
        val last = listOfNotNull(whats, apifyRelay)
        val executionPlan = mutableListOf<LookupProvider>()
        tc?.let { executionPlan.add(it) }
        eyecon?.let { executionPlan.add(it) }
        executionPlan.addAll(middle)
        executionPlan.addAll(last)

        var photoFound = false
        var nameFound = false
        for (provider in executionPlan) {
            if (alreadyCompletedProviders.contains(provider.id)) continue
            // Your rule: one picture -> skip further picture scans; one name -> skip further name scans
            // But Apify (LAST) still runs for photo even if earlier photo found? No - skip it too per rule
            val caps = provider.capabilities.toMutableSet()
            if (photoFound) caps.remove(Capability.PROFILE_PHOTO)
            if (nameFound) { caps.remove(Capability.PUBLIC_SEARCH); caps.remove(Capability.ALTERNATE_NAME); caps.remove(Capability.PUBLIC_PROFILE) }
            val usefulCapabilities = caps.intersect(remainingCapabilities)
            if (usefulCapabilities.isEmpty()) {
                if (provider.id in setOf("whatsapp_apify_direct", "authorized_backend_relay") && photoFound) continue
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

                    // DEEP DISCOVERY: any new username/email/nid/etc triggers deeper pivot
                    performDeepDiscovery(finalRes, deepScanned, onPartialResult, finalResults)

                    updateRemainingCapabilities(finalRes, remainingCapabilities)

                    // STOP CRITERIA: If we have Name and Photo with high confidence, we are done
                    if (isSufficientlyDetailed(finalResults)) break

                    if (remainingCapabilities.isEmpty()) break
                } else {
                    // Not found is not a failure - don't penalize health for empty results (common for non-listed numbers)
                    // Only report failure for actual errors (handled in catch). For not-found, record as soft miss.
                    Log.d("LookupEngine", "Provider ${provider.id} returned no result for $normalized (not found)")
                }
            } catch (e: Exception) {
                Log.e("LookupEngine", "Provider ${provider.id} failed for $normalized", e)
                providerManager.reportResult(provider.id, false, 15000)
            }
        }

        ConfidenceEngine.merge(normalized, finalResults)
    }

    // Deep discovery: any discovered detail pivots deeper (your rule: username/email/etc -> rescan)
    private suspend fun performDeepDiscovery(
        result: PartialResult,
        scanned: MutableSet<String>,
        onPartialResult: suspend (PartialResult) -> Unit,
        accumulator: MutableList<PartialResult>
    ) {
        if (scanned.size > 15) return
        var pivots = 0
        val maxPivots = 5
        // 1. usernames from social profiles
        for (profile in result.socialProfiles) {
            if (pivots >= maxPivots) break
            val username = profile.username?.trim()
            if (!username.isNullOrBlank() && !scanned.contains(username) && username.length in 3..30 && !username.contains(" ")) {
                scanned.add(username); pivots++
                Log.d("DeepDiscovery", "Pivot username: $username (${profile.platform})")
                lookupPartials(username, IdentifierType.USERNAME) { partial -> accumulator.add(partial); onPartialResult(partial) }
            }
            // also pivot profileUrl-derived handles
            val handle = profile.profileUrl?.substringAfterLast("/")?.substringBefore("?")?.trim()
            if (pivots < maxPivots && !handle.isNullOrBlank() && handle != username && handle.length in 3..30 && !scanned.contains(handle) && !handle.contains(".")) {
                scanned.add(handle); pivots++
                Log.d("DeepDiscovery", "Pivot handle: $handle")
                lookupPartials(handle, IdentifierType.USERNAME) { partial -> accumulator.add(partial); onPartialResult(partial) }
            }
        }
        if (pivots >= maxPivots) return
        // 2. email
        val email = result.email?.trim()
        if (!email.isNullOrBlank() && !scanned.contains(email) && email.contains("@")) {
            scanned.add(email); pivots++
            Log.d("DeepDiscovery", "Pivot email: $email")
            lookupPartials(email, IdentifierType.EMAIL) { partial -> accumulator.add(partial); onPartialResult(partial) }
        }
        if (pivots >= maxPivots) return
        // 3. discovered full name words as username pivot (e.g., "John Doe" -> "johndoe")
        val name = result.name?.trim()
        if (!name.isNullOrBlank() && pivots < maxPivots) {
            val slug = name.lowercase().replace(Regex("[^a-z0-9]"), "")
            if (slug.length in 4..30 && !scanned.contains(slug)) {
                scanned.add(slug); pivots++
                Log.d("DeepDiscovery", "Pivot name->username: $slug")
                lookupPartials(slug, IdentifierType.USERNAME) { partial -> accumulator.add(partial); onPartialResult(partial) }
            }
        }
        if (pivots >= maxPivots) return
        // 4. name -> social verifier (find Facebook/IG/etc by name words then keep only matching profiles)
        if (!name.isNullOrBlank() && name.split(" ").size in 2..4) {
            if (!scanned.contains("name:$name") && pivots < maxPivots) {
                scanned.add("name:$name"); pivots++
                Log.d("DeepDiscovery","Pivot name social: $name")
                lookupPartials(name, IdentifierType.FULL_NAME){ partial -> accumulator.add(partial); onPartialResult(partial) }
            }
        }
        if (pivots >= maxPivots) return
        // 5. image -> social verifier (if image is fbcdn/instagram cdn, pivot to profile)
        val img = result.imageUrl
        if (!img.isNullOrBlank() && img.startsWith("http") && !scanned.contains(img) && pivots < maxPivots) {
            scanned.add(img); pivots++
            Log.d("DeepDiscovery","Pivot image: ${img.take(60)}")
            lookupPartials(img, "IMAGE_URL"){ partial -> accumulator.add(partial); onPartialResult(partial) }
        }
        if (pivots >= maxPivots) return
        result.about?.let { about ->
            Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}").find(about)?.value?.let { foundEmail ->
                if (!scanned.contains(foundEmail) && pivots < maxPivots) { scanned.add(foundEmail); pivots++; Log.d("DeepDiscovery","Pivot about email: $foundEmail"); lookupPartials(foundEmail, IdentifierType.EMAIL){ accumulator.add(it); onPartialResult(it)} }
            }
            Regex("@([A-Za-z0-9_.]{3,30})").find(about)?.groupValues?.getOrNull(1)?.let { foundUser ->
                if (!scanned.contains(foundUser) && pivots < maxPivots && foundUser.length in 3..30) { scanned.add(foundUser); pivots++; Log.d("DeepDiscovery","Pivot about @user: $foundUser"); lookupPartials(foundUser, IdentifierType.USERNAME){ accumulator.add(it); onPartialResult(it)} }
            }
        }
        if (pivots >= maxPivots) return
        val nid = result.nid?.trim()
        if (!nid.isNullOrBlank() && !scanned.contains(nid)) { scanned.add(nid); Log.d("DeepDiscovery","Pivot NID: $nid"); lookupPartials(nid, IdentifierType.NID){ accumulator.add(it); onPartialResult(it)} }
    }

    private fun isSufficientlyDetailed(results: List<PartialResult>): Boolean {
        val hasName = results.any { it.name != null && it.confidence >= 0.85f }
        val hasPhoto = results.any { it.imageUrl != null && it.confidence >= 0.8f }
        return hasName && hasPhoto
    }

    private fun updateRemainingCapabilities(result: PartialResult, remaining: MutableSet<Capability>) {
        // NAME
        if (result.name != null && result.confidence >= 0.85f) {
            remaining.remove(Capability.PUBLIC_SEARCH)
            remaining.remove(Capability.ALTERNATE_NAME)
        }
        
        // PHOTO
        if (result.imageUrl != null && result.confidence >= 0.9f) {
            remaining.remove(Capability.PROFILE_PHOTO)
        }
        
        // METADATA
        if (result.city != null) remaining.remove(Capability.CITY)
        if (result.country != null) remaining.remove(Capability.COUNTRY)
        if (result.carrier != null) remaining.remove(Capability.CARRIER)
        
        // SOCIAL
        if (result.socialProfiles.any { it.platform == "WhatsApp" }) remaining.remove(Capability.WHATSAPP)
        if (result.socialProfiles.any { it.platform == "Telegram" }) remaining.remove(Capability.TELEGRAM)
        if (result.socialProfiles.size >= 3) remaining.remove(Capability.SOCIAL_MATCH)
        
        // EMAIL
        if (result.email != null && result.confidence >= 0.8f) remaining.remove(Capability.EMAIL)
        
        // BUSINESS
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
