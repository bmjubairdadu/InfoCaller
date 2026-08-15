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
                trySend(fetchRecentCallsSync())
            }
        }

        contentResolver.registerContentObserver(
            CallLog.Calls.CONTENT_URI,
            true,
            observer
        )

        trySend(fetchRecentCallsSync())

        awaitClose {
            contentResolver.unregisterContentObserver(observer)
        }
    }
    .debounce(300L)
    .flowOn(Dispatchers.IO)

    private fun fetchRecentCallsSync(): List<CallLogEntry> {
        val calls = mutableListOf<CallLogEntry>()
        try {
            val projection = arrayOf(
                CallLog.Calls.NUMBER,
                CallLog.Calls.CACHED_NAME,
                CallLog.Calls.TYPE,
                CallLog.Calls.DATE,
                CallLog.Calls.DURATION
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

                while (it.moveToNext()) {
                    calls.add(
                        CallLogEntry(
                            number = it.getString(numberIdx) ?: "",
                            name = it.getString(nameIdx),
                            type = it.getInt(typeIdx),
                            date = it.getLong(dateIdx),
                            duration = it.getLong(durationIdx)
                        )
                    )
                }
            }
        } catch (e: Exception) {
            // Handle error
        }
        return calls
    }

    @OptIn(kotlinx.coroutines.FlowPreview::class)
    override fun getContacts(): Flow<List<Contact>> = callbackFlow {
        val observer = object : android.database.ContentObserver(android.os.Handler(android.os.Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                trySend(fetchContactsSync())
            }
        }

        contentResolver.registerContentObserver(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            true,
            observer
        )

        trySend(fetchContactsSync())

        awaitClose {
            contentResolver.unregisterContentObserver(observer)
        }
    }
    .debounce(500L)
    .flowOn(Dispatchers.IO)

    fun fetchContactsSync(): List<Contact> {
        val contacts = mutableListOf<Contact>()
        try {
            val projection = arrayOf(
                ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER,
                ContactsContract.CommonDataKinds.Phone.PHOTO_URI
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

                val seenNumbers = mutableSetOf<String>()

                while (it.moveToNext()) {
                    val number = it.getString(numberIdx)
                    if (number != null && number !in seenNumbers) {
                        contacts.add(
                            Contact(
                                id = it.getString(idIdx) ?: "",
                                displayName = it.getString(nameIdx)?.ifBlank { null }
                                    ?: "Unknown",
                                phoneNumber = number,
                                photoUri = it.getString(photoIdx)
                            )
                        )
                        seenNumbers.add(number)
                    }
                }
            }
        } catch (e: Exception) {
            // Handle error
        }
        return contacts
    }

    override suspend fun deleteCallLogEntry(number: String, date: Long) {
        val selection = "${CallLog.Calls.NUMBER} = ? AND ${CallLog.Calls.DATE} = ?"
        val selectionArgs = arrayOf(number, date.toString())
        contentResolver.delete(CallLog.Calls.CONTENT_URI, selection, selectionArgs)
    }

    override suspend fun clearCallLog() {
        contentResolver.delete(CallLog.Calls.CONTENT_URI, null, null)
    }
}
