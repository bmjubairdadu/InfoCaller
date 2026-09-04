package com.infocaller.app.util

import com.infocaller.app.data.local.entity.ContactEnrichmentEntity


object EnrichmentGapChecker {

    data class Gaps(
        val missingName: Boolean,
        val missingPhoto: Boolean,
        val missingLocation: Boolean,
        val missingCarrier: Boolean,
        val missingEmail: Boolean,
        val hasAnyGap: Boolean,
        val isComplete: Boolean
    )

    fun check(entity: ContactEnrichmentEntity?): Gaps {
        if (entity == null) {
            return Gaps(true, true, true, true, true, true, false)
        }
        val missingName = entity.publicName.isNullOrBlank() || ContactUtils.isPlaceholderName(entity.publicName)
        val missingPhoto = entity.profileImageUrl.isNullOrBlank()
        val missingLocation = entity.city.isNullOrBlank() && entity.country.isNullOrBlank()
        val missingCarrier = entity.carrier.isNullOrBlank()
        val missingEmail = entity.email.isNullOrBlank()
        val hasAnyGap = missingName || missingPhoto || missingLocation
        val isComplete = !missingName && !missingPhoto
        return Gaps(missingName, missingPhoto, missingLocation, missingCarrier, missingEmail, hasAnyGap, isComplete)
    }

    fun missingCapabilities(gaps: Gaps): Set<String> {
        val set = mutableSetOf<String>()
        if (gaps.missingName) set.add("PUBLIC_SEARCH")
        if (gaps.missingPhoto) set.add("PROFILE_PHOTO")
        if (gaps.missingLocation) { set.add("CITY"); set.add("COUNTRY") }
        if (gaps.missingCarrier) set.add("CARRIER")
        if (gaps.missingEmail) set.add("EMAIL")
        return set
    }
}
