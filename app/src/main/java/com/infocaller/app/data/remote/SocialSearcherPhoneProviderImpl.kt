package com.infocaller.app.data.remote

import com.infocaller.app.domain.engine.*
import com.infocaller.app.util.await
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import okhttp3.OkHttpClient
import okhttp3.Request

class SocialSearcherPhoneProviderImpl(private val httpClient: OkHttpClient) : LookupProvider {
    override val id = "social_searcher_phone"
    override val name = "Social Searcher (phone pivot)"
    override val version = "1.0.0"
    override val capabilities = setOf(Capability.PUBLIC_PROFILE, Capability.PUBLIC_SEARCH)
    override val priority = 47
    override val costClass = CostClass.FREE

    private fun ua() = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/120.0 Safari/537.36"

    override suspend fun lookup(identifier: String, type: String, context: LookupContext): PartialResult? = withContext(Dispatchers.IO) {
        if (type != IdentifierType.PHONE) return@withContext null
        val digits = try { identifier.filter { it.isDigit() } } catch (_: Exception) { return@withContext null } catch (_: Error) { return@withContext null }
        if (digits.length < 7 || digits.length > 15) return@withContext null
        val e164 = try { if (identifier.trim().startsWith("+")) identifier.trim().take(20) else "+$digits" } catch (_: Exception) { "+$digits" } catch (_: Error) { "+$digits" }
        try {
            var name: String? = null
            try {
                coroutineContext.ensureActive()
                val enc = try { java.net.URLEncoder.encode(e164, "UTF-8") } catch (_: Exception) { e164 } catch (_: Error) { e164 }
                // Capped fetch — never unbounded body?.string() (OOMs manual scans).
                val body = com.infocaller.app.util.SafeWebFetch.fetchBodyCapped(
                    httpClient, "https://sync.me/search/?number=$enc", ua(), 4500L, 80_000
                )
                val title = com.infocaller.app.util.SafeWebFetch.extractTitle(body ?: "")
                // Name pivot only. Never use sync.me og:image (site logo) as a
                // profile photo, never emit a Sync.ME "account".
                if (!title.isNullOrBlank() && !title.contains("Sync.ME", true) &&
                    !title.contains("not found", true) && title.length in 3..60) name = title
            } catch (_: Exception) { } catch (_: Error) { }
            if (name == null) return@withContext null
            PartialResult(
                name = name,
                imageUrl = null,
                photoCandidates = emptyList(),
                socialProfiles = emptyList(),
                confidence = 0.6f,
                source = "Social Searcher (Sync.ME phone pivot)",
                providerId = id, providerVersion = version
            )
        } catch (_: Exception) { null } catch (_: Error) { null }
    }
}
