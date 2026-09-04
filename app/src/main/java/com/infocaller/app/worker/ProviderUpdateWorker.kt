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
            // Use GitHub raw URL for provider registry manifest
            val registryUrl = "https://raw.githubusercontent.com/bmjubairdadu/InfoCaller-Provider-Registry/main/manifest.json"
            
            Log.d("ProviderUpdate", "Checking for provider updates at $registryUrl")
            
            val response = app.registryService.fetchManifest(registryUrl)
            if (response.isSuccessful) {
                val manifest = response.body()
                Log.d("ProviderUpdate", "Successfully fetched manifest: $manifest")
                
                // Backend URL is no longer used - all providers work without backend
                manifest?.get("backend_url")?.asString?.let { url ->
                    if (url.isNotBlank()) {
                        Log.d("ProviderUpdate", "Backend URL found in manifest but not used: $url")
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
