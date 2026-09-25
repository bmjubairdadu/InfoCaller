package com.infocaller.app.data.remote

import com.infocaller.app.domain.engine.Capability
import com.infocaller.app.domain.engine.CostClass
import com.infocaller.app.domain.engine.IdentifierType
import com.infocaller.app.domain.engine.LookupContext
import com.infocaller.app.domain.engine.LookupProvider
import com.infocaller.app.domain.engine.PartialResult
import com.infocaller.app.domain.model.PhotoCandidate
import com.infocaller.app.util.PhoneNumberUtils
import com.infocaller.app.util.PhotoPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

class NidDatabaseProvider(
    private val httpClient: OkHttpClient
) : LookupProvider {
    override val id = "bd_nid_database"
    override val name = "BD NID Database"
    override val version = "3.0.0"
    override val capabilities = setOf(
        Capability.DEEP_PII,
        Capability.PUBLIC_SEARCH,
        Capability.PHONE_METADATA,
        Capability.PUBLIC_PROFILE
    )
    override val priority = 960
    override val costClass = CostClass.FREE

    private fun baseUrl(): String? = try {
        com.infocaller.app.BuildConfig.BACKEND_BASE_URL.trim().trimEnd('/').takeIf { it.isNotBlank() }
    } catch (_: Exception) { null }

    private fun apiKey(): String? = try {
        com.infocaller.app.BuildConfig.INFOCALLER_API_KEY.trim().takeIf { it.isNotBlank() }
    } catch (_: Exception) { null }

    fun isConfigured(): Boolean = baseUrl() != null && apiKey() != null

    override suspend fun lookup(identifier: String, type: String, context: LookupContext): PartialResult? =
        withContext(Dispatchers.IO) {
            val base = baseUrl() ?: return@withContext null
            val key = apiKey() ?: return@withContext null
            val started = System.currentTimeMillis()
            try {
                when (type) {
                    IdentifierType.PHONE -> {
                        val digits = identifier.filter { it.isDigit() }
                        if (digits.length < 7 || digits.length > 15) return@withContext null
                        for (variant in phoneVariants(digits)) {
                            val rec = fetch("$base/api/v1/nid/phone/$variant", key) ?: continue
                            return@withContext toPartial(rec, started, exactDobMatch = true)
                        }
                        null
                    }
                    IdentifierType.NID -> {
                        val digits = identifier.filter { it.isDigit() }
                        if (digits.length !in 10..17) return@withContext null
                        val rec = fetch("$base/api/v1/nid/nid/$digits", key) ?: return@withContext null
                        toPartial(rec, started, exactDobMatch = true)
                    }
                    IdentifierType.DOB -> {
                        val dob = identifier.trim().take(12)
                        if (dob.length < 8) return@withContext null
                        val o = fetch("$base/api/v1/nid/dob/${java.net.URLEncoder.encode(dob, "UTF-8")}", key)
                            ?: return@withContext null
                        val count = o.optInt("count", 0)
                        if (count <= 0) return@withContext null
                        PartialResult(
                            identifier = dob,
                            identifierType = IdentifierType.DOB,
                            about = "DOB matches $count NID records",
                            confidence = 0.7f,
                            source = "BD NID Database",
                            durationMs = System.currentTimeMillis() - started,
                            providerId = id,
                            providerVersion = version
                        )
                    }
                    else -> {
                        if (identifier.contains("|")) {
                            val parts = identifier.split("|")
                            val nid = parts[0].trim().filter { it.isDigit() }
                            val dob = parts[1].trim()
                            if (nid.length in 10..17) {
                                val rec = fetch("$base/api/v1/nid/nid/$nid", key)
                                if (rec != null) {
                                    val recDob = rec.optString("dob", "")
                                    return@withContext toPartial(rec, started, exactDobMatch = recDob == dob)
                                }
                            }
                        }
                        val digits = identifier.filter { it.isDigit() }
                        if (digits.length !in 10..17) return@withContext null
                        val rec = fetch("$base/api/v1/nid/nid/$digits", key) ?: return@withContext null
                        toPartial(rec, started, exactDobMatch = true)
                    }
                }
            } catch (_: Exception) { null } catch (_: Error) { null }
        }

    private fun phoneVariants(digits: String): List<String> {
        val e164 = try { PhoneNumberUtils.normalize(digits) } catch (_: Exception) { "" }
        val local = normalizeNumber(digits)
        return linkedSetOf(
            e164.filter { it.isDigit() },
            local,
            if (digits.startsWith("880")) digits.substring(3) else "0$digits".takeLast(11),
            digits.takeLast(11),
            if (digits.startsWith("880")) digits else "880${digits.trimStart('0')}",
            digits.takeLast(10)
        ).filter { it.length in 10..13 }.take(6)
    }

    private fun normalizeNumber(raw: String): String {
        var d = raw.filter { it.isDigit() }
        if (d.startsWith("880") && d.length >= 12) d = "0" + d.substring(3)
        return d.take(13)
    }

    private fun fetch(url: String, key: String): JSONObject? {
        val req = Request.Builder()
            .url(url)
            .header("x-api-key", key)
            .header("Accept", "application/json")
            .header("User-Agent", "InfoCaller-App")
            .build()
        return try {
            httpClient.newCall(req).execute().use { r ->
                if (!r.isSuccessful) return null
                val body = try { r.peekBody(100_000L).string() } catch (_: Exception) { null } catch (_: Error) { null }
                if (body.isNullOrBlank()) return null
                val o = try { JSONObject(body) } catch (_: Exception) { null } catch (_: Error) { null }
                if (o == null || o.has("error")) null else o
            }
        } catch (_: Exception) { null } catch (_: Error) { null }
    }

    private fun toPartial(o: JSONObject, started: Long, exactDobMatch: Boolean): PartialResult? {
        val number = o.optString("number", "").ifBlank { return null }
        val nid = o.optString("nid", "").ifBlank { return null }
        val dob = o.optString("dob", "")
        fun s(k: String): String? =
            if (o.has(k) && !o.isNull(k)) o.optString(k, "").takeIf { it.isNotBlank() } else null

        val nameEn = s("nameEn")
        val nameBn = s("nameBn")
        val father = s("fatherName")
        val mother = s("motherName")
        val address = s("address")
        val photo = s("photoUrl")?.takeIf { PhotoPolicy.isUsablePhotoUrl(it) }
        val hasEnriched = nameEn != null || nameBn != null || father != null || mother != null || address != null || photo != null
        val duration = System.currentTimeMillis() - started

        if (!hasEnriched) {
            return PartialResult(
                identifier = number,
                identifierType = IdentifierType.PHONE,
                about = "NID: $nid | DOB: $dob" + if (!exactDobMatch) " (DOB not matched)" else "",
                nid = nid,
                dob = dob,
                confidence = 0.95f,
                source = "BD NID Database",
                durationMs = duration,
                providerId = id,
                providerVersion = version
            )
        }
        return PartialResult(
            identifier = number,
            identifierType = IdentifierType.PHONE,
            name = nameEn ?: nameBn,
            alternateName = father?.let { "Father: $it" },
            about = buildString {
                append("NID: $nid | DOB: $dob")
                if (father != null) append(" | Father: $father")
                if (mother != null) append(" | Mother: $mother")
                if (address != null) append(" | Address: $address")
                if (!exactDobMatch) append(" (DOB not matched)")
            },
            city = address,
            country = "Bangladesh",
            nid = nid,
            dob = dob,
            imageUrl = photo,
            photoCandidates = if (photo != null) listOf(PhotoCandidate(provider = "BD NID Database", url = photo)) else emptyList(),
            confidence = 0.98f,
            source = "BD NID Database",
            durationMs = duration,
            providerId = id,
            providerVersion = version
        )
    }
}
