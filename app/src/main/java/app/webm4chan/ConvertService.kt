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

/**
 * Keeps the app alive while a batch converts, so it carries on when the screen is locked or the
 * app is closed. It does no conversion itself: [BatchRunner] does that. This service only
 *  - runs in the foreground (which gives the process priority) with a progress notification,
 *  - holds a partial wake lock so the CPU isn't throttled while the screen is off, and
 *  - handles the notification's Cancel button.
 */
class ConvertService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            BatchRunner.cancelAll()
            return START_NOT_STICKY
        }

        // Must be called promptly after startForegroundService().
        startForeground(
            NOTIF_PROGRESS,
            buildProgress(this, "Converting", "Starting…", null),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )

        if (wakeLock == null) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "webmchan:convert").apply {
                setReferenceCounted(false)
                acquire(WAKE_LOCK_TIMEOUT_MS)
            }
        }

        // The batch may already have finished by the time we got here.
        if (!BatchRunner.busy) stopSelf()
        return START_NOT_STICKY
    }

    // Android 15+ ends "data sync" services after about 6 hours; stop cleanly instead of crashing.
    override fun onTimeout(startId: Int, fgsType: Int) {
        BatchRunner.cancelAll()
        stopSelf()
    }

    override fun onDestroy() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        super.onDestroy()
    }

    companion object {
        const val ACTION_CANCEL = "app.webmchan.action.CANCEL"

        private const val CHANNEL_PROGRESS = "convert_progress"
        private const val CHANNEL_DONE = "convert_done"
        private const val NOTIF_PROGRESS = 1
        private const val NOTIF_DONE = 2
        private const val WAKE_LOCK_TIMEOUT_MS = 6 * 60 * 60 * 1000L

        private fun ensureChannels(ctx: Context) {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_PROGRESS, "Conversion progress", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Shows progress while videos are converting"
                }
            )
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_DONE, "Conversion finished", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "Tells you when a batch has finished"
                }
            )
        }

        private fun openAppIntent(ctx: Context): PendingIntent {
            // The launcher intent brings the existing app task to the front instead of
            // starting a second copy of the screen.
            val launch = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName) ?: Intent()
            return PendingIntent.getActivity(
                ctx, 0, launch,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        }

        /** [percent] null = indeterminate. */
        fun buildProgress(ctx: Context, title: String, text: String, percent: Int?): Notification {
            ensureChannels(ctx)
            val cancel = PendingIntent.getService(
                ctx, 1,
                Intent(ctx, ConvertService::class.java).setAction(ACTION_CANCEL),
                PendingIntent.FLAG_IMMUTABLE
            )
            return NotificationCompat.Builder(ctx, CHANNEL_PROGRESS)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle(title)
                .setContentText(text.lineSequence().firstOrNull() ?: text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setContentIntent(openAppIntent(ctx))
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setProgress(100, percent ?: 0, percent == null)
                .addAction(0, "Cancel", cancel)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .build()
        }

        fun postProgress(ctx: Context, title: String, text: String, percent: Int?) {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            nm.notify(NOTIF_PROGRESS, buildProgress(ctx, title, text, percent))
        }

        fun clearProgress(ctx: Context) {
            ctx.getSystemService(NotificationManager::class.java).cancel(NOTIF_PROGRESS)
        }

        fun postFinished(ctx: Context, text: String) {
            ensureChannels(ctx)
            val n = NotificationCompat.Builder(ctx, CHANNEL_DONE)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle("WebMChan")
                .setContentText(text)
                .setContentIntent(openAppIntent(ctx))
                .setAutoCancel(true)
                .build()
            ctx.getSystemService(NotificationManager::class.java).notify(NOTIF_DONE, n)
        }
    }
}
