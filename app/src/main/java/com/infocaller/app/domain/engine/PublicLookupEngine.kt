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
        const val PROVIDER_TIMEOUT_MS = 5000L

        const val MAX_PROVIDERS_PER_SCAN = 8

        const val MAX_PIVOTS = 3

        const val MAX_PIVOT_SCANNED = 24

        const val LAST_RESORT_PROVIDER_ID = "apify_backend_photo"

        const val LAST_RESORT_TIMEOUT_MS = 45000L

        const val BETWEEN_PROVIDER_DELAY_MS = 150L

        private val PIVOT_CAPS = setOf(
            Capability.SOCIAL_MATCH, Capability.PUBLIC_PROFILE,
            Capability.PROFILE_PHOTO, Capability.PUBLIC_SEARCH,
            Capability.SERVICE_PRESENCE, Capability.ABOUT
        )

        /**
         * Identity sources whose photos are trustworthy seeds for reverse-image
         * search (Truecaller pic, database pic, email pics). Everything else
         * (random social thumbnails, logos) is excluded to avoid fake matches.
         */
        private val TRUSTED_REVERSE_PROVIDER_IDS = setOf(
            "truecaller_authorized",
            "eyecon_authorized",
            "email_lookup",
            "email_avatar_bridge",
            "email_deep_social",
            "email_social_bridge",
            "bd_nid_database",
            "apify_backend_photo",
            "multi_avatar_harvester",
            "social_account_enumerator",
        )
    }

    private fun isTrustedReversePhoto(url: String, providerLabel: String?): Boolean {
        return try {
            val u = url.trim()
            if (u.isBlank()) return false
            val p = providerLabel?.trim()?.lowercase().orEmpty()
            if (p.contains("truecaller") || p.contains("eyecon") || p.contains("gravatar") ||
                p.contains("github") || p.contains("gitlab") || p.contains("unavatar") ||
                p.contains("email") || p.contains("database") || p.contains("user")
            ) return true
            val lower = u.lowercase()
            if (lower.startsWith("file://") && lower.contains("eyecon")) return true
            if (lower.contains("gravatar.com/avatar") || lower.contains("secure.gravatar.com") ||
                lower.contains("avatars.githubusercontent.com") || lower.contains("unavatar.io")
            ) return true
            false
        } catch (_: Exception) { false } catch (_: Error) { false }
    }

    private fun trustedReversePhotos(results: List<PartialResult>): List<String> {
        return try {
            val out = linkedSetOf<String>()
            for (r in results) {
                val pid = try { r.providerId } catch (_: Exception) { null } catch (_: Error) { null }
                val src = try { r.source } catch (_: Exception) { null } catch (_: Error) { null }
                val trustedResult = (pid != null && TRUSTED_REVERSE_PROVIDER_IDS.contains(pid)) ||
                    isTrustedReversePhoto("", src) || isTrustedReversePhoto("", pid)
                for (c in try { r.photoCandidates } catch (_: Exception) { emptyList() } catch (_: Error) { emptyList() }) {
                    val u = try { c.url.trim() } catch (_: Exception) { "" } catch (_: Error) { "" }
                    if (u.isBlank()) continue
                    if (!(u.startsWith("http") || u.startsWith("file://"))) continue
                    val prov = try { c.provider } catch (_: Exception) { null } catch (_: Error) { null }
                    if (trustedResult || isTrustedReversePhoto(u, prov)) out.add(u)
                    if (out.size >= 3) return out.toList()
                }
                val img = try { r.imageUrl?.trim() } catch (_: Exception) { null } catch (_: Error) { null }
                if (!img.isNullOrBlank() && (img.startsWith("http") || img.startsWith("file://"))) {
                    if (trustedResult || isTrustedReversePhoto(img, src)) out.add(img)
                    if (out.size >= 3) return out.toList()
                }
            }
            out.toList()
        } catch (_: Exception) { emptyList() } catch (_: Error) { emptyList() }
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
        val normalized = try {
            if (type == IdentifierType.PHONE) PhoneNumberUtils.normalize(identifier) else identifier.trim().take(120)
        } catch (_: Exception) { identifier } catch (_: Error) { identifier }
        if (normalized.isBlank()) {
            return@coroutineScope com.infocaller.app.domain.model.LookupResult(phoneNumber = identifier.take(40))
        }

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
        val others = allProviders.filter { it != tc && it != eyecon && it.id != LAST_RESORT_PROVIDER_ID }
            .sortedWith(compareBy<LookupProvider> { it.costClass }.thenByDescending { it.priority })

        val executionPlan = mutableListOf<LookupProvider>()
        if (type == IdentifierType.PHONE) {
            val phonePrimary = mutableListOf<LookupProvider>()
            val phoneDigits = normalized.filter { it.isDigit() }
            val isBangladeshNumber = phoneDigits.startsWith("880") ||
                (phoneDigits.length == 11 && phoneDigits.startsWith("01"))
            if (isBangladeshNumber) {
                phonePrimary.addAll(others.filter { it.id == "bd_number_intel" })
            }
            tc?.let { phonePrimary.add(it) }
            eyecon?.let { phonePrimary.add(it) }
            val nidFirst = others.filter { it.id == "bd_nid_database" }
                .sortedByDescending { it.priority }
            phonePrimary.addAll(nidFirst)
            val enumerator = others.filter { it.id == "social_account_enumerator" }
            phonePrimary.addAll(enumerator)
            val namePivot = others.filter { it.id == "name_photo_social_pivot" }
            phonePrimary.addAll(namePivot)
            executionPlan.addAll(phonePrimary)
            val primaryIds = phonePrimary.map { it.id }.toSet()
            val socialWaveIds = listOf(
                "telegram_deep", "phone_social_bridge", "social_searcher_phone",
                "social_enum", "callerid_deep_osint", "spam_reputation_scraper",
                "grepapp_code_search", "ai_assist_deep_search"
            )
            val socialWave = socialWaveIds.mapNotNull { id -> others.find { it.id == id } }
            executionPlan.addAll(socialWave)
            val waveIds = (primaryIds + socialWaveIds).toSet()
            val incompatibleForPhone = setOf(
                "email_lookup", "email_avatar_bridge", "holehe_email", "xposedornot_breach",
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
                    "email_lookup", "email_avatar_bridge", "holehe_email", "xposedornot_breach",
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
                    "name_photo_social_pivot",
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
                try { onProviderStep(provider.id, provider.name, planIndex + 1, planTotal, StepStatus.RUNNING) } catch (_: Exception) { } catch (_: Error) { }
                if (attempts > 1) {
                    try { kotlinx.coroutines.delay(BETWEEN_PROVIDER_DELAY_MS) } catch (_: Exception) { } catch (_: Error) { }
                }
                val start = System.currentTimeMillis()
                val photoCtx = try {
                    finalResults
                        .flatMap { r ->
                            listOfNotNull(r.imageUrl) + r.photoCandidates.map { it.url } +
                                r.socialProfiles.mapNotNull { it.avatarUrl }
                        }
                        .filter { it.startsWith("http") || it.startsWith("file://") }.distinct().take(3)
                } catch (_: Exception) { emptyList() } catch (_: Error) { emptyList() }
                val trustedCtx = try { trustedReversePhotos(finalResults) } catch (_: Exception) { emptyList() } catch (_: Error) { emptyList() }
                val nameCtx = try { finalResults.firstNotNullOfOrNull { it.name?.takeIf { n -> n.isNotBlank() } }?.take(80) } catch (_: Exception) { null } catch (_: Error) { null }
                val ctx = LookupContext(foundPhotos = photoCtx, trustedPhotos = trustedCtx, foundName = nameCtx)
                val result = try {
                    withTimeoutOrNull(PROVIDER_TIMEOUT_MS) {
                        try { provider.lookup(normalized, type = type, context = ctx) } catch (_: Exception) { null } catch (_: Error) { null }
                    }
                } catch (_: Exception) { null } catch (_: Error) { null }

                if (result != null) {
                    try { onProviderStep(provider.id, provider.name, planIndex + 1, planTotal, StepStatus.SUCCESS) } catch (_: Exception) { } catch (_: Error) { }
                    val duration = try { System.currentTimeMillis() - start } catch (_: Exception) { 0L } catch (_: Error) { 0L }
                    val finalRes = try {
                        val capped = result.copy(
                            name = result.name?.trim()?.take(80)?.takeIf { it.isNotBlank() },
                            imageUrl = result.imageUrl?.trim()?.take(2000)?.takeIf { it.startsWith("http") || it.startsWith("file://") },
                            photoCandidates = result.photoCandidates.take(6),
                            socialProfiles = result.socialProfiles.take(12),
                            about = result.about?.take(500),
                            durationMs = duration, identifier = normalized, identifierType = type
                        )
                        capped
                    } catch (_: Exception) { result } catch (_: Error) { result }
                    try { providerManager.reportResult(provider.id, true, duration) } catch (_: Exception) { } catch (_: Error) { }

                    try { finalResults.add(finalRes) } catch (_: Exception) { } catch (_: Error) { }
                    try { onPartialResult(finalRes) } catch (_: Exception) { } catch (_: Error) { }
                    try {
                        if (!finalRes.name.isNullOrBlank()) nameFound = true
                        if (!finalRes.imageUrl.isNullOrBlank() || finalRes.photoCandidates.isNotEmpty() ||
                            finalRes.socialProfiles.any { !it.avatarUrl.isNullOrBlank() }
                        ) photoFound = true
                    } catch (_: Exception) { } catch (_: Error) { }

                    ensureActive()
                    try { performDeepDiscovery(finalRes, deepScanned, onPartialResult, finalResults) } catch (_: Exception) { } catch (_: Error) { }

                    try { updateRemainingCapabilities(finalRes, remainingCapabilities) } catch (_: Exception) { } catch (_: Error) { }

                    try { if (isSufficientlyDetailed(finalResults)) break } catch (_: Exception) { } catch (_: Error) { }

                    try { if (remainingCapabilities.isEmpty()) break } catch (_: Exception) { } catch (_: Error) { }
                } else {
                    try { onProviderStep(provider.id, provider.name, planIndex + 1, planTotal, StepStatus.FAILED) } catch (_: Exception) { } catch (_: Error) { }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Error) {
                try { onProviderStep(provider.id, provider.name, planIndex + 1, planTotal, StepStatus.FAILED) } catch (_: Exception) { } catch (_: Error) { }
                try { providerManager.reportResult(provider.id, false, PROVIDER_TIMEOUT_MS) } catch (_: Exception) { } catch (_: Error) { }
            } catch (e: Exception) {
                try { onProviderStep(provider.id, provider.name, planIndex + 1, planTotal, StepStatus.FAILED) } catch (_: Exception) { } catch (_: Error) { }
                try { providerManager.reportResult(provider.id, false, PROVIDER_TIMEOUT_MS) } catch (_: Exception) { } catch (_: Error) { }
            }
        }

        try {
            ensureActive()
            runAutoPhotoOsint(normalized, type, finalResults, onPartialResult, onProviderStep)
        } catch (_: Exception) { } catch (_: Error) { }

        try {
            ensureActive()
            runLastResortFallback(normalized, type, finalResults, onPartialResult, onProviderStep)
        } catch (_: Exception) { } catch (_: Error) { }

        try {
            ConfidenceEngine.merge(normalized, finalResults)
        } catch (_: Error) {
            try { ConfidenceEngine.merge(normalized, finalResults.take(4)) } catch (_: Exception) { com.infocaller.app.domain.model.LookupResult(phoneNumber = normalized) } catch (_: Error) { com.infocaller.app.domain.model.LookupResult(phoneNumber = normalized) }
        } catch (e: Exception) {
            try { ConfidenceEngine.merge(normalized, finalResults.take(8)) } catch (_: Exception) { com.infocaller.app.domain.model.LookupResult(phoneNumber = normalized) } catch (_: Error) { com.infocaller.app.domain.model.LookupResult(phoneNumber = normalized) }
        }
    }

    private suspend fun runAutoPhotoOsint(
        normalized: String,
        type: String,
        finalResults: MutableList<PartialResult>,
        onPartialResult: suspend (PartialResult) -> Unit,
        onProviderStep: suspend (providerId: String, providerName: String, stepIndex: Int, stepTotal: Int, status: StepStatus) -> Unit
    ) {
        try {
            if (type == IdentifierType.PHONE && finalResults.none {
                try { !it.imageUrl.isNullOrBlank() || it.photoCandidates.isNotEmpty() } catch (_: Exception) { false } catch (_: Error) { false }
            }) return
        } catch (_: Exception) { return } catch (_: Error) { return }
        val photos = try {
            finalResults
                .flatMap { r ->
                    listOfNotNull(r.imageUrl) + r.photoCandidates.map { it.url } +
                        r.socialProfiles.mapNotNull { it.avatarUrl }
                }
                .filter { it.startsWith("http") || it.startsWith("file://") }.distinct().take(3)
        } catch (_: Exception) { emptyList() } catch (_: Error) { emptyList() }
        if (photos.isEmpty()) return
        val pivotDone = try {
            finalResults.any { r ->
                r.providerId == "face_matched_reverse_search" ||
                    ((r.providerId == "reverse_image_search" || r.providerId == "pimeyes_photo_pivot") &&
                        (r.about?.contains("lens.google.com/uploadbyurl", true) == true))
            }
        } catch (_: Exception) { true } catch (_: Error) { true }
        if (pivotDone) return
        val nameCtx = try { finalResults.firstNotNullOfOrNull { it.name?.takeIf { n -> n.isNotBlank() } }?.take(80) } catch (_: Exception) { null } catch (_: Error) { null }
        val trustedCtx = try { trustedReversePhotos(finalResults) } catch (_: Exception) { emptyList() } catch (_: Error) { emptyList() }
        val ctx = LookupContext(foundPhotos = photos, trustedPhotos = trustedCtx, foundName = nameCtx)
        val pivots = try {
            providerManager.getAllProviders().filter {
                (it.id == "face_matched_reverse_search" || it.id == "reverse_image_search" || it.id == "pimeyes_photo_pivot") &&
                    providerManager.getHealth(it.id)?.status != ProviderStatus.BROKEN
            }.sortedWith(
                compareBy<LookupProvider> { if (it.id == "face_matched_reverse_search") 0 else 1 }
                    .thenByDescending { it.priority }
            ).take(1)
        } catch (_: Exception) { emptyList() } catch (_: Error) { emptyList() }
        var faceMatched = false
        pivots.forEachIndexed { i, pivot ->
            try {
                try { onProviderStep(pivot.id, pivot.name, i + 1, pivots.size, StepStatus.RUNNING) } catch (_: Exception) { } catch (_: Error) { }
                val res = try {
                    withTimeoutOrNull(8000L) {
                        try { pivot.lookup(normalized, type = type, context = ctx) } catch (_: Exception) { null } catch (_: Error) { null }
                    }
                } catch (_: Exception) { null } catch (_: Error) { null }
                if (res != null) {
                    try { onProviderStep(pivot.id, pivot.name, i + 1, pivots.size, StepStatus.SUCCESS) } catch (_: Exception) { } catch (_: Error) { }
                    val finalRes = try {
                        res.copy(
                            name = res.name?.take(80),
                            imageUrl = res.imageUrl?.take(2000)?.takeIf { it.startsWith("http") || it.startsWith("file://") },
                            photoCandidates = res.photoCandidates.take(6),
                            socialProfiles = res.socialProfiles.take(12),
                            about = res.about?.take(500),
                            durationMs = 0L, identifier = normalized, identifierType = type
                        )
                    } catch (_: Exception) { res } catch (_: Error) { res }
                    try { finalResults.add(finalRes) } catch (_: Exception) { } catch (_: Error) { }
                    try { onPartialResult(finalRes) } catch (_: Exception) { } catch (_: Error) { }
                    try {
                        if (pivot.id == "face_matched_reverse_search" &&
                            (res.photoCandidates.any { it.faceCount > 0 } || res.confidence >= 0.8f)
                        ) {
                            faceMatched = true
                            return
                        }
                    } catch (_: Exception) { } catch (_: Error) { }
                } else {
                    try { onProviderStep(pivot.id, pivot.name, i + 1, pivots.size, StepStatus.FAILED) } catch (_: Exception) { } catch (_: Error) { }
                }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Error) {
                try { onProviderStep(pivot.id, pivot.name, i + 1, pivots.size, StepStatus.FAILED) } catch (_: Exception) { } catch (_: Error) { }
            } catch (_: Exception) {
                try { onProviderStep(pivot.id, pivot.name, i + 1, pivots.size, StepStatus.FAILED) } catch (_: Exception) { } catch (_: Error) { }
            }
            if (faceMatched) return
        }
    }

    private suspend fun runLastResortFallback(
        normalized: String,
        type: String,
        finalResults: MutableList<PartialResult>,
        onPartialResult: suspend (PartialResult) -> Unit,
        onProviderStep: suspend (providerId: String, providerName: String, stepIndex: Int, stepTotal: Int, status: StepStatus) -> Unit
    ) {
        if (type != IdentifierType.PHONE) return
        val alreadyHavePhoto = try {
            finalResults.any { r ->
                !r.imageUrl.isNullOrBlank() ||
                    r.photoCandidates.any { it.url.isNotBlank() } ||
                    r.socialProfiles.any { !it.avatarUrl.isNullOrBlank() }
            }
        } catch (_: Exception) { true } catch (_: Error) { true }
        if (alreadyHavePhoto) return

        val provider = try {
            providerManager.getAllProviders().firstOrNull { it.id == LAST_RESORT_PROVIDER_ID }
        } catch (_: Exception) { null } catch (_: Error) { null } ?: return
        if (try { providerManager.getHealth(provider.id)?.status == ProviderStatus.BROKEN } catch (_: Exception) { false }) return

        try { onProviderStep(provider.id, provider.name, 1, 1, StepStatus.RUNNING) } catch (_: Exception) { } catch (_: Error) { }
        val started = System.currentTimeMillis()
        val result = try {
            withTimeoutOrNull(LAST_RESORT_TIMEOUT_MS) {
                try { provider.lookup(normalized, type = type, context = LookupContext()) } catch (_: Exception) { null } catch (_: Error) { null }
            }
        } catch (_: Exception) { null } catch (_: Error) { null }

        if (result == null) {
            try { onProviderStep(provider.id, provider.name, 1, 1, StepStatus.FAILED) } catch (_: Exception) { } catch (_: Error) { }
            return
        }
        val duration = System.currentTimeMillis() - started
        val capped = try {
            result.copy(
                name = result.name?.trim()?.take(80)?.takeIf { it.isNotBlank() },
                imageUrl = result.imageUrl?.trim()?.take(2000)?.takeIf { it.startsWith("http") },
                photoCandidates = result.photoCandidates.take(6),
                about = result.about?.take(500),
                durationMs = duration,
                identifier = normalized,
                identifierType = type
            )
        } catch (_: Exception) { result } catch (_: Error) { result }
        try { providerManager.reportResult(provider.id, true, duration) } catch (_: Exception) { } catch (_: Error) { }
        try { finalResults.add(capped) } catch (_: Exception) { } catch (_: Error) { }
        try { onPartialResult(capped) } catch (_: Exception) { } catch (_: Error) { }
        try { onProviderStep(provider.id, provider.name, 1, 1, StepStatus.SUCCESS) } catch (_: Exception) { } catch (_: Error) { }
    }

    private class PivotDepthElement(val depth: Int) :
        kotlin.coroutines.AbstractCoroutineContextElement(PivotDepthElement) {
        companion object Key : kotlin.coroutines.CoroutineContext.Key<PivotDepthElement>
    }

    private suspend fun performDeepDiscovery(
        result: PartialResult,
        scanned: MutableSet<String>,
        onPartialResult: suspend (PartialResult) -> Unit,
        accumulator: MutableList<PartialResult>
    ) {
        val depth = kotlin.coroutines.coroutineContext[PivotDepthElement]?.depth ?: 0
        if (depth >= 1) return
        if (scanned.size > MAX_PIVOT_SCANNED) return
        var pivots = 0
        val email = result.email?.trim()?.lowercase()
        if (pivots < MAX_PIVOTS && !email.isNullOrBlank() &&
            email.contains("@") && email.substringAfter("@").contains(".") &&
            !email.startsWith("@") && !email.contains(" ") && email.length <= 120 &&
            scanned.add(email)
        ) {
            pivots++
            try {
                withContext(PivotDepthElement(depth + 1)) {
                    lookupPartials(email, IdentifierType.EMAIL, PIVOT_CAPS) { partial ->
                        accumulator.add(partial); onPartialResult(partial)
                    }
                }
            } catch (_: Exception) { }
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
            try {
                withContext(PivotDepthElement(depth + 1)) {
                    lookupPartials(username, IdentifierType.USERNAME, PIVOT_CAPS) { partial ->
                        accumulator.add(partial); onPartialResult(partial)
                    }
                }
            } catch (_: Exception) { }
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
