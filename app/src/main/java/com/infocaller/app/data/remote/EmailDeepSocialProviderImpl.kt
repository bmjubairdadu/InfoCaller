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

class EmailDeepSocialProviderImpl(private val httpClient: OkHttpClient) : LookupProvider {
    override val id = "email_deep_social"
    override val name = "Email Deep Social"
    override val version = "1.0.0"
    override val capabilities = setOf(Capability.PUBLIC_PROFILE, Capability.SOCIAL_MATCH, Capability.PROFILE_PHOTO, Capability.ABOUT, Capability.EMAIL)
    override val priority = 57
    override val costClass = CostClass.FREE

    // Capped GET: unbounded body?.string() OOMs manual scans.
    private suspend fun get(url: String): String? {
        return try {
            val req = Request.Builder().url(url).header("User-Agent", "Mozilla/5.0 (Linux; Android 14)").build()
            httpClient.newCall(req).await().use { r ->
                if (!r.isSuccessful) return null
                try { r.peekBody(100_000L).string().takeIf { it.length <= 100_000 } } catch (_: Exception) { null } catch (_: Error) { null }
            }
        } catch (_: Exception) { null } catch (_: Error) { null }
    }

    private suspend fun headOk(url: String): Boolean {
        return try {
            val req = Request.Builder().url(url).header("User-Agent", "Mozilla/5.0").head().build()
            httpClient.newCall(req).await().use { it.isSuccessful }
        } catch (_: Exception) { false }
    }

    override suspend fun lookup(identifier: String, type: String, context: LookupContext): PartialResult? = withContext(Dispatchers.IO) {
        if (type != IdentifierType.EMAIL) return@withContext null
        val email = identifier.trim().lowercase()
        if (!email.contains("@")) return@withContext null
        val prefix = email.substringBefore("@").take(40)
        if (prefix.length < 2) return@withContext null
        var name: String? = null
        var about: String? = null
        var city: String? = null
        val photos = mutableListOf<PhotoCandidate>()
        val socials = mutableListOf<SocialProfile>()
        val hash = try {
            java.security.MessageDigest.getInstance("MD5").digest(email.toByteArray())
                .joinToString("") { "%02x".format(it) }
        } catch (_: Exception) { return@withContext null }

        try {
            val j = get("https://www.gravatar.com/$hash.json")
            if (!j.isNullOrBlank() && j.contains("\"entry\"")) {
                try {
                    val entry = com.google.gson.JsonParser.parseString(j).asJsonObject
                        .getAsJsonArray("entry").firstOrNull()?.asJsonObject
                    entry?.get("displayName")?.takeIf { !it.isJsonNull }?.asString
                        ?.takeIf { it.length in 2..60 }?.let { name = it }
                    entry?.get("aboutMe")?.takeIf { !it.isJsonNull }?.asString
                        ?.takeIf { it.isNotBlank() }?.let { about = it.take(350) }
                    entry?.get("currentLocation")?.takeIf { !it.isJsonNull }?.asString
                        ?.takeIf { it.isNotBlank() }?.let { city = it.take(80) }
                    val thumbRaw = entry?.get("thumbnailUrl")?.takeIf { !it.isJsonNull }?.asString
                        ?.takeIf { it.startsWith("http") }
                    // Gravatar default renders (d=mp/identicon/blank) are placeholders, never photos.
                    val thumb = try { if (com.infocaller.app.util.PhotoPolicy.isUsablePhotoUrl(thumbRaw)) thumbRaw else null } catch (_: Exception) { null } catch (_: Error) { null }
                    thumb?.let { photos.add(PhotoCandidate(provider = "Gravatar", url = it, sourcePriority = 63)) }
                    socials.add(SocialProfile("Gravatar", prefix, "https://gravatar.com/$hash", SocialLookupStatus.PUBLIC_MATCH))
                    try {
                        entry?.getAsJsonArray("accounts")?.forEach { a ->
                            val o = a.asJsonObject
                            val svc = o.get("shortname")?.takeIf { !it.isJsonNull }?.asString ?: return@forEach
                            val url = o.get("url")?.takeIf { !it.isJsonNull }?.asString ?: return@forEach
                            if (url.startsWith("http") && url.length > 12) {
                                socials.add(SocialProfile(svc.replaceFirstChar { it.uppercase() }, prefix, url, SocialLookupStatus.PUBLIC_MATCH))
                            }
                        }
                    } catch (_: Exception) { }
                } catch (_: Exception) { }
            }
        } catch (_: Exception) { }
        for (size in listOf(400, 200)) {
            val u = "https://www.gravatar.com/avatar/$hash?s=$size&d=404"
            if (headOk(u)) photos.add(PhotoCandidate(provider = "Gravatar", url = u, sourcePriority = 60))
        }
        try {
            val body = get("https://api.github.com/users/$prefix")
            if (!body.isNullOrBlank() && !body.contains("\"message\"")) {
                try {
                    val u = com.google.gson.JsonParser.parseString(body).asJsonObject
                    val login = u.get("login")?.takeIf { !it.isJsonNull }?.asString
                    if (login != null && login.equals(prefix, ignoreCase = true)) {
                        u.get("name")?.takeIf { !it.isJsonNull }?.asString
                            ?.takeIf { it.length in 2..60 }?.let { if (name == null) name = it }
                        u.get("bio")?.takeIf { !it.isJsonNull }?.asString
                            ?.takeIf { it.isNotBlank() }?.let {
                                about = ((about?.let { a -> "$a • " } ?: "") + it).take(400)
                            }
                        u.get("location")?.takeIf { !it.isJsonNull }?.asString
                            ?.takeIf { it.isNotBlank() }?.let { if (city == null) city = it.take(80) }
                        val ghRaw = u.get("avatar_url")?.takeIf { !it.isJsonNull }?.asString
                            ?.takeIf { it.startsWith("http") }
                        val ghAvatar = try { if (com.infocaller.app.util.PhotoPolicy.isUsablePhotoUrl(ghRaw)) ghRaw else null } catch (_: Exception) { null } catch (_: Error) { null }
                        ghAvatar?.let {
                            photos.add(PhotoCandidate(provider = "GitHub", url = it, sourcePriority = 61))
                        }
                        socials.add(SocialProfile("GitHub", login, "https://github.com/$login", SocialLookupStatus.PUBLIC_MATCH))
                    }
                } catch (_: Exception) { }
            }
        } catch (_: Exception) { }
        try {
            val enc = java.net.URLEncoder.encode(prefix, "UTF-8")
            val body = get("https://gitlab.com/api/v4/users?username=$enc")
            if (!body.isNullOrBlank()) {
                try {
                    val arr = com.google.gson.JsonParser.parseString(body).asJsonArray
                    val o = arr.firstOrNull()?.asJsonObject
                    val uname = o?.get("username")?.takeIf { !it.isJsonNull }?.asString
                    if (uname != null && uname.equals(prefix, ignoreCase = true)) {
                        o.get("name")?.takeIf { !it.isJsonNull }?.asString
                            ?.takeIf { it.length in 2..60 }?.let { if (name == null) name = it }
                        o.get("bio")?.takeIf { !it.isJsonNull }?.asString
                            ?.takeIf { it.isNotBlank() }?.let {
                                about = ((about?.let { a -> "$a • " } ?: "") + it).take(400)
                            }
                        val glRaw = o.get("avatar_url")?.takeIf { !it.isJsonNull }?.asString
                            ?.takeIf { it.startsWith("http") }
                        val glAvatar = try { if (com.infocaller.app.util.PhotoPolicy.isUsablePhotoUrl(glRaw)) glRaw else null } catch (_: Exception) { null } catch (_: Error) { null }
                        glAvatar?.let {
                            photos.add(PhotoCandidate(provider = "GitLab", url = it, sourcePriority = 56))
                        }
                        socials.add(SocialProfile("GitLab", uname, "https://gitlab.com/$uname", SocialLookupStatus.PUBLIC_MATCH))
                    }
                } catch (_: Exception) { }
            }
        } catch (_: Exception) { }
        if (socials.isEmpty() && name == null && photos.isEmpty()) return@withContext null
        return@withContext PartialResult(
            name = name, about = about, city = city,
            imageUrl = photos.firstOrNull()?.url,
            photoCandidates = photos.distinctBy { it.url }.take(4),
            socialProfiles = socials.distinctBy { it.platform.lowercase() },
            confidence = if (socials.size >= 2) 0.72f else 0.58f,
            source = "Email Deep Social", providerId = id, providerVersion = version
        )
    }
}
