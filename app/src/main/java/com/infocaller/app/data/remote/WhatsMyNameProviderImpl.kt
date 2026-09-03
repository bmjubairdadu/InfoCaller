package com.infocaller.app.data.remote

import com.infocaller.app.domain.engine.*
import com.infocaller.app.domain.model.SocialLookupStatus
import com.infocaller.app.domain.model.SocialProfile
import com.google.gson.Gson
import com.google.gson.JsonParser
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * WhatsMyName-inspired provider.
 * Uses wmn-data.json (https://raw.githubusercontent.com/WebBreacher/WhatsMyName/main/wmn-data.json)
 * to check username across ~600 sites. Caches DB 24h in memory.
 * Free. Complements Sherlock/Maigret techniques.
 */
class WhatsMyNameProviderImpl(
    private val httpClient: OkHttpClient,
    private val gson: Gson = Gson()
) : LookupProvider {
    override val id = "whatsmyname"
    override val name = "WhatsMyName Username Scan"
    override val version = "1.0.0"
    override val capabilities = setOf(Capability.SOCIAL_MATCH, Capability.SERVICE_PRESENCE, Capability.PUBLIC_PROFILE)
    override val priority = 65
    override val costClass = CostClass.FREE

    @Volatile private var cachedSites: List<WmnSite>? = null
    @Volatile private var cacheAt = 0L
    private val CACHE_TTL = 24*3600*1000L
    private val DATA_URL = "https://raw.githubusercontent.com/WebBreacher/WhatsMyName/main/wmn-data.json"

    override suspend fun lookup(identifier: String, type: String, context: LookupContext): PartialResult? = withContext(Dispatchers.IO) {
        if (type != IdentifierType.USERNAME && type != IdentifierType.FULL_NAME) return@withContext null
        val username = identifier.trim().lowercase().replace(" ","").take(30)
        if (username.length < 3) return@withContext null

        val sites = loadSites() ?: return@withContext null
        // Limit to 50 popular / fast sites to avoid blocking on incoming call
        val subset = sites.shuffled().take(60)

        val profiles = coroutineScope {
            subset.map { site ->
                async {
                    val url = site.uri_check.format(username)
                    if (quickExists(url, site)) SocialProfile(site.name, username, url, SocialLookupStatus.POSSIBLE_MATCH) else null
                }
            }.awaitAll().filterNotNull()
        }
        if (profiles.isEmpty()) return@withContext null
        PartialResult(socialProfiles = profiles, confidence = 0.6f, source = "WhatsMyName DB", providerId = id, providerVersion = version)
    }

    private fun loadSites(): List<WmnSite>? {
        if (cachedSites != null && System.currentTimeMillis() - cacheAt < CACHE_TTL) return cachedSites
        return try {
            val req = Request.Builder().url(DATA_URL).header("User-Agent","Mozilla/5.0").build()
            val resp = httpClient.newCall(req).execute()
            if (!resp.isSuccessful) return cachedSites
            val json = JsonParser.parseString(resp.body?.string()).asJsonObject
            val arr = json.getAsJsonArray("sites")
            val list = arr.mapNotNull { el ->
                val o = el.asJsonObject
                WmnSite(o.get("name")?.asString?:"", o.get("uri_check")?.asString?:"")
            }.filter { it.uri_check.contains("%s") }
            cachedSites = list; cacheAt = System.currentTimeMillis(); list
        } catch (_: Exception) { cachedSites }
    }
    private fun quickExists(url: String, site: WmnSite): Boolean = try {
        val req = Request.Builder().url(url).header("User-Agent","Mozilla/5.0 (Linux; Android 14)").build()
        val r = httpClient.newCall(req).execute()
        if (r.code == 200) { val b = (r.body?.string()?:"").lowercase(); b.length > 500 && !b.contains("not found") } else false
    } catch (_: Exception){ false }
    private data class WmnSite(val name:String, val uri_check:String)
}
