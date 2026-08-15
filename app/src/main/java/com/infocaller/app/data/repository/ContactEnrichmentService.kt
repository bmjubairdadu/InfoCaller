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
import com.infocaller.app.data.remote.CallerScraper
import com.infocaller.app.domain.model.Caller
import com.infocaller.app.domain.model.SpamStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

import com.infocaller.app.domain.repository.CallerRepository

class ContactEnrichmentService(
    private val context: Context,
    private val callerScraper: CallerScraper = CallerScraper(context),
    private val repository: CallerRepository? = null
) {

    suspend fun enrichAndSaveContact(
        phoneNumber: String,
        displayName: String? = null,
        accountName: String? = null,
        accountType: String? = null,
        onProgress: (String) -> Unit = { }
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            onProgress("Looking up caller info...")
            val normalized = com.infocaller.app.util.PhoneNumberUtils.normalize(phoneNumber)
            
            var caller: Caller? = null
            
            // Try to get from local DB first (via repository - but we don't have it here)
            // For now, use scraper directly
            caller = callerScraper.fetchCallerInfo(normalized)
            
            if (caller != null) {
                onProgress("Found info: ${caller.displayName}")
                // Ensure WhatsApp photo URL is used if no photo found
                if (caller.photoUrl.isNullOrBlank()) {
                    caller = caller.copy(photoUrl = com.infocaller.app.util.PhoneNumberUtils.getImageUrl(normalized))
                }
            } else {
                onProgress("No additional info found, saving basic contact")
                caller = Caller(
                    phoneNumber = phoneNumber,
                    displayName = displayName ?: "Unknown",
                    alias = null,
                    photoUrl = com.infocaller.app.util.PhoneNumberUtils.getImageUrl(normalized),
                    organization = null,
                    country = getCountryFromNumber(normalized),
                    region = null,
                    carrier = getCarrierFromNumber(normalized),
                    spamScore = 0,
                    reportCount = 0,
                    isVerified = false,
                    spamStatus = SpamStatus.UNKNOWN,
                    socialMediaLinks = getSocialLinks(normalized)
                )
            }
            
            onProgress("Saving to contacts...")
            saveToContacts(caller, accountName, accountType)
            
            onProgress("Contact saved successfully!")
            true
        } catch (e: Exception) {
            Log.e("ContactEnrichment", "Failed to enrich and save contact", e)
            onProgress("Error: ${e.message}")
            false
        }
    }

    private fun getCountryFromNumber(phoneNumber: String): String? {
        return when {
            phoneNumber.startsWith("+880") || phoneNumber.startsWith("880") -> "Bangladesh"
            phoneNumber.startsWith("+1") -> "USA/Canada"
            phoneNumber.startsWith("+91") -> "India"
            phoneNumber.startsWith("+62") -> "Indonesia"
            phoneNumber.startsWith("+63") -> "Philippines"
            phoneNumber.startsWith("+84") -> "Vietnam"
            phoneNumber.startsWith("+66") -> "Thailand"
            phoneNumber.startsWith("+60") -> "Malaysia"
            phoneNumber.startsWith("+65") -> "Singapore"
            phoneNumber.startsWith("+81") -> "Japan"
            phoneNumber.startsWith("+82") -> "South Korea"
            phoneNumber.startsWith("+86") -> "China"
            phoneNumber.startsWith("+92") -> "Pakistan"
            phoneNumber.startsWith("+94") -> "Sri Lanka"
            phoneNumber.startsWith("+95") -> "Myanmar"
            phoneNumber.startsWith("+254") -> "Kenya"
            phoneNumber.startsWith("+255") -> "Tanzania"
            phoneNumber.startsWith("+256") -> "Uganda"
            phoneNumber.startsWith("+234") -> "Nigeria"
            phoneNumber.startsWith("+27") -> "South Africa"
            phoneNumber.startsWith("+44") -> "United Kingdom"
            phoneNumber.startsWith("+49") -> "Germany"
            phoneNumber.startsWith("+33") -> "France"
            phoneNumber.startsWith("+39") -> "Italy"
            phoneNumber.startsWith("+34") -> "Spain"
            phoneNumber.startsWith("+31") -> "Netherlands"
            phoneNumber.startsWith("+41") -> "Switzerland"
            phoneNumber.startsWith("+46") -> "Sweden"
            phoneNumber.startsWith("+47") -> "Norway"
            phoneNumber.startsWith("+45") -> "Denmark"
            phoneNumber.startsWith("+358") -> "Finland"
            phoneNumber.startsWith("+61") -> "Australia"
            phoneNumber.startsWith("+64") -> "New Zealand"
            phoneNumber.startsWith("+55") -> "Brazil"
            phoneNumber.startsWith("+52") -> "Mexico"
            phoneNumber.startsWith("+54") -> "Argentina"
            phoneNumber.startsWith("+56") -> "Chile"
            phoneNumber.startsWith("+57") -> "Colombia"
            phoneNumber.startsWith("+58") -> "Venezuela"
            else -> "Unknown"
        }
    }

    private fun getCarrierFromNumber(phoneNumber: String): String? {
        // Basic carrier detection from number prefixes (varies by country)
        // This is a simplified version - in production, use a proper carrier lookup API
        return when {
            phoneNumber.startsWith("+88013") || phoneNumber.startsWith("88013") -> "Grameenphone"
            phoneNumber.startsWith("+88017") || phoneNumber.startsWith("88017") -> "Grameenphone"
            phoneNumber.startsWith("+88018") || phoneNumber.startsWith("88018") -> "Robi"
            phoneNumber.startsWith("+88019") || phoneNumber.startsWith("88019") -> "Banglalink"
            phoneNumber.startsWith("+88015") || phoneNumber.startsWith("88015") -> "Teletalk"
            phoneNumber.startsWith("+88016") || phoneNumber.startsWith("88016") -> "Airtel"
            phoneNumber.startsWith("+919") || phoneNumber.startsWith("919") -> "Jio/Airtel/Vi"
            phoneNumber.startsWith("+6281") || phoneNumber.startsWith("6281") -> "Telkomsel"
            phoneNumber.startsWith("+6282") || phoneNumber.startsWith("6282") -> "Indosat"
            phoneNumber.startsWith("+6285") || phoneNumber.startsWith("6285") -> "XL Axiata"
            phoneNumber.startsWith("+6289") || phoneNumber.startsWith("6289") -> "Tri"
            phoneNumber.startsWith("+639") || phoneNumber.startsWith("639") -> "Globe/Smart"
            phoneNumber.startsWith("+849") || phoneNumber.startsWith("849") -> "Viettel/Vinaphone/Mobifone"
            phoneNumber.startsWith("+668") || phoneNumber.startsWith("668") -> "AIS/TrueMove/DTAC"
            phoneNumber.startsWith("+601") || phoneNumber.startsWith("601") -> "Maxis/Digi/Celcom"
            phoneNumber.startsWith("+658") || phoneNumber.startsWith("658") -> "Singtel/StarHub/M1"
            else -> "Unknown"
        }
    }

    private fun getSocialLinks(phoneNumber: String): List<String> {
        val cleanNumber = phoneNumber.removePrefix("+")
        return listOf(
            "https://wa.me/$cleanNumber",
            "https://t.me/$cleanNumber",
            "https://api.whatsapp.com/send?phone=$cleanNumber"
        )
    }

    private suspend fun downloadImage(url: String): ByteArray? = withContext(Dispatchers.IO) {
        try {
            val connection = java.net.URL(url).openConnection() as java.net.HttpURLConnection
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Android 11; Mobile; rv:86.0) Gecko/86.0 Firefox/86.0")
            connection.doInput = true
            connection.connect()
            val input = connection.inputStream
            input.use { it.readBytes() }
        } catch (e: Exception) {
            null
        }
    }

    private fun saveToContacts(caller: Caller, accountName: String? = null, accountType: String? = null) {
        runBlocking {
            doSaveToContacts(caller, accountName, accountType)
        }
    }

    private suspend fun doSaveToContacts(caller: Caller, accountName: String? = null, accountType: String? = null) {
        val resolver = context.contentResolver
        val ops = mutableListOf<ContentProviderOperation>()
        
        // Create raw contact
        val rawContactInsert = ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI)
            .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, accountType)
            .withValue(ContactsContract.RawContacts.ACCOUNT_NAME, accountName)
            .build()
        ops.add(rawContactInsert)
        
        // Add name
        caller.displayName?.let { name ->
            val blockedKeywords = listOf("Network", "Identity", "Unknown", "Identified", "Discovery", "InfoCaller")
            val isPlaceholder = blockedKeywords.any { name.contains(it, ignoreCase = true) }
            
            if (name.isNotBlank() && !isPlaceholder) {
                val nameInsert = ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                    .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                    .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE)
                    .withValue(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, name)
                    .build()
                ops.add(nameInsert)
            }
        }
        
        // Add phone number
        val phoneInsert = ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
            .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
            .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
            .withValue(ContactsContract.CommonDataKinds.Phone.NUMBER, caller.phoneNumber)
            .withValue(ContactsContract.CommonDataKinds.Phone.TYPE, ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE)
            .build()
        ops.add(phoneInsert)

        // Add Photo from WhatsApp/Cloud
        caller.photoUrl?.let { url ->
            try {
                val bytes = downloadImage(url)
                if (bytes != null) {
                    val photoInsert = ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                        .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                        .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Photo.CONTENT_ITEM_TYPE)
                        .withValue(ContactsContract.CommonDataKinds.Photo.PHOTO, bytes)
                        .build()
                    ops.add(photoInsert)
                }
            } catch (e: Exception) {
                Log.e("ContactEnrichment", "Failed to download/save contact photo", e)
            }
        }
        
        // Add organization
        caller.organization?.let { org ->
            val orgInsert = ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Organization.CONTENT_ITEM_TYPE)
                .withValue(ContactsContract.CommonDataKinds.Organization.COMPANY, org)
                .build()
            ops.add(orgInsert)
        }
        
        // Add email (if available)
        // Note: We don't have email from caller ID typically, but could add if available
        
        // Add address (country/region)
        val addressParts = mutableListOf<String>()
        caller.country?.let { addressParts.add(it) }
        caller.region?.let { addressParts.add(it) }
        if (addressParts.isNotEmpty()) {
            val addressInsert = ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.StructuredPostal.CONTENT_ITEM_TYPE)
                .withValue(ContactsContract.CommonDataKinds.StructuredPostal.COUNTRY, caller.country)
                .withValue(ContactsContract.CommonDataKinds.StructuredPostal.REGION, caller.region)
                .withValue(ContactsContract.CommonDataKinds.StructuredPostal.FORMATTED_ADDRESS, addressParts.joinToString(", "))
                .build()
            ops.add(addressInsert)
        }
        
        // Add website/social links
        caller.socialMediaLinks.forEachIndexed { index, link ->
            val websiteInsert = ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Website.CONTENT_ITEM_TYPE)
                .withValue(ContactsContract.CommonDataKinds.Website.URL, link)
                .withValue(ContactsContract.CommonDataKinds.Website.TYPE, ContactsContract.CommonDataKinds.Website.TYPE_HOMEPAGE + index)
                .build()
            ops.add(websiteInsert)
        }
        
        // Add note with additional info
        val noteParts = mutableListOf<String>()
        caller.alias?.let { noteParts.add("Alias: $it") }
        caller.carrier?.let { noteParts.add("Carrier: $it") }
        if (caller.spamStatus != SpamStatus.UNKNOWN) {
            noteParts.add("Spam Status: ${caller.spamStatus}")
        }
        if (noteParts.isNotEmpty()) {
            val noteInsert = ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Note.CONTENT_ITEM_TYPE)
                .withValue(ContactsContract.CommonDataKinds.Note.NOTE, noteParts.joinToString("\n"))
                .build()
            ops.add(noteInsert)
        }
        
        // Apply batch
        try {
            resolver.applyBatch(ContactsContract.AUTHORITY, ArrayList(ops))
        } catch (e: Exception) {
            Log.e("ContactEnrichment", "Failed to save contact", e)
            throw e
        }
    }

    suspend fun syncAllWhatsAppPhotos(onProgress: (Int, Int) -> Unit): Int = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val cursor = resolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.CONTACT_ID, ContactsContract.CommonDataKinds.Phone.NUMBER, ContactsContract.CommonDataKinds.Phone.PHOTO_URI),
            null,
            null,
            null
        )

        var updatedCount = 0
        cursor?.use {
            val total = it.count
            var current = 0
            val contactIdIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.CONTACT_ID)
            val numberIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
            val photoUriIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.PHOTO_URI)

            while (it.moveToNext()) {
                current++
                val contactId = it.getLong(contactIdIndex)
                val number = it.getString(numberIndex)
                val existingPhotoUri = it.getString(photoUriIndex)

                // Skip if contact already has a custom photo (optional - user might want to overwrite)
                // For this request, we'll try to fetch if photo is missing or if we want to force sync.
                // Let's only sync if photo is missing to be safe and efficient.
                if (existingPhotoUri != null) {
                    onProgress(current, total)
                    continue
                }

                val normalized = com.infocaller.app.util.PhoneNumberUtils.normalize(number)
                val cleanNumber = normalized.removePrefix("+")
                
                // WhatsApp Profile Pic API URL
                val photoUrl = "https://whatsapp-db.checkleaked.com/$cleanNumber.jpg"
                
                try {
                    val photoBytes = downloadImage(photoUrl)
                    if (photoBytes != null && photoBytes.size > 500) { // Basic check to avoid empty/tiny placeholder images
                        if (updateContactPhoto(contactId, photoBytes)) {
                            updatedCount++
                        }
                    }
                } catch (e: Exception) {
                    Log.e("ContactEnrichment", "Error syncing photo for $number", e)
                }
                
                onProgress(current, total)
            }
        }
        updatedCount
    }

    private fun updateContactPhoto(contactId: Long, photoBytes: ByteArray): Boolean {
        val ops = ArrayList<ContentProviderOperation>()

        // Try to update existing photo first
        ops.add(ContentProviderOperation.newUpdate(ContactsContract.Data.CONTENT_URI)
            .withSelection(
                "${ContactsContract.Data.CONTACT_ID}=? AND ${ContactsContract.Data.MIMETYPE}=?",
                arrayOf(contactId.toString(), ContactsContract.CommonDataKinds.Photo.CONTENT_ITEM_TYPE)
            )
            .withValue(ContactsContract.CommonDataKinds.Photo.PHOTO, photoBytes)
            .build())

        try {
            val results = context.contentResolver.applyBatch(ContactsContract.AUTHORITY, ops)
            if (results[0].count == 0) {
                // No existing photo row, need to find raw contact ID to insert
                val rawContactId = getRawContactId(contactId)
                if (rawContactId != -1L) {
                    val insertOps = ArrayList<ContentProviderOperation>()
                    insertOps.add(ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                        .withValue(ContactsContract.Data.RAW_CONTACT_ID, rawContactId)
                        .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Photo.CONTENT_ITEM_TYPE)
                        .withValue(ContactsContract.CommonDataKinds.Photo.PHOTO, photoBytes)
                        .build())
                    context.contentResolver.applyBatch(ContactsContract.AUTHORITY, insertOps)
                }
            }
            return true
        } catch (e: Exception) {
            Log.e("ContactEnrichment", "Failed to update contact photo", e)
            return false
        }
    }

    private fun getRawContactId(contactId: Long): Long {
        val projection = arrayOf(ContactsContract.RawContacts._ID)
        val selection = "${ContactsContract.RawContacts.CONTACT_ID}=?"
        val selectionArgs = arrayOf(contactId.toString())
        val cursor = context.contentResolver.query(
            ContactsContract.RawContacts.CONTENT_URI,
            projection,
            selection,
            selectionArgs,
            null
        )
        cursor?.use {
            if (it.moveToFirst()) {
                return it.getLong(it.getColumnIndexOrThrow(ContactsContract.RawContacts._ID))
            }
        }
        return -1L
    }

    suspend fun enrichAllContactsInBg(): Unit = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val cursor = resolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER, ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME),
            null,
            null,
            null
        )

        cursor?.use {
            val numberIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
            val nameIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)

            while (it.moveToNext()) {
                val number = it.getString(numberIndex)
                val name = it.getString(nameIndex)
                
                if (number != null) {
                    try {
                        // Slowly enrich each contact using Advanced OSINT Engine
                        val callerInfo = callerScraper.performDeepOSINT(number)
                        
                        if (callerInfo != null) {
                            Log.d("ContactEnrichment", "Deep OSINT found: ${callerInfo.displayName}")
                            // Save to local database via repository
                            repository?.saveCaller(callerInfo)
                        }

                        enrichAndSaveContact(
                            phoneNumber = number,
                            displayName = name
                        )
                        // Wait to avoid rate limiting across OSINT sites
                        kotlinx.coroutines.delay(2000)
                    } catch (e: Exception) {
                        Log.e("ContactEnrichment", "Error enriching $number in background", e)
                    }
                }
            }
        }
    }

    suspend fun checkIfContactExists(phoneNumber: String): Boolean = withContext(Dispatchers.IO) {
        val normalized = com.infocaller.app.util.PhoneNumberUtils.normalize(phoneNumber)
        val uri = Uri.withAppendedPath(
            ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
            Uri.encode(normalized)
        )
        val projection = arrayOf(ContactsContract.PhoneLookup._ID)
        val cursor = context.contentResolver.query(uri, projection, null, null, null)
        cursor?.use {
            return@withContext it.count > 0
        }
        false
    }

    suspend fun updateExistingContact(phoneNumber: String, caller: Caller): Boolean = withContext(Dispatchers.IO) {
        val normalized = com.infocaller.app.util.PhoneNumberUtils.normalize(phoneNumber)
        val uri = Uri.withAppendedPath(
            ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
            Uri.encode(normalized)
        )
        val projection = arrayOf(ContactsContract.PhoneLookup._ID)
        val cursor = context.contentResolver.query(uri, projection, null, null, null)
        val contactId = cursor?.use {
            if (it.moveToFirst()) {
                it.getLong(it.getColumnIndexOrThrow(ContactsContract.PhoneLookup._ID))
            } else -1
        } ?: -1
        
        if (contactId == -1L) return@withContext false

        // STAGE 5: Automatically update Photo if found (WhatsApp/OSINT)
        caller.photoUrl?.let { url ->
            try {
                val bytes = downloadImage(url)
                if (bytes != null && bytes.size > 500) {
                    updateContactPhoto(contactId, bytes)
                }
            } catch (e: Exception) {
                Log.e("ContactEnrichment", "Failed to update contact photo in background", e)
            }
        }
        
        val ops = mutableListOf<ContentProviderOperation>()
        
        caller.displayName?.let { name ->
            // STAGE 4: Robust Integrity Check - Never overwrite a real name with a placeholder
            val blockedKeywords = listOf(
                "Network", "Identity", "Unknown", "Identified", "Discovery", 
                "Search", "Result", "InfoCaller", "Caller", "via"
            )
            
            val isPlaceholder = blockedKeywords.any { name.contains(it, ignoreCase = true) } || 
                               name.filter { it.isDigit() }.length >= 7 // Name is just a phone number

            if (name.isNotBlank() && !isPlaceholder) {
                val nameUpdate = ContentProviderOperation.newUpdate(ContactsContract.Data.CONTENT_URI)
                    .withSelection(
                        "${ContactsContract.Data.CONTACT_ID}=? AND ${ContactsContract.Data.MIMETYPE}=?",
                        arrayOf(contactId.toString(), ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE)
                    )
                    .withValue(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, name)
                    .build()
                ops.add(nameUpdate)
            }
        }
        
        caller.organization?.let { org ->
            val orgUpdate = ContentProviderOperation.newUpdate(ContactsContract.Data.CONTENT_URI)
                .withSelection(
                    "${ContactsContract.Data.CONTACT_ID}=? AND ${ContactsContract.Data.MIMETYPE}=?",
                    arrayOf(contactId.toString(), ContactsContract.CommonDataKinds.Organization.CONTENT_ITEM_TYPE)
                )
                .withValue(ContactsContract.CommonDataKinds.Organization.COMPANY, org)
                .build()
            ops.add(orgUpdate)
        }
        
        try {
            context.contentResolver.applyBatch(ContactsContract.AUTHORITY, ArrayList(ops))
            true
        } catch (e: Exception) {
            Log.e("ContactEnrichment", "Failed to update contact", e)
            false
        }
    }

    suspend fun deleteContact(phoneNumber: String): Boolean = withContext(Dispatchers.IO) {
        val normalized = com.infocaller.app.util.PhoneNumberUtils.normalize(phoneNumber)
        val uri = Uri.withAppendedPath(
            ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
            Uri.encode(normalized)
        )
        val projection = arrayOf(ContactsContract.PhoneLookup._ID)
        val cursor = context.contentResolver.query(uri, projection, null, null, null)
        val contactId = cursor?.use {
            if (it.moveToFirst()) {
                it.getLong(it.getColumnIndexOrThrow(ContactsContract.PhoneLookup._ID))
            } else -1
        } ?: -1
        
        if (contactId == -1L) return@withContext false
        
        val ops = ArrayList<ContentProviderOperation>()
        ops.add(ContentProviderOperation.newDelete(ContactsContract.RawContacts.CONTENT_URI)
            .withSelection("${ContactsContract.RawContacts.CONTACT_ID}=?", arrayOf(contactId.toString()))
            .build())
            
        try {
            context.contentResolver.applyBatch(ContactsContract.AUTHORITY, ops)
            true
        } catch (e: Exception) {
            Log.e("ContactEnrichment", "Failed to delete contact", e)
            false
        }
    }

    /**
     * EMERGENCY RECOVERY: Removes placeholder names from the system phonebook.
     * This helps restore visibility to real names (or at least removes the fake ones).
     */
    suspend fun emergencyCleanup(onProgress: (String) -> Unit): Int = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val cursor = resolver.query(
            ContactsContract.Data.CONTENT_URI,
            arrayOf(ContactsContract.Data.CONTACT_ID, ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, ContactsContract.Data._ID),
            "${ContactsContract.Data.MIMETYPE} = ?",
            arrayOf(ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE),
            null
        )

        var cleanedCount = 0
        val blockedKeywords = listOf("Network", "Identity", "Unknown", "Identified", "Discovery", "InfoCaller", "Search Result")
        
        cursor?.use {
            val nameIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME)
            val dataIdIdx = it.getColumnIndex(ContactsContract.Data._ID)
            
            while (it.moveToNext()) {
                val name = it.getString(nameIdx) ?: ""
                val dataId = it.getLong(dataIdIdx)
                
                val isPlaceholder = blockedKeywords.any { kw -> name.contains(kw, ignoreCase = true) } || 
                                   name.filter { it.isDigit() }.length >= 7
                
                if (isPlaceholder) {
                    onProgress("Cleaning: $name")
                    resolver.delete(
                        ContactsContract.Data.CONTENT_URI,
                        "${ContactsContract.Data._ID} = ?",
                        arrayOf(dataId.toString())
                    )
                    cleanedCount++
                }
            }
        }
        cleanedCount
    }
}
