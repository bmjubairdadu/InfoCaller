package com.infocaller.app.domain.engine

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class ProviderManager(private val context: Context) {
    private val _providers = MutableStateFlow<List<LookupProvider>>(emptyList())
    val providers = _providers.asStateFlow()

    private val _registryUrl = MutableStateFlow("https://api.infocaller.app/api/v1/providers/manifest")
    val registryUrl = _registryUrl.asStateFlow()

    private val _backendUrl = MutableStateFlow("https://api.infocaller.app/v1/")
    val backendUrl = _backendUrl.asStateFlow()

    fun setRegistryUrl(url: String) {
        _registryUrl.value = url
    }

    fun setBackendUrl(url: String) {
        _backendUrl.value = url
    }

    private val healthStats = mutableMapOf<String, ProviderHealth>()

    fun registerProvider(provider: LookupProvider) {
        val current = _providers.value.toMutableList()
        current.removeAll { it.id == provider.id }
        current.add(provider)
        _providers.value = current
        healthStats[provider.id] = ProviderHealth(providerId = provider.id)
    }

    fun getHealthyProviders(): List<LookupProvider> {
        return _providers.value.filter { 
            val health = healthStats[it.id]
            health == null || health.status != ProviderStatus.BROKEN && health.status != ProviderStatus.DISABLED
        }
    }

    fun reportResult(providerId: String, success: Boolean, durationMs: Long) {
        val health = healthStats[providerId] ?: return
        val newHealth = if (success) {
            health.copy(
                successCount = health.successCount + 1,
                lastSuccess = System.currentTimeMillis(),
                avgDurationMs = (health.avgDurationMs * health.successCount + durationMs) / (health.successCount + 1),
                status = ProviderStatus.HEALTHY
            )
        } else {
            val newFailCount = health.failureCount + 1
            health.copy(
                failureCount = newFailCount,
                lastFailure = System.currentTimeMillis(),
                status = if (newFailCount > 5) ProviderStatus.DEGRADED else health.status
            )
        }
        healthStats[providerId] = newHealth
    }

    data class ProviderHealth(
        val providerId: String,
        val successCount: Int = 0,
        val failureCount: Int = 0,
        val lastSuccess: Long = 0,
        val lastFailure: Long = 0,
        val avgDurationMs: Long = 0,
        val status: ProviderStatus = ProviderStatus.HEALTHY
    )
    
    fun getHealth(providerId: String): ProviderHealth? = healthStats[providerId]
}
