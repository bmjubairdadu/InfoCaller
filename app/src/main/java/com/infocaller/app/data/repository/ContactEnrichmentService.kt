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
                profileImageSource = result.imageSource,
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
                // Auto-photo fix: persist candidates so the cached photo survives
                // for the Details Lens button + next-scan LookupContext.
                photoCandidatesJson = if (result.photoCandidates.isNotEmpty()) SocialUtils.photosToJson(result.photoCandidates) else null,
                source = result.sources.joinToString(","),
                confidence = result.confidence.toString(),
                lastChecked = System.currentTimeMillis(),
                expiresAt = System.currentTimeMillis() + (7 * 24 * 60 * 60 * 1000L)
            )
        )
    }

    private fun saveToContacts(caller: Caller, accountName: String? = null, accountType: String? = null): Long {
        // Standard Android format every contacts app understands: one
        // RawContact + StructuredName + Mobile Phone + (optional) Email +
        // (optional) Organization rows. Email uses the real Email mimetype
        // (not a note) so Gmail/Dialer/people apps file it correctly.
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

        caller.email?.trim()?.takeIf { it.contains("@") }?.let { email ->
            ops.add(ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE)
                .withValue(ContactsContract.CommonDataKinds.Email.ADDRESS, email)
                .withValue(ContactsContract.CommonDataKinds.Email.TYPE, ContactsContract.CommonDataKinds.Email.TYPE_HOME)
                .build())
        }

        caller.organization?.trim()?.takeIf { it.isNotBlank() }?.let { org ->
            ops.add(ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Organization.CONTENT_ITEM_TYPE)
                .withValue(ContactsContract.CommonDataKinds.Organization.COMPANY, org)
                .withValue(ContactsContract.CommonDataKinds.Organization.TYPE, ContactsContract.CommonDataKinds.Organization.TYPE_WORK)
                .build())
        }

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

            // Never overwrite a saved contact's own name. Previously this
            // method would rename the row when a public caller-ID arrived;
            // that is now removed. Names are shown side-by-side in UI and
            // enrichment-cache only.
            val isSavedRealName = !ContactUtils.isPlaceholderName(existingName) && existingName != normalized && existingName?.filter { it.isDigit() } != normalized.filter { it.isDigit() }

            val enrichment = database?.enrichmentDao()?.getEnrichmentSync(normalized)
            val gaps = com.infocaller.app.util.EnrichmentGapChecker.check(enrichment)

            // Always mirror whatever the scan actually returned — enrichment
            // rows + app-visible notes — even when phonebook fields are saved.
            // Phonebook is a best-effort mirror for structured fields; nothing
            // is allowed to hide because it can't go into ContactsContract.
            syncEnrichmentToPhonebookNotes(contactId, enrichment, caller)

            val shouldUpdatePhoto = photoId == -1L && gaps.missingPhoto && caller.photoUrl != null
            if (shouldUpdatePhoto) {
                val bitmap = downloadBitmap(caller.photoUrl)
                if (bitmap != null) {
                    // JPEG-90, capped at 720px: PNG-100 blobs render
                    // differently per contacts app (huge + slow sync); JPEG
                    // is the standard phonebook photo format everywhere.
                    val scaled = scaleDown(bitmap, 720)
                    val stream = ByteArrayOutputStream()
                    scaled.compress(Bitmap.CompressFormat.JPEG, 90, stream)
                    val photoBytes = stream.toByteArray()
                    val ops = mutableListOf<ContentProviderOperation>()
                    ops.add(
                        ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                            .withValue(ContactsContract.Data.RAW_CONTACT_ID, contactId)
                            .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Photo.CONTENT_ITEM_TYPE)
                            .withValue(ContactsContract.CommonDataKinds.Photo.PHOTO, photoBytes)
                            .build()
                    )
                    context.contentResolver.applyBatch(ContactsContract.AUTHORITY, ArrayList(ops))
                }
            }

            // Email gap-fill into the REAL Email field (not just notes): skip
            // when a matching address already exists on the row.
            val scanEmail = caller.email?.trim()?.takeIf { it.contains("@") }
                ?: enrichment?.email?.trim()?.takeIf { it.contains("@") }
            if (scanEmail != null && !hasEmailRow(contactId, scanEmail)) {
                try {
                    val emailOp = ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                        .withValue(ContactsContract.Data.RAW_CONTACT_ID, contactId)
                        .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE)
                        .withValue(ContactsContract.CommonDataKinds.Email.ADDRESS, scanEmail)
                        .withValue(ContactsContract.CommonDataKinds.Email.TYPE, ContactsContract.CommonDataKinds.Email.TYPE_HOME)
                        .build()
                    context.contentResolver.applyBatch(ContactsContract.AUTHORITY, arrayListOf(emailOp))
                } catch (_: Exception) { }
            }

            if (isSavedRealName && caller.displayName != null) {
                // Saved name kept; caller-ID name is displayed alongside it and
                // also written into the notes line below — never as the row name.
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun syncEnrichmentToPhonebookNotes(
        rawContactId: Long,
        enrichment: ContactEnrichmentEntity?,
        caller: Caller?,
    ) {
        try {
            // Build a compact, human-readable block from enrichment fields that
            // have no ContactsContract slot: about, alt-name, social presence,
            // NID context, business tags, carrier/region deltas, etc. This is
            // stored as a NOTE so the phonebook carries it even when the app
            // cache is cleared. Existing notes are preserved.
            val lines = mutableListOf<String>()
            val publicName = enrichment?.publicName ?: caller?.displayName
            if (!publicName.isNullOrBlank() && !ContactUtils.isPlaceholderName(publicName)) {
                lines.add("Caller ID: $publicName")
            }
            enrichment?.alternateName?.takeIf { it.isNotBlank() }?.let { lines.add("Also known as: $it") }
            enrichment?.about?.takeIf { it.isNotBlank() }?.let { lines.add("About: ${it.take(300)}") }
            PhoneNumberUtils.getLocationInfo(enrichment?.normalizedPhoneNumber ?: "")?.let {
                if (it.isNotBlank()) lines.add("Number region: $it")
            }
            val loc = com.infocaller.app.util.LocationUtils.formatCallerLocation(enrichment?.city, enrichment?.region, enrichment?.country)
            if (loc.isNotBlank()) lines.add("Location: $loc")
            enrichment?.timezone?.let { lines.add("Timezone: $it") }
            enrichment?.carrier?.let { lines.add("Carrier: $it") }
            enrichment?.lineType?.let { lines.add("Line type: $it") }
            enrichment?.email?.let { lines.add("Email: $it") }
            enrichment?.nid?.let { lines.add("NID: $it") }
            enrichment?.dob?.let { lines.add("DOB: $it") }
            val socials = try { SocialUtils.fromJson(enrichment?.socialProfilesJson) } catch (_: Exception) { emptyList() }
            if (socials.isNotEmpty()) {
                val names = socials.mapNotNull { it.platform?.takeIf { s -> s.isNotBlank() } }.distinct().take(8)
                if (names.isNotEmpty()) lines.add("Social: ${names.joinToString(", ")}")
            }
            if (lines.isEmpty()) return

            val noteHeader = "— InfoCaller —"
            val noteBody = lines.joinToString("\n")
            val newNote = "$noteHeader\n$noteBody"

            // Read any existing note so we don't clobber user text.
            val noteUri = ContactsContract.Data.CONTENT_URI
            val sel = "${ContactsContract.Data.RAW_CONTACT_ID}=? AND ${ContactsContract.Data.MIMETYPE}=?"
            val selArgs = arrayOf(rawContactId.toString(), ContactsContract.CommonDataKinds.Note.CONTENT_ITEM_TYPE)
            var existingNote: String? = null
            context.contentResolver.query(noteUri, arrayOf(ContactsContract.Data.DATA1), sel, selArgs, null)?.use { c ->
                if (c.moveToFirst()) existingNote = c.getString(0)
            }

            val merged = when {
                existingNote.isNullOrBlank() -> newNote
                existingNote!!.contains(noteHeader) -> {
                    // Replace our previous block in place.
                    val before = existingNote!!.substringBefore(noteHeader).trimEnd()
                    val after = existingNote!!.substringAfter(noteHeader, "").let { tail ->
                        // tail starts with our old block; strip up to next blank line
                        val cut = tail.indexOf("\n\n")
                        if (cut >= 0) tail.substring(cut).trimStart() else ""
                    }
                    listOfNotNull(before.takeIf { it.isNotBlank() }, newNote, after.takeIf { it.isNotBlank() })
                        .joinToString("\n\n")
                }
                else -> "${existingNote!!.trimEnd()}\n\n$newNote"
            }

            val already = context.contentResolver.query(noteUri, arrayOf(ContactsContract.Data._ID), sel, selArgs, null)?.use { it.moveToFirst() } ?: false
            val op = if (already) {
                ContentProviderOperation.newUpdate(ContactsContract.Data.CONTENT_URI)
                    .withSelection(sel, selArgs)
                    .withValue(ContactsContract.Data.DATA1, merged.take(4000))
                    .build()
            } else {
                ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                    .withValue(ContactsContract.Data.RAW_CONTACT_ID, rawContactId)
                    .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Note.CONTENT_ITEM_TYPE)
                    .withValue(ContactsContract.Data.DATA1, merged.take(4000))
                    .build()
            }
            context.contentResolver.applyBatch(ContactsContract.AUTHORITY, arrayListOf(op))
        } catch (_: Exception) { }
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

    /**
     * Permanent phonebook mirror for one bulk-scan result (BulkIdentityEngine).
     * Fills ONLY gaps — never overwrites the user's own saved name or photo:
     * - missing row photo + scan has one  -> insert Photo row
     * - saved name is a placeholder (raw number / "Unknown") + scan has a
     *   real name -> rename the StructuredName row to the caller-ID name
     * - enrichment extras (about, carrier, socials, NID...) -> InfoCaller
     *   note block (existing user notes preserved)
     * Runs best-effort: without WRITE_CONTACTS it silently no-ops.
     */
    suspend fun mirrorLookupResult(phoneNumber: String, result: LookupResult): Boolean = withContext(Dispatchers.IO) {
        try {
            if (!com.infocaller.app.permissions.PermissionManager.hasPermissions(
                    context, com.infocaller.app.permissions.PermissionManager.WRITE_CONTACTS_PERMISSION
                )
            ) return@withContext false
            val normalized = PhoneNumberUtils.normalize(phoneNumber)
            val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(normalized))
            var rawContactId = -1L
            var existingName: String? = null
            var photoId = -1L
            context.contentResolver.query(
                uri,
                arrayOf(ContactsContract.PhoneLookup._ID, ContactsContract.PhoneLookup.DISPLAY_NAME, ContactsContract.PhoneLookup.PHOTO_ID),
                null, null, null
            )?.use { c ->
                if (c.moveToFirst()) {
                    // PhoneLookup._ID is the aggregate CONTACT id; resolve a
                    // writable RAW_CONTACT id for Data-row writes below.
                    val aggregateId = c.getLong(0)
                    existingName = c.getString(1)
                    photoId = if (c.isNull(2)) -1 else c.getLong(2)
                    rawContactId = resolveWritableRawContactId(aggregateId)
                }
            }
            if (rawContactId == -1L) return@withContext false

            val ops = mutableListOf<ContentProviderOperation>()

            // 1. Gap-fill the display name (placeholder rows only).
            val scanName = result.name?.takeIf { !ContactUtils.isPlaceholderName(it) }
            val rowIsGap = ContactUtils.isPlaceholderName(existingName) ||
                existingName == normalized ||
                existingName?.filter { it.isDigit() } == normalized.filter { it.isDigit() }
            if (scanName != null && rowIsGap) {
                // Update-or-insert: some placeholder rows have no
                // StructuredName row yet (number-only imports).
                val hasNameRow = hasDataRow(rawContactId, ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE)
                if (hasNameRow) {
                    ops.add(
                        ContentProviderOperation.newUpdate(ContactsContract.Data.CONTENT_URI)
                            .withSelection(
                                "${ContactsContract.Data.RAW_CONTACT_ID}=? AND ${ContactsContract.Data.MIMETYPE}=?",
                                arrayOf(rawContactId.toString(), ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE)
                            )
                            .withValue(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, scanName)
                            .build()
                    )
                } else {
                    ops.add(
                        ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                            .withValue(ContactsContract.Data.RAW_CONTACT_ID, rawContactId)
                            .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE)
                            .withValue(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, scanName)
                            .build()
                    )
                }
            }

            // 2. Gap-fill the photo (rows without one only).
            val photoUrl = result.imageUrl?.takeIf { it.startsWith("http") }
                ?: result.photoCandidates.firstOrNull()?.url?.takeIf { it.startsWith("http") }
            if (photoId == -1L && photoUrl != null) {
                val bitmap = try { downloadBitmap(photoUrl) } catch (_: Exception) { null }
                if (bitmap != null) {
                    val stream = ByteArrayOutputStream()
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 90, stream)
                    ops.add(
                        ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                            .withValue(ContactsContract.Data.RAW_CONTACT_ID, rawContactId)
                            .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Photo.CONTENT_ITEM_TYPE)
                            .withValue(ContactsContract.CommonDataKinds.Photo.PHOTO, stream.toByteArray())
                            .build()
                    )
                }
            }

            if (ops.isNotEmpty()) {
                try {
                    context.contentResolver.applyBatch(ContactsContract.AUTHORITY, ArrayList(ops))
                } catch (_: Exception) { }
            }

            // 3. Notes block with everything that has no structured slot.
            // Email ALSO goes in notes (searchable) even though it now has
            // its own row — every other app reads the row, humans read notes.
            val caller = Caller(
                phoneNumber = normalized,
                displayName = result.name,
                alias = result.alternateName,
                photoUrl = result.imageUrl,
                organization = result.carrier,
                country = result.country,
                region = result.region,
                carrier = result.carrier,
                email = result.email,
                reportCount = 0,
                isVerified = false,
                socialMediaLinks = result.socialProfiles.mapNotNull { it.profileUrl }
            )
            syncEnrichmentToPhonebookNotes(rawContactId, null, caller)
            // 4. Same-row email gap-fill for the bulk path.
            val bulkEmail = result.email?.trim()?.takeIf { it.contains("@") }
            if (bulkEmail != null && !hasEmailRow(rawContactId, bulkEmail)) {
                try {
                    val emailOp = ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                        .withValue(ContactsContract.Data.RAW_CONTACT_ID, rawContactId)
                        .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE)
                        .withValue(ContactsContract.CommonDataKinds.Email.ADDRESS, bulkEmail)
                        .withValue(ContactsContract.CommonDataKinds.Email.TYPE, ContactsContract.CommonDataKinds.Email.TYPE_HOME)
                        .build()
                    context.contentResolver.applyBatch(ContactsContract.AUTHORITY, arrayListOf(emailOp))
                } catch (_: Exception) { }
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    /** First writable (non-deleted) raw-contact row for an aggregate contact. */
    private fun resolveWritableRawContactId(aggregateId: Long): Long {
        return try {
            context.contentResolver.query(
                ContactsContract.RawContacts.CONTENT_URI,
                arrayOf(ContactsContract.RawContacts._ID),
                "${ContactsContract.RawContacts.CONTACT_ID}=? AND ${ContactsContract.RawContacts.DELETED}=0",
                arrayOf(aggregateId.toString()),
                null
            )?.use { c -> if (c.moveToFirst()) c.getLong(0) else -1L } ?: -1L
        } catch (_: Exception) { -1L }
    }

    private fun hasDataRow(rawContactId: Long, mimeType: String): Boolean {
        return try {
            context.contentResolver.query(
                ContactsContract.Data.CONTENT_URI,
                arrayOf(ContactsContract.Data._ID),
                "${ContactsContract.Data.RAW_CONTACT_ID}=? AND ${ContactsContract.Data.MIMETYPE}=?",
                arrayOf(rawContactId.toString(), mimeType),
                null
            )?.use { it.moveToFirst() } ?: false
        } catch (_: Exception) { false }
    }

    /** True when this raw-contact already holds the address in its Email row. */
    private fun hasEmailRow(rawContactId: Long, email: String): Boolean {
        return try {
            context.contentResolver.query(
                ContactsContract.Data.CONTENT_URI,
                arrayOf(ContactsContract.Data._ID),
                "${ContactsContract.Data.RAW_CONTACT_ID}=? AND ${ContactsContract.Data.MIMETYPE}=? AND ${ContactsContract.CommonDataKinds.Email.ADDRESS}=?",
                arrayOf(rawContactId.toString(), ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE, email),
                null
            )?.use { it.moveToFirst() } ?: false
        } catch (_: Exception) { false }
    }

    /** Downscales huge avatars to a phonebook-standard size (keeps aspect). */
    private fun scaleDown(bitmap: Bitmap, maxSide: Int): Bitmap {
        return try {
            val w = bitmap.width
            val h = bitmap.height
            val longest = maxOf(w, h)
            if (longest <= maxSide) return bitmap
            val scale = maxSide.toFloat() / longest.toFloat()
            Bitmap.createScaledBitmap(bitmap, (w * scale).toInt(), (h * scale).toInt(), true)
        } catch (_: Exception) { bitmap }
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

