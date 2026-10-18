package app.webmchan

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Foreground service that keeps the app alive while a batch converts, so it carries on when the
 * screen turns off or the app is in the background. The conversion itself runs in [BatchRunner];
 * this service only provides the process protection, a progress notification with a Cancel button,
 * and a wake lock so the CPU isn't throttled while the screen is off.
 */
class ConvertService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var watcher: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_PROGRESS, "Conversion progress", NotificationManager.IMPORTANCE_LOW)
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_DONE, "Conversion finished", NotificationManager.IMPORTANCE_DEFAULT)
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            BatchRunner.cancelAll()
            return START_NOT_STICKY
        }

        // Must happen promptly after startForegroundService(), or Android stops the app.
        startForeground(
            NOTIF_PROGRESS,
            progressNotification(BatchRunner.status.ifEmpty { "Starting…" }, BatchRunner.progress),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )

        if (!BatchRunner.busy) {
            // Nothing to do (e.g. the batch was cancelled before we got going).
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        if (watcher == null) {
            acquireWakeLock()
            watcher = scope.launch {
                val nm = getSystemService(NotificationManager::class.java)
                while (isActive && BatchRunner.busy) {
                    nm.notify(NOTIF_PROGRESS, progressNotification(BatchRunner.status, BatchRunner.progress))
                    delay(1000)
                }
                finishUp(nm)
            }
        }
        return START_NOT_STICKY
    }

    private fun finishUp(nm: NotificationManager) {
        watcher = null
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        // No need to announce the result if the app is on screen, or if the user cancelled.
        if (!BatchRunner.appVisible && !BatchRunner.cancelRequested) {
            nm.notify(NOTIF_DONE, doneNotification(BatchRunner.summary ?: "Finished"))
        }
        stopSelf()
    }

    override fun onDestroy() {
        releaseWakeLock()
        scope.cancel()
        super.onDestroy()
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "WebMChan:convert").apply {
            setReferenceCounted(false)
            acquire(WAKE_LOCK_MAX_MS) // safety timeout; released as soon as the batch ends
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    private fun openAppIntent(): PendingIntent {
        val launch = packageManager.getLaunchIntentForPackage(packageName)
            ?: Intent(this, MainActivity::class.java)
        return PendingIntent.getActivity(this, 0, launch, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun progressNotification(text: String, progress: Float): Notification {
        val cancel = PendingIntent.getService(
            this, 1,
            Intent(this, ConvertService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CH_PROGRESS)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("WebMChan: converting")
            .setContentText(text)
            .setProgress(100, (progress.coerceIn(0f, 1f) * 100).toInt(), progress < 0f)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openAppIntent())
            .addAction(0, "Cancel", cancel)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun doneNotification(text: String): Notification =
        NotificationCompat.Builder(this, CH_DONE)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("WebMChan: finished")
            .setContentText(text)
            .setAutoCancel(true)
            .setContentIntent(openAppIntent())
            .build()

    companion object {
        private const val CH_PROGRESS = "progress"
        private const val CH_DONE = "done"
        private const val NOTIF_PROGRESS = 1
        private const val NOTIF_DONE = 2
        private const val ACTION_CANCEL = "app.webmchan.action.CANCEL"
        private const val WAKE_LOCK_MAX_MS = 6 * 60 * 60 * 1000L

        /** Starts the service; if Android refuses, the batch still runs, just without protection. */
        fun start(ctx: Context) {
            try {
                ContextCompat.startForegroundService(ctx, Intent(ctx, ConvertService::class.java))
            } catch (_: Exception) {
            }
        }
    }
}
