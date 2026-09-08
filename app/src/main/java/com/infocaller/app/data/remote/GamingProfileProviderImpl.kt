package com.infocaller.app.data.remote

import com.infocaller.app.domain.engine.*
import com.infocaller.app.domain.model.PhotoCandidate
import com.infocaller.app.domain.model.SocialLookupStatus
import com.infocaller.app.domain.model.SocialProfile
import com.infocaller.app.util.await
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.jsoup.Jsoup

class GamingProfileProviderImpl(private val httpClient: OkHttpClient) : LookupProvider {
    override val id = "gaming_profiles"
    override val name = "Gaming Profiles (Steam/Twitch/Chess)"
    override val version = "1.0.0"
    override val capabilities = setOf(Capability.PUBLIC_PROFILE, Capability.SOCIAL_MATCH, Capability.PROFILE_PHOTO, Capability.ABOUT)
    override val priority = 48
    override val costClass = CostClass.FREE

    override suspend fun lookup(identifier: String, type: String, context: LookupContext): PartialResult? = withContext(Dispatchers.IO) {
        if (type != IdentifierType.USERNAME && type != IdentifierType.FULL_NAME && type != IdentifierType.EMAIL) return@withContext null
        val handle = when (type) {
            IdentifierType.EMAIL -> identifier.substringBefore("@")
            else -> identifier.trim().removePrefix("@")
        }.lowercase().replace(Regex("[^a-z0-9_-]"), "")
        if (handle.length !in 3..32) return@withContext null
        var name: String? = null
        var about: String? = null
        val photos = mutableListOf<PhotoCandidate>()
        val socials = mutableListOf<SocialProfile>()

        // Steam vanity XML.
        try {
            val doc = Jsoup.connect("https://steamcommunity.com/id/$handle/?xml=1")
                .userAgent("Mozilla/5.0 (Linux; Android 14)").timeout(7000)
                .ignoreHttpErrors(true).ignoreContentType(true).get()
            val persona = doc.selectFirst("steamID")?.text()?.trim()
            val avatar = doc.selectFirst("avatarFull")?.text()?.trim()?.takeIf { it.startsWith("http") }
            val state = doc.selectFirst("onlineState")?.text()?.trim()
            val since = doc.selectFirst("memberSince")?.text()?.trim()?.take(40)
            if (!persona.isNullOrBlank() && persona.length in 2..60 && !persona.contains("error", true)) {
                if (name == null) name = persona.take(50)
                val bits = mutableListOf("Steam: $persona")
                state?.takeIf { it.isNotBlank() }?.let { bits.add(it) }
                since?.let { bits.add("since $it") }
                about = ((about?.let { "$it • " } ?: "") + bits.joinToString(" ")).take(400)
                avatar?.let { photos.add(PhotoCandidate(provider = "Steam", url = it, sourcePriority = 57)) }
                socials.add(SocialProfile("Steam", handle, "https://steamcommunity.com/id/$handle", SocialLookupStatus.PUBLIC_MATCH))
            }
        } catch (_: Exception) { }
        // Twitch og tags.
        try {
            val doc = Jsoup.connect("https://www.twitch.tv/$handle")
                .userAgent("Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/120.0 Safari/537.36")
                .timeout(7000).ignoreHttpErrors(true).followRedirects(true).get()
            val title = doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            val img = doc.selectFirst("meta[property=og:image]")?.attr("content")?.takeIf { it.startsWith("http") }
            if (!title.isNullOrBlank() && !title.contains("twitch home", true) && title.length in 2..60) {
                if (name == null) name = title.take(50)
                img?.let { photos.add(PhotoCandidate(provider = "Twitch", url = it, sourcePriority = 54)) }
                socials.add(SocialProfile("Twitch", handle, "https://www.twitch.tv/$handle", SocialLookupStatus.PUBLIC_MATCH))
            }
        } catch (_: Exception) { }
        // Chess.com public player API.
        try {
            val req = okhttp3.Request.Builder().url("https://api.chess.com/pub/player/$handle")
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14)").build()
            httpClient.newCall(req).await().use { r ->
                if (r.isSuccessful) {
                    try {
                        val j = com.google.gson.JsonParser.parseString(r.body?.string()).asJsonObject
                        val uname = j.get("username")?.takeIf { !it.isJsonNull }?.asString
                        if (uname != null && uname.equals(handle, true)) {
                            j.get("name")?.takeIf { !it.isJsonNull }?.asString
                                ?.takeIf { it.length in 2..60 }?.let { if (name == null) name = it }
                            j.get("avatar")?.takeIf { !it.isJsonNull }?.asString
                                ?.takeIf { it.startsWith("http") }?.let {
                                    photos.add(PhotoCandidate(provider = "Chess.com", url = it, sourcePriority = 53))
                                }
                            val league = j.get("league")?.takeIf { !it.isJsonNull }?.asString
                            about = ((about?.let { "$it • " } ?: "") + "Chess.com" + (league?.let { " $it league" } ?: "")).take(400)
                            socials.add(SocialProfile("Chess.com", uname, "https://www.chess.com/member/$uname", SocialLookupStatus.PUBLIC_MATCH))
                        }
                    } catch (_: Exception) { }
                }
            }
        } catch (_: Exception) { }
        if (socials.isEmpty()) return@withContext null
        return@withContext PartialResult(
            name = name, about = about,
            imageUrl = photos.firstOrNull()?.url,
            photoCandidates = photos.distinctBy { it.url }.take(3),
            socialProfiles = socials,
            confidence = 0.66f, source = "Gaming Profiles", providerId = id, providerVersion = version
        )
    }
}
