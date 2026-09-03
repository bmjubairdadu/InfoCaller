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
        // Backend keys removed in professional slim build - free providers handle all lookups.
        // No stored keys needed.
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
