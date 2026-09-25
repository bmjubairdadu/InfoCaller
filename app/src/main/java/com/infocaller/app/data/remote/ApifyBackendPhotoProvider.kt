package com.infocaller.app.data.remote

import com.infocaller.app.domain.engine.Capability
import com.infocaller.app.domain.engine.CostClass
import com.infocaller.app.domain.engine.IdentifierType
import com.infocaller.app.domain.engine.LookupContext
import com.infocaller.app.domain.engine.LookupProvider
import com.infocaller.app.domain.engine.PartialResult
import com.infocaller.app.domain.model.PhotoCandidate
import com.infocaller.app.util.PhotoPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

class ApifyBackendPhotoProvider(
    private val httpClient: OkHttpClient
) : LookupProvider {
    override val id = "apify_backend_photo"
    override val name = "Premium Intel (last resort)"
    override val version = "1.0.0"
    override val capabilities = setOf(
        Capability.PROFILE_PHOTO,
        Capability.PUBLIC_PROFILE,
        Capability.ABOUT,
        Capability.CARRIER
    )
    override val priority = -100
    override val costClass = CostClass.LOW

    private fun baseUrl(): String? = try {
        com.infocaller.app.BuildConfig.BACKEND_BASE_URL.trim().trimEnd('/').takeIf { it.isNotBlank() }
    } catch (_: Exception) { null }

    private fun apiKey(): String? = try {
        com.infocaller.app.BuildConfig.INFOCALLER_API_KEY.trim().takeIf { it.isNotBlank() }
    } catch (_: Exception) { null }

    fun isConfigured(): Boolean = baseUrl() != null && apiKey() != null

    override suspend fun lookup(
        identifier: String,
        type: String,
        context: LookupContext
    ): PartialResult? = withContext(Dispatchers.IO) {
        if (type != IdentifierType.PHONE) return@withContext null
        val base = baseUrl() ?: return@withContext null
        val key = apiKey() ?: return@withContext null
        val e164 = try {
            com.infocaller.app.util.PhoneNumberUtils.normalize(identifier)
        } catch (_: Exception) { identifier.trim() }
        val digits = e164.filter { it.isDigit() }
        if (digits.length !in 8..15) return@withContext null

        val payload = JSONObject()
            .put("phoneNumber", "+$digits")
            .toString()
            .toRequestBody("application/json; charset=utf-8".toMediaType())

        val started = System.currentTimeMillis()
        val body = try {
            val req = Request.Builder()
                .url("$base/api/v1/lookup/phone")
                .header("x-api-key", key)
                .header("Accept", "application/json")
                .header("User-Agent", "InfoCaller-App")
                .post(payload)
                .build()
            httpClient.newCall(req).execute().use { r ->
                if (!r.isSuccessful) return@withContext null
                try { r.peekBody(200_000L).string() } catch (_: Exception) { null } catch (_: Error) { null }
            }
        } catch (_: Exception) { null } catch (_: Error) { null } ?: return@withContext null
        if (body.isBlank()) return@withContext null

        val o = try { JSONObject(body) } catch (_: Exception) { return@withContext null }
        if (o.has("error")) return@withContext null

        fun str(k: String): String? =
            if (o.has(k) && !o.isNull(k)) o.optString(k, "").takeIf { it.isNotBlank() } else null

        val name = str("publicName")?.take(80)
        val image = str("profileImageUrl")?.take(2000)?.takeIf { PhotoPolicy.isUsablePhotoUrl(it) }
        val about = str("about")?.take(500)
        val carrier = str("carrier")
        val region = str("region")
        val isBusiness = if (o.has("isBusiness") && !o.isNull("isBusiness")) o.optBoolean("isBusiness") else null

        if (name == null && image == null && about == null) return@withContext null

        PartialResult(
            identifier = e164,
            identifierType = IdentifierType.PHONE,
            name = name,
            imageUrl = image,
            photoCandidates = if (image != null) listOf(PhotoCandidate(provider = "Premium Intel", url = image)) else emptyList(),
            about = about,
            city = region,
            country = str("country") ?: "Bangladesh",
            region = region,
            carrier = carrier,
            isBusiness = isBusiness,
            confidence = if (image != null) 0.7f else 0.6f,
            source = "Premium Intel",
            durationMs = System.currentTimeMillis() - started,
            providerId = id,
            providerVersion = version
        )
    }
}
