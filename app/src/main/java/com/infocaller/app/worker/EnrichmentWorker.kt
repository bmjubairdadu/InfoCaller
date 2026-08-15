package com.infocaller.app.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.infocaller.app.InfoCallerApplication
import com.infocaller.app.data.local.entity.QueuePriority
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class EnrichmentWorker(
    context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val app = applicationContext as InfoCallerApplication
        val enrichmentDao = app.database.enrichmentDao()
        val deviceRepo = app.deviceDataRepository
        
        try {
            // 1. Get all contacts
            val contacts = (deviceRepo as com.infocaller.app.data.repository.DeviceDataRepositoryImpl).fetchContactsSync()
            
            // 2. Extract and Normalize numbers
            val numbersToProcess = contacts.mapNotNull { it.phoneNumber }
                .map { com.infocaller.app.util.PhoneNumberUtils.normalize(it) }
                .distinct()
            
            // 3. Filter and Enqueue
            val currentTime = System.currentTimeMillis()
            numbersToProcess.forEach { number ->
                val cached = enrichmentDao.getEnrichmentSync(number)
                if (cached == null || cached.expiresAt < currentTime) {
                    app.enrichmentEngine.enqueue(number, priority = QueuePriority.LOW)
                }
            }
            
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }
}
