package com.infocaller.app.data.remote

import com.infocaller.app.domain.engine.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

class HoleheEmailProviderImpl(
    private val httpClient: OkHttpClient
) : LookupProvider {
    override val id = "holehe_email"
    override val name = "Email Presence Intelligence"
    override val version = "2.1.0"
    override val capabilities = setOf(Capability.EMAIL, Capability.SOCIAL_MATCH, Capability.SERVICE_PRESENCE, Capability.PROFILE_PHOTO)
    override val priority = 60
    override val costClass = CostClass.FREE

    private data class Site(val name: String, val urlTemplate: String)

    private val checks = listOf(
        Site("GitHub", "https://github.com/%s"),
        Site("GitLab", "https://gitlab.com/%s"),
        Site("Dev.to", "https://dev.to/%s"),
        Site("Replit", "https://replit.com/@%s"),
        Site("Behance", "https://www.behance.net/%s"),
        Site("Dribbble", "https://dribbble.com/%s"),
        Site("SoundCloud", "https://soundcloud.com/%s"),
        Site("Vimeo", "https://vimeo.com/%s"),
        Site("Keybase", "https://keybase.io/%s"),
        Site("NPM", "https://www.npmjs.com/~%s"),
        Site("PyPI", "https://pypi.org/user/%s"),
        Site("CodePen", "https://codepen.io/%s"),
        Site("Last.fm", "https://www.last.fm/user/%s"),
        Site("Chess.com", "https://www.chess.com/member/%s"),
        Site("about.me", "https://about.me/%s"),
        Site("Patreon", "https://www.patreon.com/%s"),
        Site("Hashnode", "https://hashnode.com/@%s"),
        Site("Ko-fi", "https://ko-fi.com/%s"),
        Site("BuyMeACoffee", "https://www.buymeacoffee.com/%s"),
        Site("Flickr", "https://www.flickr.com/people/%s"),
        Site("Mixcloud", "https://www.mixcloud.com/%s"),
        Site("Wattpad", "https://www.wattpad.com/user/%s"),
        Site("Reddit", "https://www.reddit.com/user/%s"),
        Site("SlideShare", "https://www.slideshare.net/%s"),
        Site("SpeakerDeck", "https://speakerdeck.com/%s"),
        Site("Instructables", "https://www.instructables.com/member/%s"),
        Site("Disqus", "https://disqus.com/by/%s"),
        Site("BandLab", "https://www.bandlab.com/%s"),
    )

    override suspend fun lookup(identifier: String, type: String, context: LookupContext): PartialResult? = withContext(Dispatchers.IO) {
        if (type != IdentifierType.EMAIL && !identifier.contains("@")) return@withContext null
        val email = identifier.trim().lowercase()
        val prefix = email.substringBefore("@")
        if (prefix.length !in 2..40 || prefix.contains(" ")) return@withContext null
        val handle = prefix.replace(Regex("[^a-z0-9._-]"), "")
        if (handle.length < 2) return@withContext null

        val profiles = UsernameExistenceChecker.mapBounded(checks, maxConcurrency = 28) { site ->
            try {
                UsernameExistenceChecker.fetchVerifiedProfile(
                    httpClient, site.name, site.urlTemplate.format(handle), timeoutMs = 3500L
                )
            } catch (_: Exception) { null }
        }.distinctBy { it.platform.lowercase() }

        if (profiles.isEmpty()) return@withContext null
        PartialResult(
            socialProfiles = profiles,
            confidence = when {
                profiles.size >= 5 -> 0.7f
                profiles.size >= 3 -> 0.6f
                else -> 0.5f
            },
            source = "Email Presence (multi-site)",
            providerId = id, providerVersion = version
        )
    }
}
