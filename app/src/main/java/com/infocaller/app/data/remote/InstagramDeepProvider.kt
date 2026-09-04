package com.infocaller.app.data.remote

import android.content.Context
import android.util.Log
import com.infocaller.app.domain.engine.*
import com.infocaller.app.domain.model.PhotoCandidate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup


class InstagramDeepProvider(private val context: Context) : LookupProvider {
    override val id = "instagram_deep"
    override val name = "Instagram Deep Profile"
    override val version = "1.0.0"
    override val capabilities = setOf(Capability.PUBLIC_PROFILE, Capability.SOCIAL_MATCH, Capability.PROFILE_PHOTO, Capability.ABOUT)
    override val priority = 48
    override val costClass = CostClass.FREE

    override suspend fun lookup(identifier: String, type: String, context: LookupContext): PartialResult? = withContext(Dispatchers.IO) {
        if (type != IdentifierType.USERNAME && type != IdentifierType.FULL_NAME) return@withContext null
        val username = identifier.trim().lowercase().replace(Regex("[^a-z0-9._]"), "")
        if (username.length < 3 || username.length > 40) return@withContext null
        try {
            val url = "https://www.instagram.com/$username/"
            val doc = Jsoup.connect(url)
                .userAgent("Mozilla/5.0 (iPhone; CPU iPhone OS 14_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/14.1.2 Mobile/15E148 Safari/604.1")
                .header("X-IG-App-ID","936619743392459")
                .timeout(10000).ignoreHttpErrors(true).followRedirects(true).get()
            val ogTitle = doc.selectFirst("meta[property=og:title]")?.attr("content")
            val ogDesc = doc.selectFirst("meta[property=og:description]")?.attr("content")
            val ogImage = doc.selectFirst("meta[property=og:image]")?.attr("content")?.takeIf { it.startsWith("http") }
            val ldJson = doc.selectFirst("script[type=application/ld+json]")?.data()
            var name: String? = null
            var bio: String? = ogDesc?.take(400)
            var followers: Int? = null
            try {
                if (!ldJson.isNullOrBlank()) {
                    val json = com.google.gson.JsonParser.parseString(ldJson).asJsonObject
                    name = json.get("name")?.takeIf{!it.isJsonNull}?.asString
                    bio = json.get("description")?.takeIf{!it.isJsonNull}?.asString?.take(400) ?: bio
                }
            } catch(_:Exception){}
            if (ogTitle != null) {
                val m = Regex("""(.+?)\s*\(@${Regex.escape(username)}\)""", RegexOption.IGNORE_CASE).find(ogTitle)
                if (m != null) name = m.groupValues[1].trim()
                else name = ogTitle.substringBefore("(").trim().takeIf{ it.length in 3..50 } ?: name
                if (ogDesc != null) {
                    Regex("""([\d,.]+[KM]?)\s+Followers""").find(ogDesc)?.groupValues?.getOrNull(1)?.let { fl -> 
                        bio = "IG $fl Followers | ${bio ?: ""}".take(400)
                    }
                }
            }
            if (name.isNullOrBlank() && ogTitle.isNullOrBlank()) return@withContext null
            if (doc.text().contains("Sorry, this page isn't available", true)) return@withContext null

            val social = listOf(com.infocaller.app.domain.model.SocialProfile("Instagram", username, url, com.infocaller.app.domain.model.SocialLookupStatus.PUBLIC_MATCH))
            return@withContext PartialResult(
                name = name?.takeIf{ it.length in 3..50 && !it.contains("Instagram", true) },
                about = bio,
                imageUrl = ogImage,
                photoCandidates = ogImage?.let{ listOf(PhotoCandidate(provider="Instagram", url=it, sourcePriority=70)) } ?: emptyList(),
                socialProfiles = social,
                confidence = 0.62f, source = name, providerId = id, providerVersion = version
            )
        } catch (_: Exception) { null }
    }
}
