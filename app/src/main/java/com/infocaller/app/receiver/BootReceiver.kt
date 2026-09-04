package com.infocaller.app.receiver

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.infocaller.app.R
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.ExistingPeriodicWorkPolicy
import com.infocaller.app.worker.EnrichmentWorker
import java.util.concurrent.TimeUnit

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == "android.intent.action.QUICKBOOT_POWERON") {
            // NOTE: startForegroundService() from background is blocked on Android 12+
            // (ForegroundServiceStartNotAllowedException). ScanningService.start() is safe to
            // call — it catches that exception internally — and WorkManager jobs below
            // guarantee background work resumes even if the service start is deferred.
            try {
                com.infocaller.app.service.ScanningService.start(context)
            } catch (_: Exception) { }
            try {
                val req = PeriodicWorkRequestBuilder<EnrichmentWorker>(1, TimeUnit.HOURS)
                    .setConstraints(
                        androidx.work.Constraints.Builder()
                            .setRequiredNetworkType(androidx.work.NetworkType.CONNECTED)
                            .setRequiresBatteryNotLow(true)
                            .build()
                    )
                    .build()
                // KEEP + same constraints as MainActivity so the boot spec can never
                // overwrite the constrained spec with an unconstrained one.
                WorkManager.getInstance(context).enqueueUniquePeriodicWork("EnrichmentSync", ExistingPeriodicWorkPolicy.KEEP, req)
            } catch (_: Exception) { }
            // Resume consent-gated contribution queue only if previously accepted.
            try {
                com.infocaller.app.worker.ContributionWorker.resumeIfConsented(context)
            } catch (_: Exception) { }
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
            .setSmallIcon(R.drawable.app_logo)
            .setContentTitle("Caller ID ready")
            .setContentText("Background caller identification is active")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setAutoCancel(true)
            .setOngoing(false)
            .setOnlyAlertOnce(true)
            .build()
        if (androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            mgr.notify(id, n)
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ try { mgr.cancel(id) } catch (_: Exception) {} }, 3000)
        }
    }
}
