package com.infocaller.app.util

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.util.concurrent.TimeUnit

/**
 * When a caller-ID name embeds a location token (e.g. "Ashraful vai sujansaha"),
 * resolve that token into a Bangladesh administrative place via Nominatim and
 * render "District, Upazila" style precision. Purely display + note; never
 * renames the saved contact. Capped to 1 lookup per distinct name.
 */
object VillageResolver {

    private const val PREFS = "village_resolver_prefs"
    private const val KEY_SEEN = "village_seen_v1"

    data class ResolvedVillage(
        val query: String,
        val district: String? = null,
        val upazila: String? = null,
        val union: String? = null,
        val village: String? = null,
        val display: String,
    )

    private val HONORIFICS = setOf("vai", "bhai", "apa", "apu", "uncle", "aunty", "sir", "mam", "mama", "chacha", "khalu", "dada", "dadi", "nana", "nani")
    private val STOP = setOf("and", "or", "the", "a", "an", "of", "for", "in", "on", "at", "to", "with")

    /** Heuristic token that likely is a place, not the person name. Null when none. */
    fun extractPlaceToken(fullName: String?): String? {
        if (fullName.isNullOrBlank()) return null
        val raw = fullName.trim()
        // Split on whitespace and punctuation that often joins name + place.
        val tokens = raw.split(Regex("[\\s,\\-_/()\\[\\].]+")).map { it.trim() }.filter { it.length >= 3 }
        if (tokens.size < 2) return null
        // Drop leading person-name tokens (first 1-2) and honorifics; the trailing
        // token(s) are the place candidate.
        val withoutHonor = tokens.filter { it.lowercase() !in HONORIFICS }
        if (withoutHonor.size < 2) return null
        // Prefer the last token that isn't a common stopword and is mostly letters.
        val last = withoutHonor.last()
        if (last.lowercase() in STOP) return null
        if (!last.any { it.isLetter() }) return null
        if (last.count { it.isLetterOrDigit() } < 3) return null
        // Avoid surname-looking English tokens that are actually family names in
        // this dataset; require lowercased distinct from first token.
        if (last.equals(withoutHonor.first(), ignoreCase = true)) return null
        // Keep original casing for display but ensure BD query.
        return last
    }

    fun cacheKey(name: String, token: String): String = "${name.trim().lowercase()}|$token"

    fun wasSeen(context: Context, key: String): Boolean {
        return try {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getStringSet(KEY_SEEN, emptySet())?.contains(key) == true
        } catch (_: Exception) { false }
    }

    fun markSeen(context: Context, key: String) {
        try {
            val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val cur = sp.getStringSet(KEY_SEEN, emptySet())?.toMutableSet() ?: mutableSetOf()
            cur.add(key)
            // Cap set to 800 entries.
            val trimmed = if (cur.size > 800) cur.take(800).toSet() else cur
            sp.edit().putStringSet(KEY_SEEN, trimmed).apply()
        } catch (_: Exception) { }
    }

    /**
     * Resolve a place token to BD administrative levels via Nominatim. Append
     * ", Bangladesh" to bias results. Returns null on miss/timeout/offline.
     */
    suspend fun resolve(
        context: Context,
        token: String,
        httpClient: OkHttpClient? = null,
    ): ResolvedVillage? = withContext(Dispatchers.IO) {
        if (token.isBlank() || token.length < 3) return@withContext null
        val q = "${token.trim()}, Bangladesh"
        return@withContext try {
            val client = httpClient ?: OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(5, TimeUnit.SECONDS)
                .build()
            val url = "https://nominatim.openstreetmap.org/search?q=${java.net.URLEncoder.encode(q, "UTF-8")}&format=json&addressdetails=1&limit=1&countrycodes=bd&accept-language=en"
            val req = Request.Builder().url(url).header("User-Agent", "InfoCaller/1.0 (Android; BD village lookup)").build()
            val resp = client.newCall(req).execute()
            if (!resp.isSuccessful) return@withContext null
            val arr = JSONArray(resp.body?.string().orEmpty())
            if (arr.length() == 0) return@withContext null
            val first = arr.getJSONObject(0)
            val addr = first.optJSONObject("address") ?: return@withContext null
            val village = addr.optString("village", "").takeIf { it.isNotBlank() }
                ?: addr.optString("hamlet", "").takeIf { it.isNotBlank() }
            val union = addr.optString("municipality", "").takeIf { it.isNotBlank() }
                ?: addr.optString("suburb", "").takeIf { it.isNotBlank() }
            val upazila = addr.optString("county", "").takeIf { it.isNotBlank() }
                ?: addr.optString("city_district", "").takeIf { it.isNotBlank() }
            val district = addr.optString("state_district", "").takeIf { it.isNotBlank() }
                ?: addr.optString("state", "").takeIf { it.isNotBlank() }
            // Must contain at least district or upazila to be useful in BD.
            if (district.isNullOrBlank() && upazila.isNullOrBlank() && union.isNullOrBlank() && village.isNullOrBlank()) {
                return@withContext null
            }
            val display = listOfNotNull(village, union, upazila, district).distinct().joinToString(", ")
            ResolvedVillage(
                query = token,
                district = district,
                upazila = upazila,
                union = union,
                village = village,
                display = display.ifBlank { first.optString("display_name", token).take(120) },
            )
        } catch (_: Exception) { null }
    }
}
