package com.infocaller.app.data.remote

import com.google.gson.JsonParser
import com.infocaller.app.domain.engine.*
import com.infocaller.app.domain.model.SocialLookupStatus
import com.infocaller.app.domain.model.SocialProfile
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request


class SherlockProviderImpl(private val httpClient: OkHttpClient) : LookupProvider {
    override val id = "sherlock_osint"
    override val name = "Sherlock Username Scan"
    override val version = "2.0.0"
    override val capabilities = setOf(Capability.SOCIAL_MATCH, Capability.SERVICE_PRESENCE, Capability.PUBLIC_PROFILE)
    override val priority = 72
    override val costClass = CostClass.FREE

    private val sites = mapOf(
        "GitHub" to "https://github.com/%s",
        "Reddit" to "https://www.reddit.com/user/%s",
        "Twitter" to "https://twitter.com/%s",
        "Instagram" to "https://www.instagram.com/%s/",
        "Facebook" to "https://www.facebook.com/%s",
        "TikTok" to "https://www.tiktok.com/@%s",
        "YouTube" to "https://www.youtube.com/@%s",
        "Pinterest" to "https://www.pinterest.com/%s/",
        "Tumblr" to "https://%s.tumblr.com",
        "Medium" to "https://medium.com/@%s",
        "Vimeo" to "https://vimeo.com/%s",
        "SoundCloud" to "https://soundcloud.com/%s",
        "DeviantArt" to "https://www.deviantart.com/%s",
        "Flickr" to "https://www.flickr.com/people/%s",
        "Behance" to "https://www.behance.net/%s",
        "Dribbble" to "https://dribbble.com/%s",
        "Patreon" to "https://www.patreon.com/%s",
        "Twitch" to "https://www.twitch.tv/%s",
        "Steam" to "https://steamcommunity.com/id/%s",
        "Spotify" to "https://open.spotify.com/user/%s",
        "Keybase" to "https://keybase.io/%s",
        "AboutMe" to "https://about.me/%s",
        "Gravatar" to "https://en.gravatar.com/%s",
        "Blogger" to "https://%s.blogspot.com",
        "WordPress" to "https://%s.wordpress.com",
        "Telegram" to "https://t.me/%s",
        "GitLab" to "https://gitlab.com/%s",
        "Bitbucket" to "https://bitbucket.org/%s",
        "Kaggle" to "https://www.kaggle.com/%s",
        "HackerNews" to "https://news.ycombinator.com/user?id=%s",
        "ProductHunt" to "https://www.producthunt.com/@%s",
        "Replit" to "https://replit.com/@%s",
        "Codepen" to "https://codepen.io/%s",
        "StackOverflow" to "https://stackoverflow.com/users/%s",
        "Quora" to "https://www.quora.com/profile/%s",
        "VSCO" to "https://vsco.co/%s",
        "Wattpad" to "https://www.wattpad.com/user/%s",
        "Foursquare" to "https://foursquare.com/%s",
        "Slideshare" to "https://www.slideshare.net/%s",
        "TripAdvisor" to "https://www.tripadvisor.com/Profile/%s",
    )

    override suspend fun lookup(identifier: String, type: String, context: LookupContext): PartialResult? = withContext(Dispatchers.IO) {
        if (type != IdentifierType.USERNAME && type != IdentifierType.EMAIL) return@withContext null
        val username = if (type == IdentifierType.EMAIL) identifier.substringBefore("@") else identifier.trim()
        if (username.length < 3 || username.length > 30 || username.contains(" ")) return@withContext null

        val profiles = UsernameExistenceChecker.mapBounded(sites.toList()) { (name, tmpl) ->
            val url = tmpl.format(username)
            if (UsernameExistenceChecker.exists(httpClient, url)) SocialProfile(name, username, url, SocialLookupStatus.PUBLIC_MATCH) else null
        }
        if (profiles.isEmpty()) return@withContext null
        return@withContext PartialResult(socialProfiles = profiles, confidence = 0.72f, source = "Sherlock Scan (40 sites)", providerId = id, providerVersion = version)
    }
}
