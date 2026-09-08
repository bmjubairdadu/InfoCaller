package com.infocaller.app.data.remote

import com.infocaller.app.domain.engine.*
import com.infocaller.app.domain.model.SocialProfile
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.infocaller.app.util.await
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

    private suspend fun fetchGravatar(email: String): PartialResult? {
        try {
            val hash = md5(email.trim().lowercase())
            val url = "https://www.gravatar.com/$hash.json"
            val request = Request.Builder().url(url).build()
            httpClient.newCall(request).await().use { response ->
                if (!response.isSuccessful) return null
                val json = try {
                    gson.fromJson(response.body?.string(), JsonObject::class.java)
                } catch (_: Exception) {
                    return null
                }
                val entry = try {
                    json?.getAsJsonArray("entry")?.get(0)?.asJsonObject
                } catch (_: Exception) {
                    null
                } ?: return null
                val name = entry.get("displayName")?.takeIf { !it.isJsonNull }?.asString
                val image = entry.get("thumbnailUrl")?.takeIf { !it.isJsonNull }?.asString
                val about = entry.get("aboutMe")?.takeIf { !it.isJsonNull }?.asString
                val city = entry.get("currentLocation")?.takeIf { !it.isJsonNull }?.asString
                if (name.isNullOrBlank() && image.isNullOrBlank() && about.isNullOrBlank()) return null
                return PartialResult(
                    name = name,
                    imageUrl = image,
                    about = about,
                    city = city,
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
