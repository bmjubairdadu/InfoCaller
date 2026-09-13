package com.infocaller.app.data.remote

import com.infocaller.app.domain.engine.*
import com.infocaller.app.domain.model.PhotoCandidate
import com.infocaller.app.domain.model.SocialLookupStatus
import com.infocaller.app.domain.model.SocialProfile
import com.infocaller.app.util.ContactUtils
import com.infocaller.app.util.await
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import okhttp3.OkHttpClient
import okhttp3.Request

class SocialAccountEnumeratorProviderImpl(private val httpClient: OkHttpClient) : LookupProvider {
    override val id = "social_account_enumerator"
    override val name = "Social Account Enumerator"
    override val version = "1.0.0"
    override val capabilities = setOf(
        Capability.SOCIAL_MATCH, Capability.PUBLIC_PROFILE,
        Capability.PROFILE_PHOTO, Capability.ABOUT
    )
    override val priority = 51
    override val costClass = CostClass.FREE

    private fun ua() = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/120.0 Safari/537.36"

    override suspend fun lookup(identifier: String, type: String, context: LookupContext): PartialResult? =
        withContext(Dispatchers.IO) {
            when (type) {
                IdentifierType.PHONE -> enumerateByPhone(identifier, context.foundName)
                IdentifierType.EMAIL -> enumerateByEmail(identifier)
                else -> null
            }
        }

    private suspend fun enumerateByPhone(identifier: String, ctxName: String? = null): PartialResult? {
        val digits = identifier.filter { it.isDigit() }
        if (digits.length < 7) return null
        val e164 = if (identifier.trim().startsWith("+")) identifier.trim() else "+$digits"
        val socials = mutableListOf<SocialProfile>()
        var name: String? = null
        var photo: String? = null
        var about: String? = null

        // Capped sync.me title fetch (never unbounded body?.string() — that OOMs manual scans).
        try {
            coroutineContext.ensureActive()
            val enc = try { java.net.URLEncoder.encode(e164, "UTF-8") } catch (_: Exception) { e164 } catch (_: Error) { e164 }
            val body = com.infocaller.app.util.SafeWebFetch.fetchBodyCapped(
                httpClient, "https://sync.me/search/?number=$enc", ua(), 4500L, 80_000
            )
            val title = com.infocaller.app.util.SafeWebFetch.extractTitle(body ?: "")
            // Only use the name pivot; never use sync.me og:image/description:
            // it is a site logo / SEO text, not the person's photo/about.
            // Never emit a "Sync.ME" SocialProfile either (not a real account).
            if (!title.isNullOrBlank() && !title.contains("Sync.ME", true) &&
                !title.contains("not found", true) && title.length in 3..60) {
                name = title
            }
        } catch (_: Exception) { } catch (_: Error) { }

        // No-login Truecaller web title — capped, no Jsoup DOM (DOM OOMs on big pages).
        if (name == null) {
            for (path in listOf("bd/${digits.takeLast(10)}", "search/${digits.takeLast(10)}")) {
                try {
                    coroutineContext.ensureActive()
                    val title = com.infocaller.app.util.SafeWebFetch.fetchTitle(
                        httpClient, "https://www.truecaller.com/$path", ua(), 4500L
                    )
                    val cand = com.infocaller.app.util.SafeWebFetch.truecallerTitleToName(title)
                    if (cand != null) { name = cand.take(50); break }
                } catch (_: Exception) { continue } catch (_: Error) { continue }
            }
        }

        val nameCandidates = listOfNotNull(
            name?.takeIf { it.isNotBlank() },
            ctxName?.takeIf { it.isNotBlank() }
        ).distinct()
        val handleSeeds = mutableListOf<String>()
        for (n in nameCandidates) {
            try {
                if (ContactUtils.isPlaceholderName(n)) continue
            } catch (_: Exception) { }
            val compact = n.lowercase().replace(Regex("[^a-z0-9]"), "")
            if (compact.length in 3..30 && compact.any { it.isLetter() } && compact !in handleSeeds) handleSeeds.add(compact)
            val dotted = n.lowercase().trim().replace(Regex("\\s+"), ".").replace(Regex("[^a-z0-9._]"), "")
            if (dotted.length in 3..30 && dotted.any { it.isLetter() } && dotted != compact && dotted !in handleSeeds) handleSeeds.add(dotted)
        }

        val probes = listOf(
            Triple("Facebook", "https://www.facebook.com/%s", SocialLookupStatus.PUBLIC_MATCH),
            Triple("Instagram", "https://www.instagram.com/%s/", SocialLookupStatus.PUBLIC_MATCH),
            Triple("TikTok", "https://www.tiktok.com/@%s", SocialLookupStatus.PUBLIC_MATCH),
            Triple("YouTube", "https://www.youtube.com/@%s", SocialLookupStatus.POSSIBLE_MATCH),
            Triple("X", "https://x.com/%s", SocialLookupStatus.POSSIBLE_MATCH),
            Triple("Telegram", "https://t.me/%s", SocialLookupStatus.POSSIBLE_MATCH),
        )
        val probeJobs = mutableListOf<Triple<String, String, SocialLookupStatus>>()
        for (seed in handleSeeds.distinct().take(3)) {
            if (seed.length < 3 || seed.length > 30 || seed.contains(" ")) continue
            for ((platform, tmpl, status) in probes) {
                if (socials.any { it.platform.equals(platform, true) && it.username.equals(seed, true) }) continue
                try {
                    probeJobs.add(Triple(platform, tmpl.format(seed), status))
                } catch (_: Exception) { }
            }
        }
        if (probeJobs.isNotEmpty()) {
            val found = UsernameExistenceChecker.mapBounded(probeJobs) { (platform, url, status) ->
                try {
                    // Extract inline preview (display name + avatar) so menu shows
                    // everything without opening the link. Null = not-found/error -> skip.
                    UsernameExistenceChecker.fetchVerifiedProfile(httpClient, platform, url)
                } catch (_: Exception) { null }
            }
            socials.addAll(found)
        }

        // Do NOT add blind wa.me / t.me deep links: a wa.me URL works for ANY number,
        // it does not prove the number opened WhatsApp/Telegram. Only verified
        // scrapes (e.g. TelegramDeep with og:title) may add those platforms.
        // Sync.ME is never a real account -> never added (see above).

        val realSocials = socials.filter {
            !it.platform.equals("Sync.ME", true) && !it.platform.equals("Syncme", true)
        }
        if (name == null && photo == null && realSocials.isEmpty()) return null
        return PartialResult(
            name = name, about = about, imageUrl = photo,
            photoCandidates = photo?.let { listOf(PhotoCandidate(provider = "AccountEnumerator", url = it, sourcePriority = 57)) } ?: emptyList(),
            socialProfiles = realSocials.distinctBy { it.platform.lowercase() + "|" + (it.username?.lowercase().orEmpty()) },
            confidence = when {
                name != null && realSocials.size >= 2 -> 0.78f
                name != null && realSocials.isNotEmpty() -> 0.68f
                realSocials.size >= 2 -> 0.62f
                else -> 0.5f
            },
            source = "Social Account Enumerator", providerId = id, providerVersion = version
        )
    }

    private suspend fun enumerateByEmail(identifier: String): PartialResult? {
        val email = identifier.trim().lowercase()
        if (!email.contains("@")) return null
        val prefix = email.substringBefore("@").take(40)
        if (prefix.length < 2) return null
        val socials = mutableListOf<SocialProfile>()
        var name: String? = null
        var about: String? = null
        var city: String? = null
        val photos = mutableListOf<PhotoCandidate>()

        try {
            val hash = md5(email)
            val req = Request.Builder().url("https://www.gravatar.com/$hash.json")
                .header("User-Agent", ua()).build()
            httpClient.newCall(req).await().use { r ->
                if (r.isSuccessful) {
                    val j = try { r.peekBody(100_000L).string() } catch (_: Exception) { "" } catch (_: Error) { "" }
                    if (j.length > 100_000) return@use
                    if (j.contains("\"entry\"")) {
                        val entry = com.google.gson.JsonParser.parseString(j).asJsonObject
                            .getAsJsonArray("entry").firstOrNull()?.asJsonObject
                        entry?.get("displayName")?.takeIf { !it.isJsonNull }?.asString
                            ?.takeIf { it.length in 2..60 }?.let { name = it }
                        entry?.get("aboutMe")?.takeIf { !it.isJsonNull }?.asString
                            ?.takeIf { it.isNotBlank() }?.let { about = it.take(350) }
                        entry?.get("currentLocation")?.takeIf { !it.isJsonNull }?.asString
                            ?.takeIf { it.isNotBlank() }?.let { city = it.take(80) }
                        entry?.get("thumbnailUrl")?.takeIf { !it.isJsonNull }?.asString
                            ?.takeIf { it.startsWith("http") }?.let {
                                photos.add(PhotoCandidate(provider = "Gravatar", url = it, sourcePriority = 63))
                            }
                        socials.add(SocialProfile("Gravatar", prefix, "https://gravatar.com/$hash", SocialLookupStatus.PUBLIC_MATCH))
                        try {
                            entry?.getAsJsonArray("accounts")?.forEach { a ->
                                val o = a.asJsonObject
                                val svc = o.get("shortname")?.takeIf { !it.isJsonNull }?.asString ?: return@forEach
                                val url = o.get("url")?.takeIf { !it.isJsonNull }?.asString ?: return@forEach
                                if (url.startsWith("http") && url.length > 12) {
                                    socials.add(
                                        SocialProfile(
                                            svc.replaceFirstChar { it.uppercase() }, prefix, url,
                                            SocialLookupStatus.PUBLIC_MATCH
                                        )
                                    )
                                }
                            }
                        } catch (_: Exception) { }
                    }
                }
            }
        } catch (_: Exception) { }

        try {
            val req = Request.Builder().url("https://api.github.com/users/$prefix")
                .header("User-Agent", ua()).build()
            httpClient.newCall(req).await().use { r ->
                if (r.isSuccessful) {
                    val body = try { r.peekBody(100_000L).string() } catch (_: Exception) { "" } catch (_: Error) { "" }
                    if (body.length > 100_000) return@use
                    if (!body.contains("\"message\"")) {
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
                            u.get("avatar_url")?.takeIf { !it.isJsonNull }?.asString
                                ?.takeIf { it.startsWith("http") }?.let {
                                    photos.add(PhotoCandidate(provider = "GitHub", url = it, sourcePriority = 61))
                                }
                            if (socials.none { it.platform.equals("GitHub", true) }) {
                                socials.add(SocialProfile("GitHub", login, "https://github.com/$login", SocialLookupStatus.PUBLIC_MATCH))
                            }
                        }
                    }
                }
            }
        } catch (_: Exception) { }

        if (prefix.length in 3..30 && !prefix.contains(" ")) {
            val probes = listOf(
                Triple("Facebook", "https://www.facebook.com/%s", SocialLookupStatus.PUBLIC_MATCH),
                Triple("Instagram", "https://www.instagram.com/%s/", SocialLookupStatus.PUBLIC_MATCH),
                Triple("TikTok", "https://www.tiktok.com/@%s", SocialLookupStatus.PUBLIC_MATCH),
                Triple("YouTube", "https://www.youtube.com/@%s", SocialLookupStatus.PUBLIC_MATCH),
                Triple("X", "https://x.com/%s", SocialLookupStatus.PUBLIC_MATCH),
                Triple("Telegram", "https://t.me/%s", SocialLookupStatus.PUBLIC_MATCH),
                Triple("Medium", "https://medium.com/@%s", SocialLookupStatus.PUBLIC_MATCH),
            )
            for ((platform, tmpl, status) in probes) {
                if (socials.any { it.platform.equals(platform, true) }) continue
                try {
                    val url = tmpl.format(prefix)
                    // Inline preview extraction; null = not-found/login-wall/error -> skip,
                    // so only really available accounts are kept.
                    UsernameExistenceChecker.fetchVerifiedProfile(httpClient, platform, url)?.let { socials.add(it) }
                } catch (_: Exception) { }
            }
        }

        if (name == null && photos.isEmpty() && socials.size <= 1) return null
        return PartialResult(
            name = name, about = about, city = city,
            imageUrl = photos.firstOrNull()?.url,
            photoCandidates = photos,
            socialProfiles = socials.distinctBy { it.platform.lowercase() },
            confidence = when {
                socials.size >= 3 -> 0.72f
                socials.size == 2 -> 0.62f
                else -> 0.5f
            },
            source = "Social Account Enumerator", providerId = id, providerVersion = version
        )
    }

    private fun md5(s: String): String {
        return try {
            java.security.MessageDigest.getInstance("MD5").digest(s.toByteArray())
                .joinToString("") { "%02x".format(it) }
        } catch (_: Exception) { "" }
    }
}
