package com.infocaller.app.data.remote

import com.infocaller.app.util.await
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.OkHttpClient
import okhttp3.Request
object UsernameExistenceChecker {
    private val NOT_FOUND_MARKERS = listOf(
        "page not found", "user not found",
        "profile not found", "this account doesn", "not a user",
        "sorry, this page isn't available", "couldn't find this account",
        "couldnt find this account", "this account doesn't exist",
        "user does not exist", "no such user", "profile unavailable"
    )

    private val LOGIN_WALL_MARKERS = listOf(
        "log in to facebook", "log into facebook",
        "log in to instagram", "log into instagram",
        "login to instagram", "create an account",
        "join facebook", "sign up for facebook",
        "login and join facebook"
    )

    suspend fun exists(client: OkHttpClient, url: String, minBodyLen: Int = 400, notFoundBodyCap: Int = 8000): Boolean {
        return fetchVerifiedProfile(client, "", url, minBodyLen, notFoundBodyCap) != null
    }

    /**
     * Fetch a public profile page and extract inline preview data (display name,
     * avatar, bio) so the UI menu can show everything WITHOUT opening the link.
     * Returns null for not-found / login-wall / error pages -> caller must skip
     * (never show "not found" rows).
     */
    suspend fun fetchVerifiedProfile(
        client: OkHttpClient,
        platform: String,
        url: String,
        minBodyLen: Int = 400,
        notFoundBodyCap: Int = 8000
    ): com.infocaller.app.domain.model.SocialProfile? {
        return try {
            kotlinx.coroutines.withTimeoutOrNull(5000L) {
                val req = Request.Builder().url(url)
                    .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/120.0 Safari/537.36")
                    .header("Accept-Language", "en-US,en;q=0.9")
                    .build()
                client.newCall(req).await().use { resp ->
                    if (resp.code != 200) return@withTimeoutOrNull null
                    if (resp.request.url.toString().contains("login", ignoreCase = true)) return@withTimeoutOrNull null
                    val body = resp.body?.string() ?: return@withTimeoutOrNull null
                    if (body.length < minBodyLen) return@withTimeoutOrNull null
                    val lower = body.lowercase()
                    if (LOGIN_WALL_MARKERS.any { lower.contains(it) }) return@withTimeoutOrNull null
                    if (NOT_FOUND_MARKERS.any { lower.contains(it) } && body.length < notFoundBodyCap) return@withTimeoutOrNull null
                    val doc = try { org.jsoup.Jsoup.parse(body) } catch (_: Exception) { return@withTimeoutOrNull null }
                    val text = doc.text()
                    if (text.length < 120) return@withTimeoutOrNull null
                    // Display name: og:title first, then <title>, cleaned of site suffix.
                    var displayName = doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
                    if (displayName.isNullOrBlank()) {
                        displayName = doc.selectFirst("title")?.text()?.trim()
                            ?.substringBefore("|")?.substringBefore("-")?.substringBefore("•")?.trim()
                    }
                    if (!displayName.isNullOrBlank()) {
                        if (displayName.contains("not found", true) ||
                            displayName.contains("page isn't available", true) ||
                            displayName.contains("content isn't available", true) ||
                            displayName.equals(platform, true) ||
                            displayName.length !in 2..80) displayName = null
                    }
                    // Avatar: og:image, rejecting logos / placeholders.
                    var avatar = doc.selectFirst("meta[property=og:image]")?.attr("content")?.trim()
                        ?.takeIf { it.startsWith("http") }
                    if (avatar != null) {
                        val al = avatar.lowercase()
                        if (al.contains("rsrc.php") || al.contains("placeholder") ||
                            al.contains("default_avatar") || al.contains("default-avatar") ||
                            al.contains("no_photo") || al.contains("no-photo") ||
                            al.contains("anonymous") || al.contains("sync.me") ||
                            (al.contains("logo") && al.length < 120)) avatar = null
                    }
                    val bio = doc.selectFirst("meta[property=og:description]")?.attr("content")?.trim()
                        ?.takeIf { it.isNotBlank() && !it.contains("not found", true) }?.take(300)
                        ?: doc.selectFirst("meta[name=description]")?.attr("content")?.trim()
                            ?.takeIf { it.isNotBlank() && !it.contains("not found", true) }?.take(300)
                    // Strict: need at least a display name to count as "available account".
                    if (displayName.isNullOrBlank()) return@withTimeoutOrNull null
                    val handle = url.trim().trimEnd('/').substringAfterLast("/").removePrefix("@")
                        .takeIf { it.isNotBlank() && it.length <= 60 }
                    com.infocaller.app.domain.model.SocialProfile(
                        platform = platform.ifBlank { "Unknown" },
                        username = handle,
                        profileUrl = url,
                        status = com.infocaller.app.domain.model.SocialLookupStatus.PUBLIC_MATCH,
                        displayName = displayName,
                        avatarUrl = avatar
                    )
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    suspend fun <T> mapBounded(
        items: List<T>,
        maxConcurrency: Int = 8,
        block: suspend (T) -> com.infocaller.app.domain.model.SocialProfile?
    ): List<com.infocaller.app.domain.model.SocialProfile> = coroutineScope {
        val sem = Semaphore(maxConcurrency.coerceIn(1, 16))
        items.map { item ->
            async {
                sem.withPermit {
                    try {
                        coroutineContext[kotlinx.coroutines.Job]?.ensureActive()
                        block(item)
                    } catch (_: Exception) { null }
                }
            }
        }.awaitAll().filterNotNull()
    }
}
