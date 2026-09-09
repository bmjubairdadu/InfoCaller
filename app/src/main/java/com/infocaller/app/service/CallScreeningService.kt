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

        if (details.callDirection != Call.Details.DIRECTION_INCOMING) {
            respondToCall(details, CallResponse.Builder().build())
            return
        }

        // Truecaller verification (missed/flash) call while an OTP request is
        // pending: capture its tail for auto-verify and reject it instantly —
        // before ringing. Live verify API decides validity.
        try {
            val prefs = applicationContext.getSharedPreferences("app_prefs", android.content.Context.MODE_PRIVATE)
            val pendingRid = prefs.getString("last_tc_request_id", null)
            val pendingPhone = prefs.getString("last_tc_phone", null)
            if (!pendingRid.isNullOrBlank() && !pendingPhone.isNullOrBlank()) {
                val tail = phoneNumber.filter { it.isDigit() }.takeLast(6)
                if (tail.length == 6) {
                    com.infocaller.app.util.OtpManager.onMissedCallTailSync(tail, phoneNumber)
                } else {
                    val d = phoneNumber.filter { it.isDigit() }
                    if (d.isNotBlank()) {
                        com.infocaller.app.util.OtpManager.onMissedCallTailSync(d, phoneNumber)
                    }
                }
                respondToCall(
                    details,
                    CallResponse.Builder()
                        .setDisallowCall(true)
                        .setRejectCall(true)
                        .setSkipCallLog(true)
                        .setSkipNotification(true)
                        .build()
                )
                return
            }
        } catch (_: Exception) { }

        serviceScope.launch {
            try {
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
