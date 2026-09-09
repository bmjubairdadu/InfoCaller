package com.infocaller.app.data.remote

import android.content.Context
import android.util.Log
import com.infocaller.app.domain.engine.*
import com.infocaller.app.util.await
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

class EyeconProviderImpl(private val context: Context) : LookupProvider {
    override val id: String = "eyecon_authorized"
    override val name: String = "Eyecon Visual ID"
    override val version: String = "2.1.0"
    override val capabilities: Set<Capability> = setOf(Capability.PROFILE_PHOTO, Capability.PUBLIC_SEARCH)
    override val priority: Int = 45
    override val costClass: CostClass = CostClass.LOW

    private val authStore = EyeconAuthStore(context.applicationContext)

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .build()

    override suspend fun lookup(identifier: String, type: String, context: LookupContext): PartialResult? = withContext(Dispatchers.IO) {
        if (type != IdentifierType.PHONE) return@withContext null
        val cleanNumber = identifier.replace("+", "")

        try {
            val cv = authStore.cv()?.takeIf { it.isNotBlank() } ?: "vc_786_vn_4.2026.09.06.1153_a"
            var clientId = authStore.cid()
            val nameBodies = mutableListOf<String>()
            val hosts = listOf(
                "https://api.eyecon-app.com/app/getnames.jsp?",
                "https://api2.eyecon-app.com/app/getnames.jsp?",
                "https://eyecon-app.com/app/getnames.jsp?"
            )
            var ok = false
            var sawUnauthorized = false
            for (base in hosts) {
                try {
                    val nameUrl = base +
                        "cli=$cleanNumber&" +
                        "lang=en&" +
                        "is_callerid=true&" +
                        "is_ic=true&" +
                        "cv=$cv&" +
                        "requestApi=URLconnection&" +
                        "source=StatisticBars"

                    val nameRequest = authStore.attachWith(Request.Builder().url(nameUrl), clientId)
                        .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/123.0.0.0 Safari/537.36")
                        .header("Accept", "application/json")
                        .header("Accept-Charset", "UTF-8")
                        .header("Content-Type", "application/x-www-form-urlencoded; charset=utf-8")
                        .header("Accept-Language", "en-US,en;q=0.9")
                        .build()

                    val (okHost, code, nameBody) = client.newCall(nameRequest).await().use { r ->
                        Triple(r.isSuccessful, r.code, r.body?.string() ?: "")
                    }
                    if (code == 401 || code == 403) sawUnauthorized = true
                    if (!okHost) continue
                    ok = true
                    nameBodies.add(nameBody)
                    break
                } catch (_: Exception) { continue }
            }
            if (!ok && sawUnauthorized) {
                val fresh = refreshClientId(cv)
                if (!fresh.isNullOrBlank()) {
                    clientId = fresh
                    for (base in hosts) {
                        try {
                            val nameUrl = base +
                                "cli=$cleanNumber&" +
                                "lang=en&" +
                                "is_callerid=true&" +
                                "is_ic=true&" +
                                "cv=$cv&" +
                                "requestApi=URLconnection&" +
                                "source=StatisticBars"
                            val retry = authStore.attachWith(Request.Builder().url(nameUrl), clientId)
                                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/123.0.0.0 Safari/537.36")
                                .header("Accept", "application/json")
                                .header("Accept-Charset", "UTF-8")
                                .header("Content-Type", "application/x-www-form-urlencoded; charset=utf-8")
                                .header("Accept-Language", "en-US,en;q=0.9")
                                .build()
                            val (okHost, _, nameBody) = client.newCall(retry).await().use { r ->
                                Triple(r.isSuccessful, r.code, r.body?.string() ?: "")
                            }
                            if (!okHost) continue
                            ok = true
                            nameBodies.add(nameBody)
                            break
                        } catch (_: Exception) { continue }
                    }
                }
            }
            if (!ok || nameBodies.isEmpty()) return@withContext null
            val foundName = nameBodies.firstNotNullOfOrNull { nameBody -> parseEyeconName(nameBody) }

            if (foundName.isNullOrBlank()) return@withContext null

            val photoCandidates = mutableListOf<com.infocaller.app.domain.model.PhotoCandidate>()
            val picUrlCandidates = mutableListOf<String>()
            for (base in listOf(
                "https://api.eyecon-app.com/app/pic?",
                "https://api2.eyecon-app.com/app/pic?",
                "https://eyecon-app.com/app/pic?"
            )) {
                picUrlCandidates.add(base + "cli=$cleanNumber&size=big&type=1")
                picUrlCandidates.add(
                    base +
                    "cli=$cleanNumber&" +
                    "is_callerid=true&" +
                    "size=big&" +
                    "type=0&" +
                    "src=RegistrationGetMyPhoto&" +
                    "cancelfresh=0&" +
                    "cv=3.0.0"
                )
            }
            var picUrl: String? = null
            for (candidate in picUrlCandidates) {
                try {
                    if (headServesImage(candidate, clientId)) { picUrl = candidate; break }
                    if (getServesImage(candidate, clientId)) {
                        picUrl = candidate
                        photoCandidates.add(com.infocaller.app.domain.model.PhotoCandidate(provider = "Eyecon", url = candidate, sourcePriority = 40, timestamp = System.currentTimeMillis()))
                        break
                    }
                } catch (_: Exception) { continue }
            }
            if (picUrl != null && photoCandidates.isEmpty()) {
                photoCandidates.add(com.infocaller.app.domain.model.PhotoCandidate(provider = "Eyecon", url = picUrl, sourcePriority = 80, timestamp = System.currentTimeMillis()))
            }

            return@withContext PartialResult(
                name = foundName,
                imageUrl = picUrl,
                photoCandidates = photoCandidates,
                confidence = if (photoCandidates.isNotEmpty()) 0.85f else 0.75f,
                source = "Eyecon Visual ID",
                providerId = id,
                providerVersion = version
            )
        } catch (e: Exception) { null }
    }

    private fun parseEyeconName(nameBody: String): String? {
        val nameBodyTrim = nameBody.trim()
        return try {
            when {
                nameBodyTrim.startsWith("[") -> {
                    val arr = com.google.gson.JsonParser.parseString(nameBodyTrim).asJsonArray
                    if (arr.size() == 0) null
                    else arr.firstOrNull()?.asJsonObject?.get("name")?.takeIf { !it.isJsonNull }?.asString?.takeIf { it.isNotBlank() && it.lowercase() != "unknown" && it.lowercase() != "null" }
                }
                nameBodyTrim.startsWith("{") -> {
                    val obj = com.google.gson.JsonParser.parseString(nameBodyTrim).asJsonObject
                    if (obj.has("status") && obj.get("status").asString.contains("not", true)) null
                    else obj.get("name")?.takeIf { !it.isJsonNull }?.asString?.takeIf { it.isNotBlank() && it.lowercase() != "unknown" }
                }
                Regex("(?i)^name\\s*[:=]\\s*(.+)$").find(nameBodyTrim)?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.length in 2..60 } != null -> {
                    Regex("(?i)^name\\s*[:=]\\s*(.+)$").find(nameBodyTrim)!!.groupValues[1].trim().takeIf { it.length in 2..60 }
                }
                nameBodyTrim.isEmpty() || nameBodyTrim.equals("null", true) -> null
                nameBodyTrim.length in 2..60 && nameBodyTrim.any { it.isLetter() } && !nameBodyTrim.contains("<") -> nameBodyTrim
                else -> null
            }
        } catch (_: Exception) { null }
    }

    private suspend fun headServesImage(url: String, clientId: String? = null): Boolean {
        return try {
            val headReq = authStore.attachWith(Request.Builder().url(url).head(), clientId ?: authStore.cid())
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/123.0.0.0 Safari/537.36")
                .header("Accept-Charset", "UTF-8")
                .build()
            client.newCall(headReq).await().use { headResp ->
                headResp.isSuccessful && (headResp.header("Content-Type")?.contains("image", true) == true || (headResp.header("Content-Length")?.toLongOrNull() ?: 0) > 2000)
            }
        } catch (_: Exception) { false }
    }

    private suspend fun getServesImage(url: String, clientId: String? = null): Boolean {
        return try {
            val getReq = authStore.attachWith(Request.Builder().url(url), clientId ?: authStore.cid())
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/123.0.0.0 Safari/537.36")
                .header("Accept-Charset", "UTF-8")
                .header("Range", "bytes=0-0").build()
            client.newCall(getReq).await().use { r ->
                r.isSuccessful && (r.header("Content-Type")?.contains("image", true) == true || (r.header("Content-Length")?.toLongOrNull() ?: 0) > 0 || r.code == 206)
            }
        } catch (_: Exception) { false }
    }

    private suspend fun refreshClientId(cv: String): String? {
        return try {
            val joinUrl = "https://api.eyecon-app.com/app/join.jsp?" +
                "cli=&username=New_User_Eyecon&devicename=Android" +
                "&user_lang=en&os=Android&public_id=&adv_id=&mc=&viral_id=" +
                "&cli_cc=BD&transport=so_flash&imei=" +
                "&os_id=" + java.util.UUID.randomUUID().toString().take(13) +
                "&cv=" + java.net.URLEncoder.encode(cv, "UTF-8")
            val req = Request.Builder().url(joinUrl)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/123.0.0.0 Safari/537.36")
                .header("Accept", "*/*")
                .header("Accept-Charset", "UTF-8")
                .header("Content-Type", "application/x-www-form-urlencoded; charset=utf-8")
                .build()
            val body = client.newCall(req).await().use { r ->
                if (!r.isSuccessful) return null
                r.body?.string()?.trim()
            }?.takeIf { it.matches(Regex("[0-9a-fA-F-]{36}")) } ?: return null
            if (authStore.isUsingBuiltIn()) {
                try {
                    authStore.save(body, authStore.c() ?: "", authStore.k() ?: "", null)
                } catch (_: Exception) { }
            }
            try { Log.i("Eyecon", "client id refreshed via join.jsp") } catch (_: Exception) { }
            body
        } catch (_: Exception) { null }
    }

    override suspend fun bulkLookup(identifiers: List<String>, type: String, context: LookupContext): Map<String, PartialResult> = withContext(Dispatchers.IO) {
        emptyMap()
    }
}
