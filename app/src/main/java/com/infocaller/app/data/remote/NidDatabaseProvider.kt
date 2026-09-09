package com.infocaller.app.data.remote

import com.infocaller.app.data.local.database.AppDatabase
import com.infocaller.app.domain.engine.*
import com.infocaller.app.util.PhoneNumberUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class NidDatabaseProvider(
    private val db: AppDatabase
) : LookupProvider {
    override val id = "bd_nid_database"
    override val name = "BD NID Database (115k)"
    override val version = "2.0.0"
    override val capabilities = setOf(Capability.DEEP_PII, Capability.PUBLIC_SEARCH, Capability.PHONE_METADATA, Capability.PUBLIC_PROFILE)
    override val priority = 960
    override val costClass = CostClass.FREE

    override suspend fun lookup(identifier: String, type: String, context: LookupContext): PartialResult? = withContext(Dispatchers.IO) {
        val dao = db.nidDao()
        try {
            when (type) {
                IdentifierType.PHONE -> {
                    val digits = identifier.filter { it.isDigit() }
                    val candidates = linkedSetOf(
                        digits,
                        if (digits.startsWith("880")) digits.substring(3) else "0$digits".takeLast(11),
                        digits.takeLast(11),
                        if (digits.startsWith("880")) digits else "880${digits.trimStart('0')}",
                        digits.takeLast(10),
                    ).filter { it.length >= 7 }
                    var rec: com.infocaller.app.data.local.entity.NidEntity? = null
                    for (c in candidates) {
                        rec = dao.findByPhoneExact(c)
                        if (rec != null) break
                    }
                    if (rec == null) {
                        for (c in candidates) {
                            rec = dao.findByPhone(c)
                            if (rec != null) break
                        }
                    }
                    return@withContext toPartial(rec ?: return@withContext null)
                }
                IdentifierType.NID -> {
                    val rec = dao.findByNid(identifier.trim()) ?: return@withContext null
                    return@withContext toPartial(rec)
                }
                IdentifierType.DOB -> {
                    val list = dao.findByDob(identifier.trim())
                    if (list.isEmpty()) return@withContext null
                    val rec = list.first()
                    return@withContext toPartial(rec).copy(about = "${toPartial(rec).about} | DOB matches ${list.size} records")
                }
                else -> {
                    if (identifier.contains("|")) {
                        val parts = identifier.split("|")
                        val nid = parts[0].trim()
                        val dob = parts[1].trim()
                        val rec = dao.findByNidAndDob(nid, dob) ?: dao.findByNid(nid)
                        if (rec != null) return@withContext toPartial(rec, exactDobMatch = rec.dob == dob)
                    }
                    val rec = dao.findByNid(identifier.trim())
                    if (rec != null) return@withContext toPartial(rec)
                    return@withContext null
                }
            }
        } catch (_: Exception) { null }
    }

    private fun toPartial(rec: com.infocaller.app.data.local.entity.NidEntity, exactDobMatch: Boolean = true): PartialResult {
        val hasEnriched = !rec.nameEn.isNullOrBlank() || !rec.photoUrl.isNullOrBlank() ||
            !rec.fatherName.isNullOrBlank() || !rec.motherName.isNullOrBlank() || !rec.address.isNullOrBlank()
        if (!hasEnriched) {
            return PartialResult(
                identifier = rec.number,
                identifierType = IdentifierType.PHONE,
                about = "NID: ${rec.nid} | DOB: ${rec.dob}" + if (!exactDobMatch) " (DOB not matched)" else "",
                nid = rec.nid,
                dob = rec.dob,
                confidence = 0.95f,
                source = "BD NID Database",
                providerId = id, providerVersion = version
            )
        }
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
            confidence = 0.98f,
            source = "BD NID Database (Enriched)",
            providerId = id, providerVersion = version
        )
    }
}
