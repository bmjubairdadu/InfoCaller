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

    // Expanded from 5 to include TikTok/YouTube/Telegram for free OSINT (Sherlock-style core)
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

        val profiles = mutableListOf<SocialProfile>()
        
        // Check sites in parallel
        val jobs = sites.map { (name, urlTemplate) ->
            async {
                val url = urlTemplate.format(identifier)
                if (checkSite(url)) {
                    SocialProfile(
                        platform = name,
                        username = identifier,
                        profileUrl = url,
                        status = SocialLookupStatus.PUBLIC_MATCH
                    )
                } else null
            }
        }

        profiles.addAll(jobs.awaitAll().filterNotNull())

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

    private fun checkSite(url: String): Boolean {
        return try {
            val request = Request.Builder().url(url).build()
            val response = httpClient.newCall(request).execute()
            
            if (response.code == 200) {
                val body = response.body?.string() ?: ""
                val lowerBody = body.lowercase()
                
                // Platforms often return 200 for "Not Found" but with specific keywords
                val notFoundIndicators = listOf(
                    "page not found", "doesn't exist", "not a user", 
                    "login to see", "create an account"
                )
                
                if (notFoundIndicators.any { lowerBody.contains(it) }) {
                    return false
                }
                
                // Check for login redirects
                if (response.request.url.toString().contains("login")) {
                    return false
                }
                
                return true
            }
            false
        } catch (_: Exception) {
            false
        }
    }
}
