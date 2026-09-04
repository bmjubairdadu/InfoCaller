package com.infocaller.app.data.remote

import com.infocaller.app.domain.engine.*
import com.infocaller.app.domain.model.SocialLookupStatus
import com.infocaller.app.domain.model.SocialProfile
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request

class UsernameLookupProviderImpl(
    private val httpClient: OkHttpClient
) : LookupProvider {
    override val id: String = "username_lookup"
    override val name: String = "Username Intelligence"
    override val version: String = "1.0.0"
    override val capabilities: Set<Capability> = setOf(Capability.SOCIAL_MATCH)
    override val priority: Int = 75
    override val costClass: CostClass = CostClass.FREE

    private val sites = mapOf(
        "GitHub" to "https://github.com/%s",
        "Reddit" to "https://www.reddit.com/user/%s",
        "Twitter" to "https://twitter.com/%s",
        "Instagram" to "https://www.instagram.com/%s/",
        "Facebook" to "https://www.facebook.com/%s",
        "TikTok" to "https://www.tiktok.com/@%s",
        "YouTube" to "https://www.youtube.com/@%s",
        "Telegram" to "https://t.me/%s",
        "Pinterest" to "https://www.pinterest.com/%s/",
        "Twitch" to "https://www.twitch.tv/%s",
        "SoundCloud" to "https://soundcloud.com/%s",
        "Medium" to "https://medium.com/@%s"
    )

    override suspend fun lookup(
        identifier: String,
        type: String,
        context: LookupContext
    ): PartialResult? = withContext(Dispatchers.IO) {
        if (type != IdentifierType.USERNAME) return@withContext null

        val profiles = UsernameExistenceChecker.mapBounded(sites.toList()) { (name, urlTemplate) ->
            val url = urlTemplate.format(identifier)
            if (UsernameExistenceChecker.exists(httpClient, url)) {
                SocialProfile(
                    platform = name,
                    username = identifier,
                    profileUrl = url,
                    status = SocialLookupStatus.PUBLIC_MATCH
                )
            } else null
        }

        if (profiles.isNotEmpty()) {
            PartialResult(
                socialProfiles = profiles,
                confidence = 0.7f,
                source = "Username Search",
                providerId = id,
                providerVersion = version
            )
        } else null
    }
}
