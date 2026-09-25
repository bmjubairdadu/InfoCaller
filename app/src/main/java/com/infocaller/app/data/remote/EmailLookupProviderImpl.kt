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

        fetchGravatar(identifier.trim().lowercase())
    }

    private suspend fun fetchGravatar(email: String): PartialResult? {
        try {
            val hash = md5(email.trim().lowercase())
            val result = fetchGravatarJson(email, hash)
            if (result != null) return result
            val avatarUrl = resolveDirectAvatar(hash) ?: return null
            return PartialResult(
                identifierType = IdentifierType.EMAIL,
                imageUrl = avatarUrl,
                photoCandidates = listOf(
                    com.infocaller.app.domain.model.PhotoCandidate(provider = "Gravatar", url = avatarUrl, sourcePriority = 63)
                ),
                confidence = 0.85f,
                source = "Gravatar",
                providerId = id,
                providerVersion = version
            )
        } catch (_: Exception) {
        }
        return null
    }

    private suspend fun fetchGravatarJson(email: String, hash: String): PartialResult? {
        try {
            val url = "https://www.gravatar.com/$hash.json"
            val request = Request.Builder().url(url).build()
            httpClient.newCall(request).await().use { response ->
                if (!response.isSuccessful) return null
                val body = try { response.peekBody(50_000L).string() } catch (_: Exception) { return null } catch (_: Error) { return null }
                if (body.length > 50_000) return null
                val json = try {
                    gson.fromJson(body, JsonObject::class.java)
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
                if (name.isNullOrBlank() && image.isNullOrBlank() && about.isNullOrBlank()) {
                    val avatarUrl = resolveDirectAvatar(hash) ?: return null
                    return PartialResult(
                        identifierType = IdentifierType.EMAIL,
                        imageUrl = avatarUrl,
                        photoCandidates = listOf(
                            com.infocaller.app.domain.model.PhotoCandidate(provider = "Gravatar", url = avatarUrl, sourcePriority = 63)
                        ),
                        confidence = 0.85f,
                        source = "Gravatar",
                        providerId = id,
                        providerVersion = version
                    )
                }
                val photoList = if (!image.isNullOrBlank()) listOf(
                    com.infocaller.app.domain.model.PhotoCandidate(provider = "Gravatar", url = image, sourcePriority = 63)
                ) else emptyList()
                return PartialResult(
                    identifierType = IdentifierType.EMAIL,
                    name = name,
                    imageUrl = image,
                    photoCandidates = photoList,
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

    private suspend fun resolveDirectAvatar(hash: String): String? {
        return try {
            val probe = Request.Builder().url("https://www.gravatar.com/avatar/$hash?s=512&d=404").get().build()
            var exists = false
            httpClient.newCall(probe).await().use { response ->
                val contentType = response.header("Content-Type").orEmpty()
                exists = response.isSuccessful && contentType.startsWith("image")
            }
            if (exists) "https://www.gravatar.com/avatar/$hash?s=512" else null
        } catch (_: Exception) { null } catch (_: Error) { null }
    }

    private fun md5(input: String): String {
        val md = MessageDigest.getInstance("MD5")
        return md.digest(input.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
