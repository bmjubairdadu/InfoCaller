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
import com.infocaller.app.InfoCallerApplication
import com.infocaller.app.domain.engine.ScanOrchestrator
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.combine

class ScanningService : Service() {
    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.Main + serviceJob)
    
    private var isRunning = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!isRunning) {
            isRunning = true
            // Silent foreground - no visible notification except incoming call
            startForeground(NOTIFICATION_ID, createNotification())
            // Immediately hide - this keeps process alive without tray spam
            serviceScope.launch {
                delay(500)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) stopForeground(STOP_FOREGROUND_DETACH) else @Suppress("DEPRECATION") stopForeground(false)
            }
            startContinuousScanning()
        }
        return START_STICKY
    }

    private fun startContinuousScanning() {
        serviceScope.launch {
            val app = applicationContext as InfoCallerApplication
            val orchestrator = app.orchestrator as ScanOrchestrator
            
            app.enrichmentEngine.isOnline.combine(orchestrator.isPriorityScanActive) { online, priorityActive ->
                online && !priorityActive
            }.collect { active ->
                if (active) {
                    processQueueOneByOne(app, orchestrator)
                }
            }
        }
    }

    private suspend fun processQueueOneByOne(app: InfoCallerApplication, orchestrator: ScanOrchestrator) {
        // Professional: respects 3.5s throttle inside processNextOneByOne; this loop is cancellation-aware
        while (app.enrichmentEngine.isOnline.value && !orchestrator.isPriorityScanActive.value) {
            try {
                app.enrichmentEngine.processNextOneByOne()
            } catch (e: Exception) {
                Log.e("ScanningService", "Processing failed", e)
                delay(5000)
            }
            // Small cooperative yield; actual pacing is in engine's MIN_INTERVAL_MS
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
            .setContentTitle("")
            .setContentText("")
            .setSmallIcon(android.R.drawable.ic_menu_call)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(false)
            .build()
    }

    override fun onDestroy() {
        isRunning = false
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
