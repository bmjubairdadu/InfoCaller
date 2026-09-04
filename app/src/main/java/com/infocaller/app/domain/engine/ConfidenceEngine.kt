package com.infocaller.app.domain.engine

import com.infocaller.app.domain.model.LookupResult


object ConfidenceEngine {
    
    fun merge(phoneNumber: String, results: List<PartialResult>, existingContactName: String? = null): LookupResult {
        var merged = LookupResult(phoneNumber = phoneNumber, name = existingContactName)
        if (existingContactName != null && !com.infocaller.app.util.ContactUtils.isPlaceholderName(existingContactName)) {
            merged = merged.copy(confidence = 1.0f)
        }
        results.forEach { partial ->
            merged = IntelligenceResultMerger.merge(merged, partial)
        }
        if (existingContactName != null && !com.infocaller.app.util.ContactUtils.isPlaceholderName(existingContactName)) {
            merged = merged.copy(name = existingContactName, confidence = 1.0f)
        }
        return merged
    }

    
    fun mergeForRegistry(phoneNumber: String, results: List<PartialResult>): LookupResult {
        return merge(phoneNumber, results, null)
    }
}
