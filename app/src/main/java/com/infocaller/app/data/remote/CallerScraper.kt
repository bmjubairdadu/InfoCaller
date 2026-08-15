package com.infocaller.app.data.remote

import android.content.Context
import android.util.Log
import com.infocaller.app.domain.model.Caller
import com.infocaller.app.domain.model.SpamStatus
import com.infocaller.app.util.PhoneNumberUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import okhttp3.OkHttpClient
import okhttp3.Request
import com.google.gson.Gson
import com.google.gson.JsonObject

class CallerScraper(private val context: Context) {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    /**
     * Orchestrates an automatic search for any number.
     * Tries Truecaller Protocol (if token set), then Google Snippet Scrape.
     */
    suspend fun fetchCallerInfo(phoneNumber: String): Caller? = withContext(Dispatchers.IO) {
        val normalized = PhoneNumberUtils.normalize(phoneNumber)
        val cleanNumber = normalized.filter { it.isDigit() }
        
        // 1. Try Truecaller Protocol (Requires Token in Settings)
        val truecallerName = tryFetchTruecallerName(cleanNumber)
        if (truecallerName != null) {
            return@withContext buildProfile(phoneNumber, truecallerName, "Truecaller")
        }

        // 2. Try Automatic Google Snippet Scrape (No-API OSINT)
        val googleName = tryScrapeGoogleName(cleanNumber)
        if (googleName != null) {
            return@withContext buildProfile(phoneNumber, googleName, "Google Discovery")
        }

        // 3. Final Fallback: Offline Identification (No name returned)
        return@withContext null
    }

    private fun buildProfile(number: String, name: String, source: String): Caller {
        val normalized = PhoneNumberUtils.normalize(number)
        return Caller(
            phoneNumber = number,
            displayName = name.trim(),
            alias = null,
            photoUrl = PhoneNumberUtils.getImageUrl(normalized),
            organization = source,
            country = "Bangladesh",
            region = PhoneNumberUtils.getLocationInfo(normalized) ?: "Unknown",
            carrier = PhoneNumberUtils.getCarrierInfo(normalized, context),
            spamStatus = SpamStatus.SAFE,
            socialMediaLinks = listOf("https://wa.me/${normalized.filter { it.isDigit() }}")
        )
    }

    private suspend fun tryFetchTruecallerName(number: String): String? {
        val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        val token = prefs.getString("truecaller_token", "") ?: ""
        if (token.isBlank()) return null

        return try {
            val url = "https://search5-noneu.truecaller.com/v2/search?q=$number&countryCode=BD&type=4&locAddr=&placement=SEARCHRESULTS,HISTORY,DETAILS&encoding=json"
            val request = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer $token")
                .addHeader("User-Agent", "Truecaller/11.7.5 (Android;10)")
                .build()

            val response = httpClient.newCall(request).execute()
            if (response.isSuccessful) {
                val body = response.body?.string()
                val json = Gson().fromJson(body, JsonObject::class.java)
                val data = json.getAsJsonArray("data")?.firstOrNull()?.asJsonObject
                data?.get("name")?.asString
            } else null
        } catch (e: Exception) {
            Log.e("CallerScraper", "Truecaller error: ${e.message}")
            null
        }
    }

    private suspend fun tryScrapeGoogleName(number: String): String? {
        return try {
            // Smart Dork: Search for the number and look for name-like patterns in titles
            val query = URLEncoder.encode("\"$number\"", StandardCharsets.UTF_8.toString())
            val url = "https://www.google.com/search?q=$query"
            
            val doc = Jsoup.connect(url)
                .userAgent("Mozilla/5.0 (Linux; Android 10; SM-G960F) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Mobile Safari/537.36")
                .timeout(5000)
                .get()

            // Heuristic: Extract text from the first few search results
            val results = doc.select("h3")
            for (res in results) {
                val text = res.text()
                // Simple logic: if a title contains " | " or " - " it might be a profile
                if (text.contains("|") || text.contains("-")) {
                    val potentialName = text.split("|", "-").first().trim()
                    
                    // CRITICAL: Filter out search snippets that aren't actually names
                    val isPlaceholder = potentialName.contains("Network", ignoreCase = true) || 
                                       potentialName.contains("Identity", ignoreCase = true) ||
                                       potentialName.contains("Caller", ignoreCase = true) ||
                                       potentialName.contains("Number", ignoreCase = true) ||
                                       potentialName.contains("Info", ignoreCase = true)

                    if (!isPlaceholder && potentialName.split(" ").size in 2..4) {
                        return potentialName
                    }
                }
            }
            null
        } catch (e: Exception) {
            Log.e("CallerScraper", "Google Scrape error: ${e.message}")
            null
        }
    }

    suspend fun performDeepOSINT(phoneNumber: String): Caller? {
        return fetchCallerInfo(phoneNumber)
    }
}
