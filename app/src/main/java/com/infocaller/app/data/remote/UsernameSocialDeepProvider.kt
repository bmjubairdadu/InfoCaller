package com.infocaller.app.data.remote

import com.infocaller.app.domain.engine.*
import com.infocaller.app.domain.model.SocialLookupStatus
import com.infocaller.app.domain.model.SocialProfile
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Deep username -> extra surfaces (Snapchat, TikTok, Pinterest, Medium, Quora, Behance, Reddit alt)
 * Complements Sherlock/UsernameLookup with platforms those miss, using validation patterns Sherlock uses.
 */
class UsernameSocialDeepProvider(private val client: OkHttpClient) : LookupProvider {
    override val id = "username_social_deep"
    override val name = "Username Deep Social"
    override val version = "1.0.0"
    override val capabilities = setOf(Capability.SOCIAL_MATCH, Capability.PUBLIC_PROFILE)
    override val priority = 68
    override val costClass = CostClass.FREE

    private val sites = mapOf(
        "Snapchat" to "https://www.snapchat.com/add/%s",
        "TikTok" to "https://www.tiktok.com/@%s",
        "Pinterest" to "https://www.pinterest.com/%s/",
        "Medium" to "https://medium.com/@%s",
        "Quora" to "https://www.quora.com/profile/%s",
        "Behance" to "https://www.behance.net/%s",
        "Reddit" to "https://www.reddit.com/user/%s",
        "Twitch" to "https://www.twitch.tv/%s",
        "SoundCloud" to "https://soundcloud.com/%s",
        "Flickr" to "https://www.flickr.com/people/%s",
        "DeviantArt" to "https://www.deviantart.com/%s",
        "Dribbble" to "https://dribbble.com/%s",
        "Patreon" to "https://www.patreon.com/%s",
        "Steam" to "https://steamcommunity.com/id/%s",
        "Spotify" to "https://open.spotify.com/user/%s",
        "Vimeo" to "https://vimeo.com/%s",
        "Tumblr" to "https://%s.tumblr.com",
        "WordPress" to "https://%s.wordpress.com",
        "Blogger" to "https://%s.blogspot.com",
        "YouTube" to "https://www.youtube.com/@%s"
    )

    override suspend fun lookup(identifier: String, type: String, context: LookupContext): PartialResult? = withContext(Dispatchers.IO) {
        if (type != IdentifierType.USERNAME && type != IdentifierType.EMAIL) return@withContext null
        val username = if (type == IdentifierType.EMAIL) identifier.substringBefore("@") else identifier.trim()
        if (username.length < 3 || username.length > 30 || username.contains(" ")) return@withContext null
        val profiles = coroutineScope {
            sites.map { (name, tmpl) -> async {
                val url = tmpl.format(username)
                if (exists(url)) SocialProfile(name, username, url, SocialLookupStatus.PUBLIC_MATCH) else null
            } }.awaitAll().filterNotNull()
        }
        if (profiles.isEmpty()) return@withContext null
        PartialResult(socialProfiles = profiles, confidence = 0.6f, source = "Username Deep Scan (20+)", providerId = id, providerVersion = version)
    }

    private fun exists(url: String): Boolean = try {
        val req = Request.Builder().url(url).header("User-Agent","Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36").build()
        val r = client.newCall(req).execute()
        if (r.code != 200) return false
        val b = r.body?.string() ?: ""
        if (b.length < 400) return false
        val low = b.lowercase()
        val nf = listOf("not found","user not found","page not found","doesn't exist","404")
        if (nf.any{ low.contains(it) && b.length < 9000 }) return false
        if (r.request.url.toString().contains("login", true)) return false
        true
    } catch(_:Exception){ false }
}
