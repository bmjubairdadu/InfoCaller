package com.infocaller.app.data.repository

import android.content.ContentResolver
import android.provider.CallLog
import android.provider.ContactsContract
import com.infocaller.app.domain.model.CallLogEntry
import com.infocaller.app.domain.model.Contact
import com.infocaller.app.domain.repository.DeviceDataRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flowOn

class DeviceDataRepositoryImpl(
    private val contentResolver: ContentResolver
) : DeviceDataRepository {
    @OptIn(kotlinx.coroutines.FlowPreview::class)
    override fun getRecentCalls(): Flow<List<CallLogEntry>> = callbackFlow {
        val observer = object : android.database.ContentObserver(android.os.Handler(android.os.Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                try { trySend(fetchRecentCallsSync()) } catch (_: Exception) { }
            }
        }

        val registered = try {
            contentResolver.registerContentObserver(
                CallLog.Calls.CONTENT_URI,
                true,
                observer
            )
            true
        } catch (_: Exception) {
            false
        }
        if (!registered) {
            trySend(emptyList())
            close()
            return@callbackFlow
        }

        trySend(fetchRecentCallsSync())

        awaitClose {
            try { contentResolver.unregisterContentObserver(observer) } catch (_: Exception) { }
        }
    }
    .debounce(300L)
    .flowOn(Dispatchers.IO)

    override fun fetchRecentCallsSync(): List<CallLogEntry> {
        val calls = mutableListOf<CallLogEntry>()
        try {
            val projection = arrayOf(
                CallLog.Calls.NUMBER,
                CallLog.Calls.CACHED_NAME,
                CallLog.Calls.TYPE,
                CallLog.Calls.DATE,
                CallLog.Calls.DURATION,
                CallLog.Calls.PHONE_ACCOUNT_ID
            )

            val cursor = contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                projection,
                null,
                null,
                CallLog.Calls.DATE + " DESC"
            )

            cursor?.use {
                val numberIdx = it.getColumnIndex(CallLog.Calls.NUMBER)
                val nameIdx = it.getColumnIndex(CallLog.Calls.CACHED_NAME)
                val typeIdx = it.getColumnIndex(CallLog.Calls.TYPE)
                val dateIdx = it.getColumnIndex(CallLog.Calls.DATE)
                val durationIdx = it.getColumnIndex(CallLog.Calls.DURATION)
                val subIdx = it.getColumnIndex(CallLog.Calls.PHONE_ACCOUNT_ID)

                while (it.moveToNext()) {
                    calls.add(
                        CallLogEntry(
                            number = it.getString(numberIdx) ?: "",
                            name = it.getString(nameIdx),
                            type = it.getInt(typeIdx),
                            date = it.getLong(dateIdx),
                            duration = it.getLong(durationIdx),
                            subscriptionId = it.getString(subIdx)
                        )
                    )
                }
            }
        } catch (e: Exception) {
        }
        return calls
    }

    @OptIn(kotlinx.coroutines.FlowPreview::class)
    override fun getContacts(): Flow<List<Contact>> = callbackFlow {
        val observer = object : android.database.ContentObserver(android.os.Handler(android.os.Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                try { trySend(fetchContactsSync()) } catch (_: Exception) { }
            }
        }

        val registered = try {
            contentResolver.registerContentObserver(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                true,
                observer
            )
            true
        } catch (_: Exception) {
            false
        }
        if (!registered) {
            trySend(emptyList())
            close()
            return@callbackFlow
        }

        trySend(fetchContactsSync())

        awaitClose {
            try { contentResolver.unregisterContentObserver(observer) } catch (_: Exception) { }
        }
    }
    .debounce(500L)
    .flowOn(Dispatchers.IO)

    override fun fetchContactsSync(): List<Contact> {
        val contacts = mutableListOf<Contact>()
        try {
            val projection = arrayOf(
                ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER,
                ContactsContract.CommonDataKinds.Phone.PHOTO_URI,
                ContactsContract.CommonDataKinds.Phone.PHOTO_THUMBNAIL_URI,
                ContactsContract.CommonDataKinds.Phone.STARRED,
                ContactsContract.CommonDataKinds.Phone.TIMES_CONTACTED,
                ContactsContract.CommonDataKinds.Phone.LAST_TIME_CONTACTED
            )

            val cursor = contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                projection,
                null,
                null,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC"
            )

            cursor?.use {
                val idIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.CONTACT_ID)
                val nameIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val numberIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                val photoIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.PHOTO_URI)
                val thumbIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.PHOTO_THUMBNAIL_URI)
                val starredIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.STARRED)
                val timesIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.TIMES_CONTACTED)
                val lastIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.LAST_TIME_CONTACTED)

                val seenNumbers = mutableSetOf<String>()

                while (it.moveToNext()) {
                    val number = it.getString(numberIdx)
                    if (number != null && number !in seenNumbers) {
                        val contactId = it.getString(idIdx) ?: ""
                        contacts.add(
                            Contact(
                                id = contactId,
                                displayName = it.getString(nameIdx)?.ifBlank { null }
                                    ?: "Unknown",
                                phoneNumber = number,
                                photoUri = it.getString(photoIdx),
                                photoThumbnailUri = thumbIdx.takeIf { i -> i >= 0 }?.let { i -> it.getString(i) },
                                isFavorite = starredIdx.takeIf { i -> i >= 0 }?.let { i -> it.getInt(i) == 1 } ?: false,
                                timesContacted = timesIdx.takeIf { i -> i >= 0 }?.let { i -> it.getInt(i) } ?: 0,
                                lastContacted = lastIdx.takeIf { i -> i >= 0 }?.let { i -> it.getLong(i) } ?: 0L,
                                alternateNumbers = alternateNumbersFor(contactId, number),
                                email = firstEmailFor(contactId),
                                emails = emailsFor(contactId),
                                organization = organizationFor(contactId)?.first,
                                jobTitle = organizationFor(contactId)?.second,
                                address = addressFor(contactId),
                                website = websiteFor(contactId),
                                birthday = birthdayFor(contactId),
                                nickname = nicknameFor(contactId),
                                bio = noteFor(contactId),
                                notes = noteFor(contactId)
                            )
                        )
                        seenNumbers.add(number)
                    }
                }
            }
        } catch (e: Exception) {
        }
        return contacts
    }

    private fun emailsFor(contactId: String): List<String> {
        return try {
            contentResolver.query(
                ContactsContract.CommonDataKinds.Email.CONTENT_URI,
                arrayOf(ContactsContract.CommonDataKinds.Email.ADDRESS),
                "${ContactsContract.CommonDataKinds.Email.CONTACT_ID} = ?",
                arrayOf(contactId),
                null
            )?.use { c ->
                val idx = c.getColumnIndex(ContactsContract.CommonDataKinds.Email.ADDRESS)
                val out = mutableListOf<String>()
                while (c.moveToNext()) {
                    c.getString(idx)?.trim()?.takeIf { s -> s.isNotBlank() }?.let { out.add(it) }
                }
                out.distinct()
            } ?: emptyList()
        } catch (_: Exception) { emptyList() }
    }

    private fun firstEmailFor(contactId: String): String? = emailsFor(contactId).firstOrNull()

    private fun alternateNumbersFor(contactId: String, primary: String?): List<String> {
        return try {
            contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
                "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID} = ?",
                arrayOf(contactId),
                null
            )?.use { c ->
                val idx = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                val out = mutableListOf<String>()
                while (c.moveToNext()) {
                    val n = c.getString(idx)?.trim()
                    if (!n.isNullOrBlank() && n != primary) out.add(n)
                }
                out.distinct()
            } ?: emptyList()
        } catch (_: Exception) { emptyList() }
    }

    private fun organizationFor(contactId: String): Pair<String?, String?>? {
        return try {
            contentResolver.query(
                ContactsContract.Data.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Organization.COMPANY,
                    ContactsContract.CommonDataKinds.Organization.TITLE
                ),
                "${ContactsContract.Data.CONTACT_ID} = ? AND ${ContactsContract.Data.MIMETYPE} = ?",
                arrayOf(contactId, ContactsContract.CommonDataKinds.Organization.CONTENT_ITEM_TYPE),
                null
            )?.use { c ->
                if (c.moveToFirst()) {
                    val org = c.getString(0)?.trim()?.takeIf { it.isNotBlank() }
                    val title = c.getString(1)?.trim()?.takeIf { it.isNotBlank() }
                    if (org == null && title == null) null else org to title
                } else null
            }
        } catch (_: Exception) { null }
    }

    private fun addressFor(contactId: String): String? {
        return try {
            contentResolver.query(
                ContactsContract.CommonDataKinds.StructuredPostal.CONTENT_URI,
                arrayOf(ContactsContract.CommonDataKinds.StructuredPostal.FORMATTED_ADDRESS),
                "${ContactsContract.CommonDataKinds.StructuredPostal.CONTACT_ID} = ?",
                arrayOf(contactId),
                null
            )?.use { c ->
                if (c.moveToFirst()) c.getString(0)?.trim()?.takeIf { it.isNotBlank() } else null
            }
        } catch (_: Exception) { null }
    }

    private fun websiteFor(contactId: String): String? {
        return try {
            contentResolver.query(
                ContactsContract.Data.CONTENT_URI,
                arrayOf(ContactsContract.CommonDataKinds.Website.URL),
                "${ContactsContract.CommonDataKinds.Website.CONTACT_ID} = ? AND ${ContactsContract.CommonDataKinds.Website.MIMETYPE} = ?",
                arrayOf(contactId, ContactsContract.CommonDataKinds.Website.CONTENT_ITEM_TYPE),
                null
            )?.use { c ->
                if (c.moveToFirst()) c.getString(0)?.trim()?.takeIf { it.isNotBlank() } else null
            }
        } catch (_: Exception) { null }
    }

    private fun birthdayFor(contactId: String): String? {
        return try {
            contentResolver.query(
                ContactsContract.Data.CONTENT_URI,
                arrayOf(ContactsContract.CommonDataKinds.Event.START_DATE),
                "${ContactsContract.Data.CONTACT_ID} = ? AND ${ContactsContract.Data.MIMETYPE} = ? AND ${ContactsContract.CommonDataKinds.Event.TYPE} = ?",
                arrayOf(
                    contactId,
                    ContactsContract.CommonDataKinds.Event.CONTENT_ITEM_TYPE,
                    ContactsContract.CommonDataKinds.Event.TYPE_BIRTHDAY.toString()
                ),
                null
            )?.use { c ->
                if (c.moveToFirst()) c.getString(0)?.trim()?.takeIf { it.isNotBlank() } else null
            }
        } catch (_: Exception) { null }
    }

    private fun nicknameFor(contactId: String): String? {
        return try {
            contentResolver.query(
                ContactsContract.Data.CONTENT_URI,
                arrayOf(ContactsContract.CommonDataKinds.Nickname.NAME),
                "${ContactsContract.Data.CONTACT_ID} = ? AND ${ContactsContract.Data.MIMETYPE} = ?",
                arrayOf(contactId, ContactsContract.CommonDataKinds.Nickname.CONTENT_ITEM_TYPE),
                null
            )?.use { c ->
                if (c.moveToFirst()) c.getString(0)?.trim()?.takeIf { it.isNotBlank() } else null
            }
        } catch (_: Exception) { null }
    }

    private fun noteFor(contactId: String): String? {
        return try {
            contentResolver.query(
                ContactsContract.Data.CONTENT_URI,
                arrayOf(ContactsContract.CommonDataKinds.Note.NOTE),
                "${ContactsContract.Data.CONTACT_ID} = ? AND ${ContactsContract.Data.MIMETYPE} = ?",
                arrayOf(contactId, ContactsContract.CommonDataKinds.Note.CONTENT_ITEM_TYPE),
                null
            )?.use { c ->
                if (c.moveToFirst()) c.getString(0)?.trim()?.takeIf { it.isNotBlank() } else null
            }
        } catch (_: Exception) { null }
    }
}
