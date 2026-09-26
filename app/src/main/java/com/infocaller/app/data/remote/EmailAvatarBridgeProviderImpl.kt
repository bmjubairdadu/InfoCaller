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
 * Email → avatar/name resolution through two free APIs:
 *  - GitHub user search by public email (returns the real account + avatar when
 *    the user has their email set public on GitHub)
 *  - GitLab avatar-by-email endpoint (returns the registered avatar when it is
 *    NOT a placeholder identicon)
 */
class EmailAvatarBridgeProviderImpl(
    private val httpClient: OkHttpClient
) : LookupProvider {
    override val id = "email_avatar_bridge"
    override val name = "Email Avatar Bridge"
    override val version = "1.0.0"
    override val capabilities = setOf(
        Capability.PROFILE_PHOTO, Capability.PUBLIC_PROFILE, Capability.ABOUT, Capability.SOCIAL_MATCH
    )
    override val priority = 79
    override val costClass = CostClass.FREE

    private val rawClient = OkHttpClient.Builder()
        .connectTimeout(6, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    private suspend fun getJson(url: String): String? {
        return try {
            val req = Request.Builder().url(url)
                .header("User-Agent", "InfoCaller-OSINT/2.0")
                .header("Accept", "application/vnd.github+json, application/json")
                .build()
            httpClient.newCall(req).await().use { r ->
                if (!r.isSuccessful) return@use null
                try { r.peekBody(120_000L).string().takeIf { it.length <= 120_000 } } catch (_: Exception) { null } catch (_: Error) { null }
            }
        } catch (_: Exception) { null } catch (_: Error) { null }
    }

    override suspend fun lookup(identifier: String, type: String, context: LookupContext): PartialResult? =
        withContext(Dispatchers.IO) {
            if (type != IdentifierType.EMAIL) return@withContext null
            val email = identifier.trim().lowercase()
            if (!email.contains("@") || email.length > 120) return@withContext null

            var name: String? = null
            var about: String? = null
            var city: String? = null
            var company: String? = null
            val photos = mutableListOf<PhotoCandidate>()
            val socials = mutableListOf<SocialProfile>()

            // --- GitHub: search users by public email ---
            try {
                val q = java.net.URLEncoder.encode("$email in:email", "UTF-8")
                val body = getJson("https://api.github.com/search/users?q=$q&per_page=1")
                if (!body.isNullOrBlank()) {
                    val login = try {
                        val arr = com.google.gson.JsonParser.parseString(body).asJsonObject
                            .getAsJsonArray("items")
                        arr.firstOrNull()?.asJsonObject?.get("login")?.takeIf { !it.isJsonNull }?.asString
                    } catch (_: Exception) { null } catch (_: Error) { null }
                    if (!login.isNullOrBlank()) {
                        // Fetch the full profile for avatar, bio, location, name
                        val full = getJson("https://api.github.com/users/$login")
                        if (!full.isNullOrBlank() && !full.contains("\"message\"")) {
                            try {
                                val u = com.google.gson.JsonParser.parseString(full).asJsonObject
                                u.get("name")?.takeIf { !it.isJsonNull }?.asString
                                    ?.takeIf { it.isNotBlank() && it.length in 2..60 }?.let { name = it }
                                u.get("bio")?.takeIf { !it.isJsonNull }?.asString
                                    ?.takeIf { it.isNotBlank() }?.let {
                                        about = it.take(350)
                                    }
                                u.get("location")?.takeIf { !it.isJsonNull }?.asString
                                    ?.takeIf { it.isNotBlank() }?.let { city = it.take(80) }
                                u.get("company")?.takeIf { !it.isJsonNull }?.asString
                                    ?.takeIf { it.isNotBlank() }?.let {
                                        company = it.take(60)
                                        about = ((about?.let { a -> "$a • " } ?: "") + "Company: ${it.take(60)}").take(400)
                                    }
                                val blog = u.get("blog")?.takeIf { !it.isJsonNull }?.asString
                                    ?.takeIf { it.startsWith("http") }
                                val avatarRaw = u.get("avatar_url")?.takeIf { !it.isJsonNull }?.asString
                                    ?.takeIf { it.startsWith("http") }
                                val avatar = try {
                                    avatarRaw?.takeIf { com.infocaller.app.util.PhotoPolicy.isUsablePhotoUrl(it) }
                                } catch (_: Exception) { null } catch (_: Error) { null }
                                avatar?.let {
                                    photos.add(PhotoCandidate(provider = "GitHub", url = it, sourcePriority = 74))
                                }
                                socials.add(
                                    SocialProfile(
                                        "GitHub", login, "https://github.com/$login",
                                        SocialLookupStatus.CONFIRMED, name, avatar
                                    )
                                )
                                if (!blog.isNullOrBlank() && socials.size < 6) {
                                    socials.add(
                                        SocialProfile("Website", login, blog, SocialLookupStatus.PUBLIC_MATCH)
                                    )
                                }
                            } catch (_: Exception) { } catch (_: Error) { }
                        }
                    }
                }
            } catch (_: Exception) { } catch (_: Error) { }

            // --- Unavatar: aggregated avatar-by-email across many providers ---
            // fallback=false makes it 404 instead of serving a placeholder, so a 200
            // with a real image body means at least one provider has a real avatar.
            try {
                val q = java.net.URLEncoder.encode(email, "UTF-8")
                val req = Request.Builder().url("https://unavatar.io/email/$q?fallback=false&json=false")
                    .header("User-Agent", "Mozilla/5.0 (Linux; Android 14)")
                    .build()
                rawClient.newCall(req).await().use { r ->
                    if (r.isSuccessful) {
                        val ct = r.header("Content-Type").orEmpty()
                        if (ct.startsWith("image")) {
                            val url = "https://unavatar.io/email/$q?fallback=false"
                            val ok = try { com.infocaller.app.util.PhotoPolicy.isUsablePhotoUrl(url) } catch (_: Exception) { false } catch (_: Error) { false }
                            if (ok && photos.none { it.url == url }) {
                                photos.add(PhotoCandidate(provider = "Unavatar", url = url, sourcePriority = 58))
                            }
                        }
                    }
                }
            } catch (_: Exception) { } catch (_: Error) { }

            // --- GitLab: avatar-by-email (skip placeholder identicons) ---
            try {
                val q = java.net.URLEncoder.encode(email, "UTF-8")
                val body = getJson("https://gitlab.com/api/v4/avatar?email=$q")
                if (!body.isNullOrBlank()) {
                    try {
                        val raw = com.google.gson.JsonParser.parseString(body).asJsonObject
                            .get("avatar_url")?.takeIf { !it.isJsonNull }?.asString
                        if (!raw.isNullOrBlank() && raw.startsWith("http") &&
                            !raw.contains("d=identicon") && !raw.contains("/identicon")
                        ) {
                            val ok = try { com.infocaller.app.util.PhotoPolicy.isUsablePhotoUrl(raw) } catch (_: Exception) { false } catch (_: Error) { false }
                            if (ok && photos.none { it.url == raw }) {
                                photos.add(PhotoCandidate(provider = "GitLab", url = raw, sourcePriority = 66))
                            }
                        }
                    } catch (_: Exception) { } catch (_: Error) { }
                }
            } catch (_: Exception) { } catch (_: Error) { }

            if (photos.isEmpty() && name == null && socials.isEmpty()) return@withContext null

            PartialResult(
                name = name,
                about = about,
                city = city,
                imageUrl = photos.firstOrNull()?.url,
                photoCandidates = photos.distinctBy { it.url },
                socialProfiles = socials.distinctBy { it.platform.lowercase() },
                confidence = if (socials.any { it.platform == "GitHub" }) 0.88f else 0.6f,
                source = name, providerId = id, providerVersion = version
            )
        }
}
