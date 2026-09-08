package com.infocaller.app.data.remote

import com.infocaller.app.domain.engine.*
import com.infocaller.app.domain.model.PhotoCandidate
import com.infocaller.app.domain.model.SocialLookupStatus
import com.infocaller.app.domain.model.SocialProfile
import com.infocaller.app.util.ContactUtils
import com.infocaller.app.util.await
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup

/**
 * Multi-account social enumerator: one phone number or email can own MANY
 * accounts (personal + business + old handles). This provider fans one
 * PHONE/EMAIL identifier out into every linked social account instead of
 * stopping at the first hit:
 *
 * PHONE path:
 *  1. Sync.ME server-rendered title -> real display name for the number.
 *  2. Truecaller-web title pivot -> second opinion on the name.
 *  3. Every handle fragment derived from the number + discovered names is
 *     verified site-by-site (Facebook / Instagram / TikTok / YouTube /
 *     X / Telegram / WhatsApp links), each kept as its own SocialProfile
 *     so the UI shows ALL of them, not just WhatsApp.
 * EMAIL path:
 *  1. Gravatar profile JSON -> display name + verified external accounts
 *     (each kept as its own SocialProfile).
 *  2. Prefix-as-username probes (GitHub API exact match + TikTok/IG/FB
 *     page checks), each kept separately.
 *
 * Keyless GET only; null unless at least two actionable socials resolve
 * (single WhatsApp-only rows are already covered by the presence probe).
 */
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
                IdentifierType.PHONE -> enumerateByPhone(identifier)
                IdentifierType.EMAIL -> enumerateByEmail(identifier)
                else -> null
            }
        }

    // ---------------- PHONE ----------------

    private suspend fun enumerateByPhone(identifier: String): PartialResult? {
        val digits = identifier.filter { it.isDigit() }
        if (digits.length < 7) return null
        val e164 = if (identifier.trim().startsWith("+")) identifier.trim() else "+$digits"
        val socials = mutableListOf<SocialProfile>()
        var name: String? = null
        var photo: String? = null
        var about: String? = null

        // 1. Sync.ME: server-rendered display name + avatar for the number.
        try {
            val enc = java.net.URLEncoder.encode(e164, "UTF-8")
            val req = Request.Builder().url("https://sync.me/search/?number=$enc")
                .header("User-Agent", ua()).build()
            httpClient.newCall(req).await().use { r ->
                if (r.isSuccessful) {
                    val body = r.body?.string().orEmpty()
                    val title = Regex("""<title>(.*?)</title>""", RegexOption.IGNORE_CASE)
                        .find(body)?.groupValues?.getOrNull(1)?.trim()
                    if (!title.isNullOrBlank() && !title.contains("Sync.ME", true) && title.length in 3..60) {
                        name = title
                        socials.add(SocialProfile("Sync.ME", title, "https://sync.me/search/?number=$enc", SocialLookupStatus.PUBLIC_MATCH))
                    }
                    Regex("""<meta[^>]+property=["']og:image["'][^>]+content=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
                        .find(body)?.groupValues?.getOrNull(1)?.takeIf { it.startsWith("http") }?.let { photo = it }
                    Regex("""<meta[^>]+name=["']description["'][^>]+content=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
                        .find(body)?.groupValues?.getOrNull(1)?.trim()?.take(300)
                        ?.takeIf { it.length > 20 }?.let { about = it }
                }
            }
        } catch (_: Exception) { }

        // 2. Truecaller-web title pivot: second opinion on the name.
        if (name == null) {
            for (path in listOf("bd/${digits.takeLast(10)}", "search/${digits.takeLast(10)}")) {
                try {
                    val doc = Jsoup.connect("https://www.truecaller.com/$path")
                        .userAgent(ua()).timeout(6000).ignoreHttpErrors(true).followRedirects(true).get()
                    val cand = doc.select("title").text().substringBefore("- Truecaller").trim()
                        .takeIf { it.length in 3..50 && !it.contains("Truecaller", true) }
                    if (cand != null) { name = cand; break }
                } catch (_: Exception) { }
            }
        }

        // 3. Handle fragments: every plausible username shape becomes a
        //    candidate handle, verified per platform below. A number like
        //    +8801712345678 yields "1712345678", "01712345678", and — when a
        //    display name exists — its compacted forms ("jubairhosen",
        //    "jubair.hosen").
        val handleSeeds = mutableListOf(digits.takeLast(10), digits)
        if (!name.isNullOrBlank() && !ContactUtils.isPlaceholderName(name)) {
            val compact = name!!.lowercase().replace(Regex("[^a-z0-9]"), "")
            if (compact.length in 3..30) handleSeeds.add(compact)
            val dotted = name!!.lowercase().trim().replace(Regex("\\s+"), ".").replace(Regex("[^a-z0-9._]"), "")
            if (dotted.length in 3..30 && dotted != compact) handleSeeds.add(dotted)
        }

        // 4. Per-platform verification: each hit is its own SocialProfile so
        //    one number shows Facebook + Instagram + TikTok + ... together.
        val probes = listOf(
            Triple("Facebook", "https://www.facebook.com/%s", SocialLookupStatus.PUBLIC_MATCH),
            Triple("Instagram", "https://www.instagram.com/%s/", SocialLookupStatus.PUBLIC_MATCH),
            Triple("TikTok", "https://www.tiktok.com/@%s", SocialLookupStatus.PUBLIC_MATCH),
            Triple("YouTube", "https://www.youtube.com/@%s", SocialLookupStatus.POSSIBLE_MATCH),
            Triple("X", "https://x.com/%s", SocialLookupStatus.POSSIBLE_MATCH),
            Triple("Telegram", "https://t.me/%s", SocialLookupStatus.POSSIBLE_MATCH),
        )
        for (seed in handleSeeds.distinct().take(4)) {
            if (seed.length < 3 || seed.length > 30 || seed.contains(" ")) continue
            for ((platform, tmpl, status) in probes) {
                if (socials.any { it.platform.equals(platform, true) && it.username.equals(seed, true) }) continue
                try {
                    val url = tmpl.format(seed)
                    if (UsernameExistenceChecker.exists(httpClient, url)) {
                        socials.add(SocialProfile(platform, seed, url, status))
                    }
                } catch (_: Exception) { }
            }
        }

        // Messaging deep links are always actionable for a valid number.
        if (socials.none { it.platform.equals("WhatsApp", true) }) {
            socials.add(SocialProfile("WhatsApp", digits, "https://wa.me/$digits", SocialLookupStatus.POSSIBLE_MATCH))
        }
        if (socials.none { it.platform.equals("Telegram", true) }) {
            socials.add(SocialProfile("Telegram", digits, "https://t.me/+$digits", SocialLookupStatus.POSSIBLE_MATCH))
        }

        // Keep the good stuff: drop messaging-only rows when real socials exist
        // is the UI's job — here we return everything; the merger dedupes.
        val realSocials = socials.filterNot {
            it.platform.equals("WhatsApp", true) || it.platform.equals("Telegram", true)
        }
        if (name == null && photo == null && realSocials.isEmpty()) return null
        return PartialResult(
            name = name, about = about, imageUrl = photo,
            photoCandidates = photo?.let { listOf(PhotoCandidate(provider = "AccountEnumerator", url = it, sourcePriority = 57)) } ?: emptyList(),
            socialProfiles = socials.distinctBy { it.platform.lowercase() + "|" + (it.username?.lowercase().orEmpty()) },
            confidence = when {
                name != null && realSocials.size >= 2 -> 0.78f
                name != null && realSocials.isNotEmpty() -> 0.68f
                realSocials.size >= 2 -> 0.62f
                else -> 0.5f
            },
            source = "Social Account Enumerator", providerId = id, providerVersion = version
        )
    }

    // ---------------- EMAIL ----------------

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

        // 1. Gravatar profile JSON: display name + verified linked accounts.
        try {
            val hash = md5(email)
            val req = Request.Builder().url("https://www.gravatar.com/$hash.json")
                .header("User-Agent", ua()).build()
            httpClient.newCall(req).await().use { r ->
                if (r.isSuccessful) {
                    val j = r.body?.string().orEmpty()
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

        // 2. Prefix-as-username: GitHub exact API match + per-platform probes.
        try {
            val req = Request.Builder().url("https://api.github.com/users/$prefix")
                .header("User-Agent", ua()).build()
            httpClient.newCall(req).await().use { r ->
                if (r.isSuccessful) {
                    val body = r.body?.string().orEmpty()
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
                Triple("YouTube", "https://www.youtube.com/@%s", SocialLookupStatus.POSSIBLE_MATCH),
                Triple("X", "https://x.com/%s", SocialLookupStatus.POSSIBLE_MATCH),
                Triple("Telegram", "https://t.me/%s", SocialLookupStatus.POSSIBLE_MATCH),
                Triple("Medium", "https://medium.com/@%s", SocialLookupStatus.POSSIBLE_MATCH),
            )
            for ((platform, tmpl, status) in probes) {
                if (socials.any { it.platform.equals(platform, true) }) continue
                try {
                    val url = tmpl.format(prefix)
                    if (UsernameExistenceChecker.exists(httpClient, url)) {
                        socials.add(SocialProfile(platform, prefix, url, status))
                    }
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
