package com.infocaller.app.security

import okhttp3.CertificatePinner
import okhttp3.OkHttpClient

object NetworkPins {

    private fun configuredHosts(): List<String> = try {
        com.infocaller.app.BuildConfig.PINNED_HOSTS.split(',')
            .map { it.trim() }
            .filter { it.isNotBlank() }
    } catch (_: Exception) { emptyList() } catch (_: Error) { emptyList() }

    private fun configuredPins(): List<String> = try {
        com.infocaller.app.BuildConfig.CERT_PINS.split(',')
            .map { it.trim() }
            .filter { it.isNotBlank() }
    } catch (_: Exception) { emptyList() } catch (_: Error) { emptyList() }

    fun isEnabled(): Boolean = configuredHosts().isNotEmpty() && configuredPins().isNotEmpty()

    fun apply(builder: OkHttpClient.Builder): OkHttpClient.Builder {
        return try {
            val hosts = configuredHosts()
            val pins = configuredPins()
            if (hosts.isEmpty() || pins.isEmpty()) return builder
            val pinner = CertificatePinner.Builder()
            hosts.forEach { host -> pins.forEach { pin -> pinner.add(host, pin) } }
            builder.certificatePinner(pinner.build())
        } catch (_: Exception) {
            builder
        } catch (_: Error) {
            builder
        }
    }
}
