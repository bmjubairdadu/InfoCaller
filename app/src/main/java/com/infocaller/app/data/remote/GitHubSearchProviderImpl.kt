package com.infocaller.app.data.remote

import com.infocaller.app.domain.engine.*
import com.infocaller.app.domain.model.SocialProfile
import com.infocaller.app.domain.model.SocialLookupStatus
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.nio.charset.StandardCharsets


class GitHubSearchProviderImpl(private val httpClient: OkHttpClient) : LookupProvider {
    override val id = "github_osint"
    override val name = "GitHub Intelligence"
    override val version = "1.0.0"
    override val capabilities = setOf(Capability.SOCIAL_MATCH, Capability.PUBLIC_PROFILE, Capability.PUBLIC_SEARCH, Capability.EMAIL)
    override val priority = 42
    override val costClass = CostClass.FREE

    override suspend fun lookup(identifier: String, type: String, context: LookupContext): PartialResult? = withContext(Dispatchers.IO) {
        try {
            when (type) {
                IdentifierType.USERNAME, IdentifierType.EMAIL -> {
                    val q = if (type == IdentifierType.EMAIL) identifier.substringBefore("@") else identifier
                    val url = "https://api.github.com/search/users?q=${URLEncoder.encode(q, StandardCharsets.UTF_8.toString())}+in:login&per_page=3"
                    val req = Request.Builder().url(url)
                        .header("User-Agent","InfoCaller-OSINT")
                        .header("Accept","application/vnd.github+json")
                        .build()
                    val resp = httpClient.newCall(req).execute()
                    if (!resp.isSuccessful) return@withContext null
                    val json = JsonParser.parseString(resp.body?.string()).asJsonObject
                    val items = json.getAsJsonArray("items") ?: return@withContext null
                    if (items.size() == 0) return@withContext null
                    val profiles = items.mapNotNull {
                        val o = it.asJsonObject
                        val login = o.get("login")?.asString ?: return@mapNotNull null
                        val html = o.get("html_url")?.asString ?: "https://github.com/$login"
                        SocialProfile("GitHub", login, html, SocialLookupStatus.PUBLIC_MATCH)
                    }
                    if (profiles.isEmpty()) return@withContext null
                    var displayName: String? = null
                    try {
                        val first = items[0].asJsonObject
                        val userUrl = first.get("url")?.asString
                        if (userUrl != null) {
                            val r2 = httpClient.newCall(Request.Builder().url(userUrl).header("User-Agent","InfoCaller").build()).execute()
                            if (r2.isSuccessful) {
                                val u = JsonParser.parseString(r2.body?.string()).asJsonObject
                                displayName = u.get("name")?.takeIf { !it.isJsonNull }?.asString
                                val bio = u.get("bio")?.takeIf { !it.isJsonNull }?.asString
                                val loc = u.get("location")?.takeIf { !it.isJsonNull }?.asString
                                if (displayName != null || bio != null) {
                                    return@withContext PartialResult(
                                        name = displayName, about = bio, city = loc,
                                        socialProfiles = profiles,
                                        confidence = 0.65f, source = name, providerId = id, providerVersion = version
                                    )
                                }
                            }
                        }
                    } catch (_: Exception){}
                    return@withContext PartialResult(name = displayName, socialProfiles = profiles, confidence = 0.6f, source = name, providerId = id, providerVersion = version)
                }
                IdentifierType.PHONE -> {
                    val digits = identifier.filter { it.isDigit() }.takeLast(7)
                    if (digits.length < 7) return@withContext null
                    return@withContext null
                }
                else -> return@withContext null
            }
        } catch (_: Exception){ null }
        null
    }
}
