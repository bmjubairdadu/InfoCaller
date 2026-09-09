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
        return try {
            kotlinx.coroutines.withTimeoutOrNull(3500L) {
                val req = Request.Builder().url(url)
                    .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/120.0 Safari/537.36")
                    .header("Accept-Language", "en-US,en;q=0.9")
                    .build()
                client.newCall(req).await().use { resp ->
                    if (resp.code != 200) return@withTimeoutOrNull false
                    if (resp.request.url.toString().contains("login", ignoreCase = true)) return@withTimeoutOrNull false
                    val body = resp.body?.string() ?: return@withTimeoutOrNull false
                    if (body.length < minBodyLen) return@withTimeoutOrNull false
                    val lower = body.lowercase()
                    if (LOGIN_WALL_MARKERS.any { lower.contains(it) }) return@withTimeoutOrNull false
                    if (NOT_FOUND_MARKERS.any { lower.contains(it) } && body.length < notFoundBodyCap) return@withTimeoutOrNull false
                    true
                }
            } ?: false
        } catch (_: Exception) {
            false
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
