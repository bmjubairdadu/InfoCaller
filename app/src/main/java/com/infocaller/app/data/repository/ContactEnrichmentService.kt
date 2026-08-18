package com.infocaller.app.data.repository

import android.content.ContentProviderOperation
import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.ContactsContract
import android.util.Log
import com.infocaller.app.domain.model.Caller
import com.infocaller.app.domain.model.SpamStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

import com.infocaller.app.domain.repository.CallerRepository
import com.infocaller.app.domain.engine.PublicLookupEngine
import com.infocaller.app.data.local.database.AppDatabase
import com.infocaller.app.data.local.entity.ContactBackupEntity
import com.infocaller.app.data.local.entity.ContactEnrichmentEntity
import com.infocaller.app.util.ContactUtils
import com.infocaller.app.util.SocialUtils

class ContactEnrichmentService(
    private val context: Context,
    private val lookupEngine: PublicLookupEngine? = null,
    private val repository: CallerRepository? = null,
    private val database: AppDatabase? = null
) {

    suspend fun saveContactFast(
        phoneNumber: String,
        displayName: String,
        photoUrl: String? = null,
        accountName: String? = null,
        accountType: String? = null,
        lookupResult: com.infocaller.app.domain.model.LookupResult? = null
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val caller = Caller(
                phoneNumber = phoneNumber,
                displayName = displayName,
                alias = lookupResult?.sources?.firstOrNull(),
                photoUrl = photoUrl ?: lookupResult?.imageUrl,
                organization = lookupResult?.carrier,
                country = lookupResult?.country,
                region = lookupResult?.region,
                carrier = lookupResult?.carrier,
                spamScore = lookupResult?.spamScore ?: 0,
                spamStatus = lookupResult?.spamStatus ?: SpamStatus.UNKNOWN,
                socialMediaLinks = lookupResult?.socialProfiles?.mapNotNull { it.profileUrl } ?: emptyList()
            )
            saveToContacts(caller, accountName, accountType)
            if (lookupResult != null) saveLookupResultToCache(lookupResult)
            true
        } catch (e: Exception) {
            false
        }
    }

    private suspend fun saveLookupResultToCache(result: com.infocaller.app.domain.model.LookupResult) {
        database?.enrichmentDao()?.insertEnrichment(
            ContactEnrichmentEntity(
                normalizedPhoneNumber = result.phoneNumber,
                publicName = result.name,
                profileImageUrl = result.imageUrl,
                about = result.about,
                city = result.city,
                carrier = result.carrier,
                country = result.country,
                region = result.region,
                whatsappStatus = result.socialProfiles.find { it.platform == "WhatsApp" }?.status?.name,
                telegramStatus = result.socialProfiles.find { it.platform == "Telegram" }?.status?.name,
                socialProfilesJson = SocialUtils.toJson(result.socialProfiles),
                spamScore = result.spamScore,
                spamType = result.spamType,
                spamStatus = result.spamStatus.name,
                source = result.sources.joinToString(","),
                confidence = result.confidence.toString(),
                lastChecked = System.currentTimeMillis(),
                expiresAt = System.currentTimeMillis() + (7 * 24 * 60 * 60 * 1000L)
            )
        )
    }

    private fun saveToContacts(caller: Caller, accountName: String? = null, accountType: String? = null) {
        runBlocking {
            val ops = mutableListOf<ContentProviderOperation>()
            ops.add(ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI)
                .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, accountType)
                .withValue(ContactsContract.RawContacts.ACCOUNT_NAME, accountName)
                .build())
            caller.displayName?.let { name ->
                ops.add(ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                    .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                    .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE)
                    .withValue(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, name)
                    .build())
            }
            ops.add(ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
                .withValue(ContactsContract.CommonDataKinds.Phone.NUMBER, caller.phoneNumber)
                .withValue(ContactsContract.CommonDataKinds.Phone.TYPE, ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE)
                .build())
            context.contentResolver.applyBatch(ContactsContract.AUTHORITY, ArrayList(ops))
        }
    }

    suspend fun updateExistingContact(phoneNumber: String, caller: Caller): Boolean = withContext(Dispatchers.IO) {
        true
    }

    suspend fun deleteContact(phoneNumber: String): Boolean = withContext(Dispatchers.IO) {
        true
    }

    suspend fun syncAllWhatsAppPhotos(onProgress: (Int, Int) -> Unit): Int = withContext(Dispatchers.IO) { 0 }
    suspend fun enrichAllContactsInBg(): Unit = withContext(Dispatchers.IO) { }
    suspend fun emergencyCleanup(onProgress: (String) -> Unit): Int = withContext(Dispatchers.IO) { 0 }
}
