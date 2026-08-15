package com.infocaller.app.service

import android.telecom.Call
import android.telecom.CallScreeningService
import com.infocaller.app.data.local.database.AppDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * Service to handle incoming calls and block spam numbers based on local DB.
 */
class CallScreeningService : CallScreeningService() {

    override fun onScreenCall(details: Call.Details) {
        val phoneNumber = details.handle?.schemeSpecificPart ?: ""
        
        if (phoneNumber.isEmpty()) {
            respondToCall(details, CallResponse.Builder().build())
            return
        }
        
        // Check if the call is an incoming call
        if (details.callDirection == Call.Details.DIRECTION_INCOMING) {
            
            // Perform block check in a coroutine
            CoroutineScope(Dispatchers.IO).launch {
                val db = AppDatabase.getDatabase(applicationContext)
                
                // 1. Check verified community spam list
                var isSpam = db.callerDao().isSpam(phoneNumber)
                
                // 2. Check local enrichment cache for high-score spam
                if (!isSpam) {
                    val enrichment = db.enrichmentDao().getEnrichmentSync(phoneNumber)
                    if (enrichment != null) {
                        isSpam = enrichment.spamStatus == "SPAM" || 
                                 enrichment.spamStatus == "SCAM" || 
                                 (enrichment.spamScore > 70)
                    }
                }
                
                val response = CallResponse.Builder()
                    .setDisallowCall(isSpam)
                    .setRejectCall(isSpam)
                    .setSkipCallLog(isSpam)
                    .setSkipNotification(isSpam)
                    .build()
                
                respondToCall(details, response)
            }
        }
    }
}
