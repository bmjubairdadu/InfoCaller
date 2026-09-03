package com.infocaller.app.domain.engine

import com.infocaller.app.domain.model.*
import com.infocaller.app.util.ContactUtils

/**
 * IntelligenceResultMerger is responsible for progressive merging of intelligence data,
 * deduplication, photo candidate selection, and name arbitration.
 */
object IntelligenceResultMerger {

    /**
     * Merges a new [PartialResult] into an existing [LookupResult].
     * Implements progressive information merging and deduplication.
     */
    fun merge(current: LookupResult, next: PartialResult): LookupResult {
        // 1. Merge Names (Independent)
        val (bestName, nameSource, alternateNames) = mergeNames(current, next)

        // 2. Merge Photo Candidates (Independent)
        val (bestPhoto, photoSource, candidates) = mergePhotos(current, next)

        // 3. Merge Social Profiles (Filtering false positives)
        val socialProfiles = mergeSocialProfiles(current.socialProfiles, next.socialProfiles)

        // 4. Track Sources
        val newSources = (current.sources + (next.source ?: next.providerId ?: "unknown")).distinct()

        // 5. Update Confidence (Weighted by consensus)
        var newConfidence = maxOf(current.confidence, next.confidence)
        
        // Consensus boost if multiple providers return same name
        val nameMatchCount = alternateNames[bestName]?.size ?: 0
        if (nameMatchCount >= 2 && newConfidence < 0.95f) {
            newConfidence = minOf(1.0f, newConfidence + 0.15f)
        }

        return current.copy(
            name = bestName ?: current.name,
            nameSource = nameSource ?: current.nameSource,
            alternateNames = alternateNames,
            imageUrl = bestPhoto ?: current.imageUrl,
            imageSource = photoSource ?: current.imageSource,
            photoCandidates = candidates,
            about = next.about ?: current.about,
            city = next.city ?: current.city,
            country = next.country ?: current.country,
            region = next.region ?: current.region,
            timezone = next.timezone ?: current.timezone,
            email = next.email ?: current.email,
            emailSource = if (next.email != null) (next.source ?: next.providerId) else current.emailSource,
            carrier = next.carrier ?: current.carrier,
            lineType = next.lineType ?: current.lineType,
            plateNumber = next.plateNumber ?: current.plateNumber,
            iban = next.iban ?: current.iban,
            vatId = next.vatId ?: current.vatId,
            macAddress = next.macAddress ?: current.macAddress,
            nid = next.nid ?: current.nid,
            dob = next.dob ?: current.dob,
            socialProfiles = socialProfiles,
            isBusiness = next.isBusiness ?: current.isBusiness,
            sources = newSources,
            confidence = newConfidence,
            timestamp = System.currentTimeMillis()
        )
    }

    private fun mergeNames(current: LookupResult, next: PartialResult): Triple<String?, String?, Map<String, List<String>>> {
        val nextName = next.name
        val provider = next.source ?: next.providerId ?: "unknown"
        
        val newAlternateNames = current.alternateNames.toMutableMap()
        
        if (nextName != null && !ContactUtils.isPlaceholderName(nextName)) {
            val providers = newAlternateNames.getOrDefault(nextName, emptyList()).toMutableList()
            if (!providers.contains(provider)) {
                providers.add(provider)
                newAlternateNames[nextName] = providers
            }
        }

        val currentIsPlaceholder = ContactUtils.isPlaceholderName(current.name)
        val shouldUpdate = current.name == null || currentIsPlaceholder
        
        val bestName = if (shouldUpdate && nextName != null && !ContactUtils.isPlaceholderName(nextName)) {
            nextName
        } else current.name
        
        val bestSource = if (bestName == nextName) provider else current.nameSource
        
        return Triple(bestName, bestSource, newAlternateNames)
    }

    private fun mergePhotos(current: LookupResult, next: PartialResult): Triple<String?, String?, List<PhotoCandidate>> {
        val newCandidates = (current.photoCandidates + next.photoCandidates).distinctBy { it.url }
        if (newCandidates.isEmpty()) return Triple(current.imageUrl, current.imageSource, emptyList())
        // Filter to face-clear only if any face candidate exists; otherwise allow non-face as fallback but de-prioritized
        val hasFaceCandidate = newCandidates.any { it.faceCount > 0 && it.faceConfidence >= 0.5f }
        val pool = if (hasFaceCandidate) newCandidates.filter { it.faceCount > 0 && it.faceConfidence >= 0.5f } else newCandidates
        val bestCandidate = pool.maxByOrNull { calculatePhotoScore(it) } ?: newCandidates.maxByOrNull { calculatePhotoScore(it) }
        // If best has no face and we had to fallback, mark low confidence for details screen to know
        return Triple(bestCandidate?.url, bestCandidate?.provider, newCandidates)
    }

    /**
     * Face-clear photo scoring: only faces with confidence + coverage pass.
     * - faceCount==0 -> -1000 (hidden, never picked)
     * - faceConfidence <0.5 -> -500
     * - faceCoverage <0.02 or >0.6 -> penalized (tiny or full-frame artifact)
     * - sharpness <0.15 -> penalized (blurry)
     */
    private fun calculatePhotoScore(c: PhotoCandidate): Float {
        var score = 0f
        // Must have face - otherwise last chance via sharpness only if no face candidate exists
        if (c.faceCount <= 0) return -1000f + c.imageQuality * 10f
        if (c.faceConfidence < 0.5f) return -500f + c.faceConfidence * 50f
        // Coverage penalty: too small or too huge is not portrait
        if (c.faceCoverage < 0.02f || c.faceCoverage > 0.6f) score -= 50f else score += c.faceCoverage * 100f
        if (c.imageQuality < 0.15f) score -= 30f else score += c.imageQuality * 100f
        score += 500f // face present bonus
        val resolution = c.width * c.height
        val resolutionScore = if (resolution > 250000) 100f else (resolution / 2500f)
        score += minOf(100f, resolutionScore)
        if (c.provider.lowercase().contains("truecaller") || c.provider.lowercase().contains("eyecon") || c.provider.lowercase().contains("whatsapp")) score += 50f
        score += c.faceConfidence * 50f
        return score
    }

    private fun mergeSocialProfiles(current: List<SocialProfile>, next: List<SocialProfile>): List<SocialProfile> {
        val result = current.toMutableList()
        val genericUrls = setOf(
            "https://facebook.com", "https://facebook.com/",
            "https://instagram.com", "https://instagram.com/",
            "https://linkedin.com", "https://linkedin.com/",
            "https://twitter.com", "https://twitter.com/",
            "https://x.com", "https://x.com/", "https://wa.me", "https://wa.me/"
        )

        next.forEach { n ->
            // 1. Evidence Check: Must have a handle or specific path
            val profileUrl = n.profileUrl?.lowercase()
            if (profileUrl == null || genericUrls.contains(profileUrl)) {
                return@forEach
            }
            
            // Filter out generic domain endings if they don't have a path
            if (profileUrl.endsWith(".com") || profileUrl.endsWith(".me") || profileUrl.endsWith(".org") || profileUrl.endsWith(".net")) {
                val path = profileUrl.substringAfter(".com").substringAfter(".me").substringAfter(".org").substringAfter(".net")
                if (path.isEmpty() || path == "/") return@forEach
            }

            val existing = result.find { it.platform.lowercase() == n.platform.lowercase() }
            if (existing == null) {
                result.add(n)
            } else {
                val index = result.indexOf(existing)
                // Merge details within the same platform
                result[index] = existing.copy(
                    username = n.username ?: existing.username,
                    displayName = n.displayName ?: existing.displayName,
                    avatarUrl = n.avatarUrl ?: existing.avatarUrl,
                    profileUrl = if (!n.profileUrl.isNullOrBlank() && n.profileUrl.length > (existing.profileUrl?.length ?: 0)) n.profileUrl else existing.profileUrl,
                    status = if (n.status != SocialLookupStatus.UNKNOWN) n.status else existing.status,
                    confidence = maxOf(existing.confidence, n.confidence)
                )
            }
        }
        return result
    }
}
