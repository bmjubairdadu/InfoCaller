package com.infocaller.app.data.remote

import com.infocaller.app.domain.engine.*
import com.infocaller.app.domain.model.PhotoCandidate
import com.infocaller.app.util.await
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

class MultiAvatarHarvesterProviderImpl(private val httpClient: OkHttpClient) : LookupProvider {
    override val id = "multi_avatar_harvester"
    override val name = "Multi-Avatar Harvester"
    override val version = "1.0.0"
    override val capabilities = setOf(Capability.PROFILE_PHOTO, Capability.PUBLIC_PROFILE)
    override val priority = 55
    override val costClass = CostClass.FREE

    private suspend fun headOk(url: String): Boolean {
        return try {
            val req = Request.Builder().url(url)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14)").head().build()
            httpClient.newCall(req).await().use { r -> r.isSuccessful }
        } catch (_: Exception) { false }
    }

    override suspend fun lookup(identifier: String, type: String, context: LookupContext): PartialResult? = withContext(Dispatchers.IO) {
        try {
            val cands = mutableListOf<PhotoCandidate>()
            var name: String? = null
            when (type) {
                IdentifierType.EMAIL -> {
                    val email = identifier.trim().lowercase()
                    if (!email.contains("@")) return@withContext null
                    val hash = java.security.MessageDigest.getInstance("MD5").digest(email.toByteArray()).joinToString("") { "%02x".format(it) }
                    val g400 = "https://www.gravatar.com/avatar/$hash?s=400&d=404"
                    if (headOk(g400)) cands.add(PhotoCandidate(provider = "Gravatar", url = g400, sourcePriority = 62))
                    val g200 = "https://www.gravatar.com/avatar/$hash?s=200&d=404"
                    if (headOk(g200)) cands.add(PhotoCandidate(provider = "Gravatar", url = g200, sourcePriority = 60))
                }
                IdentifierType.USERNAME, IdentifierType.FULL_NAME -> {
                    val u = identifier.trim()
                    if (u.length < 3 || u.contains(" ")) return@withContext null
                    val gh = "https://github.com/$u.png"
                    if (headOk(gh)) cands.add(PhotoCandidate(provider = "GitHub", url = gh, sourcePriority = 60))
                    val gl = "https://gitlab.com/$u.png"
                    if (headOk(gl)) cands.add(PhotoCandidate(provider = "GitLab", url = gl, sourcePriority = 55))
                    name = u
                }
                else -> return@withContext null
            }
            if (cands.isEmpty()) return@withContext null
            PartialResult(
                name = name,
                imageUrl = cands.first().url,
                photoCandidates = cands,
                confidence = 0.6f,
                source = "Multi-Avatar Harvester",
                providerId = id, providerVersion = version
            )
        } catch (_: Exception) { null }
    }
}
