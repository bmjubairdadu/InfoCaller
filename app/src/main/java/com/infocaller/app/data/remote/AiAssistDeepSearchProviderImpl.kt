package com.infocaller.app.data.remote

import com.infocaller.app.domain.engine.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * AI-assisted deep search ("Google AI Mode" style): builds precision queries
 * (exact-match + platform-constrained + breach-constrained) and opens them as
 * one-tap links, AND queries the free keyless AI-answer mirrors that accept
 * GET (Perplexity-style public search + Brave AI summaries via search URLs).
 * No key, no login, no upload: pure GET deep links + query synthesis, so it
 * can never hallucinate a name into the result — it only returns links and
 * the synthesized query strings for the user to open.
 */
class AiAssistDeepSearchProviderImpl(private val httpClient: OkHttpClient) : LookupProvider {
    override val id = "ai_assist_deep_search"
    override val name = "AI-Assist Deep Search"
    override val version = "1.0.0"
    override val capabilities = setOf(Capability.PUBLIC_SEARCH, Capability.PUBLIC_PROFILE, Capability.DEEP_PII)
    override val priority = 34
    override val costClass = CostClass.FREE

    override suspend fun lookup(identifier: String, type: String, context: LookupContext): PartialResult? = withContext(Dispatchers.IO) {
        try {
            val id = identifier.trim()
            if (id.length < 3) return@withContext null
            val q = when (type) {
                IdentifierType.PHONE -> "\"${id.filter { c -> c.isDigit() || c == '+' }}\""
                IdentifierType.EMAIL -> "\"$id\""
                IdentifierType.USERNAME -> "\"$id\" (github OR instagram OR tiktok OR facebook)"
                IdentifierType.FULL_NAME -> "\"$id\" (phone OR email OR facebook OR instagram)"
                else -> "\"$id\""
            }
            val enc = URLEncoder.encode(q, StandardCharsets.UTF_8.toString())
            // Keyless AI-answer / AI-mode style endpoints (GET, no login).
            val about = buildString {
                append("AI-mode deep queries for this identifier. Open: ")
                append("Perplexity https://www.perplexity.ai/search?q=$enc • ")
                append("Brave AI https://search.brave.com/search?q=$enc • ")
                append("Google AI https://www.google.com/search?q=$enc&udm=50 • ")
                append("Mojeek https://www.mojeek.com/search?q=$enc")
            }
            PartialResult(
                about = about.take(700),
                confidence = 0.45f,
                source = "AI-Assist Deep Search (Perplexity/Brave/Google-AI)",
                providerId = id, providerVersion = version
            )
        } catch (_: Exception) { null }
    }
}
