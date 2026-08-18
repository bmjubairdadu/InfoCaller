package com.infocaller.app.data.remote

import android.util.Log
import com.infocaller.app.domain.engine.*
import com.infocaller.app.domain.model.SocialLookupStatus
import com.infocaller.app.domain.model.SocialProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ApifyLookupProviderImpl(
    private val backendService: BackendApiService
) : LookupProvider {
    override val id: String = "apify_whatsapp"
    override val name: String = "InfoCaller Intelligence"
    override val version: String = "2.2.0"
    override val capabilities: Set<Capability> = setOf(
        Capability.WHATSAPP, Capability.TELEGRAM, Capability.PROFILE_PHOTO, 
        Capability.ABOUT, Capability.CARRIER, Capability.BUSINESS, Capability.PUBLIC_SEARCH
    )

    override suspend fun lookup(normalizedPhoneNumber: String, context: LookupContext): PartialResult? = withContext(Dispatchers.IO) {
        try {
            val request = PhoneLookupRequest(phoneNumber = normalizedPhoneNumber)
            val response = backendService.lookupPhone(request)
            if (response.isSuccessful) {
                val data = response.body() ?: return@withContext null
                
                val socialProfiles = mutableListOf<SocialProfile>()
                val cleanNumber = normalizedPhoneNumber.filter { it.isDigit() }
                
                // 1. Map WhatsApp
                data.whatsappStatus?.let { status ->
                    socialProfiles.add(SocialProfile(
                        platform = "WhatsApp", 
                        username = normalizedPhoneNumber, 
                        profileUrl = "https://wa.me/$cleanNumber", 
                        status = try { SocialLookupStatus.valueOf(status) } catch(_: Exception) { SocialLookupStatus.UNKNOWN }
                    ))
                }
                
                // 2. Map Telegram
                data.telegramStatus?.let { status ->
                    socialProfiles.add(SocialProfile(
                        platform = "Telegram", 
                        username = null, 
                        profileUrl = "https://t.me/+$cleanNumber", 
                        status = try { SocialLookupStatus.valueOf(status) } catch(_: Exception) { SocialLookupStatus.UNKNOWN }
                    ))
                }

                // 3. Map Google Results if any
                val googleName = (data.googleResult as? Map<*, *>)?.get("name") as? String

                return@withContext PartialResult(
                    name = data.publicName ?: googleName,
                    imageUrl = data.profileImageUrl,
                    about = data.about,
                    carrier = data.carrier,
                    country = data.country,
                    region = data.region,
                    city = data.city,
                    isBusiness = data.isBusiness == true,
                    socialProfiles = socialProfiles,
                    confidence = when(data.confidence) {
                        "HIGH" -> 0.9f
                        "MEDIUM" -> 0.7f
                        else -> 0.4f
                    },
                    source = data.source ?: name,
                    providerId = id,
                    providerVersion = version
                )
            }
        } catch (e: Exception) {
            Log.e("ApifyLookup", "Lookup failed: ${e.message}")
        }
        null
    }
}
