package com.infocaller.app.domain.engine

import android.util.Log
import com.infocaller.app.domain.model.LookupResult
import com.infocaller.app.util.PhoneNumberUtils
import kotlinx.coroutines.*


class PublicLookupEngine(
    private val providerManager: ProviderManager
) : IPublicLookupEngine {
    companion object {
        /** Fast-scan budget: each tool gets at most 5s. The old 7-15s budget
         *  let a single slow scraper stall the whole serial chain, so every
         *  scan felt like "many time". 5s is enough for Truecaller/Eyecon
         *  and local DB hits (the common case) while slow pages fail fast. */
        const val PROVIDER_TIMEOUT_MS = 5000L
        /** Only the highest-signal tools run in the foreground pass:
         *  Truecaller + Eyecon + NID + the next best 1-2 providers. Anything
         *  deeper continues as background enrichment instead of blocking UI. */
        const val MAX_PROVIDERS_PER_SCAN = 5
        /** Recursive username pivots re-ran the WHOLE provider set per hit
         *  (7 providers x 7s = ~49s per pivot) inside the foreground scan.
         *  Disabled here (0): pivots now belong to background enrichment. */
        const val MAX_PIVOTS = 0
        /** Minimal radio gap between tools: 120ms keeps sockets clean
         *  without adding seconds of pure waiting to every scan. */
        const val BETWEEN_PROVIDER_DELAY_MS = 120L
    }
    override suspend fun performLookup(
        identifier: String,
        type: String,
        requiredCapabilities: Set<Capability>,
        alreadyCompletedProviders: Set<String>,
        onPartialResult: suspend (PartialResult) -> Unit,
        onProviderStep: suspend (providerId: String, providerName: String, stepIndex: Int, stepTotal: Int, status: StepStatus) -> Unit
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
            // Explicit phone-first ordering: Truecaller and Eyecon must run before
            // the rest of the providers so the strongest phone identity sources are
            // attempted first, then NID, then the remaining scrapers.
            val phonePrimary = mutableListOf<LookupProvider>()
            tc?.let { phonePrimary.add(it) }
            eyecon?.let { phonePrimary.add(it) }
            val nidFirst = others.filter { it.id == "bd_nid_database" || it.id == "nid_gov_enrichment" || it.id == "nid_smartcard_auto" }
                .sortedByDescending { it.priority }
            phonePrimary.addAll(nidFirst)
            // Multi-account social enumeration runs right after identity: one
            // number/email can own many accounts and the old plan often
            // stopped at WhatsApp-only rows before reaching these.
            val enumerator = others.filter { it.id == "social_account_enumerator" }
            phonePrimary.addAll(enumerator)
            executionPlan.addAll(phonePrimary)
            executionPlan.addAll(others.filter { it.id != "bd_nid_database" && it.id != "nid_gov_enrichment" && it.id != "nid_smartcard_auto" && it.id != "social_account_enumerator" })
        } else {
            val typeFirstIds: Set<String> = when (type) {
                IdentifierType.EMAIL -> setOf(
                    "email_lookup", "holehe_email", "xposedornot_breach",
                    "email_social_bridge", "email_deep_social", "github_osint",
                    "social_account_enumerator",
                    "grepapp_code_search", "sherlock_osint", "disify_email_validation",
                    "hudsonrock_email_intel", "multi_avatar_harvester",
                    "reverse_image_search", "maigret_sweep",
                    "linkedin_profile", "x_profile", "reddit_profile",
                    "telegram_deep", "gaming_profiles",
                    "ai_assist_deep_search"
                )
                IdentifierType.USERNAME -> setOf(
                    "sherlock_osint", "github_osint", "whatsmyname",
                    "facebook_profile", "tiktok_profile", "instagram_deep",
                    "linkedin_profile", "x_profile", "youtube_profile",
                    "reddit_profile", "telegram_deep", "gaming_profiles",
                    "pinterest_medium_profiles", "music_creator",
                    "grepapp_code_search", "maigret_sweep",
                    "multi_avatar_harvester", "reverse_image_search",
                    "pimeyes_photo_pivot", "ai_assist_deep_search"
                )
                IdentifierType.FULL_NAME -> setOf(
                    "whatsmyname", "facebook_profile", "tiktok_profile",
                    "instagram_deep", "linkedin_profile", "x_profile",
                    "youtube_profile", "reddit_profile", "telegram_deep",
                    "gaming_profiles", "pinterest_medium_profiles",
                    "name_social_verifier", "grepapp_code_search",
                    "maigret_sweep", "reverse_image_search",
                    "ai_assist_deep_search"
                )
                // No NID scan path exists (search is phone-number only) — this
                // ordering is dead but kept so a future caller fails safe.
                IdentifierType.NID, IdentifierType.DOB -> setOf(
                    "nid_gov_enrichment", "bd_nid_database"
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
        val planTotal = executionPlan.size.coerceAtMost(MAX_PROVIDERS_PER_SCAN).coerceAtLeast(1)
        // STRICTLY ONE-BY-ONE: this loop awaits each provider's lookup fully
        // before the next one starts. No async{} fan-out anywhere in the scan
        // path — simultaneous API bursts were crashing release builds, so one
        // tool completes (success/timeout/error) before the next begins.
        for ((planIndex, provider) in executionPlan.withIndex()) {
            if (attempts >= MAX_PROVIDERS_PER_SCAN) break
            if (alreadyCompletedProviders.contains(provider.id)) {
                try { onProviderStep(provider.id, provider.name, planIndex + 1, planTotal, StepStatus.SKIPPED) } catch (_: Exception) { }
                continue
            }
            val caps = provider.capabilities.toMutableSet()
            // Photo pivots (reverse-image / face-search) run BECAUSE a photo was
            // found — never prune them as "photo already covered". All other
            // photo providers are pruned once a photo exists.
            val isPhotoPivot = provider.id == "reverse_image_search" ||
                provider.id == "pimeyes_photo_pivot"
            if (photoFound && !isPhotoPivot) caps.remove(Capability.PROFILE_PHOTO)
            if (nameFound) { caps.remove(Capability.PUBLIC_SEARCH); caps.remove(Capability.ALTERNATE_NAME); caps.remove(Capability.PUBLIC_PROFILE) }
            val usefulCapabilities = caps.intersect(remainingCapabilities)
            if (usefulCapabilities.isEmpty()) {
                if (caps.isEmpty()) continue
                continue
            }

            attempts++
            try {
                try { onProviderStep(provider.id, provider.name, planIndex + 1, planTotal, StepStatus.RUNNING) } catch (_: Exception) { }
                // Settle pause: the previous tool's connection fully closes and
                // the radio idles before the next API starts — cleaner responses,
                // no half-open socket reuse. Skipped before the first attempt.
                if (attempts > 1) {
                    try { kotlinx.coroutines.delay(BETWEEN_PROVIDER_DELAY_MS) } catch (_: Exception) { }
                }
                val start = System.currentTimeMillis()
                // Deep-photo context: every provider sees the photos found so
                // far (primary urls + candidates + social avatars), so
                // reverse-image / face-search / avatar tools auto-run against
                // the real photo the moment ANY api finds one.
                val photoCtx = finalResults
                    .flatMap { r ->
                        listOfNotNull(r.imageUrl) + r.photoCandidates.map { it.url } +
                            r.socialProfiles.mapNotNull { it.avatarUrl }
                    }
                    .filter { it.startsWith("http") }.distinct().take(5)
                val nameCtx = finalResults.firstNotNullOfOrNull { it.name?.takeIf { n -> n.isNotBlank() } }
                val ctx = LookupContext(foundPhotos = photoCtx, foundName = nameCtx)
                val result = withTimeoutOrNull(PROVIDER_TIMEOUT_MS) {
                    provider.lookup(normalized, type = type, context = ctx)
                }

                if (result != null) {
                    try { onProviderStep(provider.id, provider.name, planIndex + 1, planTotal, StepStatus.SUCCESS) } catch (_: Exception) { }
                    val duration = System.currentTimeMillis() - start
                    val finalRes = result.copy(durationMs = duration, identifier = normalized, identifierType = type)
                    providerManager.reportResult(provider.id, true, duration)

                    finalResults.add(finalRes)
                    onPartialResult(finalRes)
                    if (!finalRes.name.isNullOrBlank()) nameFound = true
                    if (!finalRes.imageUrl.isNullOrBlank() || finalRes.photoCandidates.isNotEmpty() ||
                        finalRes.socialProfiles.any { !it.avatarUrl.isNullOrBlank() }
                    ) photoFound = true

                    // Deep-discovery pivots run under the same cooperative cancellation
                    // so a cancelled call-path scan stops promptly.
                    ensureActive()
                    performDeepDiscovery(finalRes, deepScanned, onPartialResult, finalResults)

                    updateRemainingCapabilities(finalRes, remainingCapabilities)

                    if (isSufficientlyDetailed(finalResults)) break

                    if (remainingCapabilities.isEmpty()) break
                } else {
                    try { onProviderStep(provider.id, provider.name, planIndex + 1, planTotal, StepStatus.FAILED) } catch (_: Exception) { }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                // Never swallow cancellation — the call path must stay cancellable.
                throw e
            } catch (e: Exception) {
                try { onProviderStep(provider.id, provider.name, planIndex + 1, planTotal, StepStatus.FAILED) } catch (_: Exception) { }
                providerManager.reportResult(provider.id, false, PROVIDER_TIMEOUT_MS)
            }
        }

        try {
            ensureActive()
            runAutoPhotoOsint(normalized, type, finalResults, onPartialResult, onProviderStep)
        } catch (_: Exception) { }

        ConfidenceEngine.merge(normalized, finalResults)
    }

    private suspend fun runAutoPhotoOsint(
        normalized: String,
        type: String,
        finalResults: MutableList<PartialResult>,
        onPartialResult: suspend (PartialResult) -> Unit,
        onProviderStep: suspend (providerId: String, providerName: String, stepIndex: Int, stepTotal: Int, status: StepStatus) -> Unit
    ) {
        val photos = finalResults
            .flatMap { r ->
                listOfNotNull(r.imageUrl) + r.photoCandidates.map { it.url } +
                    r.socialProfiles.mapNotNull { it.avatarUrl }
            }
            .filter { it.startsWith("http") }.distinct().take(5)
        if (photos.isEmpty()) return
        // Skip when the face-matched pass already ran (it subsumes the
        // legacy link-only pivots with verified HD photos).
        val pivotDone = finalResults.any { r ->
            r.providerId == "face_matched_reverse_search" ||
                ((r.providerId == "reverse_image_search" || r.providerId == "pimeyes_photo_pivot") &&
                    (r.about?.contains("lens.google.com/uploadbyurl", true) == true))
        }
        if (pivotDone) return
        val nameCtx = finalResults.firstNotNullOfOrNull { it.name?.takeIf { n -> n.isNotBlank() } }
        val ctx = LookupContext(foundPhotos = photos, foundName = nameCtx)
        // Face-matched HD pass FIRST (verifies faces on-device, upgrades to
        // full-HD, emits Lens/TinEye/Bing links); legacy link-only pivots
        // only run when it finds no face.
        val pivots = providerManager.getAllProviders().filter {
            (it.id == "face_matched_reverse_search" || it.id == "reverse_image_search" || it.id == "pimeyes_photo_pivot") &&
                providerManager.getHealth(it.id)?.status != ProviderStatus.BROKEN
        }.sortedWith(
            compareBy<LookupProvider> { if (it.id == "face_matched_reverse_search") 0 else 1 }
                .thenByDescending { it.priority }
        ).take(3)
        var faceMatched = false
        pivots.forEachIndexed { i, pivot ->
            try {
                try { onProviderStep(pivot.id, pivot.name, i + 1, pivots.size, StepStatus.RUNNING) } catch (_: Exception) { }
                val res = withTimeoutOrNull(if (pivot.id == "face_matched_reverse_search") 20_000L else PROVIDER_TIMEOUT_MS) {
                    pivot.lookup(normalized, type = type, context = ctx)
                }
                if (res != null) {
                    try { onProviderStep(pivot.id, pivot.name, i + 1, pivots.size, StepStatus.SUCCESS) } catch (_: Exception) { }
                    val finalRes = res.copy(durationMs = 0L, identifier = normalized, identifierType = type)
                    finalResults.add(finalRes)
                    onPartialResult(finalRes)
                    if (pivot.id == "face_matched_reverse_search" &&
                        (res.photoCandidates.any { it.faceCount > 0 } || res.confidence >= 0.8f)
                    ) {
                        // Faces verified + HD links emitted: legacy link-only
                        // pivots would only duplicate the same Lens URLs.
                        faceMatched = true
                        return
                    }
                } else {
                    try { onProviderStep(pivot.id, pivot.name, i + 1, pivots.size, StepStatus.FAILED) } catch (_: Exception) { }
                }
            } catch (_: Exception) {
                try { onProviderStep(pivot.id, pivot.name, i + 1, pivots.size, StepStatus.FAILED) } catch (_: Exception) { }
            }
            if (faceMatched) return
        }
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
        // Fast-scan exit: a confident name (Truecaller/Eyecon hit) is enough
        // to show the caller immediately — BUT only when social enumeration
        // has also run (or definitively failed). The old rule exited on name
        // alone, which is exactly why scans showed WhatsApp-only rows while
        // Facebook/Instagram/TikTok matches never got attempted.
        val hasName = results.any { !it.name.isNullOrBlank() && it.confidence >= 0.8f }
        val hasPhoto = results.any { !it.imageUrl.isNullOrBlank() && it.confidence >= 0.8f }
        val socials = results.flatMap { it.socialProfiles }.distinctBy { (it.platform.lowercase() + "|" + (it.profileUrl?.lowercase().orEmpty())) }
        val realSocials = socials.count {
            !it.platform.equals("WhatsApp", true) && !it.platform.equals("Telegram", true)
        }
        val enumeratorRan = results.any { it.providerId == "social_account_enumerator" }
        if (hasName && (enumeratorRan || realSocials >= 1)) return true
        if (hasPhoto && realSocials >= 1) return true
        return hasPhoto && socials.size >= 3
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
        performLookup(
            identifier = identifier,
            type = type,
            requiredCapabilities = requiredCapabilities,
            onPartialResult = {
                results.add(it)
                onPartialResult(it)
            }
        )
        results
    }
}
