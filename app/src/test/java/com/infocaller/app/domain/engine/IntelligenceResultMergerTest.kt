package com.infocaller.app.domain.engine

import com.infocaller.app.domain.model.*
import org.junit.Assert.*
import org.junit.Test

class IntelligenceResultMergerTest {

    @Test
    fun testNamePreservation() {
        val current = LookupResult(phoneNumber = "+1", name = "Local Name")
        val next = PartialResult(name = "Discovered")
        
        // IntelligenceResultMerger.merge doesn't handle the system localName preservation (that's in CallerRepositoryImpl)
        // but it should handle not overwriting valid names with placeholders.
        val placeholderNext = PartialResult(name = "unknown")
        val result = IntelligenceResultMerger.merge(current, placeholderNext)
        assertEquals("Local Name", result.name)
    }

    @Test
    fun testPhotoScoring() {
        val facePhoto = PhotoCandidate(
            provider = "A", url = "face", faceCount = 1, faceConfidence = 1.0f, faceCoverage = 0.5f, imageQuality = 0.8f, width = 500, height = 500
        )
        val flowerPhoto = PhotoCandidate(
            provider = "B", url = "flower", faceCount = 0, imageQuality = 0.9f, width = 1000, height = 1000
        )
        
        val result = IntelligenceResultMerger.merge(
            LookupResult(phoneNumber = "+1"),
            PartialResult(photoCandidates = listOf(flowerPhoto, facePhoto))
        )
        assertEquals("face", result.imageUrl)
    }

    @Test
    fun testPhotoResolutionPreference() {
        val lowResFace = PhotoCandidate(
            provider = "A", url = "low", faceCount = 1, width = 100, height = 100, imageQuality = 0.5f
        )
        val highResFace = PhotoCandidate(
            provider = "B", url = "high", faceCount = 1, width = 1000, height = 1000, imageQuality = 0.5f
        )
        
        val result = IntelligenceResultMerger.merge(
            LookupResult(phoneNumber = "+1"),
            PartialResult(photoCandidates = listOf(lowResFace, highResFace))
        )
        assertEquals("high", result.imageUrl)
    }

    @Test
    fun testSocialFalsePositiveFiltering() {
        val generic = SocialProfile(platform = "Facebook", profileUrl = "https://facebook.com")
        val real = SocialProfile(platform = "Facebook", profileUrl = "https://facebook.com/realuser123")
        
        val result = IntelligenceResultMerger.merge(
            LookupResult(phoneNumber = "+1"),
            PartialResult(socialProfiles = listOf(generic, real))
        )
        
        assertEquals(1, result.socialProfiles.size)
        assertEquals("https://facebook.com/realuser123", result.socialProfiles[0].profileUrl)
    }
}
