package com.infocaller.app.util

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.infocaller.app.util.await
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

    private val HONORIFICS = setOf("vai", "vaiya", "bhai", "bhaia", "apa", "apu", "dada", "dadi", "nana", "nani", "mama", "mami", "chacha", "chachi", "khalu", "khala", "fupu", "fupa", "uncle", "aunty", "aunt", "sir", "mam", "madam", "boss", "bro", "sis", "dst", "hujur", "office", "store", "shop")
    private val STOP = setOf("and", "or", "the", "a", "an", "of", "for", "in", "on", "at", "to", "with", "new", "old", "shah", "md", "mohammad", "hossain", "hossen", "ahmed", "rahman", "khan", "ali", "uddin", "islam")
    // Common BD person-name tokens: never treated as places even when trailing.
    private val PERSON_TOKENS = setOf(
        "rahim", "karim", "rahman", "ahmed", "ahmad", "hossain", "hossen", "hassan", "hasan", "khan", "ali", "uddin",
        "islam", "mohammad", "mohammed", "abdul", "abul", "abu", "mia", "mia", "sheikh", "chowdhury", "choudhury",
        "sarker", "sarkar", "biswas", "mondal", "mandal", "das", "dass", "roy", "sen", "saha", "paul", "ghosh",
        "alam", "akter", "begum", "bibi", "khatun", "jahan", "sultana", "parvin", "fatema", "nasrin", "shirin",
        "habib", "faruk", "faruk", "rakib", "sakib", "tanvir", "nadim", "selim", "alim", "jalal", "kamal", "jamal",
        "rasel", "rubel", "sohel", "shohel", "mehedi", "mahmud", "mamun", "sumon", "sujon", "rimon", "rimon", "liton"
    )

    data class PlaceCandidate(val raw: String, val repaired: String, val wasRepaired: Boolean)

    /**
     * All trailing tokens that could be places, AFTER skipping the person
     * part: first token (given name) + honorifics are never places. Returns
     * empty when the name is just a person ("Ashraful vai" -> skipped).
     * Repair info included per token ("sujansah" -> "sujansaha").
     */
    fun extractPlaceCandidates(fullName: String?): List<PlaceCandidate> {
        if (fullName.isNullOrBlank()) return emptyList()
        val tokens = fullName.trim()
            .split(Regex("[\\s,\\-_/()\\[\\].|]+")).map { it.trim() }.filter { it.length >= 3 }
        if (tokens.size < 2) return emptyList()
        // Person part = first token + any honorific anywhere. Everything after
        // the person part is a place candidate (covers "Ashraful vai sujansaha"
        // -> [sujansaha] and "Ashraful vai" -> [] since nothing trails).
        val first = tokens.first().lowercase()
        val tail = tokens.drop(1).filter { it.lowercase() !in HONORIFICS }
        if (tail.isEmpty()) return emptyList()
        return tail.mapNotNull { tok ->
            val low = tok.lowercase()
            if (low in STOP) return@mapNotNull null
            if (low in PERSON_TOKENS) return@mapNotNull null
            if (low == first) return@mapNotNull null
            if (!tok.any { it.isLetter() }) return@mapNotNull null
            if (tok.count { it.isLetterOrDigit() } < 3) return@mapNotNull null
            // Pure-digit or digit-heavy tokens are phone fragments, not places.
            if (tok.count { it.isDigit() } >= 4) return@mapNotNull null
            val repaired = BdPlaceGazetteer.repair(low)
            PlaceCandidate(raw = tok, repaired = repaired, wasRepaired = !repaired.equals(low, ignoreCase = true))
        }
    }

    /** Heuristic token that likely is a place, not the person name. Null when none. */
    fun extractPlaceToken(fullName: String?): String? {
        // Last candidate wins (closest to old single-token behavior).
        return extractPlaceCandidates(fullName).lastOrNull()?.repaired
    }

    /** Normalized edit-distance similarity 0..1 (1 = identical). */
    internal fun similarity(a: String, b: String): Double {
        if (a.equals(b, ignoreCase = true)) return 1.0
        val x = a.lowercase(); val y = b.lowercase()
        if (x.isEmpty() || y.isEmpty()) return 0.0
        // Cheap prefix shortcut: "sujansah" vs "sujansaha" scores ~0.97.
        if (y.startsWith(x) || x.startsWith(y)) {
            val longer = maxOf(x.length, y.length).toDouble()
            return (minOf(x.length, y.length).toDouble() + 0.5) / (longer + 0.5)
        }
        val dp = Array(x.length + 1) { IntArray(y.length + 1) }
        for (i in 0..x.length) dp[i][0] = i
        for (j in 0..y.length) dp[0][j] = j
        for (i in 1..x.length) for (j in 1..y.length) {
            dp[i][j] = minOf(dp[i - 1][j] + 1, dp[i][j - 1] + 1, dp[i - 1][j - 1] + if (x[i - 1] == y[j - 1]) 0 else 1)
        }
        return 1.0 - dp[x.length][y.length].toDouble() / maxOf(x.length, y.length).toDouble()
    }

    fun cacheKey(name: String, token: String): String = "${name.trim().lowercase()}|$token"

    private const val KEY_RESOLVED = "village_resolved_v1"

    /** Last resolved display per token (7-day TTL) — avoids re-hitting Nominatim on every open. */
    fun cachedResolved(context: Context, token: String): ResolvedVillage? {
        return try {
            val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val raw = sp.getString("$KEY_RESOLVED|${token.lowercase()}", null) ?: return null
            val parts = raw.split("|")
            if (parts.size < 3) return null
            val at = parts[0].toLongOrNull() ?: return null
            if (System.currentTimeMillis() - at > 7 * 24 * 60 * 60 * 1000L) return null
            ResolvedVillage(query = token, district = parts.getOrNull(1)?.takeIf { it.isNotBlank() }, upazila = parts.getOrNull(2)?.takeIf { it.isNotBlank() }, display = parts.getOrNull(3)?.takeIf { it.isNotBlank() } ?: token)
        } catch (_: Exception) { null }
    }

    fun storeResolved(context: Context, token: String, v: ResolvedVillage) {
        try {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString("$KEY_RESOLVED|${token.lowercase()}", "${System.currentTimeMillis()}|${v.district.orEmpty()}|${v.upazila.orEmpty()}|${v.display.take(120)}")
                .apply()
        } catch (_: Exception) { }
    }

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
            val body = client.newCall(req).await().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                resp.body?.string()
            } ?: return@withContext null
            val arr = JSONArray(body)
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
