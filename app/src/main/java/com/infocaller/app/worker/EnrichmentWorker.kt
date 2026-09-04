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
            // Missing permission is not a failure — retrying would just spin. Succeed quietly.
            return@withContext Result.success()
        }
        
        try {
            val prefs = applicationContext.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
            val lastFullScan = prefs.getLong("last_full_contact_scan", 0L)
            val isFullScanNeeded = System.currentTimeMillis() - lastFullScan > 12 * 3600000

            importSystemContacts(localContactDao)

            val callerLogNumbers = try { deviceRepo.fetchRecentCallsSync().map { it.number } } catch (_: Exception) { emptyList() }
            val recentNumbers = callerLogNumbers
            
            val contactNumbers = if (isFullScanNeeded) {
                deviceRepo.fetchContactsSync().mapNotNull { it.phoneNumber }
            } else emptyList()
            
            val numbersToProcess: List<String> = (recentNumbers + contactNumbers)
                .map { com.infocaller.app.util.PhoneNumberUtils.normalize(it) }
                .filter { it.isNotBlank() }
                .distinct()
            
            val currentTime = System.currentTimeMillis()
            // Batch the cache reads (one query instead of N) and skip complete-but-
            // expired rows: gap logic treats them as stale-forever otherwise.
            val cachedByNumber: Map<String, com.infocaller.app.data.local.entity.ContactEnrichmentEntity> = try {
                if (numbersToProcess.isEmpty()) emptyMap()
                else enrichmentDao.getEnrichmentsSync(numbersToProcess).associateBy { it.normalizedPhoneNumber }
            } catch (_: Exception) { emptyMap() }
            for (number in numbersToProcess) {
                val cached = cachedByNumber[number]
                val gaps = com.infocaller.app.util.EnrichmentGapChecker.check(cached)
                val expired = cached != null && cached.expiresAt < currentTime
                val shouldEnqueue = when {
                    cached == null -> true
                    expired -> gaps.hasAnyGap
                    gaps.isComplete -> false
                    else -> gaps.hasAnyGap
                }
                if (shouldEnqueue) app.enrichmentEngine.enqueue(number, priority = QueuePriority.LOW)
            }

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
        // A throw here must never escape into doWork's retry path forever (battery
        // drain) — and a missing column index (-1) would crash outright. Fail quiet.
        try {
            val resolver = applicationContext.contentResolver
            val currentTime = System.currentTimeMillis()
            val cursor = try {
                resolver.query(
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
            } catch (_: Exception) {
                return
            }

            cursor?.use {
                val contacts = mutableListOf<LocalContactEntity>()
                val idIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.CONTACT_ID)
                val keyIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.LOOKUP_KEY)
                val nameIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val numIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                val photoIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.PHOTO_URI)
                val thumbIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.PHOTO_THUMBNAIL_URI)
                if (idIdx < 0 || numIdx < 0) return

                while (it.moveToNext()) {
                    val name = try { it.getString(nameIdx) } catch (_: Exception) { null } ?: "Unknown"
                    val rawNumber = try { it.getString(numIdx) } catch (_: Exception) { null } ?: ""
                    val normalized = com.infocaller.app.util.PhoneNumberUtils.normalize(rawNumber)

                    contacts.add(LocalContactEntity(
                        id = try { it.getLong(idIdx) } catch (_: Exception) { continue },
                        lookupKey = try { it.getString(keyIdx) } catch (_: Exception) { null } ?: "",
                        displayName = name,
                        phoneNumber = normalized,
                        photoUri = try { it.getString(photoIdx) } catch (_: Exception) { null },
                        photoThumbnailUri = try { it.getString(thumbIdx) } catch (_: Exception) { null },
                        lastSynced = currentTime
                    ))
                }

                runBlocking {
                    dao.insertContacts(contacts)
                }
            }
        } catch (_: Exception) { }
    }
}
