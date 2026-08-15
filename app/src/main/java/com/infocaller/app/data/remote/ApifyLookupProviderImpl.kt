package com.infocaller.app.data.remote

import android.util.Log
import com.infocaller.app.data.remote.model.ApifyLookupRequest
import com.infocaller.app.domain.engine.*
import com.infocaller.app.domain.model.SocialLookupStatus
import com.infocaller.app.domain.model.SocialProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ApifyLookupProviderImpl(
    private val context: android.content.Context,
    private val backendService: BackendApiService
) : LookupProvider {
    override val id: String = "apify_whatsapp"
    override val name: String = "InfoCaller Intelligence"
    override val version: String = "2.1.0"
    override val capabilities: Set<Capability> = setOf(
        Capability.WHATSAPP, Capability.TELEGRAM, Capability.PROFILE_PHOTO, 
        Capability.ABOUT, Capability.CARRIER, Capability.BUSINESS
    )

    override suspend fun lookup(normalizedPhoneNumber: String, context: LookupContext): PartialResult = withContext(Dispatchers.IO) {
        val app = this@ApifyLookupProviderImpl.context.applicationContext as com.infocaller.app.InfoCallerApplication
        val backendUrl = app.providerManager.backendUrl.value

        if (backendUrl.isBlank()) {
            return@withContext tryDevDirectLookup(normalizedPhoneNumber)
        }

        try {
            val request = PhoneLookupRequest(phoneNumber = normalizedPhoneNumber)
            
            val response = backendService.lookupPhone(request)
            if (response.isSuccessful) {
                val data = response.body()
                if (data != null) {
                    val socialProfiles = mutableListOf<SocialProfile>()
                    
                    val cleanNumber = normalizedPhoneNumber.filter { it.isDigit() }
                    data.whatsappStatus?.let {
                        try {
                            socialProfiles.add(SocialProfile("WhatsApp", normalizedPhoneNumber, "https://wa.me/$cleanNumber", SocialLookupStatus.valueOf(it)))
                        } catch (_: Exception) {}
                    }
                    
                    data.telegramStatus?.let {
                        try {
                            socialProfiles.add(SocialProfile("Telegram", normalizedPhoneNumber, "https://t.me/$cleanNumber", SocialLookupStatus.valueOf(it)))
                        } catch (_: Exception) {}
                    }

                    return@withContext PartialResult(
                        name = data.publicName,
                        imageUrl = data.profileImageUrl,
                        about = data.about,
                        carrier = data.carrier,
                        country = data.country,
                        region = data.region,
                        socialProfiles = socialProfiles,
                        confidence = if (data.confidence == "HIGH") 0.9f else if (data.confidence == "MEDIUM") 0.6f else 0.3f,
                        source = name,
                        providerId = id,
                        providerVersion = version
                    )
                }
            } else {
                Log.e("BackendLookup", "Error: ${response.code()} ${response.message()}")
            }
        } catch (e: Exception) {
            Log.e("BackendLookup", "Lookup failed", e)
        }
        PartialResult()
    }

    private suspend fun tryDevDirectLookup(phoneNumber: String): PartialResult {
        val prefs = context.getSharedPreferences("app_prefs", android.content.Context.MODE_PRIVATE)
        val devToken = prefs.getString("apify_dev_token", "") ?: ""
        
        if (devToken.isBlank()) {
            Log.w("ApifyLookup", "Development mode: No direct Apify token configured.")
            return PartialResult()
        }

        try {
            // We use a temporary Retrofit instance for direct Apify calls in dev mode
            val retrofit = retrofit2.Retrofit.Builder()
                .baseUrl("https://api.apify.com/v2/")
                .addConverterFactory(retrofit2.converter.gson.GsonConverterFactory.create())
                .build()
            val apifyService = retrofit.create(ApifyApiService::class.java)
            
            val request = ApifyLookupRequest(numbers = listOf(phoneNumber))
            val response = apifyService.lookupNumbers(request, devToken)
            
            if (response.isSuccessful) {
                val item = response.body()?.firstOrNull()
                if (item != null) {
                    val socialProfiles = mutableListOf<SocialProfile>()
                    
                    if (item.exists == true) {
                        socialProfiles.add(SocialProfile("WhatsApp", phoneNumber, "https://wa.me/${item.number}", SocialLookupStatus.CONFIRMED))
                    }
                    
                    item.telegram?.let { tg ->
                        socialProfiles.add(SocialProfile("Telegram", phoneNumber, tg.username?.let { "https://t.me/$it" }, SocialLookupStatus.UNKNOWN))
                    }

                    return PartialResult(
                        name = item.lookup?.get("name") as? String,
                        imageUrl = item.urlImage,
                        carrier = item.carrier,
                        socialProfiles = socialProfiles,
                        confidence = 0.7f,
                        source = "$name (Dev Direct)"
                    )
                }
            }
        } catch (e: Exception) {
            Log.e("ApifyLookup", "Dev direct lookup failed", e)
        }
        return PartialResult()
    }
}
