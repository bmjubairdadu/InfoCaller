package com.infocaller.app.data.remote

import android.util.Log
import com.infocaller.app.domain.engine.*
import com.infocaller.app.domain.model.SocialLookupStatus
import com.infocaller.app.domain.model.SocialProfile
import com.infocaller.app.util.PhoneNumberUtils
import com.infocaller.app.util.await
import kotlinx.coroutines.*
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.HttpURLConnection
import java.net.URL

class SocialEnumProviderImpl : SocialProvider {
    override val id: String = "social_enum"
    override val name: String = "Social Registry Scan"
    override val version: String = "1.2.0"
    override val capabilities: Set<Capability> = setOf(Capability.SOCIAL_MATCH, Capability.SERVICE_PRESENCE)
    override val priority: Int = 30
    override val costClass: CostClass = CostClass.FREE

    private val httpClient = OkHttpClient.Builder()
        .followRedirects(false)
        .connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    override suspend fun lookup(identifier: String, type: String, context: LookupContext): PartialResult? = withContext(Dispatchers.IO) {
        if (type != IdentifierType.PHONE) return@withContext null
        val normalizedPhoneNumber = identifier
        val cleanNumber = normalizedPhoneNumber.filter { it.isDigit() }

        val profiles = mutableListOf<SocialProfile>()
        try {
            checkWhatsApp(cleanNumber)?.let { profiles.add(it) }
        } catch (_: Exception) { }
        try {
            kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]?.ensureActive()
            checkTelegram(cleanNumber)?.let { profiles.add(it) }
        } catch (_: Exception) { }

        if (profiles.isNotEmpty()) {
            PartialResult(
                socialProfiles = profiles,
                confidence = 0.8f,
                source = "Social Presence",
                providerId = id,
                providerVersion = version
            )
        } else null
    }

    override suspend fun bulkLookup(identifiers: List<String>, type: String, context: LookupContext): Map<String, PartialResult> = coroutineScope {
        emptyMap()
    }

    private suspend fun checkWhatsApp(cleanNumber: String): SocialProfile? = withContext(Dispatchers.IO) {
        // wa.me / api.whatsapp.com links resolve for ANY number, they never prove
        // the number opened a WhatsApp account. Never emit a guess -> return null
        // so unverified WhatsApp is never shown in the menu.
        null
    }

    private suspend fun checkTelegram(cleanNumber: String): SocialProfile? = withContext(Dispatchers.IO) {
        try {
            // Only show Telegram when t.me/+<digits> resolves to a real public
            // profile with a display name (extracted inline). Otherwise skip.
            UsernameExistenceChecker.fetchVerifiedProfile(
                httpClient, "Telegram", "https://t.me/+$cleanNumber"
            )
        } catch (_: Exception) { null }
    }
}
