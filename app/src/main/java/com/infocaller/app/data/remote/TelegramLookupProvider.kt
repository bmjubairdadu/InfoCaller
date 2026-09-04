package com.infocaller.app.data.remote

import com.infocaller.app.domain.engine.*
import com.infocaller.app.domain.model.SocialLookupStatus
import com.infocaller.app.domain.model.SocialProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup


class TelegramLookupProvider : SocialProvider {
    override val id: String = "telegram_intel"
    override val name: String = "Telegram Intelligence"
    override val version: String = "1.0.0"
    override val capabilities: Set<Capability> = setOf(Capability.TELEGRAM, Capability.TELEGRAM_LINK)
    override val priority: Int = 45
    override val costClass: CostClass = CostClass.FREE

    override suspend fun lookup(identifier: String, type: String, context: LookupContext): PartialResult? = withContext(Dispatchers.IO) {
        if (type != IdentifierType.PHONE) return@withContext null
        val normalizedPhoneNumber = identifier
        val cleanNumber = normalizedPhoneNumber.filter { it.isDigit() }
        
        // Never mark CONFIRMED from a generic page shell: t.me renders title
        // containers for missing numbers too. Require a username/extra block.
        try {
            val url = "https://t.me/+$cleanNumber"
            val doc = Jsoup.connect(url)
                .userAgent("Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/120.0 Safari/537.36")
                .timeout(7000)
                .ignoreHttpErrors(true)
                .followRedirects(true)
                .get()
            
            val hasProfile = doc.select("div.tgme_page_extra").isNotEmpty() || 
                             doc.select("div.tgme_page_title").isNotEmpty()
            
            if (hasProfile) {
                return@withContext PartialResult(
                    socialProfiles = listOf(
                        SocialProfile(
                            platform = "Telegram",
                            username = "+$cleanNumber",
                            profileUrl = url,
                            status = SocialLookupStatus.CONFIRMED
                        )
                    ),
                    confidence = 0.9f,
                    source = "Telegram Web",
                    providerId = id,
                    providerVersion = version
                )
            }
        } catch (_: Exception) {}
        
        return@withContext null
    }

    override suspend fun bulkLookup(identifiers: List<String>, type: String, context: LookupContext): Map<String, PartialResult> = withContext(Dispatchers.IO) {
        if (type != IdentifierType.PHONE) return@withContext emptyMap()
        emptyMap()
    }
}
