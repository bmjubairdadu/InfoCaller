package com.infocaller.app.data.remote

import android.content.Context
import android.util.Log

/**
 * Manages API keys for providers.
 * Rotation logic removed in favor of backend secret management where possible.
 */
class ProviderKeyManager(private val context: Context) {
    private val providerKeys = mutableMapOf<String, String>()

    init {
        // Defensive: backend key removed - BuildConfig field no longer exists (optional key pruned). Keep no-op.
        try { providerKeys["backend"] = com.infocaller.app.BuildConfig.BACKEND_API_KEY } catch (_: Exception) {}
    }

    /**
     * Retrieves the current active key for a provider.
     */
    fun getActiveKey(providerId: String): String? {
        return providerKeys[providerId.lowercase()]
    }

    /**
     * Mark key rotation logic as DELETED.
     * Do not bypass provider restrictions or rate limits by rotating identities.
     */
    @Deprecated("Rotation logic removed to comply with security requirements.")
    fun rotateKey(providerId: String, currentKey: String) {
        // NO-OP
    }
}
