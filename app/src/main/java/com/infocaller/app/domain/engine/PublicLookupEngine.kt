package com.infocaller.app.domain.engine

import android.util.Log
import com.infocaller.app.domain.model.LookupResult
import com.infocaller.app.util.ContactUtils
import com.infocaller.app.util.PhoneNumberUtils
import kotlinx.coroutines.*

class PublicLookupEngine(
    private val providerManager: ProviderManager
) : IPublicLookupEngine {
    companion object {
        const val PROVIDER_TIMEOUT_MS = 8000L

        const val MAX_PROVIDERS_PER_SCAN = 24

        const val MAX_PIVOTS = 3

        const val BETWEEN_PROVIDER_DELAY_MS = 120L

        @Volatile
        private var pivotDepth = 0

        private val PIVOT_CAPS = setOf(
            Capability.SOCIAL_MATCH, Capability.PUBLIC_PROFILE,
            Capability.PROFILE_PHOTO, Capability.PUBLIC_SEARCH,
            Capability.SERVICE_PRESENCE, Capability.ABOUT
        )
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

        val allProviders = providerManager.getAllProviders()
            .filter { p ->
                (p.costClass == CostClass.FREE || p.costClass == CostClass.LOW) &&
                    providerManager.getHealth(p.id)?.status != ProviderStatus.BROKEN
            }
        val tc = allProviders.find { it.id.contains("truecaller", ignoreCase = true) }
        val eyecon = allProviders.find { it.id.contains("eyecon", ignoreCase = true) }
        val others = allProviders.filter { it != tc && it != eyecon }
            .sortedWith(compareBy<LookupProvider> { it.costClass }.thenByDescending { it.priority })

        val executionPlan = mutableListOf<LookupProvider>()
        if (type == IdentifierType.PHONE) {
            val phonePrimary = mutableListOf<LookupProvider>()
            tc?.let { phonePrimary.add(it) }
            eyecon?.let { phonePrimary.add(it) }
            val nidFirst = others.filter { it.id == "bd_nid_database" }
                .sortedByDescending { it.priority }
            phonePrimary.addAll(nidFirst)
            val enumerator = others.filter { it.id == "social_account_enumerator" }
            phonePrimary.addAll(enumerator)
            executionPlan.addAll(phonePrimary)
            val primaryIds = phonePrimary.map { it.id }.toSet()
            val socialWaveIds = listOf(
                "telegram_deep", "phone_social_bridge", "social_searcher_phone",
                "social_enum", "callerid_deep_osint", "spam_reputation_scraper",
                "community_spam_csv", "grepapp_code_search", "ai_assist_deep_search"
            )
            val socialWave = socialWaveIds.mapNotNull { id -> others.find { it.id == id } }
            executionPlan.addAll(socialWave)
            val waveIds = (primaryIds + socialWaveIds).toSet()
            val incompatibleForPhone = setOf(
                "email_lookup", "holehe_email", "xposedornot_breach",
                "email_social_bridge", "email_deep_social", "disify_email_validation",
                "sherlock_osint", "whatsmyname",
                "facebook_profile", "tiktok_profile", "instagram_deep",
                "linkedin_profile", "x_profile", "youtube_profile",
                "gaming_profiles", "pinterest_medium_profiles",
                "music_creator", "maigret_sweep", "multi_avatar_harvester",
                "github_osint"
            )
            val rest = others.filter { it.id !in waveIds }
            val (compat, incompat) = rest.partition { it.id !in incompatibleForPhone }
            executionPlan.addAll(compat)
            executionPlan.addAll(incompat)
        } else {
            val typeFirstIds: Set<String> = when (type) {
                IdentifierType.EMAIL -> setOf(
                    "email_lookup", "holehe_email", "xposedornot_breach",
                    "email_social_bridge", "email_deep_social", "github_osint",
                    "social_account_enumerator",
                    "grepapp_code_search", "sherlock_osint", "disify_email_validation",
                    "multi_avatar_harvester",
                    "reverse_image_search", "maigret_sweep",
                    "linkedin_profile", "x_profile",
                    "telegram_deep", "gaming_profiles",
                    "ai_assist_deep_search"
                )
                IdentifierType.USERNAME -> setOf(
                    "sherlock_osint", "github_osint", "whatsmyname",
                    "facebook_profile", "tiktok_profile", "instagram_deep",
                    "linkedin_profile", "x_profile", "youtube_profile",
                    "telegram_deep", "gaming_profiles",
                    "pinterest_medium_profiles", "music_creator",
                    "grepapp_code_search", "maigret_sweep",
                    "multi_avatar_harvester", "reverse_image_search",
                    "pimeyes_photo_pivot", "ai_assist_deep_search"
                )
                IdentifierType.FULL_NAME -> setOf(
                    "whatsmyname", "facebook_profile", "tiktok_profile",
                    "instagram_deep", "linkedin_profile", "x_profile",
                    "youtube_profile", "telegram_deep",
                    "gaming_profiles", "pinterest_medium_profiles",
                    "grepapp_code_search",
                    "maigret_sweep", "reverse_image_search",
                    "ai_assist_deep_search"
                )
                IdentifierType.NID, IdentifierType.DOB -> setOf(
                    "bd_nid_database"
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
        for ((planIndex, provider) in executionPlan.withIndex()) {
            if (attempts >= MAX_PROVIDERS_PER_SCAN) break
            if (alreadyCompletedProviders.contains(provider.id)) {
                try { onProviderStep(provider.id, provider.name, planIndex + 1, planTotal, StepStatus.SKIPPED) } catch (_: Exception) { }
                continue
            }
            val caps = provider.capabilities.toMutableSet()
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
                if (attempts > 1) {
                    try { kotlinx.coroutines.delay(BETWEEN_PROVIDER_DELAY_MS) } catch (_: Exception) { }
                }
                val start = System.currentTimeMillis()
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

                    ensureActive()
                    performDeepDiscovery(finalRes, deepScanned, onPartialResult, finalResults)

                    updateRemainingCapabilities(finalRes, remainingCapabilities)

                    if (isSufficientlyDetailed(finalResults)) break

                    if (remainingCapabilities.isEmpty()) break
                } else {
                    try { onProviderStep(provider.id, provider.name, planIndex + 1, planTotal, StepStatus.FAILED) } catch (_: Exception) { }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
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
        val pivotDone = finalResults.any { r ->
            r.providerId == "face_matched_reverse_search" ||
                ((r.providerId == "reverse_image_search" || r.providerId == "pimeyes_photo_pivot") &&
                    (r.about?.contains("lens.google.com/uploadbyurl", true) == true))
        }
        if (pivotDone) return
        val nameCtx = finalResults.firstNotNullOfOrNull { it.name?.takeIf { n -> n.isNotBlank() } }
        val ctx = LookupContext(foundPhotos = photos, foundName = nameCtx)
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
        if (pivotDepth >= 1) return
        if (scanned.size > 12) return
        var pivots = 0
        val email = result.email?.trim()?.lowercase()
        if (pivots < MAX_PIVOTS && !email.isNullOrBlank() &&
            email.contains("@") && email.substringAfter("@").contains(".") &&
            !email.startsWith("@") && !email.contains(" ") && email.length <= 120 &&
            scanned.add(email)
        ) {
            pivots++
            pivotDepth++
            try {
                lookupPartials(email, IdentifierType.EMAIL, PIVOT_CAPS) { partial ->
                    accumulator.add(partial); onPartialResult(partial)
                }
            } catch (_: Exception) { } finally {
                pivotDepth--
            }
        }
        for (profile in result.socialProfiles) {
            if (pivots >= MAX_PIVOTS) break
            val username = profile.username?.trim()?.removePrefix("@")
            if (username.isNullOrBlank() || username.length !in 3..30 ||
                username.contains(" ") || username.contains("@")
            ) continue
            if (username.all { it.isDigit() }) continue
            if (username.all { it.isDigit() || it == '+' }) continue
            try {
                if (ContactUtils.isPlaceholderName(username)) continue
            } catch (_: Exception) { }
            if (!scanned.add(username)) continue
            pivots++
            pivotDepth++
            try {
                lookupPartials(username, IdentifierType.USERNAME, PIVOT_CAPS) { partial ->
                    accumulator.add(partial); onPartialResult(partial)
                }
            } catch (_: Exception) { } finally {
                pivotDepth--
            }
        }
    }

    private fun isSufficientlyDetailed(results: List<PartialResult>): Boolean {
        val hasName = results.any { !it.name.isNullOrBlank() && it.confidence >= 0.8f }
        val hasPhoto = results.any { !it.imageUrl.isNullOrBlank() && it.confidence >= 0.8f }
        val socials = results.flatMap { it.socialProfiles }.distinctBy { (it.platform.lowercase() + "|" + (it.profileUrl?.lowercase().orEmpty())) }
        val realSocials = socials.count { !isMessagingOnly(it.platform) }
        if (hasName && realSocials >= 1) return true
        if (hasPhoto && realSocials >= 1) return true
        return hasPhoto && realSocials >= 3
    }

    private fun isMessagingOnly(platform: String): Boolean {
        return platform.equals("WhatsApp", true) || platform.equals("Telegram", true)
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
        val realSocials = result.socialProfiles.count { !isMessagingOnly(it.platform) }
        if (realSocials >= 4) remaining.remove(Capability.SOCIAL_MATCH)

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
