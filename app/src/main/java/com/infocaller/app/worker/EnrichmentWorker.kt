package com.infocaller.app.worker

import android.content.Context
import android.provider.ContactsContract
import android.util.Log
import androidx.core.content.edit
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
        
        if (!com.infocaller.app.permissions.PermissionManager.hasPermissions(applicationContext, com.infocaller.app.permissions.PermissionManager.CONTACTS_PERMISSIONS)) {
            Log.w("EnrichmentWorker", "Missing contacts permissions, skipping sync.")
            return@withContext Result.failure()
        }
        
        try {
            val prefs = applicationContext.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
            val lastFullScan = prefs.getLong("last_full_contact_scan", 0L)
            val isFullScanNeeded = System.currentTimeMillis() - lastFullScan > 12 * 3600000 // Every 12 hours

            // 1. Refresh local contacts from system (Import)
            importSystemContacts(localContactDao)

            // 2. Scan Recent Calls for intelligence gathering
            val recentNumbers = deviceRepo.fetchRecentCallsSync().map { it.number }
            
            // 3. Get all contacts for enrichment
            val contactNumbers = if (isFullScanNeeded) {
                deviceRepo.fetchContactsSync().mapNotNull { it.phoneNumber }
            } else emptyList()
            
            // 4. Combine, Normalize and Enqueue
            val numbersToProcess: List<String> = (recentNumbers + contactNumbers)
                .map { com.infocaller.app.util.PhoneNumberUtils.normalize(it) }
                .filter { it.isNotBlank() }
                .distinct()
            
            val currentTime = System.currentTimeMillis()
            for (number in numbersToProcess) {
                val cached = enrichmentDao.getEnrichmentSync(number)
                val gaps = com.infocaller.app.util.EnrichmentGapChecker.check(cached)
                // Native gap-aware: skip complete (name+photo), else re-enqueue if expired or has gaps
                val shouldEnqueue = when {
                    cached == null -> true
                    gaps.isComplete && cached.expiresAt > currentTime -> false // skip - already complete & fresh
                    gaps.isComplete -> false // complete, even if slightly stale - don't churn
                    cached.expiresAt < currentTime -> true // expired with gaps -> refresh
                    else -> gaps.hasAnyGap // missing critical gap
                }
                if (shouldEnqueue) app.enrichmentEngine.enqueue(number, priority = QueuePriority.LOW)
            }

            // 5. Process a single item immediately while we are running (Main work is done by ScanningService)
            // Professional: only one dequeue per Work run; ScanningService continues one-by-one with throttle
            app.enrichmentEngine.processNextOneByOne()

            if (isFullScanNeeded) {
                prefs.edit { putLong("last_full_contact_scan", System.currentTimeMillis()) }
            }
            
            Result.success()
        } catch (e: Exception) {
            Log.e("EnrichmentWorker", "Work failed", e)
            Result.retry()
        }
    }

    private fun importSystemContacts(dao: com.infocaller.app.data.local.dao.LocalContactDao) {
        val resolver = applicationContext.contentResolver
        val currentTime = System.currentTimeMillis()
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
                val rawNumber = it.getString(numIdx) ?: ""
                val normalized = com.infocaller.app.util.PhoneNumberUtils.normalize(rawNumber)
                
                contacts.add(LocalContactEntity(
                    id = it.getLong(idIdx),
                    lookupKey = it.getString(keyIdx),
                    displayName = name,
                    phoneNumber = normalized,
                    photoUri = it.getString(photoIdx),
                    photoThumbnailUri = it.getString(thumbIdx),
                    lastSynced = currentTime
                ))
            }
            
            runBlocking {
                dao.insertContacts(contacts)
            }
        }
    }
}
