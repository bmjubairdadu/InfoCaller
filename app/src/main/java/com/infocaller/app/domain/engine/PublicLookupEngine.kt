package com.infocaller.app.domain.engine

import android.util.Log
import com.infocaller.app.domain.model.LookupResult
import com.infocaller.app.util.PhoneNumberUtils
import kotlinx.coroutines.*


class PublicLookupEngine(
    private val providerManager: ProviderManager
) : IPublicLookupEngine {
    companion object {
        /** Per-provider network timeout (was 15s — radio held open too long). */
        const val PROVIDER_TIMEOUT_MS = 8000L
        /** Max providers tried per scan — bounds radio/CPU per lookup. */
        const val MAX_PROVIDERS_PER_SCAN = 8
        /** Max deep-discovery pivots per scan (was 5 + NID + email/handle mining). */
        const val MAX_PIVOTS = 1
    }
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

        // Heat/lag fix: cap the fan-out. Dozens of providers x deep-discovery
        // pivots x 15s timeouts = sustained radio+CPU = hot phone. Skip BROKEN
        // providers, stop after a small number of attempts, and let the
        // capability/early-exit logic below end the scan as soon as we have
        // a name + photo.
        val allProviders = providerManager.getAllProviders()
            .filter { p ->
                // Only FREE/LOW providers run on-device. HIGH/MEDIUM are
                // opt-in via registration and never auto-run.
                (p.costClass == CostClass.FREE || p.costClass == CostClass.LOW) &&
                    providerManager.getHealth(p.id)?.status != ProviderStatus.BROKEN
            }
        val tc = allProviders.find { it.id.contains("truecaller", ignoreCase = true) }
        val eyecon = allProviders.find { it.id.contains("eyecon", ignoreCase = true) }
        val others = allProviders.filter { it != tc && it != eyecon }
            .sortedWith(compareBy<LookupProvider> { it.costClass }.thenByDescending { it.priority })

        val executionPlan = mutableListOf<LookupProvider>()
        // Truecaller/Eyecon are PHONE-only and null out instantly for other types —
        // but each still costs one of the 8 scan attempts. Only prioritize them for
        // PHONE scans so EMAIL scans spend attempts on email-capable providers.
        // Same problem in general: phone-only providers sort first by priority and
        // would burn all 8 attempts on instant nulls for EMAIL/USERNAME/NID scans.
        // Fix: for non-phone scans, run the providers that actually handle that
        // type first (verified per-file: which IdentifierType each lookup accepts).
        if (type == IdentifierType.PHONE) {
            tc?.let { executionPlan.add(it) }
            eyecon?.let { executionPlan.add(it) }
            executionPlan.addAll(others)
        } else {
            val typeFirstIds: Set<String> = when (type) {
                IdentifierType.EMAIL -> setOf(
                    "email_lookup", "holehe_email", "xposedornot_breach",
                    "email_social_bridge", "github_osint", "grepapp_code_search",
                    "sherlock_osint", "disify_email_validation"
                )
                IdentifierType.USERNAME -> setOf(
                    "sherlock_osint", "github_osint", "whatsmyname",
                    "facebook_profile", "tiktok_profile", "instagram_deep",
                    "grepapp_code_search"
                )
                IdentifierType.FULL_NAME -> setOf(
                    "whatsmyname", "facebook_profile", "tiktok_profile",
                    "instagram_deep", "name_social_verifier", "grepapp_code_search"
                )
                IdentifierType.NID, IdentifierType.DOB -> setOf(
                    "bd_nid_database", "nid_gov_enrichment"
                )
                else -> emptySet()
            }
            val (typeFirst, typeRest) = others.partition { it.id in typeFirstIds }
            executionPlan.addAll(typeFirst.sortedByDescending { it.priority })
            executionPlan.addAll(typeRest)
        }

        var photoFound = false
        var nameFound = false
        var attempts = 0
        for (provider in executionPlan) {
            if (attempts >= MAX_PROVIDERS_PER_SCAN) break
            if (alreadyCompletedProviders.contains(provider.id)) continue
            val caps = provider.capabilities.toMutableSet()
            if (photoFound) caps.remove(Capability.PROFILE_PHOTO)
            if (nameFound) { caps.remove(Capability.PUBLIC_SEARCH); caps.remove(Capability.ALTERNATE_NAME); caps.remove(Capability.PUBLIC_PROFILE) }
            val usefulCapabilities = caps.intersect(remainingCapabilities)
            if (usefulCapabilities.isEmpty()) {
                if (caps.isEmpty()) continue
                continue
            }

            attempts++
            try {
                val start = System.currentTimeMillis()
                val result = withTimeoutOrNull(PROVIDER_TIMEOUT_MS) {
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
                providerManager.reportResult(provider.id, false, PROVIDER_TIMEOUT_MS)
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
        // Heat fix: deep discovery used to fan out up to 5 pivots + NID/email/
        // handle mining per provider hit — each pivot re-runs the provider set.
        // Cap total pivots and only follow high-signal username pivots.
        if (scanned.size > 10) return
        var pivots = 0
        val maxPivots = MAX_PIVOTS
        for (profile in result.socialProfiles) {
            if (pivots >= maxPivots) break
            val username = profile.username?.trim()
            if (!username.isNullOrBlank() && !scanned.contains(username) && username.length in 3..30 && !username.contains(" ")) {
                scanned.add(username); pivots++
                lookupPartials(username, IdentifierType.USERNAME) { partial -> accumulator.add(partial); onPartialResult(partial) }
            }
            if (pivots >= maxPivots) break
        }
        // Heat fix: handle/email/slug/full-name/image/about/NID pivots removed.
        // Each re-ran the provider set for near-zero signal and multiplied
        // radio + ML cost per scan. Username pivots above are the only
        // high-signal follow-ups retained.
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
