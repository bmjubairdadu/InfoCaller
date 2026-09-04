package com.infocaller.app.data.remote

import com.google.gson.JsonParser
import com.infocaller.app.domain.engine.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Grep.app code search — free, no key.
 * Indexes public GitHub code. Endpoint: https://grep.app/api/search?q=<query>
 * Great for finding phone numbers / emails / usernames leaked in source,
 * configs, paste mirrors and sample data.
 *
 * Docs pattern observed: { "hits": { "hits": [ { "repo": {"raw": "o/r"},
 * "path": {"raw": "..."}, "content": {"snippet": "..."} } ] } }
 */
class GrepAppCodeSearchProviderImpl(
    private val httpClient: OkHttpClient
) : LookupProvider {
    override val id = "grepapp_code_search"
    override val name = "Grep.app Code Search"
    override val version = "1.0.0"
    override val capabilities = setOf(
        Capability.PUBLIC_SEARCH, Capability.PUBLIC_PROFILE, Capability.INFOSTEALER_LEAK
    )
    override val priority = 43
    override val costClass = CostClass.FREE

    override suspend fun lookup(identifier: String, type: String, context: LookupContext): PartialResult? =
        withContext(Dispatchers.IO) {
            val query = when (type) {
                IdentifierType.PHONE -> {
                    val digits = identifier.filter { it.isDigit() }
                    if (digits.length < 7) return@withContext null
                    // Try full digits + last-7 variant implicitly via grep relevance
                    "\"$digits\""
                }
                IdentifierType.EMAIL -> {
                    if (!identifier.contains("@")) return@withContext null
                    "\"${identifier.trim()}\""
                }
                IdentifierType.USERNAME -> {
                    val u = identifier.trim()
                    if (u.length < 3 || u.contains(" ")) return@withContext null
                    "\"$u\""
                }
                IdentifierType.DOMAIN -> identifier.trim().takeIf { it.contains(".") } ?: return@withContext null
                IdentifierType.FULL_NAME -> {
                    val n = identifier.trim()
                    if (n.length < 4) return@withContext null
                    "\"$n\""
                }
                else -> return@withContext null
            }
            try {
                val url = "https://grep.app/api/search?q=" +
                    URLEncoder.encode(query, StandardCharsets.UTF_8.toString())
                val req = Request.Builder().url(url)
                    .header("User-Agent", "InfoCaller-OSINT/2.0")
                    .header("Accept", "application/json")
                    .build()
                val resp = httpClient.newCall(req).execute()
                if (!resp.isSuccessful) return@withContext null
                val body = resp.body?.string() ?: return@withContext null
                val root = try { JsonParser.parseString(body).asJsonObject } catch (_: Exception) { return@withContext null }
                val hitsObj = root.getAsJsonObject("hits") ?: return@withContext null
                val hits = try { hitsObj.getAsJsonArray("hits") } catch (_: Exception) { null }
                    ?: return@withContext null
                if (hits.size() == 0) return@withContext null

                val repos = mutableListOf<String>()
                val snippets = mutableListOf<String>()
                for (i in 0 until minOf(hits.size(), 5)) {
                    try {
                        val h = hits[i].asJsonObject
                        val repo = h.getAsJsonObject("repo")?.get("raw")?.asString
                        val path = h.getAsJsonObject("path")?.get("raw")?.asString
                        val snippet = h.getAsJsonObject("content")?.get("snippet")?.asString
                        if (repo != null) repos.add(if (path != null) "$repo:$path" else repo)
                        if (!snippet.isNullOrBlank()) snippets.add(snippet.trim().take(140))
                    } catch (_: Exception) { }
                }
                if (repos.isEmpty()) return@withContext null
                val about = buildString {
                    append("Code mentions: ${hits.size()}+ hits. ")
                    append(repos.distinct().take(3).joinToString(" | "))
                    if (snippets.isNotEmpty()) {
                        append(" — e.g. \"${snippets.first().take(120)}\"")
                    }
                }
                PartialResult(
                    about = about.take(600),
                    confidence = if (hits.size() >= 3) 0.62f else 0.5f,
                    source = name,
                    providerId = id, providerVersion = version
                )
            } catch (_: Exception) { null }
        }
}
