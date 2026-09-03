package com.infocaller.app.data.remote

import android.util.Log
import com.infocaller.app.domain.engine.*
import com.infocaller.app.domain.model.SocialLookupStatus
import com.infocaller.app.domain.model.SocialProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Checks for username existence.
 * Strictly filters out false positive home pages.
 */
class SocialSearchProviderImpl : SocialProvider {
    override val id: String = "username_checker_authorized"
    override val name: String = "Social Handle Check"
    override val version: String = "2.0.0"
    override val capabilities: Set<Capability> = setOf(Capability.SOCIAL_MATCH)
    override val priority: Int = 10
    override val costClass: CostClass = CostClass.FREE

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    override suspend fun lookup(identifier: String, type: String, context: LookupContext): PartialResult? {
        return null
    }

    override suspend fun bulkLookup(identifiers: List<String>, type: String, context: LookupContext): Map<String, PartialResult> {
        return emptyMap()
    }

    suspend fun checkUsername(username: String): List<SocialProfile> = coroutineScope {
        val platforms = listOf(
            Platform("GitHub", "https://github.com/$username"),
            Platform("Twitter", "https://twitter.com/$username"),
            Platform("Instagram", "https://instagram.com/$username")
        )

        val deferredResults = platforms.map { platform ->
            async {
                if (exists(platform.url)) {
                    SocialProfile(platform.name, username, platform.url, SocialLookupStatus.CONFIRMED)
                } else null
            }
        }

        deferredResults.awaitAll().filterNotNull()
    }

    private suspend fun exists(url: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url(url).build()
            val response = httpClient.newCall(request).execute()
            
            if (response.code == 200) {
                val body = response.body?.string() ?: ""
                // Advanced filtering for login redirects or "Page Not Found" that return 200
                val lowQualityIndicators = listOf("login", "sign up", "not found", "does not exist")
                if (lowQualityIndicators.any { body.contains(it, ignoreCase = true) }) {
                    return@withContext false
                }
                return@withContext true
            }
            false
        } catch (e: Exception) {
            false
        }
    }

    private data class Platform(val name: String, val url: String)
}
