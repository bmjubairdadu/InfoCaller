package com.infocaller.app.domain.engine

import com.infocaller.app.domain.model.LookupResult
import com.infocaller.app.domain.model.SocialLookupStatus
import com.infocaller.app.domain.model.SpamStatus

object ConfidenceEngine {
    
    fun merge(phoneNumber: String, results: List<PartialResult>, existingContactName: String? = null): LookupResult {
        // 1. Existing Contact Priority
        if (existingContactName != null) {
            return buildFinalResult(phoneNumber, results, existingContactName, 1.0f)
        }

        // 2. Score Calculation
        val topResult = results.maxByOrNull { it.confidence }
        var baseConfidence = topResult?.confidence ?: 0f
        
        // Boost if multiple providers found the same name
        val nameCounts = results.mapNotNull { it.name }.groupingBy { it }.eachCount()
        val consensusName = nameCounts.maxByOrNull { it.value }
        if ((consensusName?.value ?: 0) >= 2) {
            baseConfidence = minOf(1.0f, baseConfidence + 0.2f)
        }

        return buildFinalResult(phoneNumber, results, consensusName?.key ?: topResult?.name, baseConfidence)
    }

    private fun buildFinalResult(phoneNumber: String, results: List<PartialResult>, finalName: String?, confidence: Float): LookupResult {
        val finalSocialProfiles = results.flatMap { it.socialProfiles }
            .distinctBy { it.platform + it.profileUrl }
            .sortedBy { getStatusPriority(it.status) }

        val sources = results.mapNotNull { it.source }.distinct()
        val performance = results.map { com.infocaller.app.domain.model.ProviderPerformance(it.source ?: "Unknown", it.durationMs) }
        
        return LookupResult(
            phoneNumber = phoneNumber,
            name = finalName,
            imageUrl = results.firstOrNull { it.imageUrl != null }?.imageUrl,
            about = results.firstOrNull { it.about != null }?.about,
            country = results.firstOrNull { it.country != null }?.country,
            region = results.firstOrNull { it.region != null }?.region,
            carrier = results.firstOrNull { it.carrier != null }?.carrier,
            socialProfiles = finalSocialProfiles,
            spamScore = results.map { it.spamScore }.maxOrNull() ?: 0,
            spamType = results.firstOrNull { it.spamType != null }?.spamType,
            sources = sources,
            confidence = confidence,
            performance = performance,
            timestamp = System.currentTimeMillis()
        )
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
