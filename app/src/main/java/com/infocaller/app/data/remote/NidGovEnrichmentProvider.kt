package com.infocaller.app.data.remote

import com.infocaller.app.data.local.database.AppDatabase
import com.infocaller.app.domain.engine.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient


class NidGovEnrichmentProvider(
    private val db: AppDatabase,
    private val httpClient: OkHttpClient
) : LookupProvider {
    override val id = "nid_gov_enrichment"
    override val name = "NID Full Identity (NID+DOB)"
    override val version = "1.0.0"
    override val capabilities = setOf(Capability.DEEP_PII, Capability.PROFILE_PHOTO, Capability.PUBLIC_SEARCH, Capability.PUBLIC_PROFILE, Capability.CITY, Capability.COUNTRY)
    override val priority = 88
    override val costClass = CostClass.FREE

    override suspend fun lookup(identifier: String, type: String, context: LookupContext): PartialResult? = withContext(Dispatchers.IO) {
        val nid: String
        val dob: String?
        when {
            identifier.contains("|") -> { val p = identifier.split("|"); nid = p[0].trim(); dob = p.getOrNull(1)?.trim() }
            type == IdentifierType.NID -> { nid = identifier.trim(); dob = null }
            else -> return@withContext null
        }
        if (nid.length < 7) return@withContext null
        val dao = db.nidDao()
        val recNullable = if (dob != null) dao.findByNidAndDob(nid, dob) ?: dao.findByNid(nid) else dao.findByNid(nid)
        val rec = recNullable ?: return@withContext null

        if (!rec.nameEn.isNullOrBlank() || !rec.photoUrl.isNullOrBlank() || !rec.fatherName.isNullOrBlank()) {
            return@withContext PartialResult(
                name = rec.nameEn ?: rec.nameBn,
                alternateName = rec.fatherName,
                about = "Father: ${rec.fatherName ?: "-"} | Mother: ${rec.motherName ?: "-"} | Address: ${rec.address ?: "-"} | NID: ${rec.nid} DOB: ${rec.dob}",
                city = rec.address, country = "Bangladesh",
                imageUrl = rec.photoUrl, nid = rec.nid, dob = rec.dob,
                confidence = 0.97f, source = "NID Enriched Cache", providerId = id, providerVersion = version
            )
        }

        // NOTE: the old DuckDuckGo "NID dork" fallback was removed — DDG
        // scraping was pruned repo-wide (blocks + title-guess hallucinations
        // for NIDs are dangerous). Fall through to the DB row below.
        return@withContext PartialResult(
            nid = rec.nid, dob = rec.dob, city = rec.address, country = "Bangladesh",
            about = "NID: ${rec.nid} | DOB: ${rec.dob} | Phone: ${rec.number}",
            confidence = 0.9f, source = "BD NID Database", providerId = id, providerVersion = version
        )
    }
}
