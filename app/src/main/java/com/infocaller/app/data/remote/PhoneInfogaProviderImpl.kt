package com.infocaller.app.data.remote

import com.infocaller.app.domain.engine.*
import com.infocaller.app.util.PhoneNumberUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit


/**
 * Native port of PhoneInfoga free scanners (inspired by GPL-3.0 project
 * https://github.com/sundowndev/phoneinfoga).
 *
 * Free tasks ported (no API key needed):
 * - local: offline libphonenumber parse -> E164 / international / national / rawLocal / country / carrier
 * - ovh: free OVH Telecom API (FR/BE/GB/ES/CH) to detect VoIP ranges + city/zip
 * - googlesearch: generate Google dork links (social / disposable / reputation / individuals / general)
 *
 * Paid/keyed scanners (numverify, googlecse) are intentionally NOT run here.
 * Upstream deliberately does NOT scrape Google - it only generates links.
 */
class PhoneInfogaProviderImpl : LookupProvider {
    override val id = "phoneinfoga_osint"
    override val name = "PhoneInfoga Free Scans"
    override val version = "2.0.0"
    override val capabilities = setOf(Capability.PUBLIC_SEARCH, Capability.SERVICE_PRESENCE, Capability.PHONE_METADATA, Capability.COUNTRY, Capability.CARRIER, Capability.CITY)
    override val priority = 48
    override val costClass = CostClass.FREE

    private val http = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private data class Dork(val category: String, val dork: String, val url: String)
    private data class OvhHit(val found: Boolean, val range: String? = null, val city: String? = null, val zip: String? = null)

    override suspend fun lookup(identifier: String, type: String, context: LookupContext): PartialResult? = withContext(Dispatchers.IO) {
        if (type != IdentifierType.PHONE) return@withContext null
        val normalized = PhoneNumberUtils.normalize(identifier)
        val digits = normalized.filter { it.isDigit() }
        if (digits.length < 8) return@withContext null

        try {
            val formats = buildFormats(normalized) ?: return@withContext null
            val (e164, international, national, rawLocal, countryCode, region) = formats

            val ovh = runOvhCheck(rawLocal, countryCode, region)
            val dorks = buildAllDorks(e164, international, national, rawLocal)

            val socialCount = dorks.count { it.category == "social" }
            val repCount = dorks.count { it.category == "reputation" }
            val dispCount = dorks.count { it.category == "disposable" }
            val indCount = dorks.count { it.category == "individuals" }
            val genCount = dorks.count { it.category == "general" }

            // Pick a few representative links for the UI (upstream generates dozens; we surface samples)
            val topLinks = dorks.take(6).joinToString("\n") { "• [${it.category}] ${it.url}" }

            val about = buildString {
                append("PhoneInfoga free scan • E164: $e164")
                append(" | Intl: $international | Local: $national")
                if (ovh != null) {
                    if (ovh.found) append(" | OVH Telecom: FOUND (${ovh.range}, ${ovh.city ?: "-"} ${ovh.zip ?: ""})")
                    else append(" | OVH Telecom: not found")
                } else {
                    append(" | OVH: skipped (only FR/BE/GB/ES/CH supported)")
                }
                append(" | Dorks: ${dorks.size} (social:$socialCount rep:$repCount disp:$dispCount ind:$indCount gen:$genCount)")
                append("\n$topLinks")
            }

            val confidence = when {
                ovh?.found == true -> 0.85f
                dorks.isNotEmpty() -> 0.6f
                else -> 0.5f
            }

            PartialResult(
                identifier = normalized,
                identifierType = IdentifierType.PHONE,
                about = about.take(1800),
                city = ovh?.city ?: PhoneNumberUtils.getLocationInfo(normalized),
                country = regionToCountryName(region),
                carrier = PhoneNumberUtils.getCarrierInfo(normalized, null)?.takeIf { it.isNotBlank() }
                    ?: if (ovh?.found == true) "OVH Telecom (VoIP)" else null,
                confidence = confidence,
                source = "PhoneInfoga (local+ovh+dorks, free)",
                providerId = id, providerVersion = version
            )
        } catch (_: Exception) { null }
    }

    private data class Formats(
        val e164: String,
        val international: String,
        val national: String,
        val rawLocal: String,
        val countryCode: Int,
        val region: String?
    )

    private fun buildFormats(normalized: String): Formats? {
        return try {
            val util = com.google.i18n.phonenumbers.PhoneNumberUtil.getInstance()
            val parsed = util.parse(normalized, null)
            val e164 = util.format(parsed, com.google.i18n.phonenumbers.PhoneNumberUtil.PhoneNumberFormat.E164)
            val national = util.format(parsed, com.google.i18n.phonenumbers.PhoneNumberUtil.PhoneNumberFormat.NATIONAL)
            val international = e164.removePrefix("+")
            val rawLocal = parsed.nationalNumber.toString()
            val cc = parsed.countryCode
            val region = try { util.getRegionCodeForNumber(parsed) } catch (_: Exception) { null }
            Formats(e164, international, national, rawLocal, cc, region)
        } catch (_: Exception) { null }
    }

    private fun regionToCountryName(region: String?): String? {
        if (region.isNullOrBlank()) return null
        return when (region.uppercase()) {
            "BD" -> "Bangladesh"; "IN" -> "India"; "PK" -> "Pakistan"; "US" -> "United States"
            "GB" -> "United Kingdom"; "FR" -> "France"; "DE" -> "Germany"; "ES" -> "Spain"
            "IT" -> "Italy"; "BE" -> "Belgium"; "CH" -> "Switzerland"; "SA" -> "Saudi Arabia"
            "AE" -> "United Arab Emirates"; "MY" -> "Malaysia"; "SG" -> "Singapore"
            else -> region.uppercase()
        }
    }

    // ---- OVH (free, no key) ----
    // Upstream: GET https://api.ovh.com/1.0/telephony/number/detailedZones?country={iso-lower}
    // then match rawLocal[0:6] + "xxxx" against Number field.
    private fun runOvhCheck(rawLocal: String, countryCode: Int, region: String?): OvhHit? {
        if (countryCode !in setOf(33, 32, 44, 34, 41)) return null
        val iso = (region ?: return null).lowercase()
        if (rawLocal.length <= 6) return OvhHit(false)
        return try {
            val url = "https://api.ovh.com/1.0/telephony/number/detailedZones?country=$iso"
            val req = Request.Builder().url(url)
                .addHeader("User-Agent", "InfoCaller/2.0 (PhoneInfoga free scan)")
                .build()
            val resp = http.newCall(req).execute()
            if (!resp.isSuccessful) return OvhHit(false)
            val body = resp.body?.string() ?: return OvhHit(false)
            // Lightweight parse without new deps: look for "number":"XXXXXXxxxx" + city/zip nearby
            val wanted = rawLocal.take(6) + "xxxx"
            val idx = body.indexOf(wanted)
            if (idx < 0) return OvhHit(false)
            val window = body.substring(maxOf(0, idx - 400), minOf(body.length, idx + 400))
            val city = Regex("\"city\"\\s*:\\s*\"([^\"]+)\"").find(window)?.groupValues?.getOrNull(1)
            val zip = Regex("\"zipCode\"\\s*:\\s*\"([^\"]+)\"").find(window)?.groupValues?.getOrNull(1)
            OvhHit(true, wanted, city, zip)
        } catch (_: Exception) { OvhHit(false) }
    }

    // ---- Google dorks (free, no scraping - generate links like upstream) ----
    private fun gq(dork: String): String {
        val q = URLEncoder.encode(dork, StandardCharsets.UTF_8.toString())
        return "https://www.google.com/search?q=$q"
    }

    private fun d(category: String, dork: String, e164: String): Dork = Dork(category, dork, gq(dork))

    private fun buildAllDorks(e164: String, international: String, national: String, rawLocal: String): List<Dork> {
        val out = mutableListOf<Dork>()

        // Social media (upstream: facebook/twitter/linkedin/instagram/vk, Intl OR E164 OR RawLocal)
        listOf("facebook.com", "twitter.com", "linkedin.com", "instagram.com", "vk.com").forEach { site ->
            val q = "site:$site intext:\"$international\" | intext:\"$e164\" | intext:\"$rawLocal\""
            out += d("social", q, e164)
        }

        // Disposable providers (upstream list, Intl OR RawLocal; first entry Intl only)
        val disposableSites = listOf(
            "hs3x.com" to false, "receive-sms-now.com" to true, "smslisten.com" to true,
            "smsnumbersonline.com" to true, "freesmscode.com" to true, "catchsms.com" to true,
            "smstibo.com" to true, "smsreceiving.com" to true, "getfreesmsnumber.com" to true,
            "sellaite.com" to true, "receive-sms-online.info" to true, "receivesmsonline.com" to true,
            "receive-a-sms.com" to true, "sms-receive.net" to true, "receivefreesms.com" to true,
            "receive-sms.com" to true, "receivetxt.com" to true, "freephonenum.com" to true,
            "freesmsverification.com" to true, "receive-sms-online.com" to true, "smslive.co" to true
        )
        disposableSites.forEach { (site, orLocal) ->
            val q = if (orLocal) "site:$site intext:\"$international\" | intext:\"$rawLocal\""
            else "site:$site intext:\"$international\""
            out += d("disposable", q, e164)
        }

        // Reputation (upstream subset)
        out += d("reputation", "site:whosenumber.info intext:\"$e164\" intitle:\"who called\"", e164)
        out += d("reputation", "intitle:\"Phone Fraud\" intext:\"$international\" | intext:\"$e164\" | intext:\"$rawLocal\"", e164)
        out += d("reputation", "site:findwhocallsme.com intext:\"$e164\" | intext:\"$international\"", e164)
        out += d("reputation", "site:yellowpages.ca intext:\"$e164\"", e164)
        out += d("reputation", "site:phonenumbers.ie intext:\"$e164\"", e164)
        out += d("reputation", "site:who-calledme.com intext:\"$e164\"", e164)
        out += d("reputation", "site:usphonesearch.net intext:\"$rawLocal\"", e164)
        out += d("reputation", "site:whocalled.us inurl:\"$rawLocal\"", e164)
        out += d("reputation", "site:quinumero.info intext:\"$rawLocal\" | intext:\"$international\"", e164)
        out += d("reputation", "site:uk.popularphotolook.com inurl:\"$rawLocal\"", e164)

        // Individuals (upstream subset)
        out += d("individuals", "site:numinfo.net intext:\"$international\" | intext:\"$e164\" | intext:\"$rawLocal\"", e164)
        out += d("individuals", "site:sync.me intext:\"$international\" | intext:\"$e164\" | intext:\"$rawLocal\"", e164)
        out += d("individuals", "site:whocallsyou.de intext:\"$rawLocal\"", e164)
        out += d("individuals", "site:pastebin.com intext:\"$international\" | intext:\"$e164\" | intext:\"$rawLocal\"", e164)
        out += d("individuals", "site:whycall.me intext:\"$international\" | intext:\"$e164\" | intext:\"$rawLocal\"", e164)
        out += d("individuals", "site:locatefamily.com intext:\"$international\" | intext:\"$e164\" | intext:\"$rawLocal\"", e164)
        out += d("individuals", "site:spytox.com intext:\"$rawLocal\"", e164)

        // General (upstream: all formats + document search)
        out += d("general", "intext:\"$international\" | intext:\"$e164\" | intext:\"$rawLocal\" | intext:\"$national\"", e164)
        out += d("general", "(ext:doc | ext:docx | ext:pdf | ext:txt | ext:xls | ext:csv | ext:ppt) intext:\"$international\" | intext:\"$e164\" | intext:\"$rawLocal\"", e164)

        return out
    }
}
