package com.infocaller.app.util

import android.content.ContentProviderOperation
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.provider.ContactsContract
import android.util.Log

object ContactPhotoUpdater {
    /**
     * Writes image bytes directly into the Android System Phonebook.
     */
    fun updateSystemContactPhoto(context: Context, contactId: Long, imageBytes: ByteArray): Boolean {
        val contentResolver = context.contentResolver
        val values = ContentValues()

        // Try to find an existing photo row for this contact
        val cursor = contentResolver.query(
            ContactsContract.Data.CONTENT_URI,
            arrayOf(ContactsContract.Data._ID),
            "${ContactsContract.Data.CONTACT_ID} = ? AND ${ContactsContract.Data.MIMETYPE} = ?",
            arrayOf(contactId.toString(), ContactsContract.CommonDataKinds.Photo.CONTENT_ITEM_TYPE),
            null
        )

        return cursor?.use {
            if (it.moveToFirst()) {
                // Update existing
                val dataId = it.getLong(0)
                values.put(ContactsContract.CommonDataKinds.Photo.PHOTO, imageBytes)
                contentResolver.update(
                    ContactsContract.Data.CONTENT_URI,
                    values,
                    "${ContactsContract.Data._ID} = ?",
                    arrayOf(dataId.toString())
                ) > 0
            } else {
                // Insert new photo row (Requires finding the RAW_CONTACT_ID)
                val rawContactId = getRawContactId(contentResolver, contactId)
                if (rawContactId != -1L) {
                    values.put(ContactsContract.Data.RAW_CONTACT_ID, rawContactId)
                    values.put(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Photo.CONTENT_ITEM_TYPE)
                    values.put(ContactsContract.CommonDataKinds.Photo.PHOTO, imageBytes)
                    contentResolver.insert(ContactsContract.Data.CONTENT_URI, values) != null
                } else false
            }
        } ?: false
    }

    private fun getRawContactId(resolver: ContentResolver, contactId: Long): Long {
        val cursor = resolver.query(
            ContactsContract.RawContacts.CONTENT_URI,
            arrayOf(ContactsContract.RawContacts._ID),
            "${ContactsContract.RawContacts.CONTACT_ID} = ?",
            arrayOf(contactId.toString()),
            null
        )
        return cursor?.use { if (it.moveToFirst()) it.getLong(0) else -1L } ?: -1L
    }
}
