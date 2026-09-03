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

/**
 * Account Enumeration provider.
 * Strictly filters out generic results without specific handles.
 */
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
            async { checkTelegram(cleanNumber) },
            async { checkFacebook(cleanNumber) }
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
            val response = httpClient.newCall(request).execute()
            val text = response.body?.string() ?: ""
            
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
            val response = httpClient.newCall(request).execute()
            val text = response.body?.string() ?: ""
            
            if (text.contains("tg://resolve") || text.contains("View in Telegram")) {
                return@withContext SocialProfile("Telegram", cleanNumber, "https://t.me/+$cleanNumber", SocialLookupStatus.POSSIBLE_MATCH)
            }
        } catch (e: Exception) { }
        null
    }

    // Expanded: LinkedIn via phone is discoverable via Google dork but keep light
    private suspend fun checkFacebook(cleanNumber: String): SocialProfile? = withContext(Dispatchers.IO) {
        // Check if number-derived Facebook vanity exists (true phone->FB requires Graph API, so we skip false positives)
        // Instead we skip blind FB hit and rely on Truecaller/DuckDuckGo signals; return null to avoid spam
        null
    }

    private suspend fun checkLinkedInViaDork(cleanNumber: String): SocialProfile? = withContext(Dispatchers.IO) {
        try {
            val q = "\"$cleanNumber\" site:linkedin.com"
            val url = "https://html.duckduckgo.com/html/?q=${java.net.URLEncoder.encode(q, "UTF-8")}"
            val doc = org.jsoup.Jsoup.connect(url).userAgent("Mozilla/5.0").timeout(6000).ignoreHttpErrors(true).get()
            val href = doc.select("a.result__a").firstOrNull()?.attr("href") ?: return@withContext null
            if (href.contains("linkedin.com/in/")) return@withContext SocialProfile("LinkedIn", null, href, SocialLookupStatus.PUBLIC_MATCH)
        } catch (_: Exception) {}
        null
    }

    private suspend fun checkTruecallerWeb(cleanNumber: String): SocialProfile? = withContext(Dispatchers.IO) {
        try {
            val tc = "https://www.truecaller.com/search/bd/${cleanNumber}"
            val r = httpClient.newCall(Request.Builder().url(tc).header("User-Agent","Mozilla/5.0").build()).execute()
            val b = r.body?.string() ?: ""
            if (b.contains("- Truecaller") && b.length > 500) return@withContext SocialProfile("TruecallerWeb", cleanNumber, tc, SocialLookupStatus.PUBLIC_MATCH)
        } catch (_: Exception) {}
        null
    }
}
