package com.infocaller.app.service

import android.telecom.Call
import android.telecom.CallScreeningService
import com.infocaller.app.data.local.database.AppDatabase
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch


class CallScreeningService : CallScreeningService() {

    private val serviceScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO
    )

    override fun onScreenCall(details: Call.Details) {
        val phoneNumber = details.handle?.schemeSpecificPart ?: ""

        if (phoneNumber.isEmpty()) {
            respondToCall(details, CallResponse.Builder().build())
            return
        }

        // Non-incoming calls must still be answered promptly — never leave the framework waiting.
        if (details.callDirection != Call.Details.DIRECTION_INCOMING) {
            respondToCall(details, CallResponse.Builder().build())
            return
        }

        serviceScope.launch {
            try {
                val isBlocked = kotlinx.coroutines.withTimeoutOrNull(3500) {
                    val db = AppDatabase.getDatabase(applicationContext)
                    db.blocklistDao().isBlocked(phoneNumber)
                } ?: false
                val response = CallResponse.Builder()
                    .setDisallowCall(isBlocked)
                    .setRejectCall(isBlocked)
                    .setSkipCallLog(isBlocked)
                    .setSkipNotification(isBlocked)
                    .build()
                respondToCall(details, response)
            } catch (_: Exception) {
                try {
                    respondToCall(details, CallResponse.Builder().build())
                } catch (_: Exception) { }
            }
        }
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }
}
