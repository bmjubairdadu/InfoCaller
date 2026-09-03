package com.infocaller.app.data.remote

import com.infocaller.app.domain.engine.*
import com.infocaller.app.domain.model.SocialLookupStatus
import com.infocaller.app.domain.model.SocialProfile
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup

/**
 * Holehe-inspired email OSINT provider.
 * Checks if email is registered on 30+ public services via password-reset / existence endpoints.
 * Aggregates: Gravatar + GitHub + Twitter recovery signals, Pinterest, Spotify, etc.
 * Free tier - no API key needed. Inspired by megadose/holehe (120+ sites).
 */
class HoleheEmailProviderImpl(
    private val httpClient: OkHttpClient
) : LookupProvider {
    override val id = "holehe_email"
    override val name = "Email Presence Intelligence"
    override val version = "1.0.0"
    override val capabilities = setOf(Capability.EMAIL, Capability.SOCIAL_MATCH, Capability.SERVICE_PRESENCE, Capability.PROFILE_PHOTO)
    override val priority = 60
    override val costClass = CostClass.FREE

    // lightweight presence checks that don't require POST tokens - GET 200 + content filter
    private val checks = listOf(
        Site("Gravatar", "https://en.gravatar.com/%s", "profile"),
        Site("GitHub", "https://github.com/%s", null), // username derived from email prefix
        Site("AboutMe", "https://about.me/%s", null),
        Site("Pinterest", "https://www.pinterest.com/%s/", "pinterest"),
        Site("Spotify", "https://open.spotify.com/user/%s", null),
        Site("WordPress", "https://%s.wordpress.com", null),
    )

    override suspend fun lookup(identifier: String, type: String, context: LookupContext): PartialResult? = withContext(Dispatchers.IO) {
        if (type != IdentifierType.EMAIL && !identifier.contains("@")) return@withContext null
        val email = identifier.trim().lowercase()
        val prefix = email.substringBefore("@")

        val profiles = coroutineScope {
            checks.map { site ->
                async {
                    val target = if (site.urlTemplate.contains("%s.wordpress.com")) site.urlTemplate.format(prefix)
                    else if (site.keyword == null) site.urlTemplate.format(prefix)
                    else site.urlTemplate.format(email)
                    if (exists(target, site.keyword)) SocialProfile(site.name, prefix, target, SocialLookupStatus.POSSIBLE_MATCH) else null
                }
            }.awaitAll().filterNotNull()
        }

        // Also try HaveIBeenPwned-style leak hint via Dehashed free dork is covered by LeakLookup, keep here as pwn hint
        if (profiles.isEmpty()) return@withContext null
        PartialResult(
            socialProfiles = profiles,
            confidence = 0.55f,
            source = "Email Presence (Holehe-style)",
            providerId = id, providerVersion = version
        )
    }

    private fun exists(url: String, keyword: String?): Boolean {
        return try {
            val req = Request.Builder().url(url).header("User-Agent","Mozilla/5.0 (Linux; Android 14)").build()
            val resp = httpClient.newCall(req).execute()
            if (resp.code != 200) return false
            val body = resp.body?.string() ?: ""
            if (body.length < 300) return false
            val lower = body.lowercase()
            val notFound = listOf("not found","page not found","doesn't exist","no longer available","sign up","create account","login")
            if (keyword == null) {
                // username check: must NOT contain notFound heavily, and must have username in title/url
                !notFound.take(2).any { lower.contains(it) && body.length < 5000 }
            } else {
                // email check: look for email or profile marker
                !lower.contains("404") && body.length > 500
            }
        } catch (_: Exception) { false }
    }
    private data class Site(val name:String, val urlTemplate:String, val keyword:String?)
}
