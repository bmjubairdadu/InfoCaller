package com.infocaller.app.data.remote

import com.infocaller.app.util.await
import kotlinx.coroutines.ensureActive
import okhttp3.OkHttpClient
import okhttp3.Request
object UsernameExistenceChecker {

    private val NOT_FOUND_MARKERS = listOf(
        "page not found", "not found", "doesn't exist", "user not found",
        "profile not found", "this account doesn", "not a user",
        "login to see", "create an account"
    )

    suspend fun exists(client: OkHttpClient, url: String, minBodyLen: Int = 400, notFoundBodyCap: Int = 8000): Boolean {
        return try {
            val req = Request.Builder().url(url)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/120.0 Safari/537.36")
                .build()
            client.newCall(req).await().use { resp ->
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
        maxConcurrency: Int = 1,
        block: suspend (T) -> com.infocaller.app.domain.model.SocialProfile?
    ): List<com.infocaller.app.domain.model.SocialProfile> {
        val out = ArrayList<com.infocaller.app.domain.model.SocialProfile>(items.size)
        for (item in items) {
            try {
                kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]?.ensureActive()
            } catch (_: Exception) { }
            try {
                val profile = block(item)
                if (profile != null) out.add(profile)
            } catch (_: Exception) { }
        }
        return out
    }
}
