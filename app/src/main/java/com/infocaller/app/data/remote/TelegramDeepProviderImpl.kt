package com.infocaller.app.data.remote

import com.infocaller.app.domain.engine.*
import com.infocaller.app.domain.model.PhotoCandidate
import com.infocaller.app.domain.model.SocialLookupStatus
import com.infocaller.app.domain.model.SocialProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup

class TelegramDeepProviderImpl(private val httpClient: OkHttpClient) : LookupProvider {
    override val id = "telegram_deep"
    override val name = "Telegram Deep Profile"
    override val version = "1.0.0"
    override val capabilities = setOf(Capability.PUBLIC_PROFILE, Capability.SOCIAL_MATCH, Capability.PROFILE_PHOTO, Capability.ABOUT, Capability.TELEGRAM_LINK)
    override val priority = 53
    override val costClass = CostClass.FREE

    // OOM-safe scrape: capped body + regex meta extraction, never a Jsoup DOM fetch.
    // Jsoup.connect().get() downloads unbounded HTML and crashes manual scans.
    private suspend fun scrape(url: String): Triple<String?, String?, String?> {
        return try {
            coroutineContext.ensureActive()
            val body = com.infocaller.app.util.SafeWebFetch.fetchBodyCapped(
                httpClient, url,
                "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/120.0 Safari/537.36",
                5000L, 80_000
            ) ?: return Triple(null, null, null)
            val head = try { body.take(80_000) } catch (_: Exception) { return Triple(null, null, null) } catch (_: Error) { return Triple(null, null, null) }
            fun meta(prop: String): String? = try {
                Regex("""<meta[^>]+property=["']""" + prop + """["'][^>]*content=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
                    .find(head)?.groupValues?.getOrNull(1)?.trim()
                    ?: Regex("""<meta[^>]+content=["']([^"']+)["'][^>]*property=["']""" + prop + """["']""", RegexOption.IGNORE_CASE)
                        .find(head)?.groupValues?.getOrNull(1)?.trim()
            } catch (_: Exception) { null } catch (_: Error) { null }
            // Live probe (2026-09-13): t.me/<anything> returns HTTP 200 with generic
            // og:title "Telegram: Contact @handle" + logo for EVERY handle, real or not.
            // Generic contact-chrome titles are NOT proof -> only a real display name counts.
            // Real public t.me profiles render og:title = the person's/channel name.
            val rawTitle = meta("og:title")?.trim().orEmpty()
            val title = rawTitle.takeIf {
                it.length in 2..80 &&
                    !it.equals("Telegram: Contact", true) &&
                    !it.startsWith("Telegram: Contact @", true) &&
                    !it.startsWith("Join group chat", true) &&
                    !it.contains("Telegram", true)
            }
            val desc = meta("og:description")?.takeIf { it.isNotBlank() && !it.contains("Telegram", true) }?.take(350)
            // Official Telegram chrome (telegram.org/img/...) is a logo, never a profile photo.
            val rawImg = meta("og:image")?.takeIf { it.startsWith("http") && it.length in 20..2000 }
            val img = try { if (com.infocaller.app.util.PhotoPolicy.isUsablePhotoUrl(rawImg)) rawImg else null } catch (_: Exception) { null } catch (_: Error) { null }
            if (title == null && img == null) Triple(null, null, null) else Triple(title, desc, img)
        } catch (_: Exception) { Triple(null, null, null) } catch (_: Error) { Triple(null, null, null) }
    }

    override suspend fun lookup(identifier: String, type: String, context: LookupContext): PartialResult? = withContext(Dispatchers.IO) {
        when (type) {
            IdentifierType.USERNAME, IdentifierType.FULL_NAME -> {
                val handle = identifier.trim().removePrefix("@").lowercase()
                    .replace(Regex("[^a-z0-9_]"), "")
                if (handle.length !in 4..32) return@withContext null
                val url = "https://t.me/$handle"
                val (name, bio, img) = scrape(url)
                if (name == null && img == null) return@withContext null
                return@withContext PartialResult(
                    name = name?.take(50), about = bio, imageUrl = img,
                    photoCandidates = img?.let { listOf(PhotoCandidate(provider = "Telegram", url = it, sourcePriority = 59)) } ?: emptyList(),
                    socialProfiles = listOf(SocialProfile("Telegram", handle, url, SocialLookupStatus.PUBLIC_MATCH)),
                    confidence = if (name != null) 0.7f else 0.52f,
                    source = "Telegram Deep", providerId = id, providerVersion = version
                )
            }
            IdentifierType.PHONE -> {
                val digits = identifier.filter { it.isDigit() }
                if (digits.length < 7) return@withContext null
                val url = "https://t.me/+$digits"
                val (name, bio, img) = scrape(url)
                if (name == null && img == null) return@withContext null
                return@withContext PartialResult(
                    name = name?.take(50), about = bio, imageUrl = img,
                    photoCandidates = img?.let { listOf(PhotoCandidate(provider = "Telegram", url = it, sourcePriority = 59)) } ?: emptyList(),
                    socialProfiles = listOf(SocialProfile("Telegram", digits, url, SocialLookupStatus.PUBLIC_MATCH)),
                    confidence = if (name != null) 0.72f else 0.55f,
                    source = "Telegram Deep", providerId = id, providerVersion = version
                )
            }
            else -> return@withContext null
        }
    }
}
