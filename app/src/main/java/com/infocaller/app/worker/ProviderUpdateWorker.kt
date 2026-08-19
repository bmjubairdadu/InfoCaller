package com.infocaller.app.worker

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.infocaller.app.InfoCallerApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ProviderUpdateWorker(
    context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val app = applicationContext as InfoCallerApplication
        val providerManager = app.providerManager
        
        try {
            val registryUrl = providerManager.registryUrl.value
            if (registryUrl.isBlank()) {
                Log.d("ProviderUpdate", "Registry URL not configured, skipping update check.")
                return@withContext Result.success()
            }
            
            Log.d("ProviderUpdate", "Checking for provider updates at $registryUrl")
            
            // Step 1: Fetch Registry from URL
            val response = app.registryService.fetchManifest(registryUrl)
            if (response.isSuccessful) {
                val manifest = response.body()
                Log.d("ProviderUpdate", "Successfully fetched manifest: $manifest")
                
                // Step 2: Update Backend URL if specified in manifest
                manifest?.get("backend_url")?.asString?.let { url ->
                    if (url.isNotBlank()) {
                        providerManager.setBackendUrl(url)
                    }
                }
            } else {
                Log.e("ProviderUpdate", "Failed to fetch manifest: ${response.code()}")
            }
            
            Result.success()
        } catch (e: Exception) {
            Log.e("ProviderUpdate", "Update check failed", e)
            Result.failure()
        }
    }
}
