package com.infocaller.app.data.remote

import com.infocaller.app.domain.engine.*
import com.infocaller.app.domain.model.PhotoCandidate
import com.infocaller.app.domain.model.SocialLookupStatus
import com.infocaller.app.domain.model.SocialProfile
import com.infocaller.app.util.await
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Reddit profile extractor (keyless JSON: reddit.com/user/{name}/about.json).
 * Returns karma breakdown, account age, avatar (snoovatar/icon_img) when the
 * account exists; 404/empty JSON = no match, never a guess.
 * Extracts: username, total karma, created date, avatar.
 */
class RedditProfileProviderImpl(private val httpClient: OkHttpClient) : LookupProvider {
    override val id = "reddit_profile"
    override val name = "Reddit Profile"
    override val version = "1.0.0"
    override val capabilities = setOf(Capability.PUBLIC_PROFILE, Capability.SOCIAL_MATCH, Capability.PROFILE_PHOTO, Capability.ABOUT)
    override val priority = 50
    override val costClass = CostClass.FREE

    override suspend fun lookup(identifier: String, type: String, context: LookupContext): PartialResult? = withContext(Dispatchers.IO) {
        if (type != IdentifierType.USERNAME && type != IdentifierType.FULL_NAME && type != IdentifierType.EMAIL) return@withContext null
        val user = when (type) {
            IdentifierType.EMAIL -> identifier.substringBefore("@")
            else -> identifier.trim().removePrefix("@").removePrefix("u/")
        }.lowercase().replace(Regex("[^a-z0-9_-]"), "")
        if (user.length !in 3..20) return@withContext null
        try {
            val req = Request.Builder().url("https://www.reddit.com/user/$user/about.json")
                .header("User-Agent", "InfoCaller-OSINT/1.0 (Android)").build()
            httpClient.newCall(req).await().use { r ->
                if (!r.isSuccessful) return@withContext null
                val body = r.body?.string() ?: return@withContext null
                val data = try {
                    com.google.gson.JsonParser.parseString(body).asJsonObject.getAsJsonObject("data")
                } catch (_: Exception) { return@withContext null }
                if (data == null || data.has("error")) return@withContext null
                val linkKarma = data.get("link_karma")?.takeIf { !it.isJsonNull }?.asInt ?: 0
                val commentKarma = data.get("comment_karma")?.takeIf { !it.isJsonNull }?.asInt ?: 0
                val created = data.get("created_utc")?.takeIf { !it.isJsonNull }?.asLong ?: 0L
                val avatar = data.get("snoovatar_img")?.takeIf { !it.isJsonNull }?.asString?.takeIf { it.startsWith("http") }
                    ?: data.get("icon_img")?.takeIf { !it.isJsonNull }?.asString?.takeIf { it.startsWith("http") }
                val createdStr = if (created > 0) {
                    try { java.text.SimpleDateFormat("MMM yyyy", java.util.Locale.US).format(java.util.Date(created * 1000)) }
                    catch (_: Exception) { null }
                } else null
                val total = linkKarma + commentKarma
                val about = buildString {
                    append("Reddit u/$user · $total karma")
                    createdStr?.let { append(" · since $it") }
                    val sub = data.get("subreddit")?.takeIf { !it.isJsonNull }?.asJsonObject
                    sub?.get("public_description")?.takeIf { !it.isJsonNull }?.asString
                        ?.takeIf { it.isNotBlank() }?.let { append(" · ${it.take(200)}") }
                }.take(400)
                val url = "https://www.reddit.com/user/$user"
                return@withContext PartialResult(
                    name = user, about = about, imageUrl = avatar,
                    photoCandidates = avatar?.let { listOf(PhotoCandidate(provider = "Reddit", url = it, sourcePriority = 55)) } ?: emptyList(),
                    socialProfiles = listOf(SocialProfile("Reddit", user, url, SocialLookupStatus.PUBLIC_MATCH)),
                    confidence = 0.7f, source = "Reddit Profile", providerId = id, providerVersion = version
                )
            }
        } catch (_: Exception) { null }
    }
}
