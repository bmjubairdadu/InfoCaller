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

    override suspend fun getFreshCachedCaller(phoneNumber: String): Caller? {
        val normalized = PhoneNumberUtils.normalize(phoneNumber)
        val cached = callerDao.getCallerSync(normalized) ?: return null
        return cached.takeIf {
            System.currentTimeMillis() - it.lastUpdated < 7 * 24 * 60 * 60 * 1000L
        }?.toDomain()
    }

    override suspend fun searchCaller(phoneNumber: String): Caller? {
        val normalized = PhoneNumberUtils.normalize(phoneNumber)

        val cached = callerDao.getCallerSync(normalized)
        if (cached != null) {
            val age = System.currentTimeMillis() - cached.lastUpdated
            val isFresh = age < 7 * 24 * 60 * 60 * 1000L
            if (isFresh) return cached.toDomain()
        }

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
        val raw = result.phoneNumber
        val isEmailAddr = com.infocaller.app.util.IdentifierRouter.isEmail(raw)
        val isNonPhone = isEmailAddr ||
            com.infocaller.app.util.IdentifierRouter.routeType(raw) == com.infocaller.app.domain.engine.IdentifierType.USERNAME
        val normalized = when {
            isEmailAddr -> raw.trim().lowercase()
            com.infocaller.app.util.IdentifierRouter.routeType(raw) == com.infocaller.app.domain.engine.IdentifierType.USERNAME -> raw.trim().lowercase().removePrefix("@")
            else -> PhoneNumberUtils.normalize(raw)
        }
        val existing = enrichmentDao.getEnrichmentSync(normalized)

        if (existing != null) {
            val hasNew = (result.name != null && (existing.publicName.isNullOrBlank() || com.infocaller.app.util.ContactUtils.isPlaceholderName(existing.publicName))) ||
                    (result.imageUrl != null && existing.profileImageUrl.isNullOrBlank()) ||
                    (result.city != null && existing.city.isNullOrBlank()) ||
                    (result.email != null && existing.email.isNullOrBlank()) ||
                    (result.about != null && existing.about.isNullOrBlank()) ||
                    (result.carrier != null && existing.carrier.isNullOrBlank()) ||
                    (result.country != null && existing.country.isNullOrBlank()) ||
                    (result.socialProfiles.isNotEmpty()) ||
                    (result.photoCandidates.isNotEmpty()) ||
                    (result.alternateName != null && existing.alternateName.isNullOrBlank())
            if (!hasNew) return
        }

        val existingCaller = callerDao.getCallerSync(normalized)
        val localName = if (isNonPhone) existingCaller?.localName else existingCaller?.localName ?: findLocalNameInSystem(normalized)

        val resultToStore = if (isNonPhone && result.phoneNumber != normalized) result.copy(phoneNumber = normalized) else result
        val merged = mapToEntity(resultToStore, existing)
        enrichmentDao.insertEnrichment(merged)

        val incomingPublicName =
            result.name?.takeIf { !com.infocaller.app.util.ContactUtils.isPlaceholderName(it) }
        callerDao.insertCaller(CallerEntity(
            phoneNumber = normalized,
            localName = localName,
            displayName = incomingPublicName ?: existingCaller?.displayName,
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
        val existingHasRealSavedName = !com.infocaller.app.util.ContactUtils.isPlaceholderName(existing?.publicName) && !existing?.publicName.isNullOrBlank()
        val incomingIsValid = !res.name.isNullOrBlank() && !com.infocaller.app.util.ContactUtils.isPlaceholderName(res.name)
        val publicNameToStore = when {
            existingHasRealSavedName -> existing?.publicName
            incomingIsValid -> res.name
            else -> existing?.publicName
        }
        val alternateToStore = when {
            existingHasRealSavedName && incomingIsValid && res.name != existing?.publicName -> res.name
            res.alternateName != null -> res.alternateName
            else -> existing?.alternateName
        }
        val socialJson = if (res.socialProfiles.isNotEmpty()) SocialUtils.toJson(res.socialProfiles) else existing?.socialProfilesJson
        val photoJson = if (res.photoCandidates.isNotEmpty()) gson.toJson(res.photoCandidates) else existing?.photoCandidatesJson
        val altNamesJson = if (res.alternateNames.isNotEmpty()) gson.toJson(res.alternateNames) else existing?.alternateNamesJson
        // Photo policy at rest (predictable):
        // - user pick always wins, never overwritten here
        // - empty slot: adopt only usable + auto (Truecaller/Eyecon/Email) photos
        // - occupied slot: overwrite only with usable + auto photos; Telegram/Twitch/
        //   Steam/etc stay as tap-to-set options, never auto-primary.
        val incomingPhotoOk = try { com.infocaller.app.util.PhotoPolicy.isUsablePhotoUrl(res.imageUrl) } catch (_: Exception) { false } catch (_: Error) { false }
        val incomingIsAuto = try { com.infocaller.app.util.PhotoPolicy.isAutoProvider(res.imageSource, null) } catch (_: Exception) { false } catch (_: Error) { false }
        val existingIsUser = try { com.infocaller.app.util.PhotoPolicy.isUserPicked(existing?.profileImageSource) } catch (_: Exception) { false } catch (_: Error) { false }
        val finalPhotoUrl = when {
            existingIsUser -> existing?.profileImageUrl
            existing?.profileImageUrl.isNullOrBlank() -> if (incomingPhotoOk && incomingIsAuto) res.imageUrl else null
            incomingPhotoOk && incomingIsAuto -> res.imageUrl
            else -> existing?.profileImageUrl
        }
        val finalPhotoSource = when {
            existingIsUser -> existing?.profileImageSource
            existing?.profileImageUrl.isNullOrBlank() -> if (incomingPhotoOk && incomingIsAuto) res.imageSource else null
            incomingPhotoOk && incomingIsAuto -> res.imageSource
            else -> existing?.profileImageSource
        }
        return ContactEnrichmentEntity(
            normalizedPhoneNumber = res.phoneNumber,
            contactId = existing?.contactId,
            publicName = publicNameToStore,
            publicNameSource = if (publicNameToStore == res.name) res.nameSource else existing?.publicNameSource,
            publicNameConfidence = if (publicNameToStore == res.name) res.confidence else existing?.publicNameConfidence,
            alternateName = alternateToStore,
            profileImageUrl = finalPhotoUrl,
            profileImageSource = finalPhotoSource,
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
            expiresAt = System.currentTimeMillis() + (30 * 24 * 60 * 60 * 1000L)
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

    override fun cancelAllScans() {
        try { orchestrator.cancelAllScans() } catch (_: Exception) { }
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
