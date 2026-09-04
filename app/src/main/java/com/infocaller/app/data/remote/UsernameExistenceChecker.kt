package com.infocaller.app.data.remote

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Shared username-existence checker: bounded concurrency (no more unbounded
 * 40–600-way fan-outs), closed OkHttp responses, consistent login/not-found
 * heuristics. Used by Sherlock / UsernameLookup / UsernameSocialDeep /
 * WhatsMyName / Holehe providers.
 */
object UsernameExistenceChecker {
    const val MAX_CONCURRENCY = 8

    private val NOT_FOUND_MARKERS = listOf(
        "page not found", "not found", "doesn't exist", "user not found",
        "profile not found", "this account doesn", "not a user",
        "login to see", "create an account"
    )

    fun exists(client: OkHttpClient, url: String, minBodyLen: Int = 400, notFoundBodyCap: Int = 8000): Boolean {
        return try {
            val req = Request.Builder().url(url)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/120.0 Safari/537.36")
                .build()
            client.newCall(req).execute().use { resp ->
                if (resp.code != 200) return false
                if (resp.request.url.toString().contains("login", ignoreCase = true)) return false
                val body = resp.body?.string() ?: return false
                if (body.length < minBodyLen) return false
                val lower = body.lowercase()
                if (NOT_FOUND_MARKERS.any { lower.contains(it) } && body.length < notFoundBodyCap) return false
                true
            }
        } catch (_: Exception) {
            false
        }
    }

    suspend fun <T> mapBounded(
        items: List<T>,
        maxConcurrency: Int = MAX_CONCURRENCY,
        block: suspend (T) -> com.infocaller.app.domain.model.SocialProfile?
    ): List<com.infocaller.app.domain.model.SocialProfile> = coroutineScope {
        val sem = Semaphore(maxConcurrency)
        items.map { item ->
            async {
                sem.acquire()
                try {
                    block(item)
                } finally {
                    sem.release()
                }
            }
        }.awaitAll().filterNotNull()
    }
}
