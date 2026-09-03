package com.infocaller.app.receiver

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import android.util.Log
import com.infocaller.app.InfoCallerApplication
import kotlinx.coroutines.*

/**
 * Monitors the system contacts database for changes.
 * Automatically triggers enrichment for new or updated contacts.
 */
class ContactsObserver(
    private val context: Context,
    private val scope: CoroutineScope
) : ContentObserver(Handler(Looper.getMainLooper())) {

    override fun onChange(selfChange: Boolean, uri: Uri?) {
        super.onChange(selfChange, uri)
        Log.d("ContactsObserver", "Contacts changed: $uri")
        
        // Use a small delay to debounce multiple rapid changes
        scope.launch {
            delay(5000)
            triggerBackgroundSync()
        }
    }

    private fun triggerBackgroundSync() {
        val app = context.applicationContext as? InfoCallerApplication ?: return
        
        // Trigger the enrichment worker to scan for new/changed contacts
        val workRequest = androidx.work.OneTimeWorkRequestBuilder<com.infocaller.app.worker.EnrichmentWorker>()
            .setConstraints(
                androidx.work.Constraints.Builder()
                    .setRequiredNetworkType(androidx.work.NetworkType.CONNECTED)
                    .build()
            )
            .build()

        androidx.work.WorkManager.getInstance(context).enqueueUniqueWork(
            "InstantContactsSync",
            androidx.work.ExistingWorkPolicy.REPLACE,
            workRequest
        )
    }

    companion object {
        fun register(context: Context, scope: CoroutineScope) {
            val observer = ContactsObserver(context, scope)
            context.contentResolver.registerContentObserver(
                ContactsContract.Contacts.CONTENT_URI,
                true,
                observer
            )
        }
    }
}
