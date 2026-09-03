package com.infocaller.app.data.remote

import com.infocaller.app.data.local.database.AppDatabase
import com.infocaller.app.domain.engine.*
import com.infocaller.app.util.PhoneNumberUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Local NID Database provider - backed by Room nid_records (115k BD NID+DOB).
 * Prioritized highest so incoming call resolves instantly offline.
 * Supports: PHONE -> nid/dob, NID -> phone/dob, NID+DOB combined, DOB search.
 */
class NidDatabaseProvider(
    private val db: AppDatabase
) : LookupProvider {
    override val id = "bd_nid_database"
    override val name = "BD NID Database (115k)"
    override val version = "2.0.0"
    override val capabilities = setOf(Capability.DEEP_PII, Capability.PUBLIC_SEARCH, Capability.PHONE_METADATA, Capability.PUBLIC_PROFILE)
    override val priority = 960 // highest, above local_json_db (950), so offline hit wins
    override val costClass = CostClass.FREE

    override suspend fun lookup(identifier: String, type: String, context: LookupContext): PartialResult? = withContext(Dispatchers.IO) {
        val dao = db.nidDao()
        try {
            when (type) {
                IdentifierType.PHONE -> {
                    val digits = identifier.filter { it.isDigit() }
                    // try exact suffix 11 digits BD number
                    val suffix = if (digits.startsWith("880")) digits.substring(3) else if (digits.length == 11) digits else digits.takeLast(11)
                    val rec = dao.findByPhone(suffix) ?: dao.findByPhone(digits.takeLast(10)) ?: return@withContext null
                    return@withContext toPartial(rec)
                }
                IdentifierType.NID -> {
                    val rec = dao.findByNid(identifier.trim()) ?: return@withContext null
                    return@withContext toPartial(rec)
                }
                IdentifierType.DOB -> {
                    val list = dao.findByDob(identifier.trim())
                    if (list.isEmpty()) return@withContext null
                    // return first with NID context; also expose count via about
                    val rec = list.first()
                    return@withContext toPartial(rec).copy(about = "${toPartial(rec).about} | DOB matches ${list.size} records")
                }
                else -> {
                    // Support "NID|DOB" combined identifier e.g. "19912345678901234|1992-10-11"
                    if (identifier.contains("|")) {
                        val parts = identifier.split("|")
                        val nid = parts[0].trim()
                        val dob = parts[1].trim()
                        val rec = dao.findByNidAndDob(nid, dob) ?: dao.findByNid(nid)
                        if (rec != null) return@withContext toPartial(rec, exactDobMatch = rec.dob == dob)
                    }
                    // fallback: try nid
                    val rec = dao.findByNid(identifier.trim())
                    if (rec != null) return@withContext toPartial(rec)
                    return@withContext null
                }
            }
        } catch (_: Exception) { null }
    }

    private fun toPartial(rec: com.infocaller.app.data.local.entity.NidEntity, exactDobMatch: Boolean = true): PartialResult {
        val hasEnriched = !rec.nameEn.isNullOrBlank()
        return PartialResult(
            identifier = rec.number,
            identifierType = IdentifierType.PHONE,
            name = rec.nameEn ?: rec.nameBn,
            alternateName = rec.fatherName?.let { "Father: $it" },
            about = buildString {
                append("NID: ${rec.nid} | DOB: ${rec.dob}")
                if (!rec.fatherName.isNullOrBlank()) append(" | Father: ${rec.fatherName}")
                if (!rec.motherName.isNullOrBlank()) append(" | Mother: ${rec.motherName}")
                if (!rec.address.isNullOrBlank()) append(" | Address: ${rec.address}")
                if (!exactDobMatch) append(" (DOB not matched)")
            },
            city = rec.address,
            country = "Bangladesh",
            nid = rec.nid,
            dob = rec.dob,
            imageUrl = rec.photoUrl,
            confidence = if (hasEnriched) 0.98f else 0.95f,
            source = if (hasEnriched) "BD NID Database (Enriched)" else "BD NID Database",
            providerId = id, providerVersion = version
        )
    }
}
