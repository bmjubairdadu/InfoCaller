package com.infocaller.app.domain.engine

import com.infocaller.app.domain.model.*
import com.infocaller.app.util.ContactUtils


object IntelligenceResultMerger {

    
    fun merge(current: LookupResult, next: PartialResult): LookupResult {
        val (bestName, nameSource, alternateNames) = mergeNames(current, next)

        val (bestPhoto, photoSource, candidates) = mergePhotos(current, next)

        val socialProfiles = mergeSocialProfiles(current.socialProfiles, next.socialProfiles)

        val newSources = (current.sources + (next.source ?: next.providerId ?: "unknown")).distinct()

        var newConfidence = maxOf(current.confidence, next.confidence)
        
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
        
        if (newCandidates.isEmpty()) {
            return Triple(current.imageUrl, current.imageSource, emptyList())
        }

        val bestCandidate = newCandidates.maxByOrNull { calculatePhotoScore(it) }
        
        return Triple(bestCandidate?.url, bestCandidate?.provider, newCandidates)
    }

    
    private fun calculatePhotoScore(c: PhotoCandidate): Float {
        var score = 0f
        
        if (c.faceCount > 0) score += 500f
        score += c.faceCoverage * 100f
        score += c.imageQuality * 100f
        
        val resolution = c.width * c.height
        val resolutionScore = if (resolution > 250000) 100f else (resolution / 2500f)
        score += minOf(100f, resolutionScore)
        
        if (c.provider.lowercase().contains("truecaller") || c.provider.lowercase().contains("eyecon")) {
            score += 50f
        }
        
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
            val profileUrl = n.profileUrl?.lowercase()
            if (profileUrl == null || genericUrls.contains(profileUrl)) {
                return@forEach
            }
            
            if (profileUrl.endsWith(".com") || profileUrl.endsWith(".me") || profileUrl.endsWith(".org") || profileUrl.endsWith(".net")) {
                val path = profileUrl.substringAfter(".com").substringAfter(".me").substringAfter(".org").substringAfter(".net")
                if (path.isEmpty() || path == "/") return@forEach
            }

            // Dedup by platform + normalized URL so two distinct accounts on the
            // same platform are both kept instead of dropping the second one.
            val dedupKey = (n.platform.lowercase() + "|" + profileUrl).take(300)
            val existing = result.find {
                (it.platform.lowercase() + "|" + (it.profileUrl?.lowercase() ?: "")).take(300) == dedupKey
            }
            if (existing == null) {
                result.add(n)
            } else {
                val index = result.indexOf(existing)
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
