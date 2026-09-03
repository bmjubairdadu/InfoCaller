package com.infocaller.app.domain.engine

import com.infocaller.app.domain.model.LookupResult

/**
 * Legacy ConfidenceEngine refactored to delegate to IntelligenceResultMerger.
 * Maintains backward compatibility for bulk or simple merge calls.
 */
object ConfidenceEngine {
    
    fun merge(phoneNumber: String, results: List<PartialResult>, existingContactName: String? = null): LookupResult {
        var merged = LookupResult(phoneNumber = phoneNumber, name = existingContactName)
        if (existingContactName != null && !com.infocaller.app.util.ContactUtils.isPlaceholderName(existingContactName)) {
            merged = merged.copy(confidence = 1.0f)
        }
        results.forEach { partial ->
            merged = IntelligenceResultMerger.merge(merged, partial)
        }
        // If it was already 1.0f (local contact), IntelligenceResultMerger might have reduced it if it only looked at partials.
        // We ensure local contact always has 1.0f.
        if (existingContactName != null && !com.infocaller.app.util.ContactUtils.isPlaceholderName(existingContactName)) {
            merged = merged.copy(name = existingContactName, confidence = 1.0f)
        }
        return merged
    }

    /**
     * Privacy-safe merge for the shared registry.
     */
    fun mergeForRegistry(phoneNumber: String, results: List<PartialResult>): LookupResult {
        return merge(phoneNumber, results, null)
    }
}
