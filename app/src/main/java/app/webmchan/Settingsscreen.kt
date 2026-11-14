// #Settingsscreen
package app.webmchan

import android.content.Context
import android.content.SharedPreferences
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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
            "during analysis, the percentage while encoding, plus paused, failed and finished icons.",
    ),
    NOTIFICATION_LOCK_SCREEN(
        "notificationLockScreen", true, "Show on lock screen",
        "Shows the notification card on the lock screen just as it looks in the notification shade, " +
            "including video names. Off shows only a plain WebMChan line there.",
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
    AUDIO_LOGO(
        "audioLogo", true, "WebMChan text on blank pictures",
        "In Audio Mode, a track made with the plain black picture (no image, and no placeholder chosen) gets the word " +
            "\"WebMChan\" in the bottom-left corner. Off leaves the picture completely black.",
    ),
    GIF_WHITE_BACKGROUND(
        "gifWhiteBackground", false, "White behind transparent GIFs",
        "In Audio Mode, the see-through parts of an animated GIF are shown over black. On shows them over white instead. " +
            "Applies to tracks converted from now on."
    ),
    CONFIRM_DESTRUCTIVE(
        "confirmDestructive", true, "Confirm before destructive actions",
        "Asks first before Cancel / Cancel all, Delete file, and Remove card (or Remove from history).",
    ),
    FASTEST_MODE(
        "fastestMode", false, "Offer Fastest mode",
        "Adds Fastest to the encoding choices on the main screen: the quickest conversion, with softer detail " +
            "and smaller frames. Off keeps it hidden. Turning this off while Fastest is chosen switches back to Quality.",
    ),
    BATTLE_TEST_CARDS(
        "battleTestCards", false, "Battle-Cards test fighters",
        "Adds 40 made-up fighters to the Battle-Cards roster so the mode can be tried without a History. " +
            "A party that already includes some keeps them until you change it.",
    ),
    BATTLE_BOSS_TEST(
        "battleBossTest", false, "Battle-Cards boss test",
        "For testing: a new run is one opponent long and starts at the secret boss fight, so the boss can be " +
            "tried without winning 50 fights first. A run already saved is not changed.",
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

    /** Every switch back to its default, and what was saved forgotten (for Reset app on the Cache page). */
    fun resetAll() {
        prefs?.edit()?.clear()?.apply()
        for (t in AppToggle.values()) values[t.key] = t.default
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
        SaveLocationGroup()
        group("Look", AppToggle.ANIMATIONS_OFF)
        group(
            "Notifications",
            AppToggle.NOTIFICATION_PROGRESS, AppToggle.STATUS_BAR_ICON, AppToggle.NOTIFICATION_LOCK_SCREEN,
            AppToggle.NOTIFICATION_FINISHED, AppToggle.NOTIFICATION_FAILURE,
        )
        group("While converting", AppToggle.KEEP_SCREEN_ON)
        PlaceholderGroup()
        group("Audio Mode", AppToggle.AUDIO_LOGO, AppToggle.GIF_WHITE_BACKGROUND)
        group(
            "Videos and cards",
            AppToggle.AUTO_CLEAR_FINISHED, AppToggle.CONFIRM_DESTRUCTIVE, AppToggle.SHARE_TO_RUNNING_BATCH,
        )
        group("Battle-Cards", AppToggle.BATTLE_TEST_CARDS, AppToggle.BATTLE_BOSS_TEST)
        group("Encoding", AppToggle.FASTEST_MODE)
    }
}

/** Audio Mode's placeholder picture: what a card with no image is made with (black when none is set). */
@Composable
private fun PlaceholderGroup() {
    val ctx = LocalContext.current
    val current = AudioArt.placeholder
    var error by remember { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) error = if (AudioArt.setPlaceholder(ctx, uri)) null else "Couldn't read that as a picture."
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        P5SectionTitle("Audio Mode placeholder")
        Text(
            "The picture an Audio Mode track is made with when it has no image of its own. Plain black when none is chosen.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            if (current != null) "Placeholder: ${current.w}×${current.h} picture" else "Placeholder: plain black",
            style = MaterialTheme.typography.titleSmall,
        )
        error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = P5.Amber) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            P5Button(if (current != null) "Change picture" else "Choose picture", onClick = { picker.launch("image/*") }, kind = P5Kind.Secondary, compact = true)
            if (current != null) {
                P5Button("Remove", onClick = { AudioArt.clearPlaceholder(ctx); error = null }, kind = P5Kind.Secondary, compact = true)
            }
        }
    }
}

/** Where converted videos are saved, and the buttons to change it (they can't while a batch is running). */
@Composable
private fun SaveLocationGroup() {
    val ctx = LocalContext.current
    var outputLabel by remember { mutableStateOf(OutputStorage.displayName(ctx)) }
    var hasCustomDir by remember { mutableStateOf(OutputStorage.savedDirUri(ctx) != null) }
    var folderError by remember { mutableStateOf<String?>(null) }
    val busy = BatchRunner.busy

    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { picked ->
        if (picked != null) {
            try {
                OutputStorage.rememberDir(ctx, picked)
                outputLabel = OutputStorage.displayName(ctx)
                hasCustomDir = true
                folderError = null
            } catch (e: Exception) {
                folderError = "Couldn't get permission for that folder. Try a different one."
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        P5SectionTitle("Save location")
        Text(outputLabel, style = MaterialTheme.typography.bodyMedium)
        Text(
            "Videos are saved in a WebMChan folder inside the location you choose. " +
                "If it becomes unavailable (e.g. SD card removed), they go to Download/WebMChan instead.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        folderError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            P5Button(
                "Choose folder",
                onClick = { folderPicker.launch(null) },
                enabled = !busy,
                kind = P5Kind.Secondary,
                modifier = Modifier.weight(1f),
            )
            if (hasCustomDir) {
                P5Button(
                    "Use default",
                    onClick = {
                        OutputStorage.clearDir(ctx)
                        outputLabel = OutputStorage.displayName(ctx)
                        hasCustomDir = false
                        folderError = null
                    },
                    enabled = !busy,
                    kind = P5Kind.Secondary,
                    modifier = Modifier.weight(1f),
                )
            }
        }
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
