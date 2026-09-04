package com.infocaller.app

import com.infocaller.app.data.local.entity.ContactEnrichmentEntity
import com.infocaller.app.util.EnrichmentGapChecker
import org.junit.Assert.*
import org.junit.Test

class EnrichmentGapCheckerTest {

    private fun entity(
        name: String? = "Acme Corp",
        photo: String? = "https://example.com/a.png",
        city: String? = "Dhaka",
        country: String? = "Bangladesh",
        carrier: String? = "Grameenphone",
        email: String? = null
    ) = ContactEnrichmentEntity(
        normalizedPhoneNumber = "+8801712345678",
        publicName = name,
        profileImageUrl = photo,
        city = city,
        country = country,
        carrier = carrier,
        email = email,
        expiresAt = System.currentTimeMillis() + 86_400_000L
    )

    @Test
    fun testNullEntityHasAllGaps() {
        val gaps = EnrichmentGapChecker.check(null)
        assertTrue(gaps.hasAnyGap)
        assertFalse(gaps.isComplete)
        assertTrue(gaps.missingName)
        assertTrue(gaps.missingPhoto)
    }

    @Test
    fun testCompleteEntity() {
        val gaps = EnrichmentGapChecker.check(entity())
        assertTrue(gaps.isComplete)
        assertFalse(gaps.hasAnyGap)
    }

    @Test
    fun testPlaceholderNameCountsAsGap() {
        val gaps = EnrichmentGapChecker.check(entity(name = "Unknown"))
        assertTrue(gaps.missingName)
        assertFalse(gaps.isComplete)
    }

    @Test
    fun testMissingPhotoCountsAsGap() {
        val gaps = EnrichmentGapChecker.check(entity(photo = null))
        assertTrue(gaps.missingPhoto)
        assertFalse(gaps.isComplete)
        assertFalse(gaps.missingName)
    }

    @Test
    fun testMissingCapabilitiesMapping() {
        val gaps = EnrichmentGapChecker.check(entity(name = null, photo = null, carrier = null, email = null))
        val caps = EnrichmentGapChecker.missingCapabilities(gaps)
        assertTrue(caps.contains("PUBLIC_SEARCH"))
        assertTrue(caps.contains("PROFILE_PHOTO"))
        assertTrue(caps.contains("CARRIER"))
        assertTrue(caps.contains("EMAIL"))
    }
}
