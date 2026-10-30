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
import android.view.View
import android.widget.RemoteViews
import androidx.compose.ui.graphics.toArgb
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
        private const val NOTIF_PAUSED = 3
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

        /**
         * The two custom views every notification uses, in the app's look (black, red stripe, heavy
         * italic capitals): the collapsed one shows the title and the first line of [text], the
         * expanded one all of it. With [showPercent] they also carry the big red percentage (dashes
         * when [percent] is null), and the expanded one the progress bar.
         */
        private fun styledViews(
            ctx: Context, title: String, text: String, showPercent: Boolean, percent: Int?,
        ): Pair<RemoteViews, RemoteViews> {
            val small = RemoteViews(ctx.packageName, R.layout.notification_progress_small)
            val big = RemoteViews(ctx.packageName, R.layout.notification_progress_big)
            small.setTextViewText(R.id.notif_title, title)
            small.setTextViewText(R.id.notif_text, text.lineSequence().firstOrNull() ?: text)
            big.setTextViewText(R.id.notif_title, title)
            big.setTextViewText(R.id.notif_text, text)
            if (showPercent) {
                val counter = NotificationArt.percent(ctx, percent)
                val spoken = if (percent != null) "$percent percent" else "In progress"
                for (v in listOf(small, big)) {
                    v.setImageViewBitmap(R.id.notif_percent, counter)
                    v.setContentDescription(R.id.notif_percent, spoken)
                }
                big.setImageViewBitmap(R.id.notif_bar, NotificationArt.progressBar(ctx, percent?.let { it / 100f }))
                big.setContentDescription(R.id.notif_bar, spoken)
            } else {
                small.setViewVisibility(R.id.notif_percent, View.GONE)
                big.setViewVisibility(R.id.notif_percent, View.GONE)
                big.setViewVisibility(R.id.notif_bar, View.GONE)
            }
            return small to big
        }

        /** [percent] null = indeterminate. */
        fun buildProgress(ctx: Context, title: String, text: String, percent: Int?): Notification {
            ensureChannels(ctx)
            val cancel = PendingIntent.getService(
                ctx, 1,
                Intent(ctx, ConvertService::class.java).setAction(ACTION_CANCEL),
                PendingIntent.FLAG_IMMUTABLE
            )
            val (small, big) = styledViews(ctx, title, text, showPercent = true, percent = percent)
            return NotificationCompat.Builder(ctx, CHANNEL_PROGRESS)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                // Still set, for what can't show the custom views (accessibility, lock screen, wearables).
                .setContentTitle(title)
                .setContentText(text.lineSequence().firstOrNull() ?: text)
                .setStyle(NotificationCompat.DecoratedCustomViewStyle())
                .setCustomContentView(small)
                .setCustomBigContentView(big)
                // Black all the way round, header included (Android honours this for the
                // notification of a foreground service).
                .setColor(P5.Black.toArgb())
                .setColorized(true)
                .setContentIntent(openAppIntent(ctx))
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .addAction(0, "Cancel", cancel)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .build()
        }

        fun postProgress(ctx: Context, title: String, text: String, percent: Int?) {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            nm.notify(NOTIF_PROGRESS, buildProgress(ctx, title, text, percent))
        }

        /** A quiet, ordinary (not ongoing) notification while a batch is paused. */
        fun postPaused(ctx: Context, text: String) {
            ensureChannels(ctx)
            val (small, big) = styledViews(ctx, "WebMChan: paused", text, showPercent = false, percent = null)
            val n = NotificationCompat.Builder(ctx, CHANNEL_PROGRESS)
                .setSmallIcon(android.R.drawable.ic_media_pause)
                .setContentTitle("WebMChan: paused")
                .setContentText(text)
                .setStyle(NotificationCompat.DecoratedCustomViewStyle())
                .setCustomContentView(small)
                .setCustomBigContentView(big)
                .setColor(P5.Red.toArgb())
                .setContentIntent(openAppIntent(ctx))
                .setOnlyAlertOnce(true)
                .build()
            ctx.getSystemService(NotificationManager::class.java).notify(NOTIF_PAUSED, n)
        }

        fun clearPaused(ctx: Context) {
            ctx.getSystemService(NotificationManager::class.java).cancel(NOTIF_PAUSED)
        }

        fun clearProgress(ctx: Context) {
            ctx.getSystemService(NotificationManager::class.java).cancel(NOTIF_PROGRESS)
        }

        fun postFinished(ctx: Context, text: String) {
            ensureChannels(ctx)
            val (small, big) = styledViews(ctx, "WebMChan", text, showPercent = false, percent = null)
            val n = NotificationCompat.Builder(ctx, CHANNEL_DONE)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle("WebMChan")
                .setContentText(text)
                .setStyle(NotificationCompat.DecoratedCustomViewStyle())
                .setCustomContentView(small)
                .setCustomBigContentView(big)
                .setColor(P5.Red.toArgb())
                .setContentIntent(openAppIntent(ctx))
                .setAutoCancel(true)
                .build()
            ctx.getSystemService(NotificationManager::class.java).notify(NOTIF_DONE, n)
        }
    }
}
