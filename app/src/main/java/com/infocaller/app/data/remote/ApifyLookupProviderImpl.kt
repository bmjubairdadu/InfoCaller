package com.infocaller.app.data.remote

import android.util.Log
import com.infocaller.app.BuildConfig
import com.infocaller.app.data.remote.model.ApifyLookupRequest
import com.infocaller.app.domain.engine.*
import com.infocaller.app.domain.model.SocialLookupStatus
import com.infocaller.app.domain.model.SocialProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ApifyLookupProviderImpl(
    private val apifyApiService: ApifyApiService
) : LookupProvider {
    override val id: String = "apify_whatsapp"
    override val name: String = "InfoCaller Intelligence"
    override val version: String = "2.3.0"
    override val capabilities: Set<Capability> = setOf(
        Capability.WHATSAPP, Capability.TELEGRAM, Capability.PROFILE_PHOTO, 
        Capability.ABOUT, Capability.CARRIER, Capability.BUSINESS, Capability.PUBLIC_SEARCH
    )
    override val priority: Int = 10
    override val costClass: CostClass = CostClass.HIGH

    override suspend fun lookup(normalizedPhoneNumber: String, context: LookupContext): PartialResult? = withContext(Dispatchers.IO) {
        // Try Key 1
        var result = performRequest(normalizedPhoneNumber, BuildConfig.APIFY_TOKEN_1)
        
        // Failover to Key 2 if Key 1 fails with account/usage limits (401, 403, 429)
        if (result == null && BuildConfig.APIFY_TOKEN_2.isNotBlank()) {
            Log.w("ApifyLookup", "Key 1 failed or unavailable, trying failover Key 2")
            result = performRequest(normalizedPhoneNumber, BuildConfig.APIFY_TOKEN_2)
        }
        
        return@withContext result
    }

    private suspend fun performRequest(phoneNumber: String, token: String): PartialResult? {
        if (token.isBlank()) return null
        
        try {
            val request = ApifyLookupRequest(numbers = listOf(phoneNumber))
            val response = apifyApiService.lookupNumbers(request, token)
            
            if (response.isSuccessful) {
                val items = response.body()
                val data = items?.firstOrNull() ?: return null
                
                val socialProfiles = mutableListOf<SocialProfile>()
                val cleanNumber = phoneNumber.filter { it.isDigit() }
                
                // 1. Map WhatsApp
                if (data.exists == true) {
                    socialProfiles.add(SocialProfile(
                        platform = "WhatsApp", 
                        username = phoneNumber, 
                        profileUrl = "https://wa.me/$cleanNumber", 
                        status = SocialLookupStatus.CONFIRMED
                    ))
                }
                
                // 2. Map Telegram
                data.telegram?.let { tg ->
                    if (tg.error == null) {
                        socialProfiles.add(SocialProfile(
                            platform = "Telegram", 
                            username = tg.username, 
                            profileUrl = tg.username?.let { "https://t.me/$it" } ?: "https://t.me/+$cleanNumber", 
                            status = SocialLookupStatus.CONFIRMED
                        ))
                    }
                }

                // 3. Extract Name from multiple possible fields
                val lookupName = (data.lookup?.get("name") as? String) ?: data.telegram?.name
                
                // 4. Map About/Bio
                val finalAbout = data.about ?: data.description ?: data.telegram?.bio

                return PartialResult(
                    name = lookupName,
                    imageUrl = data.profilePicture ?: data.urlImage ?: data.telegram?.photo,
                    about = finalAbout,
                    carrier = data.carrier,
                    country = data.country,
                    region = data.region,
                    city = data.location,
                    isBusiness = data.isBusiness == true,
                    socialProfiles = socialProfiles,
                    confidence = 0.85f,
                    source = data.source ?: "Apify/WhatsApp",
                    providerId = id,
                    providerVersion = version
                )
            } else {
                Log.e("ApifyLookup", "HTTP Error: ${response.code()}")
            }
        } catch (e: Exception) {
            Log.e("ApifyLookup", "Request failed: ${e.message}")
        }
        return null
    }

    override suspend fun bulkLookup(normalizedPhoneNumbers: List<String>, context: LookupContext): Map<String, PartialResult> = withContext(Dispatchers.IO) {
        // Bulk implementation omitted for simplicity, but could follow same failover pattern
        emptyMap()
    }
}
