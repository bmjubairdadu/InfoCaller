package com.infocaller.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.infocaller.app.R
import com.infocaller.app.InfoCallerApplication
import com.infocaller.app.domain.engine.ScanOrchestrator
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first

class ScanningService : Service() {
    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)

    private var isRunning = false
    @Volatile private var queueJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!isRunning) {
            isRunning = true
            // Keep the service in foreground while the queue drains; do NOT detach after 500ms —
            // detaching a specialUse FGS immediately violates foreground-service policy on A12+.
            startForeground(NOTIFICATION_ID, createNotification())
            startContinuousScanning()
        }
        return START_STICKY
    }

    private fun startContinuousScanning() {
        // Cancel any previous collector before starting a new one (e.g. sticky restart).
        queueJob?.cancel()
        queueJob = serviceScope.launch {
            val app = applicationContext as InfoCallerApplication
            val orchestrator = app.orchestrator as ScanOrchestrator

            app.enrichmentEngine.isOnline.combine(orchestrator.isPriorityScanActive) { online, priorityActive ->
                online && !priorityActive
            }.collect { active ->
                // processQueueOneByOne loops while active; run it in a child that we cancel
                // as soon as the condition flips so priority scans can pre-empt.
                if (!active) return@collect
                val child = launch { processQueueOneByOne(app, orchestrator) }
                // Wait until inactive, then stop the child promptly.
                app.enrichmentEngine.isOnline.combine(orchestrator.isPriorityScanActive) { o, p -> o && !p }
                    .first { !it }
                child.cancelAndJoin()
            }
        }
    }

    private suspend fun processQueueOneByOne(app: InfoCallerApplication, orchestrator: ScanOrchestrator) {
        while (app.enrichmentEngine.isOnline.value && !orchestrator.isPriorityScanActive.value) {
            try {
                app.enrichmentEngine.processNextOneByOne()
            } catch (e: Exception) {
                Log.e("ScanningService", "Processing failed", e)
                delay(5000)
            }
            delay(1000)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Background", NotificationManager.IMPORTANCE_MIN).apply {
                description = "Silent background work"
                setShowBadge(false)
                enableLights(false); enableVibration(false); setSound(null,null)
            }
            getSystemService(Context.NOTIFICATION_SERVICE).let { it as NotificationManager }.createNotificationChannel(channel)
        }
    }
    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Caller ID active")
            .setContentText("Identifying incoming calls in background")
            .setSmallIcon(R.drawable.app_logo)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
            .setAutoCancel(false)
            .setOnlyAlertOnce(true)
            .build()
    }

    override fun onDestroy() {
        isRunning = false
        queueJob?.cancel()
        queueJob = null
        serviceJob.cancel()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "scanning_service_channel"
        private const val NOTIFICATION_ID = 1002

        fun start(context: Context) {
            val intent = Intent(context, ScanningService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, ScanningService::class.java)
            context.stopService(intent)
        }
    }
}
