package com.infocaller.app.data.remote

import com.infocaller.app.domain.engine.*
import com.infocaller.app.util.await
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import okhttp3.OkHttpClient
import okhttp3.Request

class PhoneSocialBridgeProviderImpl(private val httpClient: OkHttpClient) : LookupProvider {
    override val id = "phone_social_bridge"
    override val name = "Phone → Social Bridge"
    override val version = "1.0.0"
    override val capabilities = setOf(Capability.PUBLIC_PROFILE, Capability.PUBLIC_SEARCH)
    override val priority = 49
    override val costClass = CostClass.FREE

    private fun ua() = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/120.0 Safari/537.36"

    override suspend fun lookup(identifier: String, type: String, context: LookupContext): PartialResult? = withContext(Dispatchers.IO) {
        try {
            if (type != IdentifierType.PHONE) return@withContext null
            val digits = try { identifier.filter { it.isDigit() } } catch (_: Exception) { "" } catch (_: Error) { "" }
            if (digits.length < 7 || digits.length > 15) return@withContext null
            val e164 = try {
                if (identifier.trim().startsWith("+")) identifier.trim().take(20) else "+$digits"
            } catch (_: Exception) { "+$digits" } catch (_: Error) { "+$digits" }
            var name: String? = null
            // Truecaller web fallback (no login needed): capped, cancellable, never OOM.
            try {
                val tail = digits.takeLast(10)
                for (path in listOf("bd/$tail", "search/$tail")) {
                    try {
                        coroutineContext.ensureActive()
                        val title = com.infocaller.app.util.SafeWebFetch.fetchTitle(
                            httpClient, "https://www.truecaller.com/$path", ua(), 4500L
                        )
                        val cand = com.infocaller.app.util.SafeWebFetch.truecallerTitleToName(title)
                        if (cand != null) { name = cand.take(50); break }
                    } catch (_: Exception) { continue } catch (_: Error) { continue }
                }
            } catch (_: Exception) { } catch (_: Error) { }
            // sync.me name pivot only (capped body, no DOM parse).
            if (name == null) {
                try {
                    coroutineContext.ensureActive()
                    val enc = try { java.net.URLEncoder.encode(e164, "UTF-8") } catch (_: Exception) { e164 } catch (_: Error) { e164 }
                    val body = com.infocaller.app.util.SafeWebFetch.fetchBodyCapped(
                        httpClient, "https://sync.me/search/?number=$enc", ua(), 4500L
                    )
                    val title = com.infocaller.app.util.SafeWebFetch.extractTitle(body ?: "")
                    if (!title.isNullOrBlank() && !title.contains("Sync.ME", true) &&
                        !title.contains("not found", true) && title.length in 3..60) {
                        name = title
                    }
                } catch (_: Exception) { } catch (_: Error) { }
            }
            // Never emit Sync.ME / blind wa.me / t.me profiles: none of these prove a
            // real account opened with this number. Only verified scrapes may add them.
            if (name == null) return@withContext null
            return@withContext PartialResult(
                name = name, about = null, imageUrl = null,
                photoCandidates = emptyList(),
                socialProfiles = emptyList(),
                confidence = 0.62f,
                source = "Phone → Social Bridge", providerId = id, providerVersion = version
            )
        } catch (_: Exception) { null } catch (_: Error) { null }
    }
}
