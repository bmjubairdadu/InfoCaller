package com.infocaller.app.data.remote

import android.content.Context
import android.util.Log
import com.infocaller.app.domain.engine.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Eyecon Provider refactored according to API Reference.
 * Implements authorized name and photo lookup.
 */
class EyeconProviderImpl(private val context: Context) : LookupProvider {
    override val id: String = "eyecon_authorized"
    override val name: String = "Eyecon Visual ID"
    override val version: String = "2.0.0"
    override val capabilities: Set<Capability> = setOf(Capability.PROFILE_PHOTO, Capability.PUBLIC_SEARCH)
    override val priority: Int = 45
    override val costClass: CostClass = CostClass.LOW

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .build()

    override suspend fun lookup(identifier: String, type: String, context: LookupContext): PartialResult? = withContext(Dispatchers.IO) {
        if (type != IdentifierType.PHONE) return@withContext null
        val cleanNumber = identifier.replace("+", "")
        
        try {
            // 1. Name Lookup: /app/getnames.jsp
            val nameUrl = "https://api.eyecon-app.com/app/getnames.jsp?" +
                    "cli=$cleanNumber&" +
                    "lang=en&" +
                    "is_callerid=true&" +
                    "is_ic=false&" +
                    "cv=3.0.0&" +
                    "requestApi=URLconnection&" +
                    "source=RegistrationGetMyName"
            
            val nameRequest = Request.Builder()
                .url(nameUrl)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 10; SM-G975F) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/80.0.3987.149 Mobile Safari/537.36")
                .header("Accept", "*/*")
                .header("Accept-Language", "en-US,en;q=0.9")
                .build()

            val nameResponse = client.newCall(nameRequest).execute()
            val nameBody = nameResponse.body?.string() ?: ""
            
            // Validate HTTP status before parsing - Eyecon returns 404/403 for not-found, not JSON
            if (!nameResponse.isSuccessful) {
                android.util.Log.d("Eyecon", "getnames non-200: ${nameResponse.code} for $cleanNumber")
                return@withContext null
            }
            val nameBodyTrim = nameBody.trim()
            val foundName = try {
                when {
                    nameBodyTrim.startsWith("[") -> {
                        val arr = com.google.gson.JsonParser.parseString(nameBodyTrim).asJsonArray
                        // Eyecon returns [] for not found - check empty
                        if (arr.size() == 0) null
                        else arr.firstOrNull()?.asJsonObject?.get("name")?.takeIf { !it.isJsonNull }?.asString?.takeIf { it.isNotBlank() && it.lowercase() != "unknown" && it.lowercase() != "null" }
                    }
                    nameBodyTrim.startsWith("{") -> {
                        val obj = com.google.gson.JsonParser.parseString(nameBodyTrim).asJsonObject
                        // Eyecon sometimes returns {"status":"not found"} or {"name":null}
                        if (obj.has("status") && obj.get("status").asString.contains("not", true)) null
                        else obj.get("name")?.takeIf { !it.isJsonNull }?.asString?.takeIf { it.isNotBlank() && it.lowercase() != "unknown" }
                    }
                    nameBodyTrim.isEmpty() || nameBodyTrim.equals("null", true) -> null
                    else -> null
                }
            } catch (e: Exception) { android.util.Log.w("Eyecon", "Parse error: ${e.message} body=${nameBodyTrim.take(200)}"); null }

            // If no name found, Eyecon has no data for this number - return null instead of fake photo
            if (foundName.isNullOrBlank()) {
                android.util.Log.d("Eyecon", "No name for $cleanNumber - not found")
                return@withContext null
            }

            // 2. Photo Lookup: only construct pic URL if name exists (verified hit)
            val picUrl = "https://api.eyecon-app.com/app/pic?" +
                    "cli=$cleanNumber&" +
                    "is_callerid=true&" +
                    "size=big&" +
                    "type=0&" +
                    "src=RegistrationGetMyPhoto&" +
                    "cancelfresh=0&" +
                    "cv=3.0.0"

            // Verify photo actually exists (HEAD check) - Eyecon returns placeholder otherwise
            val photoCandidates = mutableListOf<com.infocaller.app.domain.model.PhotoCandidate>()
            try {
                val headReq = Request.Builder().url(picUrl).head().header("User-Agent","Mozilla/5.0").build()
                val headResp = client.newCall(headReq).execute()
                val hasPhoto = headResp.isSuccessful && (headResp.header("Content-Type")?.contains("image", true) == true || (headResp.header("Content-Length")?.toLongOrNull() ?: 0) > 2000)
                headResp.close()
                if (hasPhoto) {
                    photoCandidates.add(com.infocaller.app.domain.model.PhotoCandidate(provider = "Eyecon", url = picUrl, sourcePriority = 80, timestamp = System.currentTimeMillis()))
                }
            } catch (_: Exception) {
                // If HEAD fails, still add candidate but with lower confidence - DetailsScreen will validate image load
                photoCandidates.add(com.infocaller.app.domain.model.PhotoCandidate(provider = "Eyecon", url = picUrl, sourcePriority = 40, timestamp = System.currentTimeMillis()))
            }

            return@withContext PartialResult(
                name = foundName,
                imageUrl = if (photoCandidates.isNotEmpty()) picUrl else null,
                photoCandidates = photoCandidates,
                confidence = if (photoCandidates.isNotEmpty()) 0.85f else 0.75f,
                source = "Eyecon Visual ID",
                providerId = id,
                providerVersion = version
            )
        } catch (e: Exception) {
            Log.e("Eyecon", "Authorized lookup failed for $identifier: ${e.message}")
            null
        }
    }

    override suspend fun bulkLookup(identifiers: List<String>, type: String, context: LookupContext): Map<String, PartialResult> = withContext(Dispatchers.IO) {
        emptyMap()
    }
}
