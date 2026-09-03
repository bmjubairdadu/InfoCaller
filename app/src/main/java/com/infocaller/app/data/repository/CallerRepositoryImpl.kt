package com.infocaller.app.data.repository

import android.content.Context
import com.infocaller.app.data.local.dao.CallerDao
import com.infocaller.app.data.local.dao.BlocklistDao
import com.infocaller.app.data.local.dao.EnrichmentDao
import com.infocaller.app.data.local.entity.BlocklistEntity
import com.infocaller.app.data.local.entity.CallerEntity
import com.infocaller.app.data.local.entity.ContactEnrichmentEntity
import com.infocaller.app.domain.model.Caller
import com.infocaller.app.domain.model.LookupResult
import com.infocaller.app.domain.repository.CallerRepository
import com.infocaller.app.domain.engine.*
import com.infocaller.app.util.PhoneNumberUtils
import com.infocaller.app.util.SocialUtils
import com.google.gson.Gson
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

class CallerRepositoryImpl(
    private val callerDao: CallerDao,
    private val blocklistDao: BlocklistDao,
    private val enrichmentDao: EnrichmentDao,
    private val lookupEngine: IPublicLookupEngine,
    private val orchestrator: IScanOrchestrator,
    private val contextResolver: com.infocaller.app.util.IContextResolver
) : com.infocaller.app.domain.repository.ICallerRepository {

    private val gson = Gson()

    override fun getCaller(phoneNumber: String): Flow<Caller?> {
        val normalized = PhoneNumberUtils.normalize(phoneNumber)
        return callerDao.getCaller(normalized).map { it?.toDomain() }
    }

    override suspend fun searchCaller(phoneNumber: String): Caller? {
        val normalized = PhoneNumberUtils.normalize(phoneNumber)
        
        // 1. Check local DB
        val cached = callerDao.getCallerSync(normalized)
        if (cached != null) {
            val age = System.currentTimeMillis() - cached.lastUpdated
            val isFresh = age < 7 * 24 * 60 * 60 * 1000L
            if (isFresh) return cached.toDomain()
        }

        // 2. Perform Foreground Scan via Orchestrator
        // Note: For searchCaller we wait for completion to maintain backward compatibility,
        // but new UI will use startScan directly for progressive updates.
        return try {
            val finalState = orchestrator.startScan(phoneNumber, ScanPriority.FOREGROUND).first { 
                it is ScanState.Completed || it is ScanState.Error 
            }
            
            if (finalState is ScanState.Completed) {
                saveLookupResult(finalState.result)
                getCaller(phoneNumber).first()
            } else {
                cached?.toDomain()
            }
        } catch (e: Exception) {
            cached?.toDomain()
        }
    }

    override suspend fun saveLookupResult(result: LookupResult) {
        val normalized = PhoneNumberUtils.normalize(result.phoneNumber)
        val existing = enrichmentDao.getEnrichmentSync(normalized)

        // Skip if incoming result has no new fields beyond what we already have
        if (existing != null) {
            val gaps = com.infocaller.app.util.EnrichmentGapChecker.check(existing)
            if (gaps.isComplete) {
                val hasNew = (result.name != null && (existing.publicName.isNullOrBlank() || com.infocaller.app.util.ContactUtils.isPlaceholderName(existing.publicName))) ||
                        (result.imageUrl != null && existing.profileImageUrl.isNullOrBlank()) ||
                        (result.city != null && existing.city.isNullOrBlank()) ||
                        (result.email != null && existing.email.isNullOrBlank())
                if (!hasNew) return
            }
        }

        // 1. Preserve local contact name if already in DB
        val existingCaller = callerDao.getCallerSync(normalized)
        val localName = existingCaller?.localName ?: findLocalNameInSystem(normalized)

        // 2. Merge with existing enrichment record (field-aware: keep existing if new is blank)
        val merged = mapToEntity(result, existing)
        enrichmentDao.insertEnrichment(merged)
        
        // 3. Update main Caller list entry (non-destructive, NAME LOCKED if user-saved)
        val hasRealSavedName = existingCaller?.displayName != null && !com.infocaller.app.util.ContactUtils.isPlaceholderName(existingCaller.displayName)
        callerDao.insertCaller(CallerEntity(
            phoneNumber = normalized,
            localName = localName,
            displayName = if (hasRealSavedName) existingCaller?.displayName else (result.name ?: existingCaller?.displayName),
            alias = result.alternateName ?: existingCaller?.alias,
            photoUrl = result.imageUrl ?: existingCaller?.photoUrl,
            organization = result.carrier ?: existingCaller?.organization,
            country = result.country ?: existingCaller?.country,
            region = result.region ?: existingCaller?.region,
            carrier = result.carrier ?: existingCaller?.carrier,
            reportCount = existingCaller?.reportCount ?: 0,
            isVerified = existingCaller?.isVerified ?: false,
            socialMediaLinks = if (result.socialProfiles.isNotEmpty()) {
                result.socialProfiles.joinToString(",") { it.profileUrl ?: "" }
            } else {
                existingCaller?.socialMediaLinks
            },
            lastUpdated = System.currentTimeMillis()
        ))
    }

    private fun findLocalNameInSystem(phoneNumber: String): String? {
        val uri = android.net.Uri.withAppendedPath(android.provider.ContactsContract.PhoneLookup.CONTENT_FILTER_URI, android.net.Uri.encode(phoneNumber))
        val projection = arrayOf(android.provider.ContactsContract.PhoneLookup.DISPLAY_NAME)
        return try {
            contextResolver.query(uri, projection, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun mapToEntity(res: LookupResult, existing: ContactEnrichmentEntity?): ContactEnrichmentEntity {
        // NAME LOCK: If existing publicName is a real saved name (not placeholder), never overwrite with enrichment name.
        // Only update name if existing is null/placeholder (i.e., not user-saved). Use alternateName for discovered names.
        val existingHasRealSavedName = !com.infocaller.app.util.ContactUtils.isPlaceholderName(existing?.publicName) && !existing?.publicName.isNullOrBlank()
        val incomingIsValid = !res.name.isNullOrBlank() && !com.infocaller.app.util.ContactUtils.isPlaceholderName(res.name)
        val publicNameToStore = when {
            existingHasRealSavedName -> existing?.publicName // LOCKED - never change
            incomingIsValid -> res.name // first time fill
            else -> existing?.publicName
        }
        val alternateToStore = when {
            existingHasRealSavedName && incomingIsValid && res.name != existing?.publicName -> res.name // discovered name goes to alternateName
            res.alternateName != null -> res.alternateName
            else -> existing?.alternateName
        }
        // Preserve existing social profiles if new list is empty
        val socialJson = if (res.socialProfiles.isNotEmpty()) SocialUtils.toJson(res.socialProfiles) else existing?.socialProfilesJson
        val photoJson = if (res.photoCandidates.isNotEmpty()) gson.toJson(res.photoCandidates) else existing?.photoCandidatesJson
        val altNamesJson = if (res.alternateNames.isNotEmpty()) gson.toJson(res.alternateNames) else existing?.alternateNamesJson
        return ContactEnrichmentEntity(
            normalizedPhoneNumber = res.phoneNumber,
            contactId = existing?.contactId,
            publicName = publicNameToStore,
            publicNameSource = if (publicNameToStore == res.name) res.nameSource else existing?.publicNameSource,
            publicNameConfidence = if (publicNameToStore == res.name) res.confidence else existing?.publicNameConfidence,
            alternateName = alternateToStore,
            profileImageUrl = res.imageUrl ?: existing?.profileImageUrl,
            profileImageSource = res.imageSource ?: existing?.profileImageSource,
            about = res.about ?: existing?.about,
            email = res.email ?: existing?.email,
            emailSource = res.emailSource ?: existing?.emailSource,
            city = res.city ?: existing?.city,
            country = res.country ?: existing?.country,
            carrier = res.carrier ?: existing?.carrier,
            lineType = res.lineType ?: existing?.lineType,
            region = res.region ?: existing?.region,
            timezone = res.timezone ?: existing?.timezone,
            isBusiness = res.isBusiness ?: existing?.isBusiness,
            nid = res.nid ?: existing?.nid,
            dob = res.dob ?: existing?.dob,
            socialProfilesJson = socialJson,
            photoCandidatesJson = photoJson,
            alternateNamesJson = altNamesJson,
            lastScannedAt = System.currentTimeMillis(),
            expiresAt = System.currentTimeMillis() + (30 * 24 * 60 * 60 * 1000L) // 30 days
        )
    }

    override suspend fun saveCaller(caller: Caller) {
        callerDao.insertCaller(CallerEntity.fromDomain(caller))
    }

    override suspend fun contributeCallerInfo(caller: Caller) {
        saveCaller(caller)
    }

    override fun getScanStates(): StateFlow<Map<String, ScanState>> = orchestrator.scanStates

    override fun startScan(identifier: String, priority: ScanPriority, type: String): Flow<ScanState> {
        return orchestrator.startScan(identifier, priority, type)
    }

    override fun cancelScan(identifier: String) {
        orchestrator.cancelScan(identifier)
    }

    override fun getBlocklist(): Flow<List<String>> {
        return blocklistDao.getAllBlocked().map { list -> list.map { it.phoneNumber } }
    }

    override suspend fun blockNumber(phoneNumber: String) {
        blocklistDao.block(BlocklistEntity(PhoneNumberUtils.normalize(phoneNumber)))
    }

    override suspend fun unblockNumber(phoneNumber: String) {
        blocklistDao.unblock(PhoneNumberUtils.normalize(phoneNumber))
    }

    override suspend fun isBlocked(phoneNumber: String): Boolean {
        return blocklistDao.isBlocked(PhoneNumberUtils.normalize(phoneNumber))
    }
}
