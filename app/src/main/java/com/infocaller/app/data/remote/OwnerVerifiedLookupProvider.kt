package com.infocaller.app.data.remote

import android.content.Context
import com.infocaller.app.domain.engine.*
import com.infocaller.app.util.PhoneNumberUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.util.concurrent.TimeUnit

/**
 * Owner-verified lookup (strict owner-consent model).
 *
 * Reads ONLY public directory rows from Supabase `owner_profiles`:
 * verified=true AND consent_granted=true AND visibility='public'.
 * Lookup key is SHA-256(phone_e164); plain numbers are never stored server-side.
 * No login/consent needed to READ public data; publishing requires OTP + backend.
 */
class OwnerVerifiedLookupProvider(
    private val context: Context,
    private val httpClient: OkHttpClient? = null
) : LookupProvider {
    override val id = "owner_verified"
    override val name = "Verified Owner Directory"
    override val version = "1.0.0"
    override val capabilities = setOf(
        Capability.PUBLIC_SEARCH, Capability.PUBLIC_PROFILE, Capability.PROFILE_PHOTO,
        Capability.BUSINESS, Capability.CITY, Capability.COUNTRY, Capability.ABOUT
    )
    override val priority = 990
    override val costClass = CostClass.FREE

    private val client = httpClient ?: OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private fun config(): Pair<String, String>? {
        return try {
            val url = com.infocaller.app.BuildConfig.SUPABASE_URL.trim().trimEnd('/')
            val key = com.infocaller.app.BuildConfig.SUPABASE_ANON_KEY.trim()
            if (url.isBlank() || key.isBlank()) null else url to key
        } catch (_: Exception) { null }
    }

    override suspend fun lookup(identifier: String, type: String, context: LookupContext): PartialResult? = withContext(Dispatchers.IO) {
        if (type != IdentifierType.PHONE) return@withContext null
        val (baseUrl, anonKey) = config() ?: return@withContext null
        val normalized = PhoneNumberUtils.normalize(identifier)
        if (normalized.isBlank()) return@withContext null
        val hash = try { PhoneHash.sha256Hex(normalized) } catch (_: Exception) { return@withContext null }
        try {
            val url = "$baseUrl/rest/v1/owner_profiles?phone_hash=eq.$hash" +
                "&verified=eq.true&consent_granted=eq.true&visibility=eq.public" +
                "&select=display_name,photo_url,business_name,business_category,country,is_business,report_count,spam_score,updated_at"
            val req = Request.Builder().url(url)
                .addHeader("apikey", anonKey)
                .addHeader("Authorization", "Bearer $anonKey")
                .addHeader("Accept", "application/json")
                .build()
            val resp = client.newCall(req).execute()
            if (resp.code == 404) return@withContext null
            if (!resp.isSuccessful) return@withContext null
            val body = resp.body?.string() ?: return@withContext null
            val arr = JSONArray(body)
            if (arr.length() == 0) return@withContext null
            val o = arr.getJSONObject(0)
            val name = o.optString("display_name", "").takeIf { it.isNotBlank() } ?: return@withContext null
            val photo = o.optString("photo_url", "").takeIf { it.startsWith("https://") }
            val business = o.optString("business_name", "").takeIf { it.isNotBlank() }
            val category = o.optString("business_category", "").takeIf { it.isNotBlank() }
            val country = o.optString("country", "").takeIf { it.isNotBlank() }
            val isBusiness = o.optBoolean("is_business", false)
            val reports = o.optInt("report_count", 0)
            val spam = o.optInt("spam_score", 0)
            val about = buildString {
                if (isBusiness && !business.isNullOrBlank()) append(business)
                if (!category.isNullOrBlank()) { if (isNotEmpty()) append(" • "); append(category) }
                if (reports > 0) { if (isNotEmpty()) append(" • "); append("Reports: $reports") }
                if (spam >= 30) { if (isNotEmpty()) append(" • "); append("Spam risk: $spam/100") }
            }.takeIf { it.isNotBlank() }
            PartialResult(
                identifier = normalized,
                identifierType = IdentifierType.PHONE,
                name = name,
                alternateName = business?.takeIf { it != name },
                imageUrl = photo,
                about = about,
                country = country,
                isBusiness = if (isBusiness) true else null,
                confidence = if (reports > 5) 0.90f else 0.95f,
                source = "Verified Owner",
                providerId = id,
                providerVersion = version
            )
        } catch (_: Exception) { null }
    }
}
