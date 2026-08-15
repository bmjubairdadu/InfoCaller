package com.infocaller.app.worker

import android.content.Context
import android.provider.ContactsContract
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.infocaller.app.data.local.database.AppDatabase
import com.infocaller.app.data.local.entity.LocalContactEntity
import com.infocaller.app.util.PhoneNumberUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ContactSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val db = AppDatabase.getDatabase(applicationContext)
        val dao = db.localContactDao()
        
        // Refresh local contacts from system
        importSystemContacts(dao)

        val contacts = dao.getUnsyncedContacts()

        contacts.forEach { contact ->
            try {
                // STAGE 1: Advanced Automatic Search (No-API)
                val scraper = com.infocaller.app.data.remote.CallerScraper(applicationContext)
                val result = scraper.performDeepOSINT(contact.phoneNumber)

                if (result != null) {
                    val currentName = contact.displayName
                    
                    // Treat placeholders as "Unknown" to allow discovery
                    val isCurrentUnknown = currentName.isBlank() || 
                                         currentName == "Unknown" || 
                                         currentName.contains("Network", ignoreCase = true) ||
                                         currentName.contains("Identity", ignoreCase = true) ||
                                         currentName.contains("Identified", ignoreCase = true)
                    
                    // Only use result if it's a high-quality human name
                    val foundRealName = !result.displayName.isNullOrBlank() && 
                                       !result.displayName.contains("Network", ignoreCase = true) && 
                                       !result.displayName.contains("Found via", ignoreCase = true)

                    val newName = if (isCurrentUnknown && foundRealName) {
                        result.displayName
                    } else {
                        currentName
                    }

                    dao.updateContact(contact.copy(
                        about = "Carrier: ${result.carrier ?: "Unknown"} | Location: ${result.region ?: "Unknown"}",
                        whatsappProfilePic = result.photoUrl,
                        displayName = newName,
                        isSynced = true,
                        lastSynced = System.currentTimeMillis()
                    ))
                    
                    
                    // CRITICAL FIX: Always try to update system contacts to push the Photo/Location.
                    // The service itself will protect the Name from being overwritten.
                    val enrichment = com.infocaller.app.data.repository.ContactEnrichmentService(applicationContext)
                    enrichment.updateExistingContact(contact.phoneNumber, result.copy(displayName = newName))
                }
                
                // Wait to avoid blocks
                kotlinx.coroutines.delay(3000)
            } catch (e: Exception) {
                Log.e("ContactSyncWorker", "Failed to sync ${contact.phoneNumber}", e)
            }
        }
        Result.success()
    }

    private fun importSystemContacts(dao: com.infocaller.app.data.local.dao.LocalContactDao) {
        val resolver = applicationContext.contentResolver
        val cursor = resolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
                ContactsContract.CommonDataKinds.Phone.LOOKUP_KEY,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER
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

            while (it.moveToNext()) {
                val name = it.getString(nameIdx) ?: "Unknown"
                
                contacts.add(LocalContactEntity(
                    id = it.getLong(idIdx),
                    lookupKey = it.getString(keyIdx),
                    displayName = name,
                    phoneNumber = it.getString(numIdx)
                ))
            }
            
            kotlinx.coroutines.runBlocking {
                dao.insertContacts(contacts)
            }
        }
    }
}
