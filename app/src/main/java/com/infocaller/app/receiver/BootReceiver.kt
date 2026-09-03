package com.infocaller.app.receiver

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.ExistingPeriodicWorkPolicy
import com.infocaller.app.worker.EnrichmentWorker
import java.util.concurrent.TimeUnit

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == "android.intent.action.QUICKBOOT_POWERON") {
            Log.d("BootReceiver", "Boot - starting silent background")
            com.infocaller.app.service.ScanningService.start(context)
            val req = PeriodicWorkRequestBuilder<EnrichmentWorker>(1, TimeUnit.HOURS).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork("EnrichmentSync", ExistingPeriodicWorkPolicy.KEEP, req)
            showAutoCloseNotification(context)
        }
    }

    private fun showAutoCloseNotification(context: Context) {
        val channelId = "boot_auto"
        val id = 1003
        val mgr = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(channelId, "System", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Auto-close on boot"
                setShowBadge(false); enableVibration(false); setSound(null,null)
            }
            mgr.createNotificationChannel(ch)
        }
        val n = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.ic_menu_call)
            .setContentTitle("InfoCaller")
            .setContentText("Ready to identify calls")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setAutoCancel(true)
            .setOngoing(false)
            .setOnlyAlertOnce(true)
            .build()
        mgr.notify(id, n)
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ mgr.cancel(id) }, 1500)
    }
}
