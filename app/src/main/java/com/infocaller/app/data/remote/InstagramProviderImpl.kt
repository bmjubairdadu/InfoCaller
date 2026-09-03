package com.infocaller.app.data.remote

import android.content.Context
import android.util.Log
import com.infocaller.app.domain.engine.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Advanced Instagram Metadata provider.
 * Requires an Instagram sessionid provided by the user in Settings.
 * Derived from toutatis and yesitsme logic.
 */
class InstagramProviderImpl(private val context: Context) : LookupProvider {
    override val id: String = "instagram_osint"
    override val name: String = "Instagram Advanced"
    override val version: String = "1.0.0"
    override val capabilities: Set<Capability> = setOf(Capability.SOCIAL_MATCH, Capability.ABOUT, Capability.PROFILE_PHOTO)
    override val priority: Int = 15
    override val costClass: CostClass = CostClass.LOW

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    override suspend fun lookup(identifier: String, type: String, context: LookupContext): PartialResult? = withContext(Dispatchers.IO) {
        if (type != IdentifierType.PHONE) return@withContext null
        val normalizedPhoneNumber = identifier
        val sessionId = getSessionId() ?: return@withContext null
        val cleanNumber = normalizedPhoneNumber.filter { it.isDigit() }

        try {
            // Reimplementation of Instagram USERS_LOOKUP logic from yesitsme/toutatis
            // This attempts to find an IG account linked to the phone number.
            val requestBody = "q=$cleanNumber&device_id=android-${java.util.UUID.randomUUID()}&skip_recovery=1"
            val mediaType = "application/x-www-form-urlencoded".toMediaTypeOrNull()
            
            val request = Request.Builder()
                .url("https://i.instagram.com/api/v1/users/lookup/")
                .addHeader("User-Agent", "Instagram 101.0.0.15.120 Android")
                .addHeader("X-IG-App-ID", "124024574287414")
                .addHeader("Cookie", "sessionid=$sessionId")
                .post(requestBody.toRequestBody(mediaType))
                .build()

            val response = httpClient.newCall(request).execute()
            if (response.isSuccessful) {
                val body = response.body?.string() ?: return@withContext null
                val json = com.google.gson.JsonParser.parseString(body).asJsonObject
                val user = json.getAsJsonObject("user") ?: return@withContext null
                
                val username = user.get("username")?.asString
                if (username != null) {
                    return@withContext fetchProfileData(username)
                }
            }
        } catch (e: Exception) {
            Log.e("InstagramProvider", "Lookup failed: ${e.message}")
        }
        null
    }

    override suspend fun bulkLookup(identifiers: List<String>, type: String, context: LookupContext): Map<String, PartialResult> = withContext(Dispatchers.IO) {
        if (type != IdentifierType.PHONE) return@withContext emptyMap()
        emptyMap()
    }

    @Suppress("UNUSED")
    suspend fun fetchProfileData(username: String): PartialResult? = withContext(Dispatchers.IO) {
        val sessionId = getSessionId() ?: return@withContext null
        
        try {
            val request = Request.Builder()
                .url("https://i.instagram.com/api/v1/users/web_profile_info/?username=$username")
                .addHeader("User-Agent", "Mozilla/5.0 (iPhone; CPU iPhone OS 14_8 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/14.1.2 Mobile/15E148 Safari/604.1")
                .addHeader("x-ig-app-id", "936619743392459")
                .addHeader("Cookie", "sessionid=$sessionId")
                .build()

            val response = httpClient.newCall(request).execute()
            if (response.isSuccessful) {
                val body = response.body?.string() ?: return@withContext null
                val json = com.google.gson.JsonParser.parseString(body).asJsonObject
                val user = json.getAsJsonObject("data")?.getAsJsonObject("user") ?: return@withContext null
                
                val fullName = user.get("full_name")?.asString
                val biography = user.get("biography")?.asString
                val profilePic = user.get("profile_pic_url_hd")?.asString ?: user.get("profile_pic_url")?.asString
                val isBusiness = user.get("is_business_account")?.asBoolean ?: false
                
                val followers = user.getAsJsonObject("edge_followed_by")?.get("count")?.asInt ?: 0
                val following = user.getAsJsonObject("edge_follow")?.get("count")?.asInt ?: 0

                return@withContext PartialResult(
                    name = fullName,
                    about = "IG: $biography | Followers: $followers | Following: $following",
                    imageUrl = profilePic,
                    isBusiness = isBusiness,
                    confidence = 0.9f,
                    source = name,
                    providerId = id,
                    providerVersion = version
                )
            }
        } catch (e: Exception) {
            Log.e("InstagramProvider", "Fetch failed: ${e.message}")
        }
        null
    }

    private fun getSessionId(): String? {
        val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        return prefs.getString("instagram_session_id", "")?.takeIf { it.isNotBlank() }
    }
}
