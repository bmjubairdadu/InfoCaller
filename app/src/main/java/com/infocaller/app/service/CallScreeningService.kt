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
                // Ordered on-device decision engine (anonymous -> exact blocklist ->
                // prefix -> unknown-not-in-contacts). Pattern source: humanjuan/iOG26.
                // Fails open: any error allows the call. Bounded so we always answer
                // the framework promptly.
                val decision = kotlinx.coroutines.withTimeoutOrNull(3500) {
                    val app = applicationContext
                    val db = AppDatabase.getDatabase(app)
                    val rules = com.infocaller.app.data.local.CallScreeningRules
                    val verdict = rules.decide(
                        app,
                        db.screeningDao(),
                        isExactBlocked = { normalized ->
                            try { db.blocklistDao().isBlocked(normalized) } catch (_: Exception) { false }
                        },
                        phoneNumber
                    )
                    if (verdict is com.infocaller.app.data.local.CallScreeningRules.Decision.Block) {
                        try { rules.logBlocked(db.screeningDao(), phoneNumber, verdict.reason) } catch (_: Exception) { }
                    }
                    verdict
                } ?: com.infocaller.app.data.local.CallScreeningRules.Decision.Allow
                val isBlocked = decision is com.infocaller.app.data.local.CallScreeningRules.Decision.Block
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
