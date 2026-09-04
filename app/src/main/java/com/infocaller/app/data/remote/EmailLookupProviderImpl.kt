package com.infocaller.app.data.remote

import com.infocaller.app.domain.engine.*
import com.infocaller.app.domain.model.SocialProfile
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.security.MessageDigest

class EmailLookupProviderImpl(
    private val httpClient: OkHttpClient,
    private val gson: Gson
) : LookupProvider {
    override val id: String = "email_lookup"
    override val name: String = "Email Intelligence"
    override val version: String = "1.0.0"
    override val capabilities: Set<Capability> = setOf(
        Capability.PUBLIC_PROFILE,
        Capability.PROFILE_PHOTO,
        Capability.ABOUT,
        Capability.CITY,
        Capability.COUNTRY
    )
    override val priority: Int = 80
    override val costClass: CostClass = CostClass.FREE

    override suspend fun lookup(
        identifier: String,
        type: String,
        context: LookupContext
    ): PartialResult? = withContext(Dispatchers.IO) {
        if (type != IdentifierType.EMAIL) return@withContext null
        
        val gravatarResult = fetchGravatar(identifier)
        
        gravatarResult
    }

    private fun fetchGravatar(email: String): PartialResult? {
        try {
            val hash = md5(email.trim().lowercase())
            val url = "https://www.gravatar.com/$hash.json"
            val request = Request.Builder().url(url).build()
            val response = httpClient.newCall(request).execute()
            
            if (response.isSuccessful) {
                val json = gson.fromJson(response.body?.string(), JsonObject::class.java)
                val entry = json.getAsJsonArray("entry")?.get(0)?.asJsonObject
                
                return PartialResult(
                    name = entry?.get("displayName")?.asString,
                    imageUrl = entry?.get("thumbnailUrl")?.asString,
                    about = entry?.get("aboutMe")?.asString,
                    city = entry?.get("currentLocation")?.asString,
                    confidence = 0.9f,
                    source = "Gravatar",
                    providerId = id,
                    providerVersion = version
                )
            }
        } catch (_: Exception) {
        }
        return null
    }

    private fun md5(input: String): String {
        val md = MessageDigest.getInstance("MD5")
        return md.digest(input.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
