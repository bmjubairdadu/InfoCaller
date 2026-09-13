package com.infocaller.app.domain.engine

import com.infocaller.app.domain.model.*
import com.infocaller.app.util.ContactUtils

object IntelligenceResultMerger {
    fun merge(current: LookupResult, next: PartialResult): LookupResult {
        return try {
            mergeSafe(current, next)
        } catch (_: Exception) { try { current } catch (_: Exception) { current } catch (_: Error) { current } }
        catch (_: Error) { try { current } catch (_: Exception) { current } catch (_: Error) { current } }
    }

    private fun mergeSafe(current: LookupResult, next: PartialResult): LookupResult {
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

    // Photo policy: logos are never photos; only Truecaller/Eyecon/Email auto-set primary.
    // Every other source stays as a tap-to-set option; user picks always win.
    private fun mergePhotos(current: LookupResult, next: PartialResult): Triple<String?, String?, List<PhotoCandidate>> {
        fun usable(url: String?): Boolean {
            return try { com.infocaller.app.util.PhotoPolicy.isUsablePhotoUrl(url) } catch (_: Exception) { false } catch (_: Error) { false }
        }
        val identType = try { next.identifierType } catch (_: Exception) { com.infocaller.app.domain.engine.IdentifierType.PHONE } catch (_: Error) { com.infocaller.app.domain.engine.IdentifierType.PHONE }
        val nextProvider = try { next.source ?: next.providerId ?: "photo" } catch (_: Exception) { "photo" } catch (_: Error) { "photo" }
        val nextIsAuto = try { com.infocaller.app.util.PhotoPolicy.isAutoProvider(nextProvider, identType) } catch (_: Exception) { false } catch (_: Error) { false }
        val currentIsUser = try { com.infocaller.app.util.PhotoPolicy.isUserPicked(current.imageSource) } catch (_: Exception) { false } catch (_: Error) { false }
        val synthetics = listOfNotNull(
            // Keep the user's own pick even if its URL was stored before the logo filter.
            current.imageUrl?.takeIf { it == current.imageUrl && (currentIsUser || usable(it)) }?.let { PhotoCandidate(provider = current.imageSource ?: "photo", url = it) },
            // Non-auto providers (Telegram/Twitch/...) are option-only: never become imageUrl here.
            next.imageUrl?.takeIf { usable(it) && nextIsAuto }?.let { PhotoCandidate(provider = nextProvider, url = it) }
        )
        val optionCandidates = (current.photoCandidates + next.photoCandidates + synthetics)
            .filter { usable(it.url) }
            .distinctBy { it.url }
        val autoPool = optionCandidates.filter {
            try { com.infocaller.app.util.PhotoPolicy.isAutoProvider(it.provider, identType) } catch (_: Exception) { false } catch (_: Error) { false }
        }
        if (optionCandidates.isEmpty()) {
            // Do NOT keep a stale bad photo: clear it when the new merge has no usable photo.
            val keepCurrent = currentIsUser || usable(current.imageUrl)
            return Triple(
                if (keepCurrent) current.imageUrl else null,
                if (keepCurrent) current.imageSource else null,
                emptyList()
            )
        }
        // Primary = best AUTO-source photo (or the user's own pick). Options keep everything usable.
        val bestCandidate = (if (currentIsUser) optionCandidates.filter { it.url == current.imageUrl } else autoPool)
            .ifEmpty { if (currentIsUser) optionCandidates else emptyList() }
            .maxByOrNull { calculatePhotoScore(it) }
        if (bestCandidate == null) {
            // No auto photo yet (e.g. only Telegram/Twitch found): primary stays empty,
            // options remain for the user to tap. Never auto-promote a logo/non-auto pic.
            val keepCurrent = currentIsUser || (usable(current.imageUrl) && try { com.infocaller.app.util.PhotoPolicy.isAutoProvider(current.imageSource, identType) } catch (_: Exception) { false } catch (_: Error) { false })
            return Triple(
                if (keepCurrent) current.imageUrl else null,
                if (keepCurrent) current.imageSource else null,
                optionCandidates
            )
        }

        return Triple(bestCandidate.url, bestCandidate.provider, optionCandidates)
    }

    private fun calculatePhotoScore(c: PhotoCandidate): Float {
        var score = 0f

        // Verified human faces strictly win; non-face images never beat a face.
        if (c.faceCount > 0 && c.faceConfidence >= 0.5f) score += 500f
        else score -= 400f
        score += c.faceCoverage * 100f
        score += c.imageQuality * 100f

        val resolution = c.width * c.height
        val resolutionScore = if (resolution > 250000) 100f else (resolution / 2500f)
        score += minOf(100f, resolutionScore)

        if (c.provider.lowercase().contains("truecaller") || c.provider.lowercase().contains("eyecon")) {
            score += 50f
        }
        // Non-auto sources (Telegram/Twitch/...) must never outrank an auto source photo.
        val autoish = c.provider.lowercase().contains("truecaller") || c.provider.lowercase().contains("eyecon") ||
            c.provider.lowercase().contains("gravatar") || c.provider.lowercase().contains("github") ||
            c.provider.lowercase().contains("gitlab") || c.provider.lowercase().startsWith("user:")
        if (!autoish) score -= 200f
        // Aggregator leftovers must never win even if they slip through.
        if (c.provider.lowercase().contains("sync") || c.provider.lowercase().contains("accountenumerator") ||
            c.provider.lowercase().contains("phonebridge")) {
            score -= 300f
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
            "https://x.com", "https://x.com/", "https://wa.me", "https://wa.me/",
            "https://t.me", "https://t.me/"
        )

        fun isUsable(n: SocialProfile): Boolean {
            val platform = n.platform.trim().lowercase()
            // Aggregator / placeholder platforms are never real accounts.
            if (platform.isBlank()) return false
            if (platform in setOf(
                    "generic", "unknown", "sync.me", "syncme", "sync",
                    "truecaller", "osint", "search", "callerid", "caller id"
                )) return false
            // Only verified accounts opened with this number/email.
            // POSSIBLE_MATCH = guess, NOT_FOUND/UNKNOWN/UNSUPPORTED/error = skip.
            if (n.status != SocialLookupStatus.CONFIRMED &&
                n.status != SocialLookupStatus.PUBLIC_MATCH) return false
            val url = n.profileUrl?.trim().orEmpty()
            if (url.isBlank() || !url.startsWith("http", ignoreCase = true)) return false
            if (url.length < 20) return false
            val lower = url.lowercase()
            if (lower in genericUrls) return false
            if (lower.contains("sync.me/search")) return false
            return true
        }

        // Drop any stale unverified entries already stored.
        result.retainAll { isUsable(it) }

        next.forEach { n ->
            if (!isUsable(n)) return@forEach
            val profileUrl = n.profileUrl?.lowercase() ?: return@forEach
            if (profileUrl == null || genericUrls.contains(profileUrl)) {
                return@forEach
            }

            if (profileUrl.endsWith(".com") || profileUrl.endsWith(".me") || profileUrl.endsWith(".org") || profileUrl.endsWith(".net")) {
                val path = profileUrl.substringAfter(".com").substringAfter(".me").substringAfter(".org").substringAfter(".net")
                if (path.isEmpty() || path == "/") return@forEach
            }

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
