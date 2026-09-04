package com.infocaller.app.data.remote

import com.infocaller.app.domain.engine.*
import com.infocaller.app.domain.model.SocialLookupStatus
import com.infocaller.app.domain.model.SocialProfile
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup


class HoleheEmailProviderImpl(
    private val httpClient: OkHttpClient
) : LookupProvider {
    override val id = "holehe_email"
    override val name = "Email Presence Intelligence"
    override val version = "1.0.0"
    override val capabilities = setOf(Capability.EMAIL, Capability.SOCIAL_MATCH, Capability.SERVICE_PRESENCE, Capability.PROFILE_PHOTO)
    override val priority = 60
    override val costClass = CostClass.FREE

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

        val profiles = UsernameExistenceChecker.mapBounded(checks) { site ->
            val target = if (site.urlTemplate.contains("%s.wordpress.com")) site.urlTemplate.format(prefix)
            else if (site.keyword == null) site.urlTemplate.format(prefix)
            else site.urlTemplate.format(email)
            // Keyword sites (Gravatar/Pinterest) need page-specific presence text;
            // plain profile probes use the shared heuristic.
            val found = if (site.keyword != null) keywordExists(site, target)
            else UsernameExistenceChecker.exists(httpClient, target)
            if (found) SocialProfile(site.name, prefix, target, SocialLookupStatus.POSSIBLE_MATCH) else null
        }

        if (profiles.isEmpty()) return@withContext null
        PartialResult(
            socialProfiles = profiles,
            confidence = 0.55f,
            source = "Email Presence (Holehe-style)",
            providerId = id, providerVersion = version
        )
    }

    private fun keywordExists(site: Site, url: String): Boolean {
        return try {
            val req = Request.Builder().url(url).header("User-Agent","Mozilla/5.0 (Linux; Android 14)").build()
            httpClient.newCall(req).execute().use { resp ->
                if (resp.code != 200) return false
                val body = resp.body?.string() ?: return false
                if (body.length < 500) return false
                !body.lowercase().contains("404")
            }
        } catch (_: Exception) { false }
    }
    private data class Site(val name:String, val urlTemplate:String, val keyword:String?)
}
