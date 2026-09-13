package com.infocaller.app.util

import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Crash-safe tiny web fetcher for manual scans.
 *
 * Why this exists:
 * - Jsoup.connect(...).get() downloads unbounded HTML + builds a full DOM.
 *   On big profile/search pages this OOMs / ANRs and crashes manual scans.
 * - resp.body?.string() is likewise unbounded.
 *
 * This helper caps bytes (~100KB), times out fast, never throws (null on
 * failure), and extracts <title> via regex instead of a DOM parse.
 * Keeps dialer/manual scans smooth even on slow networks.
 */
object SafeWebFetch {
    private val TITLE_REGEX = Regex("""<title[^>]*>(.*?)</title>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))

    suspend fun fetchTitle(
        client: OkHttpClient,
        url: String,
        userAgent: String = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/120.0 Safari/537.36",
        timeoutMs: Long = 5000L,
        maxBytes: Int = 100_000
    ): String? {
        return try {
            val body = fetchBodyCapped(client, url, userAgent, timeoutMs, maxBytes) ?: return null
            extractTitle(body)?.take(80)
        } catch (_: Exception) { null } catch (_: Error) { null }
    }

    suspend fun fetchBodyCapped(
        client: OkHttpClient,
        url: String,
        userAgent: String = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/120.0 Safari/537.36",
        timeoutMs: Long = 5000L,
        maxBytes: Int = 100_000
    ): String? {
        return try {
            val safeUrl = try { url.trim().take(500) } catch (_: Exception) { return null } catch (_: Error) { return null }
            if (safeUrl.isBlank() || safeUrl.length > 500) return null
            if (!safeUrl.startsWith("http://") && !safeUrl.startsWith("https://")) return null
            withTimeoutOrNull(timeoutMs) {
                try {
                    ensureActive()
                    val req = Request.Builder().url(safeUrl)
                        .header("User-Agent", userAgent)
                        .header("Accept-Language", "en-US,en;q=0.9")
                        .header("Accept", "text/html,*/*;q=0.5")
                        .build()
                    client.newCall(req).await().use { resp ->
                        try {
                            if (resp.code != 200) return@use null
                            val ct = try { resp.header("Content-Type").orEmpty() } catch (_: Exception) { "" } catch (_: Error) { "" }
                            // Only HTML; never download images/videos as "title".
                            if (ct.isNotBlank() && !ct.contains("text", true) && !ct.contains("html", true)) return@use null
                            val len = try { resp.header("Content-Length")?.toLongOrNull() } catch (_: Exception) { null } catch (_: Error) { null }
                            if (len != null && (len <= 0 || len > maxBytes)) return@use null
                            val stream = try { resp.body?.byteStream() } catch (_: Exception) { null } catch (_: Error) { null } ?: return@use null
                            stream.use { input ->
                                val out = java.io.ByteArrayOutputStream(16_384)
                                val buf = ByteArray(8_192)
                                var total = 0
                                while (true) {
                                    ensureActive()
                                    val n = try { input.read(buf) } catch (_: Exception) { break } catch (_: Error) { break }
                                    if (n <= 0) break
                                    total += n
                                    if (total > maxBytes) return@use null
                                    out.write(buf, 0, n)
                                    // Title is in <head>; stop early once we have it.
                                    if (total > 24_000) {
                                        val probe = try { out.toString("UTF-8") } catch (_: Exception) { "" } catch (_: Error) { "" }
                                        if (TITLE_REGEX.containsMatchIn(probe)) break
                                    }
                                }
                                val bytes = out.toByteArray()
                                if (bytes.isEmpty() || bytes.size < 100) return@use null
                                try { bytes.toString(Charsets.UTF_8) } catch (_: Exception) { null } catch (_: Error) { null }
                            }
                        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                        catch (_: Error) { null } catch (_: Exception) { null }
                    }
                } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (_: Error) { null } catch (_: Exception) { null }
            }
        } catch (_: Exception) { null } catch (_: Error) { null }
    }

    fun extractTitle(html: String): String? {
        return try {
            val raw = TITLE_REGEX.find(html.take(100_000))?.groupValues?.getOrNull(1)?.trim() ?: return null
            if (raw.isBlank() || raw.length > 200) return null
            // Strip nested tags/entities crudely without a DOM parse.
            val noTags = raw.replace(Regex("<[^>]*>"), " ").replace(Regex("\\s+"), " ").trim()
            decodeEntities(noTags).trim().takeIf { it.length in 3..80 }
        } catch (_: Exception) { null } catch (_: Error) { null }
    }

    private fun decodeEntities(s: String): String {
        return try {
            var r = s
            // Common ones only; enough for <title> pivots.
            r = r.replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'")
                .replace("&lt;", "<").replace("&gt;", ">").replace("&nbsp;", " ")
            r
        } catch (_: Exception) { s } catch (_: Error) { s }
    }

    /** Truecaller web title → person name, or null. Never throws. */
    fun truecallerTitleToName(title: String?): String? {
        return try {
            if (title.isNullOrBlank()) return null
            if (!title.contains("Truecaller", ignoreCase = true)) {
                // sync.me style titles handled by callers; here only Truecaller pages.
                return null
            }
            val cand = title.substringBefore("- Truecaller").trim()
            if (cand.length !in 3..50) return null
            if (cand.contains("Truecaller", ignoreCase = true)) return null
            if (cand.equals("search", ignoreCase = true)) return null
            cand
        } catch (_: Exception) { null } catch (_: Error) { null }
    }
}
