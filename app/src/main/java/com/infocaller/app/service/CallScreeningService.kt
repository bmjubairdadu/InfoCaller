package com.infocaller.app.service

import android.telecom.Call
import android.telecom.CallScreeningService
import com.infocaller.app.data.local.database.AppDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * Service to handle incoming calls and block numbers based on local blocklist.
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
                
                // 1. Check local blocklist
                val isBlocked = db.blocklistDao().isBlocked(phoneNumber)
                
                val response = CallResponse.Builder()
                    .setDisallowCall(isBlocked)
                    .setRejectCall(isBlocked)
                    .setSkipCallLog(isBlocked)
                    .setSkipNotification(isBlocked)
                    .build()
                
                respondToCall(details, response)
            }
        }
    }
}
