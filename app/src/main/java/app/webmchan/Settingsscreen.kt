// #Settingsscreen
package app.webmchan

import android.content.Context
import android.content.SharedPreferences
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** One on/off setting: how it is saved, labelled and explained on the Settings page. */
enum class AppToggle(val key: String, val default: Boolean, val title: String, val help: String) {
    ANIMATIONS_OFF(
        "animationsOff", false, "Animations off",
        "Turns off the running lights round the converting card, the flash and shake when a video " +
            "finishes or fails, the NEXT pulse and the moving band on waiting cards. The hourglass " +
            "stands still. The percentage and the progress bars still work as normal.",
    ),
    NOTIFICATION_PROGRESS(
        "notificationProgress", true, "Progress in notification",
        "Shows the big percentage (or the hourglass) and the progress bar in the notification while " +
            "converting. Off leaves just the title and the step.",
    ),
    STATUS_BAR_ICON(
        "statusBarIcon", true, "Status bar indicator",
        "A small monochrome icon at the top of the screen while WebMChan is working: an hourglass " +
            "during analysis, the percentage while encoding, plus paused, failed and finished icons. On " +
            "Android 16 and up it also adds a short note beside it (video 1/2, Retry, Failed, Done) if " +
            "Live Updates are allowed for the app.",
    ),
    NOTIFICATION_FINISHED(
        "notificationFinished", true, "Finished notification",
        "Tells you when a run has finished, if the app isn't on screen. With this off, the finished tick still shows in the status bar, quietly, if the status bar indicator is on.",
    ),
    NOTIFICATION_FAILURE(
        "notificationFailure", false, "Notify on each failure",
        "A separate notification the moment a video fails, rather than only the summary at the end.",
    ),
    KEEP_SCREEN_ON(
        "keepScreenOn", false, "Keep screen on while converting",
        "Stops the screen turning off while a run is going and the app is open. Converting with the " +
            "screen off or the app closed works as before.",
    ),
    AUTO_CLEAR_FINISHED(
        "autoClearFinished", false, "Clear Finished cards when a new run starts",
        "The Finished list starts empty each run. The cards stay in History.",
    ),
    CONFIRM_DESTRUCTIVE(
        "confirmDestructive", true, "Confirm before destructive actions",
        "Asks first before Cancel / Cancel all, Delete file, and Remove card (or Remove from history).",
    ),
    SHARE_TO_RUNNING_BATCH(
        "shareToRunningBatch", false, "Add shared videos to a running batch",
        "If a conversion is already running (or paused) when you share a video to WebMChan, it goes " +
            "straight into the queue with that run's settings. Otherwise it goes to the Holding Pen as usual.",
    ),
}

/**
 * The on/off settings from the Settings page. Saved on the phone; readable from anywhere (the cards,
 * the notification, the runner) once [init] has been called, which is harmless to repeat.
 */
object AppSettings {
    private const val PREFS = "webmchan_settings"
    private val values = mutableStateMapOf<String, Boolean>()
    private var prefs: SharedPreferences? = null

    fun init(ctx: Context) {
        if (prefs != null) return
        val p = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = p
        for (t in AppToggle.values()) values[t.key] = p.getBoolean(t.key, t.default)
    }

    /** Reading this inside a composable makes it redraw when the setting changes. */
    fun get(t: AppToggle): Boolean = values[t.key] ?: t.default

    fun set(t: AppToggle, on: Boolean) {
        values[t.key] = on
        prefs?.edit()?.putBoolean(t.key, on)?.apply()
    }
}

/** The Settings page: a switch for each [AppToggle], in groups. */
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        P5Button("← Back", onClick = onBack, kind = P5Kind.Ghost)
        P5Title("Settings")
        group("Look", AppToggle.ANIMATIONS_OFF)
        group(
            "Notifications",
            AppToggle.NOTIFICATION_PROGRESS, AppToggle.STATUS_BAR_ICON,
            AppToggle.NOTIFICATION_FINISHED, AppToggle.NOTIFICATION_FAILURE,
        )
        group("While converting", AppToggle.KEEP_SCREEN_ON)
        group(
            "Videos and cards",
            AppToggle.AUTO_CLEAR_FINISHED, AppToggle.CONFIRM_DESTRUCTIVE, AppToggle.SHARE_TO_RUNNING_BATCH,
        )
    }
}

@Composable
private fun group(title: String, vararg toggles: AppToggle) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        P5SectionTitle(title)
        for ((n, t) in toggles.withIndex()) {
            if (n > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(P5.Line))
            ToggleRow(t)
        }
    }
}

@Composable
private fun ToggleRow(t: AppToggle) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f).padding(end = 12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(t.title, style = MaterialTheme.typography.titleSmall)
            Text(
                t.help,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = AppSettings.get(t),
            onCheckedChange = { AppSettings.set(t, it) },
            colors = p5SwitchColors(),
        )
    }
}
