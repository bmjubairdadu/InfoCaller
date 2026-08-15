package com.infocaller.app.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import org.jsoup.Jsoup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.util.Log

object OSINTManager {

    data class OSINTLink(
        val title: String,
        val url: String,
        val icon: String? = null
    )

    fun generateDorkLinks(phoneNumber: String): List<OSINTLink> {
        val cleanNumber = phoneNumber.filter { it.isDigit() }
        val formatted = if (cleanNumber.startsWith("880")) cleanNumber else "+$cleanNumber"
        
        return listOf(
            OSINTLink("Google Search", "https://www.google.com/search?q=%22$cleanNumber%22+OR+%22$formatted%22"),
            OSINTLink("LinkedIn", "https://www.google.com/search?q=site:linkedin.com+%22$cleanNumber%22"),
            OSINTLink("Facebook", "https://www.google.com/search?q=site:facebook.com+%22$cleanNumber%22"),
            OSINTLink("Truecaller Web", "https://www.truecaller.com/search/bd/$cleanNumber"),
            OSINTLink("Sync.me", "https://sync.me/search?number=$cleanNumber"),
            OSINTLink("WhatsApp", "https://wa.me/$cleanNumber"),
            OSINTLink("Telegram", "https://t.me/+$cleanNumber")
        )
    }

    /**
     * Attempts to scrape public reverse phone lookup directories that don't require APIs.
     * Note: This is hit-or-miss due to bot protection on many sites.
     */
    suspend fun scrapePublicDirectory(phoneNumber: String): String? = withContext(Dispatchers.IO) {
        val cleanNumber = phoneNumber.filter { it.isDigit() }
        
        // Example: Searching a public directory like whocalled.us (concept)
        try {
            val doc = Jsoup.connect("https://www.whocalled.us/lookup/$cleanNumber")
                .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36")
                .timeout(5000)
                .get()
            
            // This is placeholder logic as most sites have protection.
            // In a real scenario, you'd parse specific CSS selectors.
            return@withContext null 
        } catch (e: Exception) {
            Log.e("OSINT", "Scraping failed: ${e.message}")
            null
        }
    }

    fun openLink(context: Context, url: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e("OSINT", "Failed to open link: $url")
        }
    }
}
