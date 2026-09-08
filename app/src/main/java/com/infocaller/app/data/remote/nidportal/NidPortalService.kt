package com.infocaller.app.data.remote.nidportal

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

const val NID_PORTAL_BASE = "https://services.nidw.gov.bd"

/**
 * User-driven client for the Bangladesh NID public portal, built from Reqable
 * captures (nid.har / nid information.har / smart card ckk.har, 2026-09-08).
 *
 * Captured, working request shapes:
 * - GET /nid-pub/captcha/?t=.. → image/png captcha (session cookie bound)
 * - POST /nid-pub/claim-account/validate
 *   (nid, day, month, year, captcha + X-CSRF-TOKEN) → {"status":"SUCCESS",
 *   "success":{"data":true,"template":"/claim-account/partial-views/address"}}
 * - POST /nid-pub/claim-account/partial-views/district (multipart divisionId)
 *   → {"87":"সাতক্ষীরা",...}; upozila likewise with districtId
 * - POST /nid-pub/claim-account/validate-address
 *   (division, district, upozila + per* mirrors) → template old-mobile-email
 * - GET .../partial-views/old-mobile-email → masked-mobile form HTML
 * - POST /nid-pub/claim-account/send-otp (contactType=SMS) →
 *   {"status":"PENDING","pending":{"url":"/claim-account/send-sms/status",...}}
 * - POST /nid-pub/card-status/validate (same NID+DOB+captcha shape) → SUCCESS
 *   then GET .../partial-views/smart-card-status → result HTML
 *
 * Everything here runs with the user's own input (their NID/DOB + the captcha
 * they read). Nothing is automated around the captcha and no credentials are
 * stored — the service only holds the session cookie jar in memory.
 */
data class NidPortalReply(
    val status: String,
    val dataRaw: String?,
    val template: String?,
    val pendingUrl: String?,
    val pendingIntervalMs: Long,
    val errorRaw: String?,
)

/** Divisions exactly as rendered by the claim-account address form. */
object NidDivisions {
    val all = listOf(
        "10" to "Barishal",
        "20" to "Chattogram",
        "30" to "Dhaka",
        "40" to "Khulna",
        "45" to "Mymensingh",
        "50" to "Rajshahi",
        "55" to "Rangpur",
        "60" to "Sylhet",
    )
}

class NidPortalService {
    companion object {
        private const val UA = "Mozilla/5.0 (Linux; Android 11; Mi A2 Lite Build/RQ3A.211001.001) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/96.0.4664.104 Mobile Safari/537.36"
        private val FORM = "application/x-www-form-urlencoded; charset=UTF-8".toMediaType()

        /** Renders a portal partial-view HTML fragment as readable lines. */
        fun htmlToText(html: String): String {
            var t = html
                .replace(Regex("(?is)<script.*?</script>"), " ")
                .replace(Regex("(?is)<style.*?</style>"), " ")
                .replace(Regex("(?i)<br\\s*/?>"), "\n")
                .replace(Regex("(?i)</p>"), "\n")
                .replace(Regex("(?i)</div>"), "\n")
                .replace(Regex("<[^>]+>"), " ")
            t = t.replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<")
                .replace("&gt;", ">").replace("&quot;", "\"")
            return t.split("\n").map { it.replace(Regex("\\s+"), " ").trim() }
                .filter { it.isNotBlank() }.joinToString("\n").take(2000)
        }
    }

    private val inMemoryCookies = mutableListOf<Cookie>()
    private val client = OkHttpClient.Builder()
        // In-memory cookie jar ONLY (session cookie for the portal; the user
        // types NID/DOB + captcha themselves — nothing sensitive persisted).
        .cookieJar(object : CookieJar {
            override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
                for (c in cookies) {
                    inMemoryCookies.removeAll { it.name == c.name }
                    inMemoryCookies.add(c)
                }
            }
            override fun loadForRequest(url: HttpUrl): List<Cookie> =
                inMemoryCookies.filter { it.matches(url) }
        })
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    var csrfToken: String? = null
        private set

    // ---------- session bootstrap ----------

    suspend fun openClaimAccount(): Boolean = openPage("$NID_PORTAL_BASE/nid-pub/claim-account")
    suspend fun openCardStatus(): Boolean = openPage("$NID_PORTAL_BASE/nid-pub/card-status")

    private suspend fun openPage(url: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder().url(url)
                .header("User-Agent", UA)
                .header("Accept-Language", "en-US,en;q=0.9")
                .build()
            client.newCall(req).execute().use { r ->
                if (!r.isSuccessful) return@withContext false
                val html = r.body?.string().orEmpty()
                Regex("<meta name=\"_csrf\" content=\"([^\"]+)\"").find(html)?.groupValues?.getOrNull(1)?.let {
                    csrfToken = it
                }
                return@withContext true
            }
        } catch (_: Exception) { false }
    }

    suspend fun captchaPng(referer: String): ByteArray? = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder()
                .url("$NID_PORTAL_BASE/nid-pub/captcha/?t=${System.currentTimeMillis()}")
                .header("User-Agent", UA)
                .header("Accept", "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8")
                .header("Referer", referer)
                .header("Accept-Language", "en-US,en;q=0.9")
                .build()
            client.newCall(req).execute().use { r ->
                if (!r.isSuccessful) return@withContext null
                r.body?.bytes()
            }
        } catch (_: Exception) { null }
    }

    // ---------- claim-account flow ----------

    suspend fun validateClaim(nid: String, day: String, month: String, year: String, captcha: String) =
        postForm(
            url = "$NID_PORTAL_BASE/nid-pub/claim-account/validate",
            referer = "$NID_PORTAL_BASE/nid-pub/claim-account",
            fields = mapOf("nid" to nid, "day" to day, "month" to month, "year" to year, "captcha" to captcha),
        )

    suspend fun districts(divisionId: String): Map<String, String> =
        postLookup("$NID_PORTAL_BASE/nid-pub/claim-account/partial-views/district", "divisionId" to divisionId)

    suspend fun upozilas(districtId: String): Map<String, String> =
        postLookup("$NID_PORTAL_BASE/nid-pub/claim-account/partial-views/upozila", "districtId" to districtId)

    suspend fun validateAddress(
        division: String, district: String, upozila: String,
        perDivision: String, perDistrict: String, perUpozila: String,
    ) = postForm(
        url = "$NID_PORTAL_BASE/nid-pub/claim-account/validate-address",
        referer = "$NID_PORTAL_BASE/nid-pub/claim-account",
        fields = mapOf(
            "division" to division, "district" to district, "upozila" to upozila,
            "perDivision" to perDivision, "perDistrict" to perDistrict, "perUpozila" to perUpozila,
        ),
    )

    suspend fun oldMobileEmailView(): String? = withContext(Dispatchers.IO) {
        getHtml(
            "$NID_PORTAL_BASE/nid-pub/claim-account/partial-views/old-mobile-email",
            "$NID_PORTAL_BASE/nid-pub/claim-account",
        )
    }

    suspend fun sendOtpSms() = postForm(
        url = "$NID_PORTAL_BASE/nid-pub/claim-account/send-otp",
        referer = "$NID_PORTAL_BASE/nid-pub/claim-account",
        fields = mapOf("contactType" to "SMS"),
    )

    // ---------- smart-card status flow ----------

    suspend fun validateCard(nid: String, day: String, month: String, year: String, captcha: String) =
        postForm(
            url = "$NID_PORTAL_BASE/nid-pub/card-status/validate",
            referer = "$NID_PORTAL_BASE/nid-pub/card-status",
            fields = mapOf("nid" to nid, "day" to day, "month" to month, "year" to year, "captcha" to captcha),
        )

    suspend fun smartCardStatusView(): String? = withContext(Dispatchers.IO) {
        getHtml(
            "$NID_PORTAL_BASE/nid-pub/card-status/partial-views/smart-card-status?t=${System.currentTimeMillis()}",
            "$NID_PORTAL_BASE/nid-pub/card-status",
        )
    }

    // ---------- transport ----------

    private suspend fun postForm(url: String, referer: String, fields: Map<String, String>): NidPortalReply? =
        withContext(Dispatchers.IO) {
            try {
                val body = fields.entries.joinToString("&") { (k, v) ->
                    "${URLEncoder.encode(k, "UTF-8")}=${URLEncoder.encode(v, "UTF-8")}"
                }.toRequestBody(FORM)
                val b = Request.Builder().url(url).post(body)
                    .header("User-Agent", UA)
                    .header("Accept", "*/*")
                    .header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                    .header("Origin", NID_PORTAL_BASE)
                    .header("X-Requested-With", "XMLHttpRequest")
                    .header("Referer", referer)
                    .header("Accept-Language", "en-US,en;q=0.9")
                csrfToken?.let { b.header("X-CSRF-TOKEN", it) }
                client.newCall(b.build()).execute().use { r ->
                    val text = r.body?.string().orEmpty()
                    if (!r.isSuccessful) return@withContext NidPortalReply("HTTP_${r.code}", null, null, null, 0L, text.take(300))
                    return@withContext try {
                        parseReply(text)
                    } catch (_: Exception) {
                        NidPortalReply("INVALID_RESPONSE", null, null, null, 0L, text.take(300))
                    }
                }
            } catch (e: Exception) {
                NidPortalReply("TRANSPORT_ERROR", null, null, null, 0L, (e.message ?: "network error").take(300))
            }
        }

    private suspend fun postLookup(url: String, field: Pair<String, String>): Map<String, String> =
        withContext(Dispatchers.IO) {
            try {
                // Captured as multipart/form-data carrying a single field.
                val body = MultipartBody.Builder().setType(MultipartBody.FORM)
                    .addFormDataPart(field.first, field.second)
                    .build()
                val b = Request.Builder().url(url).post(body)
                    .header("User-Agent", UA)
                    .header("Accept", "text/plain, */*; q=0.01")
                    .header("X-Requested-With", "XMLHttpRequest")
                    .header("Origin", NID_PORTAL_BASE)
                    .header("Referer", "$NID_PORTAL_BASE/nid-pub/claim-account")
                    .header("Accept-Language", "en-US,en;q=0.9")
                csrfToken?.let { b.header("X-CSRF-TOKEN", it) }
                client.newCall(b.build()).execute().use { r ->
                    if (!r.isSuccessful) return@withContext emptyMap()
                    val j = JSONObject(r.body?.string().orEmpty())
                    val out = linkedMapOf<String, String>()
                    j.keys().forEach { k -> out[k] = j.optString(k, "") }
                    out
                }
            } catch (_: Exception) { emptyMap() }
        }

    private fun getHtml(url: String, referer: String): String? {
        return try {
            val req = Request.Builder().url(url)
                .header("User-Agent", UA)
                .header("Accept", "*/*")
                .header("X-Requested-With", "XMLHttpRequest")
                .header("Referer", referer)
                .header("Accept-Language", "en-US,en;q=0.9")
                .build()
            client.newCall(req).execute().use { r ->
                if (!r.isSuccessful) return null
                htmlToText(r.body?.string().orEmpty())
            }
        } catch (_: Exception) { null }
    }

    private fun parseReply(text: String): NidPortalReply {
        val j = JSONObject(text)
        val success = if (j.isNull("success")) null else j.optJSONObject("success")
        val pending = if (j.isNull("pending")) null else j.optJSONObject("pending")
        return NidPortalReply(
            status = j.optString("status", "UNKNOWN"),
            dataRaw = success?.let { if (it.isNull("data")) null else it.opt("data")?.toString() },
            template = success?.let { if (it.isNull("template")) null else it.optString("template", null) },
            pendingUrl = pending?.let { if (it.isNull("url")) null else it.optString("url", null) },
            pendingIntervalMs = pending?.optLong("interval", 5000L) ?: 5000L,
            errorRaw = if (j.isNull("error")) null else j.opt("error")?.toString(),
        )
    }
}
