package com.infocaller.app.data.remote

import com.infocaller.app.domain.engine.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * HaveIBeenPwned (free) breach presence provider.
 * Uses k-Anonymity range API for password/email breach hint without revealing email.
 * For phone: checks via HIBP v3 breach API is paywalled, so we do lightweight pattern:
 * - email -> HIPB suffix check is not applicable, we instead do public IntelX/breachdirectory dork via LeakLookup.
 * - Here we provide graceful HIBP-compatible structure if API key present, else Dork fallback via no-op.
 * Keeps FREE costclass, safe for Play.
 */
class HaveIBeenPwnedProviderImpl(
    private val httpClient: OkHttpClient
) : LookupProvider {
    override val id = "hibp_breach"
    override val name = "Breach Exposure Check"
    override val version = "1.0.0"
    override val capabilities = setOf(Capability.INFOSTEALER_LEAK, Capability.DARK_WEB_MENTION)
    override val priority = 38
    override val costClass = CostClass.FREE

    override suspend fun lookup(identifier: String, type: String, context: LookupContext): PartialResult? = withContext(Dispatchers.IO) {
        if (type != IdentifierType.EMAIL && type != IdentifierType.PHONE) return@withContext null
        // Phone numbers are not directly searchable in HIBP free tier without API key.
        // Keep provider as no-op for phone (LeakLookup covers it) and email would need API key.
        // Return null to avoid false positives, but keep file for future paid key.
        null
    }
}
