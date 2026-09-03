package com.infocaller.app.data.remote

import android.content.Context
import android.util.Log
import com.infocaller.app.BuildConfig
import com.infocaller.app.data.remote.model.ApifyLookupItem
import com.infocaller.app.data.remote.model.ApifyLookupRequest
import com.infocaller.app.domain.engine.*
import com.infocaller.app.domain.model.PhotoCandidate
import com.infocaller.app.domain.model.SocialLookupStatus
import com.infocaller.app.domain.model.SocialProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Direct WhatsApp profile extraction via Apify actor eduair94/whatsapp-data-lookup
 * Uses 2 Apify keys (fallback rotation) from BuildConfig APiFY_TOKEN_1/2.
 * If APiFY keys absent, falls back to backend relay automatically via authorized provider.
 * This provider does NOT touch contact name storage — name is handled separately.
 */
class WhatsappApifyProvider(
    private val context: Context,
    private val backendApi: BackendApiService? = null
) : LookupProvider {
    override val id = "whatsapp_apify_direct"
    override val name = "WhatsApp Intelligence (Apify)"
    override val version = "1.0.0"
    override val capabilities = setOf(Capability.WHATSAPP, Capability.PROFILE_PHOTO, Capability.ABOUT, Capability.CITY, Capability.COUNTRY, Capability.CARRIER)
    override val priority = 82
    override val costClass = CostClass.HIGH

    private val client = OkHttpClient.Builder().connectTimeout(30, TimeUnit.SECONDS).readTimeout(90, TimeUnit.SECONDS).build()
    private val gson = com.google.gson.Gson()

    private fun keys(): List<String> {
        val k1 = try { BuildConfig.APIFY_TOKEN_1 } catch(_:Exception) { "" }
        val k2 = try { BuildConfig.APIFY_TOKEN_2 } catch(_:Exception) { "" }
        return listOf(k1, k2).filter { it.isNotBlank() }
    }

    override suspend fun lookup(identifier: String, type: String, context: LookupContext): PartialResult? = withContext(Dispatchers.IO) {
        if (type != IdentifierType.PHONE) return@withContext null
        val normalized = com.infocaller.app.util.PhoneNumberUtils.normalize(identifier)
        val keys = keys()
        // Try direct Apify with key rotation; if no keys, try backend relay
        if (keys.isNotEmpty()) {
            for (key in keys) {
                val res = runApify(normalized, key)
                if (res != null) return@withContext res
                Log.w("WhatsappApify", "Key exhausted/failed, trying next")
            }
        }
        // Fallback to backend relay (if configured)
        try {
            if (backendApi != null) {
                val resp = backendApi.lookupPhone(PhoneLookupRequest(normalized))
                if (resp.isSuccessful) {
                    val d = resp.body() ?: return@withContext null
                    return@withContext PartialResult(
                        imageUrl = d.profileImageUrl,
                        photoCandidates = d.profileImageUrl?.let { listOf(PhotoCandidate(provider="WhatsApp", url=it, sourcePriority=85)) } ?: emptyList(),
                        about = d.about,
                        city = d.city, country = d.country, region = d.region,
                        carrier = d.carrier,
                        socialProfiles = if (d.whatsappStatus=="CONFIRMED") listOf(SocialProfile("WhatsApp", normalized, "https://wa.me/${normalized.filter{it.isDigit()}}", SocialLookupStatus.CONFIRMED)) else emptyList(),
                        confidence = 0.9f, source = "WhatsApp (Backend Relay)", providerId = id, providerVersion = version
                    )
                }
            }
        } catch(e:Exception){ Log.w("WhatsappApify","Backend fallback failed: ${e.message}") }
        null
    }

    private fun runApify(phone: String, token: String): PartialResult? {
        return try {
            val url = "https://api.apify.com/v2/acts/eduair94~whatsapp-data-lookup/run-sync-get-dataset-items?token=$token"
            val payload = ApifyLookupRequest(numbers = listOf(phone))
            val json = gson.toJson(payload)
            val req = Request.Builder().url(url).post(json.toRequestBody("application/json".toMediaType()))
                .addHeader("Content-Type","application/json").build()
            val resp = client.newCall(req).execute()
            if (!resp.isSuccessful) { Log.w("WhatsappApify","Apify ${resp.code} for $phone"); return null }
            val body = resp.body?.string() ?: return null
            val arr = try { gson.fromJson(body, Array<ApifyLookupItem>::class.java) } catch(_:Exception){ null } ?: return null
            val item = arr.firstOrNull() ?: return null
            val photo = item.urlImage ?: (item.lookup?.get("profilePicture") as? String)
            val name = item.lookup?.get("name") as? String ?: item.lookup?.get("displayName") as? String
            val about = item.about ?: (item.lookup?.get("about") as? String) ?: item.description
            val exists = item.exists == true
            // Build PartialResult without relying on name for system contact write (name is skipped separately)
            val social = mutableListOf<SocialProfile>()
            if (exists) social.add(SocialProfile("WhatsApp", phone, "https://wa.me/${phone.filter{it.isDigit()}}", SocialLookupStatus.CONFIRMED))
            if (item.telegram?.username != null) social.add(SocialProfile("Telegram", item.telegram.username, "https://t.me/${item.telegram.username}", SocialLookupStatus.CONFIRMED))

            // If neither photo nor about nor existence, treat as no data
            if (photo.isNullOrBlank() && about.isNullOrBlank() && !exists && item.carrier.isNullOrBlank()) return null

            PartialResult(
                name = name, // will be ignored for system contact name write (see ContactEnrichmentService)
                imageUrl = photo,
                photoCandidates = photo?.let { listOf(PhotoCandidate(provider="WhatsApp", url=it, sourcePriority=85)) } ?: emptyList(),
                about = about,
                carrier = item.carrier,
                city = item.region ?: item.location,
                country = item.country,
                region = item.region,
                socialProfiles = social,
                confidence = if (exists) 0.95f else 0.7f,
                source = "WhatsApp Apify",
                providerId = id, providerVersion = version
            )
        } catch(e:Exception){ Log.e("WhatsappApify","Apify error for $phone: ${e.message}"); null }
    }
}
