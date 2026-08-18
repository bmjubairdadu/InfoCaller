package com.infocaller.app.worker

import android.content.Context
import android.provider.ContactsContract
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.infocaller.app.InfoCallerApplication
import com.infocaller.app.data.local.entity.LocalContactEntity
import com.infocaller.app.data.local.entity.QueuePriority
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

class EnrichmentWorker(
    context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val app = applicationContext as InfoCallerApplication
        val enrichmentDao = app.database.enrichmentDao()
        val localContactDao = app.database.localContactDao()
        val deviceRepo = app.deviceDataRepository
        
        try {
            // 1. Refresh local contacts from system (Import)
            importSystemContacts(localContactDao)

            // 2. Get all contacts for enrichment
            val contacts = (deviceRepo as com.infocaller.app.data.repository.DeviceDataRepositoryImpl).fetchContactsSync()
            
            // 3. Extract and Normalize numbers
            val numbersToProcess = contacts.mapNotNull { it.phoneNumber }
                .map { com.infocaller.app.util.PhoneNumberUtils.normalize(it) }
                .distinct()
            
            // 4. Filter and Enqueue
            val currentTime = System.currentTimeMillis()
            numbersToProcess.forEach { number ->
                val cached = enrichmentDao.getEnrichmentSync(number)
                if (cached == null || cached.expiresAt < currentTime) {
                    app.enrichmentEngine.enqueue(number, priority = QueuePriority.LOW)
                }
            }
            
            Result.success()
        } catch (e: Exception) {
            Log.e("EnrichmentWorker", "Work failed", e)
            Result.retry()
        }
    }

    private fun importSystemContacts(dao: com.infocaller.app.data.local.dao.LocalContactDao) {
        val resolver = applicationContext.contentResolver
        val cursor = resolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
                ContactsContract.CommonDataKinds.Phone.LOOKUP_KEY,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER,
                ContactsContract.CommonDataKinds.Phone.PHOTO_URI,
                ContactsContract.CommonDataKinds.Phone.PHOTO_THUMBNAIL_URI
            ),
            null,
            null,
            null
        )

        cursor?.use {
            val contacts = mutableListOf<LocalContactEntity>()
            val idIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.CONTACT_ID)
            val keyIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.LOOKUP_KEY)
            val nameIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            val numIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
            val photoIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.PHOTO_URI)
            val thumbIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.PHOTO_THUMBNAIL_URI)

            while (it.moveToNext()) {
                val name = it.getString(nameIdx) ?: "Unknown"
                contacts.add(LocalContactEntity(
                    id = it.getLong(idIdx),
                    lookupKey = it.getString(keyIdx),
                    displayName = name,
                    phoneNumber = it.getString(numIdx),
                    photoUri = it.getString(photoIdx),
                    photoThumbnailUri = it.getString(thumbIdx)
                ))
            }
            
            runBlocking {
                dao.insertContacts(contacts)
            }
        }
    }
}
