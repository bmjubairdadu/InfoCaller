package com.infocaller.app.data.remote

import com.infocaller.app.data.local.database.AppDatabase
import com.infocaller.app.data.remote.nidportal.NidCaptchaSolver
import com.infocaller.app.data.remote.nidportal.NidPortalService
import com.infocaller.app.domain.engine.*
import com.infocaller.app.util.PhoneNumberUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Fully automatic NID smart-card status (no manual captcha screen).
 *
 * Flow per phone scan:
 *  1. database.json row matched by number -> NID + DOB (already imported
 *     on-device; zero network, zero captcha — this is the common path and
 *     renders NID + DOB instantly like before).
 *  2. Same NID + DOB is then used for an automatic smart-card status check:
 *     open session -> fetch captcha PNG -> solve on-device with ML Kit OCR
 *     ([NidCaptchaSolver]) -> POST card-status/validate -> fetch the status
 *     partial view. Up to 3 fresh captcha attempts; any OCR/server failure
 *     degrades silently to step-1 data (never blocks the scan, never shows
 *     a captcha to the user).
 *
 * Extra personal data (name/address/photo) is NOT expected: the portal's own
 * responses carry only status templates, and database.json rows carry only
 * number/nid/dob. What this adds automatically is the live smart-card
 * distribution status text when the portal answers it.
 */
class NidSmartCardAutoProvider(
    private val db: AppDatabase,
) : LookupProvider {
    override val id = "nid_smartcard_auto"
    override val name = "NID Smart-Card Status (auto)"
    override val version = "1.0.0"
    override val capabilities = setOf(Capability.DEEP_PII, Capability.PUBLIC_SEARCH)
    override val priority = 87
    override val costClass = CostClass.FREE

    override suspend fun lookup(identifier: String, type: String, context: LookupContext): PartialResult? =
        withContext(Dispatchers.IO) {
            if (type != IdentifierType.PHONE) return@withContext null
            val digits = identifier.filter { it.isDigit() }
            if (digits.length < 7) return@withContext null
            // Same exact-match-first strategy as NidDatabaseProvider.
            val dao = db.nidDao()
            val candidates = linkedSetOf(
                digits,
                if (digits.startsWith("880")) digits.substring(3) else "0$digits".takeLast(11),
                digits.takeLast(11),
                if (digits.startsWith("880")) digits else "880${digits.trimStart('0')}",
                digits.takeLast(10),
            ).filter { it.length >= 7 }
            var rec: com.infocaller.app.data.local.entity.NidEntity? = null
            for (c in candidates) {
                rec = try { dao.findByPhoneExact(c) } catch (_: Exception) { null }
                if (rec != null) break
            }
            if (rec == null) {
                for (c in candidates) {
                    rec = try { dao.findByPhone(c) } catch (_: Exception) { null }
                    if (rec != null) break
                }
            }
            rec ?: return@withContext null

            val base = PartialResult(
                nid = rec.nid,
                dob = rec.dob,
                about = "NID: ${rec.nid} | DOB: ${rec.dob}",
                confidence = 0.9f,
                source = "BD NID Database",
                providerId = id, providerVersion = version
            )

            // Automatic smart-card status (best-effort, silent on failure).
            val statusText = try {
                fetchSmartCardStatus(rec.nid, rec.dob)
            } catch (_: Exception) { null }
            if (statusText.isNullOrBlank()) return@withContext base
            return@withContext base.copy(
                about = "${base.about} | Smart card: $statusText".take(500),
                confidence = 0.93f,
                source = "BD NID Database + Smart-Card Status"
            )
        }

    private suspend fun fetchSmartCardStatus(nid: String, dob: String): String? {
        val (day, month, year) = splitDob(dob) ?: return null
        if (day.isBlank() || month.isBlank() || year.isBlank()) return null
        val service = NidPortalService()
        if (!service.openCardStatus()) return null
        repeat(3) {
            try {
                val png = service.captchaPng("https://services.nidw.gov.bd/nid-pub/card-status")
                    ?: return@repeat
                val solved = NidCaptchaSolver.solve(png) ?: return@repeat
                val reply = service.validateCard(nid, day, month, year, solved) ?: return@repeat
                if (reply.status != "SUCCESS") return@repeat
                val view = service.smartCardStatusView()?.trim().orEmpty()
                if (view.isBlank()) return@repeat
                // Error block ("কোন তথ্য পাওয়া যায়নি" = no info) -> no
                // status for this NID; report that plainly, not as failure.
                if (view.contains("পাওয়া যায়নি")) return "no information found"
                return view.take(300)
            } catch (_: Exception) { }
        }
        return null
    }

    /** DOB in database.json is yyyy-MM-dd — split to day/month/year. */
    private fun splitDob(dob: String): Triple<String, String, String>? {
        val p = dob.split("-")
        return if (p.size == 3 && p[0].length == 4) Triple(p[2], p[1], p[0]) else null
    }
}
