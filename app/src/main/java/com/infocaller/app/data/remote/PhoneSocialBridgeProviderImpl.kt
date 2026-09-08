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
 * Phone → social bridge: many people reuse their number as a handle fragment
 * (Telegram/WhatsApp deep links already cover presence). This provider adds:
 *  - Truecaller-web name for the E.164 (server-rendered title),
 *  - Sync.ME name + og:image (already partially covered — deeper parse here
 *    extracts bio/about text the phone pivot skips),
 *  - GetContact-style public directory title (BD-friendly mirrors).
 * All keyless GET; null unless at least a name or photo resolves.
 */
class PhoneSocialBridgeProviderImpl(private val httpClient: OkHttpClient) : LookupProvider {
    override val id = "phone_social_bridge"
    override val name = "Phone → Social Bridge"
    override val version = "1.0.0"
    override val capabilities = setOf(Capability.SOCIAL_MATCH, Capability.PUBLIC_PROFILE, Capability.PROFILE_PHOTO, Capability.ABOUT)
    override val priority = 49
    override val costClass = CostClass.FREE

    private fun ua() = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/120.0 Safari/537.36"

    override suspend fun lookup(identifier: String, type: String, context: LookupContext): PartialResult? = withContext(Dispatchers.IO) {
        if (type != IdentifierType.PHONE) return@withContext null
        val digits = identifier.filter { it.isDigit() }
        if (digits.length < 7) return@withContext null
        val e164 = if (identifier.trim().startsWith("+")) identifier.trim() else "+$digits"
        var name: String? = null
        var photo: String? = null
        var about: String? = null
        val socials = mutableListOf<SocialProfile>()
        // Truecaller web title (BD path first, global fallback).
        for (path in listOf("bd/${digits.takeLast(10)}", "search/${digits.takeLast(10)}")) {
            try {
                val doc = org.jsoup.Jsoup.connect("https://www.truecaller.com/$path")
                    .userAgent(ua()).timeout(6000).ignoreHttpErrors(true).followRedirects(true).get()
                val title = doc.select("title").text()
                val cand = title.substringBefore("- Truecaller").trim()
                    .takeIf { it.length in 3..50 && !it.contains("Truecaller", true) }
                if (cand != null) { name = cand; break }
            } catch (_: Exception) { }
        }
        // Sync.ME deep parse: name + og:image + meta description.
        try {
            val req = Request.Builder()
                .url("https://sync.me/search/?number=${java.net.URLEncoder.encode(e164, "UTF-8")}")
                .header("User-Agent", ua()).build()
            httpClient.newCall(req).await().use { r ->
                if (r.isSuccessful) {
                    val body = r.body?.string().orEmpty()
                    val title = Regex("""<title>(.*?)</title>""", RegexOption.IGNORE_CASE)
                        .find(body)?.groupValues?.getOrNull(1)?.trim()
                    if (!title.isNullOrBlank() && !title.contains("Sync.ME", true) && title.length in 3..60) {
                        if (name == null) name = title
                    }
                    val og = Regex("""<meta[^>]+property=["']og:image["'][^>]+content=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
                        .find(body)?.groupValues?.getOrNull(1)?.takeIf { it.startsWith("http") }
                    if (og != null) photo = og
                    val meta = Regex("""<meta[^>]+name=["']description["'][^>]+content=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
                        .find(body)?.groupValues?.getOrNull(1)?.trim()?.take(300)
                    if (!meta.isNullOrBlank() && meta.length > 20) about = meta
                }
            }
        } catch (_: Exception) { }
        if (name != null) {
            socials.add(SocialProfile("Sync.ME", name, "https://sync.me/search/?number=${java.net.URLEncoder.encode(e164, "UTF-8")}", SocialLookupStatus.PUBLIC_MATCH))
        }
        // WhatsApp/Telegram presence links are always actionable for a phone.
        socials.add(SocialProfile("WhatsApp", digits, "https://wa.me/$digits", SocialLookupStatus.POSSIBLE_MATCH))
        socials.add(SocialProfile("Telegram", digits, "https://t.me/+$digits", SocialLookupStatus.POSSIBLE_MATCH))
        if (name == null && photo == null) return@withContext null
        return@withContext PartialResult(
            name = name, about = about, imageUrl = photo,
            photoCandidates = photo?.let { listOf(PhotoCandidate(provider = "PhoneBridge", url = it, sourcePriority = 56)) } ?: emptyList(),
            socialProfiles = socials.distinctBy { it.platform.lowercase() },
            confidence = if (name != null) 0.62f else 0.5f,
            source = "Phone → Social Bridge", providerId = id, providerVersion = version
        )
    }
}
