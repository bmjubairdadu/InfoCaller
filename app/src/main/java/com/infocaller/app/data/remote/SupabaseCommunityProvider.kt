package com.infocaller.app.data.remote

import android.content.Context
import android.content.SharedPreferences
import com.infocaller.app.domain.engine.*
import com.infocaller.app.util.PhoneNumberUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

object CommunityConsent {
    private const val PREFS = "community_prefs"
    private const val KEY_ENABLED = "community_lookup_enabled"

    fun isEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, false)
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun prefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }
}

object PhoneHash {
    fun sha256Hex(normalizedE164: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest(normalizedE164.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}

class SupabaseCommunityProvider(
    private val context: Context,
    private val httpClient: OkHttpClient? = null
) : LookupProvider {
    override val id = "supabase_community"
    override val name = "Community Lookup (Supabase)"
    override val version = "1.0.0"
    override val capabilities = setOf(Capability.PUBLIC_SEARCH, Capability.PUBLIC_PROFILE)
    override val priority = 850
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
        // Opt-in only: disabled by default, explicit consent required.
        if (!CommunityConsent.isEnabled(this@SupabaseCommunityProvider.context)) return@withContext null
        val (baseUrl, anonKey) = config() ?: return@withContext null
        val normalized = PhoneNumberUtils.normalize(identifier)
        if (normalized.isBlank()) return@withContext null
        val hash = try { PhoneHash.sha256Hex(normalized) } catch (_: Exception) { return@withContext null }

        try {
            // Lookup-first by hash. No bulk upload. Table: community_lookups(phone_hash PK, display_name, updated_at)
            val url = "$baseUrl/rest/v1/community_lookups?phone_hash=eq.$hash&select=display_name,updated_at,report_count"
            val req = Request.Builder().url(url)
                .addHeader("apikey", anonKey)
                .addHeader("Authorization", "Bearer $anonKey")
                .addHeader("Accept", "application/json")
                .build()
            val resp = client.newCall(req).execute()
            if (!resp.isSuccessful) return@withContext null
            val body = resp.body?.string() ?: return@withContext null
            val arr = JSONArray(body)
            if (arr.length() == 0) return@withContext null
            val obj = arr.getJSONObject(0)
            val name = obj.optString("display_name", "").takeIf { it.isNotBlank() }
            val reportCount = if (obj.has("report_count")) obj.optInt("report_count", 0) else 0
            if (name.isNullOrBlank() && reportCount <= 0) return@withContext null
            PartialResult(
                identifier = normalized,
                identifierType = IdentifierType.PHONE,
                name = name,
                about = if (reportCount > 0) "Community reports: $reportCount" else "Found in community database",
                confidence = 0.75f,
                source = name,
                providerId = id,
                providerVersion = version
            )
        } catch (_: Exception) { null }
    }
}
