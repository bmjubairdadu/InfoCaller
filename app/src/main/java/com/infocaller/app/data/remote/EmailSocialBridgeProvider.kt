package com.infocaller.app.data.remote

import com.infocaller.app.domain.engine.*
import com.infocaller.app.domain.model.SocialLookupStatus
import com.infocaller.app.domain.model.SocialProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import java.net.URLEncoder

/**
 * Bridge: discovered email -> social hunt (Gravatar, GitHub, LinkedIn via dorks)
 * Free, no keys. Uses email prefix as username pivot.
 */
class EmailSocialBridgeProvider(private val client: OkHttpClient) : LookupProvider {
    override val id = "email_social_bridge"
    override val name = "Email → Social Bridge"
    override val version = "1.0.0"
    override val capabilities = setOf(Capability.PUBLIC_PROFILE, Capability.SOCIAL_MATCH, Capability.PROFILE_PHOTO)
    override val priority = 54
    override val costClass = CostClass.FREE

    override suspend fun lookup(identifier: String, type: String, context: LookupContext): PartialResult? = withContext(Dispatchers.IO) {
        if (type != IdentifierType.EMAIL) return@withContext null
        if (!identifier.contains("@")) return@withContext null
        val email = identifier.trim().lowercase()
        val prefix = email.substringBefore("@")
        if (prefix.length < 3) return@withContext null
        val domain = email.substringAfter("@")
        try {
            val profiles = mutableListOf<SocialProfile>()
            // 1. Gravatar exists
            val hash = java.security.MessageDigest.getInstance("MD5").digest(email.toByteArray()).joinToString(""){"%02x".format(it)}
            val gravReq = Request.Builder().url("https://www.gravatar.com/$hash.json")
                .header("User-Agent","Mozilla/5.0 (Linux; Android 14)").build()
            val gravResp = client.newCall(gravReq).execute()
            var gravName: String? = null
            var gravPhoto: String? = null
            if (gravResp.isSuccessful) {
                val j = gravResp.body?.string() ?: ""
                if (j.contains("\"entry\"")) {
                    try {
                        val obj = com.google.gson.JsonParser.parseString(j).asJsonObject.getAsJsonArray("entry").firstOrNull()?.asJsonObject
                        gravName = obj?.get("displayName")?.takeIf{!it.isJsonNull}?.asString
                        gravPhoto = obj?.get("thumbnailUrl")?.takeIf{!it.isJsonNull}?.asString
                        if (!gravPhoto.isNullOrBlank()) profiles.add(SocialProfile("Gravatar", prefix, "https://gravatar.com/$hash", SocialLookupStatus.PUBLIC_MATCH))
                    } catch(_:Exception){}
                }
            }
            // 2. GitHub username exists (prefix)
            val ghReq = Request.Builder().url("https://github.com/$prefix").header("User-Agent","Mozilla/5.0").build()
            val ghResp = try { client.newCall(ghReq).execute() } catch(_:Exception){ null }
            if (ghResp?.code == 200) {
                val b = ghResp.body?.string()?.lowercase() ?: ""
                if (!b.contains("page not found") && !b.contains("not found") && ghResp.request.url.toString().contains(prefix, true)) {
                    profiles.add(SocialProfile("GitHub", prefix, "https://github.com/$prefix", SocialLookupStatus.PUBLIC_MATCH))
                }
            }
            // 3. LinkedIn via DDG dork by email
            try {
                val q = "\"$email\" site:linkedin.com/in"
                val url = "https://html.duckduckgo.com/html/?q=${URLEncoder.encode(q, "UTF-8")}"
                val doc = Jsoup.connect(url).userAgent("Mozilla/5.0 (Linux; Android 14)").timeout(7000).ignoreHttpErrors(true).get()
                val href = doc.select("a.result__a").firstOrNull()?.attr("href")
                if (href != null && href.contains("linkedin.com/in/")) profiles.add(SocialProfile("LinkedIn", prefix, href, SocialLookupStatus.PUBLIC_MATCH))
            } catch(_:Exception){}

            if (profiles.isEmpty() && gravName == null && gravPhoto == null) return@withContext null
            PartialResult(
                name = gravName,
                imageUrl = gravPhoto,
                photoCandidates = gravPhoto?.let{ listOf(com.infocaller.app.domain.model.PhotoCandidate(provider="Gravatar", url=it, sourcePriority=60)) } ?: emptyList(),
                socialProfiles = profiles,
                confidence = if (profiles.size >= 2) 0.7f else 0.55f,
                source = "Email→Social Bridge",
                providerId = id, providerVersion = version
            )
        } catch(_:Exception){ null }
    }
}
