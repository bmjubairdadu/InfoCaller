package com.infocaller.app.data.remote

import com.infocaller.app.domain.engine.*
import com.infocaller.app.util.PhoneNumberUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Community spam-CSV feed provider (opt-in dataset, no key).
 *
 * Dataset format source: tareknahas85-star/block-number-data
 * (number,category,negative,positive,neutral,name — E.164).
 * Default feed URL points at that repo's raw spamdb.csv; override via
 * constructor for forks/mirrors. Cached 24h in memory. Read-only GETs only.
 */
class CommunitySpamCsvProviderImpl(
    httpClient: OkHttpClient? = null,
    private val feedUrl: String = DEFAULT_FEED_URL
) : LookupProvider {
    override val id = "community_spam_csv"
    override val name = "Community Spam Feed"
    override val version = "1.0.0"
    override val capabilities = setOf(
        Capability.PUBLIC_SEARCH, Capability.PUBLIC_PROFILE, Capability.ABOUT
    )
    override val priority = 55
    override val costClass = CostClass.FREE

    private val client = httpClient ?: OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    @Volatile private var cached: Map<String, CsvRow>? = null
    @Volatile private var cacheAt = 0L
    private val ttlMs = 24L * 3600 * 1000

    private data class CsvRow(
        val category: String?,
        val negative: Int,
        val positive: Int,
        val neutral: Int,
        val name: String?
    )

    private fun loadFeed(): Map<String, CsvRow>? {
        val now = System.currentTimeMillis()
        cached?.let { if (now - cacheAt < ttlMs) return it }
        return try {
            val req = Request.Builder().url(feedUrl)
                .header("User-Agent", "InfoCaller/1.0 (Android)")
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return cached
                val body = resp.body?.string() ?: return cached
                val map = HashMap<String, CsvRow>(1024)
                var first = true
                for (rawLine in body.lineSequence()) {
                    val line = rawLine.trim()
                    if (line.isEmpty()) continue
                    if (first) { first = false; if (line.startsWith("number")) continue }
                    // Minimal CSV split that tolerates a quoted trailing name.
                    val parts = splitCsv(line)
                    if (parts.size < 5) continue
                    val number = parts[0].trim()
                    if (number.length < 7) continue
                    map[number] = CsvRow(
                        category = parts[1].trim().takeIf { it.isNotBlank() },
                        negative = parts[2].trim().toIntOrNull() ?: 0,
                        positive = parts[3].trim().toIntOrNull() ?: 0,
                        neutral = parts[4].trim().toIntOrNull() ?: 0,
                        name = parts.getOrNull(5)?.trim()?.takeIf { it.isNotBlank() }
                    )
                }
                cached = map; cacheAt = now; map
            }
        } catch (_: Exception) { cached }
    }

    private fun splitCsv(line: String): List<String> {
        val out = ArrayList<String>()
        val cur = StringBuilder()
        var inQuotes = false
        for (ch in line) {
            when {
                ch == '"' -> inQuotes = !inQuotes
                ch == ',' && !inQuotes -> { out += cur.toString(); cur.clear() }
                else -> cur.append(ch)
            }
        }
        out += cur.toString()
        return out
    }

    override suspend fun lookup(identifier: String, type: String, context: LookupContext): PartialResult? =
        withContext(Dispatchers.IO) {
            if (type != IdentifierType.PHONE) return@withContext null
            val normalized = PhoneNumberUtils.normalize(identifier)
            if (normalized.length < 7) return@withContext null
            val feed = loadFeed() ?: return@withContext null
            val digits = normalized.filter { it.isDigit() }
            val row = feed[normalized]
                ?: feed["+$digits"]
                ?: feed[digits]
                ?: return@withContext null
            val votes = row.negative + row.positive + row.neutral
            val about = buildString {
                append("Community spam feed")
                row.category?.let { append(": $it") }
                if (votes > 0) append(" • votes neg=${row.negative}/pos=${row.positive}/neu=${row.neutral}")
            }.take(500)
            PartialResult(
                name = row.name?.takeIf { it.length in 2..80 },
                about = about,
                confidence = when {
                    row.negative >= 5 -> 0.8f
                    row.negative >= 2 -> 0.65f
                    else -> 0.5f
                },
                source = "Community Spam CSV",
                providerId = id, providerVersion = version
            )
        }

    companion object {
        const val DEFAULT_FEED_URL =
            "https://raw.githubusercontent.com/tareknahas85-star/block-number-data/main/spamdb.csv"
    }
}
