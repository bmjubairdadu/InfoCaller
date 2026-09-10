package com.infocaller.app.data.remote

import com.infocaller.app.domain.engine.*
import com.infocaller.app.domain.model.SocialLookupStatus
import com.infocaller.app.domain.model.SocialProfile
import com.infocaller.app.util.ContactUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

/**
 * Name + photo pivot -> public social profiles.
 *
 * Compliant alternative to forgot-password enumeration:
 * - NEVER calls forgot-password / recovery endpoints with phone/email.
 * - NEVER tries to prove "this number opened this account".
 * - Only derives handle seeds from a real caller name (Truecaller/Eyecon
 *   result via [LookupContext.foundName], or FULL_NAME / EMAIL input),
 *   then checks public profile URLs. Only URLs that publicly exist
 *   (no login-wall, no not-found markers) are returned.
 *
 * Multiple valid accounts are all returned; UI filters to PUBLIC_MATCH.
 */
class NamePhotoSocialPivotProviderImpl(
    private val httpClient: OkHttpClient
) : LookupProvider {
    override val id = "name_photo_social_pivot"
    override val name = "Name Photo Social Pivot (public)"
    override val version = "1.0.0"
    override val capabilities = setOf(
        Capability.SOCIAL_MATCH,
        Capability.PUBLIC_PROFILE,
        Capability.PUBLIC_SEARCH
    )
    override val priority = 52
    override val costClass = CostClass.FREE

    override suspend fun lookup(
        identifier: String,
        type: String,
        context: LookupContext
    ): PartialResult? = withContext(Dispatchers.IO) {
        val rawName: String? = when (type) {
            IdentifierType.PHONE -> context.foundName?.takeIf { it.isNotBlank() }
            IdentifierType.FULL_NAME -> identifier.takeIf { it.isNotBlank() }
            IdentifierType.EMAIL -> {
                val prefix = identifier.substringBefore("@").trim()
                // Email prefix alone is weak; only use when it looks like a name.
                if (prefix.length >= 3 && prefix.any { it.isLetter() } && !prefix.contains(" ")) prefix
                else context.foundName?.takeIf { it.isNotBlank() }
            }
            IdentifierType.USERNAME -> identifier.takeIf { it.isNotBlank() }
            else -> null
        } ?: return@withContext null

        if (type == IdentifierType.PHONE || type == IdentifierType.FULL_NAME) {
            try {
                if (ContactUtils.isPlaceholderName(rawName)) return@withContext null
            } catch (_: Exception) { }
        }
        val seeds = buildSeeds(rawName!!)
        if (seeds.isEmpty()) return@withContext null

        val probes = mutableListOf<Triple<String, String, SocialLookupStatus>>()
        for (seed in seeds.take(3)) {
            for ((platform, tmpl) in listOf(
                "Facebook" to "https://www.facebook.com/%s",
                "Instagram" to "https://www.instagram.com/%s/",
                "TikTok" to "https://www.tiktok.com/@%s",
                "YouTube" to "https://www.youtube.com/@%s",
                "X" to "https://x.com/%s"
            )) {
                try {
                    probes.add(Triple(platform, tmpl.format(seed), SocialLookupStatus.PUBLIC_MATCH))
                } catch (_: Exception) { }
            }
        }
        if (probes.isEmpty()) return@withContext null

        val found = UsernameExistenceChecker.mapBounded(probes, maxConcurrency = 8) { (platform, url, status) ->
            try {
                // Inline preview extraction; null = not-found/login-wall/error -> skipped,
                // so only really available accounts are returned (no bare guesses).
                UsernameExistenceChecker.fetchVerifiedProfile(httpClient, platform, url)
            } catch (_: Exception) { null }
        }.distinctBy { it.platform.lowercase() + "|" + (it.username?.lowercase().orEmpty()) }

        if (found.isEmpty()) return@withContext null

        PartialResult(
            socialProfiles = found,
            confidence = if (found.size >= 2) 0.68f else 0.6f,
            source = "Name -> Social Pivot (public, name-matched)",
            providerId = id,
            providerVersion = version
        )
    }

    private fun buildSeeds(name: String): List<String> {
        val clean = name.trim().lowercase()
        if (clean.length < 3) return emptyList()
        // Drop pure-digit / phone-like names.
        if (clean.filter { it.isDigit() }.length >= 7 && clean.filter { it.isLetter() }.isEmpty()) return emptyList()
        val tokens = clean.split(Regex("[^a-z0-9]+")).filter { it.length >= 3 && it.any { c -> c.isLetter() } }
        if (tokens.isEmpty()) return emptyList()
        val seeds = LinkedHashSet<String>()
        fun add(s: String) {
            val v = s.lowercase().replace(Regex("[^a-z0-9._]"), "")
            if (v.length in 3..30 && v.any { it.isLetter() } && !v.all { it.isDigit() }) seeds.add(v)
        }
        val compact = tokens.joinToString("")
        add(compact.take(30))
        add(tokens.joinToString(".").take(30))
        if (tokens.size >= 2) {
            add("${tokens.first()}.${tokens.last()}".take(30))
            add("${tokens.first()}${tokens.last()}".take(30))
        }
        for (t in tokens.take(3)) add(t)
        return seeds.toList().take(3)
    }
}
