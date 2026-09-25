package com.infocaller.app.data.remote

import android.content.Context
import com.infocaller.app.domain.engine.IdentifierType
import com.infocaller.app.domain.model.LookupResult
import com.infocaller.app.domain.model.PhotoCandidate
import com.infocaller.app.domain.model.SocialProfile
import com.infocaller.app.util.IdentifierRouter
import com.infocaller.app.util.PhoneNumberUtils
import com.infocaller.app.util.PhotoPolicy
import com.infocaller.app.util.SocialUtils
import com.infocaller.app.util.await
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

class SharedRegistryClient(
    context: Context,
    private val httpClient: OkHttpClient
) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun baseUrl(): String? = try {
        com.infocaller.app.BuildConfig.BACKEND_BASE_URL.trim().trimEnd('/').takeIf { it.isNotBlank() }
    } catch (_: Exception) { null }

    private fun apiKey(): String? = try {
        com.infocaller.app.BuildConfig.INFOCALLER_API_KEY.trim().takeIf { it.isNotBlank() }
    } catch (_: Exception) { null }

    fun isConfigured(): Boolean = baseUrl() != null

    suspend fun lookup(rawPhone: String): LookupResult? = withContext(Dispatchers.IO) {
        val base = baseUrl() ?: return@withContext null
        val e164 = PhoneNumberUtils.normalize(rawPhone)
        val digits = e164.filter { it.isDigit() }
        if (digits.length !in 8..15) return@withContext null
        val body = withTimeoutOrNull(6000L) {
            try {
                val req = Request.Builder()
                    .url("$base/api/v1/registry/lookup/$digits")
                    .header("Accept", "application/json")
                    .header("User-Agent", "InfoCaller-App")
                    .build()
                httpClient.newCall(req).await().use { r ->
                    if (!r.isSuccessful) return@use null
                    try { r.peekBody(200_000L).string() } catch (_: Exception) { null } catch (_: Error) { null }
                }
            } catch (_: Exception) { null } catch (_: Error) { null }
        } ?: return@withContext null
        if (body.length > 200_000) return@withContext null
        parseRecord(e164, body)
    }

    fun publish(result: LookupResult) {
        if (baseUrl() == null || apiKey() == null) return
        if (!isPhone(result.phoneNumber)) return
        if (!hasMeaningfulData(result)) return
        scope.launch { publishBlocking(result) }
    }

    private suspend fun publishBlocking(result: LookupResult) {
        val base = baseUrl() ?: return
        val key = apiKey() ?: return
        val e164 = PhoneNumberUtils.normalize(result.phoneNumber)
        if (e164.filter { it.isDigit() }.length !in 8..15) return
        try {
            val json = buildRecord(e164, result).toString()
            val reqBody = json.toRequestBody("application/json; charset=utf-8".toMediaType())
            val req = Request.Builder()
                .url("$base/api/v1/registry/publish")
                .header("x-api-key", key)
                .header("Accept", "application/json")
                .post(reqBody)
                .build()
            httpClient.newCall(req).await().use { }
        } catch (_: Exception) { } catch (_: Error) { }
    }

    private fun isPhone(raw: String): Boolean = try {
        IdentifierRouter.routeType(raw) == IdentifierType.PHONE
    } catch (_: Exception) { false }

    private fun hasMeaningfulData(r: LookupResult): Boolean {
        return !r.name.isNullOrBlank() ||
            !r.imageUrl.isNullOrBlank() ||
            !r.about.isNullOrBlank() ||
            r.socialProfiles.isNotEmpty()
    }

    private fun buildRecord(e164: String, r: LookupResult): JSONObject {
        val o = JSONObject()
        o.put("number", e164)
        r.name?.takeIf { it.isNotBlank() }?.let { o.put("publicName", it.take(120)) }
        r.alternateName?.takeIf { it.isNotBlank() }?.let { o.put("alternateName", it.take(120)) }
        r.imageUrl?.takeIf { it.isNotBlank() }?.let { o.put("profileImageUrl", it.take(2000)) }
        r.about?.takeIf { it.isNotBlank() }?.let { o.put("about", it.take(1000)) }
        r.city?.takeIf { it.isNotBlank() }?.let { o.put("city", it.take(120)) }
        r.country?.takeIf { it.isNotBlank() }?.let { o.put("country", it.take(120)) }
        r.region?.takeIf { it.isNotBlank() }?.let { o.put("region", it.take(120)) }
        r.timezone?.takeIf { it.isNotBlank() }?.let { o.put("timezone", it.take(80)) }
        r.email?.takeIf { it.isNotBlank() }?.let { o.put("email", it.take(160)) }
        r.carrier?.takeIf { it.isNotBlank() }?.let { o.put("carrier", it.take(120)) }
        r.lineType?.takeIf { it.isNotBlank() }?.let { o.put("lineType", it.take(60)) }
        r.isBusiness?.let { o.put("isBusiness", it) }
        if (r.socialProfiles.isNotEmpty()) {
            o.put("socialProfilesJson", SocialUtils.toJson(r.socialProfiles.take(12)))
        }
        o.put("confidence", if (r.confidence >= 0.8f) "HIGH" else "MEDIUM")
        o.put("source", (r.sources.firstOrNull() ?: "InfoCaller scan").take(80))
        o.put("lastChecked", System.currentTimeMillis())
        return o
    }

    private fun parseRecord(e164: String, body: String): LookupResult? {
        val o = try { JSONObject(body) } catch (_: Exception) { return null }
        if (o.has("error")) return null
        fun str(k: String): String? =
            if (o.has(k) && !o.isNull(k)) o.optString(k, "").takeIf { it.isNotBlank() } else null

        val name = str("publicName")
        val image = str("profileImageUrl")?.takeIf { PhotoPolicy.isUsablePhotoUrl(it) }
        val about = str("about")
        val socialJson = str("socialProfilesJson")
        val socials: List<SocialProfile> = if (socialJson != null) {
            try { SocialUtils.fromJson(socialJson) } catch (_: Exception) { emptyList() }
        } else emptyList()

        if (name.isNullOrBlank() && image.isNullOrBlank() && about.isNullOrBlank() && socials.isEmpty()) return null

        val conf = when (o.optString("confidence", "")) {
            "HIGH" -> 0.85f
            "MEDIUM" -> 0.7f
            else -> 0.8f
        }
        val photoCandidates = if (image != null) {
            listOf(PhotoCandidate(provider = "Shared Registry", url = image))
        } else emptyList()

        return LookupResult(
            phoneNumber = e164,
            name = name,
            nameSource = if (name != null) "Shared Registry" else null,
            alternateName = str("alternateName"),
            imageUrl = image,
            imageSource = if (image != null) "Shared Registry" else null,
            photoCandidates = photoCandidates,
            about = about,
            city = str("city"),
            country = str("country"),
            region = str("region"),
            timezone = str("timezone"),
            email = str("email"),
            emailSource = if (str("email") != null) "Shared Registry" else null,
            carrier = str("carrier"),
            lineType = str("lineType"),
            isBusiness = if (o.has("isBusiness") && !o.isNull("isBusiness")) o.optBoolean("isBusiness") else null,
            socialProfiles = socials,
            sources = listOf("Shared Registry"),
            confidence = conf
        )
    }
}
