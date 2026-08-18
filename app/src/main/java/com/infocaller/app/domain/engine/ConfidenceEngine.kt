package com.infocaller.app.domain.engine

import com.infocaller.app.domain.model.LookupResult
import com.infocaller.app.domain.model.SocialLookupStatus
import com.infocaller.app.domain.model.SpamStatus

object ConfidenceEngine {
    
    fun merge(phoneNumber: String, results: List<PartialResult>, existingContactName: String? = null): LookupResult {
        return buildMergedResult(phoneNumber, results, existingContactName)
    }

    /**
     * Privacy-safe merge for the shared registry.
     * Excludes any user-provided local names.
     */
    fun mergeForRegistry(phoneNumber: String, results: List<PartialResult>): LookupResult {
        return buildMergedResult(phoneNumber, results, null)
    }

    private fun buildMergedResult(phoneNumber: String, results: List<PartialResult>, existingContactName: String?): LookupResult {
        // 1. Score Calculation for fields
        val sources = results.mapNotNull { it.source }.distinct()
        val performance = results.map { com.infocaller.app.domain.model.ProviderPerformance(it.source ?: "Unknown", it.durationMs) }
        
        // 2. Resolve Name (Consensus or highest confidence)
        val finalName = resolveName(results, existingContactName)
        
        // 3. Resolve Photo (Candidate Pipeline)
        val finalPhoto = resolvePhoto(results)
        
        // 4. Resolve Spam
        val maxSpamScore = results.map { it.spamScore }.maxOrNull() ?: 0
        val finalSpamStatus = when {
            maxSpamScore > 80 -> com.infocaller.app.domain.model.SpamStatus.SCAM
            maxSpamScore > 50 -> com.infocaller.app.domain.model.SpamStatus.SPAM
            maxSpamScore > 20 -> com.infocaller.app.domain.model.SpamStatus.SUSPICIOUS
            results.any { it.spamScore > 0 } -> com.infocaller.app.domain.model.SpamStatus.SAFE
            else -> com.infocaller.app.domain.model.SpamStatus.UNKNOWN
        }

        // 5. Build Result by merging strongest valid source for each field
        return LookupResult(
            phoneNumber = phoneNumber,
            name = finalName,
            alternateName = findStrongestField(results) { it.alternateName },
            imageUrl = finalPhoto,
            about = findStrongestField(results) { it.about },
            city = findStrongestField(results) { it.city },
            country = findStrongestField(results) { it.country },
            region = findStrongestField(results) { it.region },
            timezone = findStrongestField(results) { it.timezone },
            email = findStrongestField(results) { it.email },
            carrier = findStrongestField(results) { it.carrier },
            isBusiness = results.any { it.isBusiness == true },
            socialProfiles = results.flatMap { it.socialProfiles }
                .distinctBy { it.platform + it.profileUrl }
                .sortedBy { getStatusPriority(it.status) },
            spamScore = maxSpamScore,
            spamType = results.firstOrNull { it.spamType != null }?.spamType,
            spamStatus = finalSpamStatus,
            sources = sources,
            confidence = if (existingContactName != null && !com.infocaller.app.util.ContactUtils.isPlaceholderName(existingContactName)) 1.0f else calculateOverallConfidence(results),
            performance = performance,
            timestamp = System.currentTimeMillis()
        )
    }

    private fun resolveName(results: List<PartialResult>, existingContactName: String?): String? {
        if (existingContactName != null && !com.infocaller.app.util.ContactUtils.isPlaceholderName(existingContactName)) {
            return existingContactName
        }
        
        val validNames = results.filter { it.name != null && !com.infocaller.app.util.ContactUtils.isPlaceholderName(it.name) }
        if (validNames.isEmpty()) return existingContactName ?: results.firstOrNull { it.name != null }?.name

        // Consensus logic
        val nameCounts = validNames.mapNotNull { it.name }.groupingBy { it }.eachCount()
        val consensusName = nameCounts.maxByOrNull { it.value }
        if ((consensusName?.value ?: 0) >= 2) return consensusName?.key
        
        // Highest confidence provider
        return validNames.maxByOrNull { it.confidence }?.name
    }

    private fun resolvePhoto(results: List<PartialResult>): String? {
        val candidates = results.filter { it.imageUrl != null }
        if (candidates.isEmpty()) return null

        val truecallerPhoto = candidates.find { it.providerId?.contains("truecaller") == true }?.imageUrl
        val registryPhoto = candidates.find { it.providerId == "shared_registry" }?.imageUrl
        val whatsappPhoto = candidates.find { it.providerId?.contains("apify") == true || it.providerId?.contains("whatsapp") == true }?.imageUrl

        return truecallerPhoto ?: registryPhoto ?: whatsappPhoto ?: candidates.firstOrNull()?.imageUrl
    }

    private fun <T> findStrongestField(results: List<PartialResult>, selector: (PartialResult) -> T?): T? {
        return results.filter { selector(it) != null }
            .maxByOrNull { it.confidence }
            ?.let { selector(it) }
    }

    private fun calculateOverallConfidence(results: List<PartialResult>): Float {
        val base = results.map { it.confidence }.maxOrNull() ?: 0f
        val consensusNames = results.mapNotNull { it.name }.groupingBy { it }.eachCount().values.maxOrNull() ?: 0
        return if (consensusNames >= 2) minOf(1.0f, base + 0.15f) else base
    }

    private fun getStatusPriority(status: SocialLookupStatus): Int {
        return when (status) {
            SocialLookupStatus.CONFIRMED -> 0
            SocialLookupStatus.PUBLIC_MATCH -> 1
            SocialLookupStatus.POSSIBLE_MATCH -> 2
            else -> 3
        }
    }
}
