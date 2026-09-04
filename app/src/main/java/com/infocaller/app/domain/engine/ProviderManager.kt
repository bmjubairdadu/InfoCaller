package com.infocaller.app.domain.engine

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class ProviderManager(private val context: Context) {
    private val _providers = MutableStateFlow<List<LookupProvider>>(emptyList())
    val providers = _providers.asStateFlow()

    private val healthStats = mutableMapOf<String, ProviderHealth>()

    fun registerProviders(newProviders: List<LookupProvider>) {
        val current = _providers.value.toMutableList()
        newProviders.forEach { provider ->
            current.removeAll { it.id == provider.id }
            current.add(provider)
            if (!healthStats.containsKey(provider.id)) {
                healthStats[provider.id] = ProviderHealth(providerId = provider.id)
            }
        }
        _providers.value = current
    }

    fun registerProvider(provider: LookupProvider) {
        registerProviders(listOf(provider))
    }

    fun getAllProviders(): List<LookupProvider> {
        return _providers.value
    }

    fun updateStatus(providerId: String, status: ProviderStatus) {
        val health = healthStats[providerId] ?: ProviderHealth(providerId)
        healthStats[providerId] = health.copy(status = status)
    }

    fun reportResult(providerId: String, success: Boolean, durationMs: Long) {
        val health = healthStats[providerId] ?: return
        val newHealth = if (success) {
            val newCount = health.successCount + 1
            health.copy(
                successCount = newCount,
                lastSuccess = System.currentTimeMillis(),
                avgDurationMs = if (health.avgDurationMs==0L) durationMs else ((health.avgDurationMs * health.successCount + durationMs) / newCount),
                status = if (health.status == ProviderStatus.DEGRADED || health.status == ProviderStatus.RATE_LIMITED) ProviderStatus.HEALTHY else health.status
            )
        } else {
            val newFailCount = health.failureCount + 1
            health.copy(
                failureCount = newFailCount,
                lastFailure = System.currentTimeMillis(),
                status = when {
                    newFailCount > 15 -> ProviderStatus.BROKEN
                    newFailCount > 5 -> ProviderStatus.DEGRADED
                    else -> health.status
                }
            )
        }
        healthStats[providerId] = newHealth
    }
    fun reportRateLimited(providerId: String) {
        val h = healthStats[providerId] ?: return
        healthStats[providerId] = h.copy(status = ProviderStatus.RATE_LIMITED, lastFailure = System.currentTimeMillis())
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
