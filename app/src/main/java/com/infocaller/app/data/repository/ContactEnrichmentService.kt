package com.infocaller.app.data.repository

import android.content.ContentProviderOperation
import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.ContactsContract
import android.util.Log
import com.infocaller.app.domain.model.Caller
import com.infocaller.app.domain.model.LookupResult
import com.infocaller.app.domain.repository.CallerRepository
import com.infocaller.app.domain.engine.PublicLookupEngine
import com.infocaller.app.domain.engine.Capability
import com.infocaller.app.data.local.database.AppDatabase
import com.infocaller.app.data.local.entity.ContactEnrichmentEntity
import com.infocaller.app.util.ContactUtils
import com.infocaller.app.util.SocialUtils
import com.infocaller.app.util.PhoneNumberUtils
import androidx.core.net.toUri
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream
import java.net.URL

class ContactEnrichmentService(
    private val context: Context,
    private val lookupEngine: com.infocaller.app.domain.engine.IPublicLookupEngine? = null,
    private val repository: CallerRepository? = null,
    private val database: AppDatabase? = null
) {

    suspend fun saveContactFast(
        phoneNumber: String,
        displayName: String,
        photoUrl: String? = null,
        accountName: String? = null,
        accountType: String? = null,
        lookupResult: LookupResult? = null
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val normalized = PhoneNumberUtils.normalize(phoneNumber)
            val caller = Caller(
                phoneNumber = normalized,
                displayName = displayName,
                alias = lookupResult?.sources?.firstOrNull(),
                photoUrl = photoUrl ?: lookupResult?.imageUrl,
                organization = lookupResult?.carrier,
                country = lookupResult?.country,
                region = lookupResult?.region,
                carrier = lookupResult?.carrier,
                reportCount = 0,
                isVerified = false,
                socialMediaLinks = lookupResult?.socialProfiles?.mapNotNull { it.profileUrl } ?: emptyList()
            )
            
            val rawContactId = saveToContacts(caller, accountName, accountType)
            
            if (lookupResult != null) {
                saveLookupResultToCache(lookupResult, rawContactId)
            }
            
            enrichSingleContact(normalized, rawContactId)
            
            true
        } catch (e: Exception) {
            Log.e("EnrichmentService", "Save contact failed", e)
            false
        }
    }

    private suspend fun saveLookupResultToCache(result: LookupResult, contactId: Long? = null) {
        database?.enrichmentDao()?.insertEnrichment(
            ContactEnrichmentEntity(
                normalizedPhoneNumber = result.phoneNumber,
                contactId = contactId,
                publicName = result.name,
                alternateName = result.alternateName,
                profileImageUrl = result.imageUrl,
                about = result.about,
                city = result.city,
                carrier = result.carrier,
                country = result.country,
                region = result.region,
                timezone = result.timezone,
                email = result.email,
                whatsappStatus = result.socialProfiles.find { it.platform == "WhatsApp" }?.status?.name,
                telegramStatus = result.socialProfiles.find { it.platform == "Telegram" }?.status?.name,
                socialProfilesJson = SocialUtils.toJson(result.socialProfiles),
                source = result.sources.joinToString(","),
                confidence = result.confidence.toString(),
                lastChecked = System.currentTimeMillis(),
                expiresAt = System.currentTimeMillis() + (7 * 24 * 60 * 60 * 1000L)
            )
        )
    }

    private fun saveToContacts(caller: Caller, accountName: String? = null, accountType: String? = null): Long {
        val ops = mutableListOf<ContentProviderOperation>()
        
        ops.add(ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI)
            .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, accountType)
            .withValue(ContactsContract.RawContacts.ACCOUNT_NAME, accountName)
            .build())
            
        ops.add(ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
            .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
            .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE)
            .withValue(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, caller.displayName)
            .build())
            
        ops.add(ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
            .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
            .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
            .withValue(ContactsContract.CommonDataKinds.Phone.NUMBER, caller.phoneNumber)
            .withValue(ContactsContract.CommonDataKinds.Phone.TYPE, ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE)
            .build())

        val results = context.contentResolver.applyBatch(ContactsContract.AUTHORITY, ArrayList(ops))
        return ContentUris.parseId(results[0].uri!!)
    }

    suspend fun updateExistingContact(phoneNumber: String, caller: Caller): Boolean = withContext(Dispatchers.IO) {
        try {
            val normalized = PhoneNumberUtils.normalize(phoneNumber)
            val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(normalized))
            val projection = arrayOf(
                ContactsContract.PhoneLookup._ID,
                ContactsContract.PhoneLookup.DISPLAY_NAME,
                ContactsContract.PhoneLookup.PHOTO_ID
            )

            var contactId: Long = -1
            var existingName: String? = null
            var photoId: Long = -1

            context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    contactId = cursor.getLong(0)
                    existingName = cursor.getString(1)
                    photoId = if (cursor.isNull(2)) -1 else cursor.getLong(2)
                }
            }

            if (contactId == -1L) return@withContext false

            val isSavedRealName = !ContactUtils.isPlaceholderName(existingName) && existingName != normalized && existingName?.filter { it.isDigit() } != normalized.filter { it.isDigit() }
            val shouldUpdateName = !isSavedRealName && com.infocaller.app.util.EnrichmentGapChecker.check(database?.enrichmentDao()?.getEnrichmentSync(normalized)).missingName && !ContactUtils.isPlaceholderName(caller.displayName) && caller.displayName != null

            if (isSavedRealName && caller.displayName != null) {
                // Keep the user's own saved name; enrichment must never rename it.
            }

            val gaps = com.infocaller.app.util.EnrichmentGapChecker.check(database?.enrichmentDao()?.getEnrichmentSync(normalized))
            if (gaps.isComplete && caller.displayName == null && caller.photoUrl == null) return@withContext false

            val ops = mutableListOf<ContentProviderOperation>()

            if (shouldUpdateName) {
                ops.add(ContentProviderOperation.newUpdate(ContactsContract.Data.CONTENT_URI)
                    .withSelection("${ContactsContract.Data.RAW_CONTACT_ID} = ? AND ${ContactsContract.Data.MIMETYPE} = ?",
                        arrayOf(contactId.toString(), ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE))
                    .withValue(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, caller.displayName)
                    .build())
            }

            val shouldUpdatePhoto = photoId == -1L && gaps.missingPhoto && caller.photoUrl != null
            if (shouldUpdatePhoto) {
                val bitmap = downloadBitmap(caller.photoUrl)
                if (bitmap != null) {
                    val stream = ByteArrayOutputStream()
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
                    val photoBytes = stream.toByteArray()

                    ops.add(ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                        .withValue(ContactsContract.Data.RAW_CONTACT_ID, contactId)
                        .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Photo.CONTENT_ITEM_TYPE)
                        .withValue(ContactsContract.CommonDataKinds.Photo.PHOTO, photoBytes)
                        .build())
                }
            }

            if (ops.isNotEmpty()) {
                context.contentResolver.applyBatch(ContactsContract.AUTHORITY, ArrayList(ops))
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun downloadBitmap(url: String): Bitmap? {
        return try {
            if (url.startsWith("content://") || url.startsWith("file://")) {
                context.contentResolver.openInputStream(url.toUri())?.use { inputStream ->
                    BitmapFactory.decodeStream(inputStream)
                }
            } else {
                val connection = URL(url).openConnection()
                connection.doInput = true
                connection.connectTimeout = 10000
                connection.readTimeout = 10000
                connection.getInputStream().use { input ->
                    BitmapFactory.decodeStream(input)
                }
            }
        } catch (e: Exception) {
            null
        }
    }

    suspend fun deleteContact(phoneNumber: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val normalized = PhoneNumberUtils.normalize(phoneNumber)
            val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(normalized))
            val projection = arrayOf(ContactsContract.PhoneLookup._ID)
            
            var contactId: Long = -1
            context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    contactId = cursor.getLong(0)
                }
            }
            
            if (contactId != -1L) {
                val deleteUri = Uri.withAppendedPath(ContactsContract.Contacts.CONTENT_URI, contactId.toString())
                context.contentResolver.delete(deleteUri, null, null)
                database?.localContactDao()?.deleteByNumber(normalized)
                true
            } else {
                false
            }
        } catch (e: Exception) {
            false
        }
    }

    suspend fun syncAllWhatsAppPhotos(onProgress: (Int, Int) -> Unit): Int = withContext(Dispatchers.IO) {
        val contacts = database?.localContactDao()?.getAllContactsSync() ?: return@withContext 0
        var count = 0
        contacts.forEachIndexed { index, contact ->
            val res = lookupEngine?.performLookup(contact.phoneNumber, com.infocaller.app.domain.engine.IdentifierType.PHONE, setOf(Capability.WHATSAPP, Capability.PROFILE_PHOTO))
            if (res?.imageUrl != null) {
                updateExistingContact(contact.phoneNumber, Caller(
                    phoneNumber = contact.phoneNumber, 
                    photoUrl = res.imageUrl,
                    displayName = null,
                    alias = null,
                    organization = null,
                    country = null,
                    region = null,
                    carrier = null
                ))
                count++
            }
            onProgress(index + 1, contacts.size)
        }
        count
    }

    suspend fun enrichAllContactsInBg() = withContext(Dispatchers.IO) {
        val contacts = database?.localContactDao()?.getAllContactsSync() ?: return@withContext
        contacts.forEach { contact ->
            enrichSingleContact(contact.phoneNumber, contact.id)
        }
    }

    private suspend fun enrichSingleContact(phoneNumber: String, contactId: Long?) {
        val normalized = PhoneNumberUtils.normalize(phoneNumber)
        val existing = database?.enrichmentDao()?.getEnrichmentSync(normalized)
        val isStale = existing == null || existing.expiresAt < System.currentTimeMillis()

        if (isStale) {
            val result = lookupEngine?.performLookup(normalized)
            if (result != null) {
                saveLookupResultToCache(result, contactId)
            }
        }
    }
}

