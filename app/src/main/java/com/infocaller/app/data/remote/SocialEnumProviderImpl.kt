package com.infocaller.app.data.remote

import android.util.Log
import com.infocaller.app.domain.engine.*
import com.infocaller.app.domain.model.SocialLookupStatus
import com.infocaller.app.domain.model.SocialProfile
import com.infocaller.app.util.PhoneNumberUtils
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

    override suspend fun lookup(identifier: String, type: String, context: LookupContext): PartialResult? = coroutineScope {
        if (type != IdentifierType.PHONE) return@coroutineScope null
        val normalizedPhoneNumber = identifier
        val cleanNumber = normalizedPhoneNumber.filter { it.isDigit() }

        val deferredResults = listOf(
            async { checkWhatsApp(cleanNumber) },
            async { checkTelegram(cleanNumber) }
        )

        val profiles = deferredResults.awaitAll().filterNotNull()
        
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
        try {
            val url = "https://api.whatsapp.com/send/?phone=$cleanNumber&text&type=phone_number&app_absent=0"
            val request = Request.Builder().url(url).header("User-Agent", "Mozilla/5.0").build()
            val text = httpClient.newCall(request).execute().use { response ->
                response.body?.string() ?: ""
            }
            
            if (text.contains("action=open") || text.contains("whatsapp://send")) {
                return@withContext SocialProfile("WhatsApp", cleanNumber, "https://wa.me/$cleanNumber", SocialLookupStatus.PUBLIC_MATCH)
            }
        } catch (e: Exception) { }
        null
    }

    private suspend fun checkTelegram(cleanNumber: String): SocialProfile? = withContext(Dispatchers.IO) {
        try {
            val url = "https://t.me/+$cleanNumber"
            val request = Request.Builder().url(url).header("User-Agent", "Mozilla/5.0").build()
            val text = httpClient.newCall(request).execute().use { response ->
                response.body?.string() ?: ""
            }
            
            if (text.contains("tg://resolve") || text.contains("View in Telegram")) {
                return@withContext SocialProfile("Telegram", cleanNumber, "https://t.me/+$cleanNumber", SocialLookupStatus.POSSIBLE_MATCH)
            }
        } catch (e: Exception) { }
        null
    }
}
