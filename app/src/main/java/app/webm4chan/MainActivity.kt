package app.webmchan

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.widget.Toast
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.util.Locale

/** The frame rate caps offered as presets. Custom follows them in the radio list. */
private val FPS_PRESETS = listOf(24, 30, 60)

/** Index into [FPS_PRESETS] of the default (30). */
private const val FPS_DEFAULT_INDEX = 1

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Bring back the queue and Finished list from last time (does nothing if already loaded).
        BatchRunner.restore(applicationContext)
        // The fitting numbers from About > Advanced (defaults unless changed).
        TuningStore.load(applicationContext)
        // Videos shared to us from another app. Skipped when the system recreates the activity,
        // so videos the user already removed don't come back.
        if (savedInstanceState == null) handleShare(intent)
        setContent {
            val ctx = LocalContext.current
            val dark = isSystemInDarkTheme()
            // Dynamic colors need Android 12+; minSdk is 29.
            val scheme = when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
                    if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
                dark -> darkColorScheme()
                else -> lightColorScheme()
            }
            MaterialTheme(colorScheme = scheme) {
                var showAbout by remember { mutableStateOf(false) }
                var showAdvanced by remember { mutableStateOf(false) }
                Surface(Modifier.fillMaxSize()) {
                    Box {
                        // App stays composed underneath, so opening About doesn't lose the scroll
                        // position or the settings.
                        App(onAbout = { showAbout = true })
                        if (showAbout) {
                            Surface(Modifier.fillMaxSize()) {
                                AboutScreen(onBack = { showAbout = false }, onAdvanced = { showAdvanced = true })
                            }
                        }
                        if (showAdvanced) {
                            Surface(Modifier.fillMaxSize()) { AdvancedScreen(onBack = { showAdvanced = false }) }
                        }
                    }
                }
            }
        }
    }

    // Sharing again while the app is open arrives here (launchMode singleTop).
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShare(intent)
    }

    /** Adds any videos from a Share (SEND / SEND_MULTIPLE) intent to the queue. */
    private fun handleShare(intent: Intent?) {
        if (intent == null) return
        if (intent.action != Intent.ACTION_SEND && intent.action != Intent.ACTION_SEND_MULTIPLE) return
        val uris = LinkedHashSet<Uri>()
        if (intent.action == Intent.ACTION_SEND) {
            IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)?.let { uris.add(it) }
        } else {
            IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                ?.let { uris.addAll(it) }
        }
        // Some apps only put the video in the clip data.
        intent.clipData?.let { clip ->
            for (n in 0 until clip.itemCount) clip.getItemAt(n).uri?.let { uris.add(it) }
        }
        if (uris.isEmpty()) return
        BatchRunner.addShared(this, uris.toList())
        // Handled; stops a later recreation from adding them again.
        intent.action = null
    }

    // BatchRunner skips the "finished" notification if the screen is already visible.
    override fun onStart() {
        super.onStart()
        BatchRunner.uiVisible = true
    }

    override fun onStop() {
        BatchRunner.uiVisible = false
        super.onStop()
    }
}

/** Opens [uri] in whatever video player the phone offers (its default, or a chooser). */
private fun playVideo(ctx: Context, uri: Uri, mime: String) {
    val view = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, mime)
        // The player isn't us, so it needs a read grant for this particular file.
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    try {
        ctx.startActivity(view)
    } catch (_: Exception) {
        Toast.makeText(ctx, "No app found to play this video.", Toast.LENGTH_SHORT).show()
    }
}

/**
 * A card's preview picture. Shows a plain box until the thumbnail exists (or if it never does).
 * Tapping it ([onClick], when set) plays the video.
 */
@Composable
private fun Thumb(item: QueueItem, onClick: (() -> Unit)?) {
    val ctx = LocalContext.current
    val bitmap by produceState<ImageBitmap?>(null, item.id, item.hasThumb) {
        value = if (item.hasThumb) {
            withContext(Dispatchers.IO) {
                BitmapFactory.decodeFile(Thumbnails.file(ctx, item.id).absolutePath)?.asImageBitmap()
            }
        } else null
    }
    Box(
        Modifier
            .size(width = 112.dp, height = 72.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(Color.Black)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        bitmap?.let {
            // Fit, not Crop: shows the whole frame, so a portrait clip isn't cut off.
            Image(it, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
        }
    }
}

/** A frame rate for display: "30" for a whole number, otherwise two decimals ("29.97"). */
private fun formatFps(fps: Double): String {
    val whole = Math.round(fps)
    return if (Math.abs(fps - whole) < 0.005) whole.toString() else String.format(Locale.US, "%.2f", fps)
}

/**
 * The frame rate part of a card's info line. A rate that couldn't be read is never guessed at:
 * a queued video says it's unreadable, and a finished one also says what the encoder aimed for.
 */
private fun fpsLabel(i: SourceInfo, finished: Boolean): String = when {
    i.fpsReadable -> "${formatFps(i.fps)} fps"
    !finished -> "fps unreadable"
    i.targetedFps > 0 -> "Cannot read fps. Encoder targeted ${formatFps(i.targetedFps)} fps"
    else -> "Cannot read fps. Encoder kept the source rate"
}

@Composable
private fun QueueButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        contentPadding = PaddingValues(0.dp),
        modifier = Modifier.size(40.dp),
    ) { Text(label) }
}

@Composable
private fun QueueRow(
    item: QueueItem,
    busy: Boolean,
    maxDuration: Int,
    /** True when the Custom target is selected (its length cap isn't a board's rule). */
    isCustom: Boolean,
    minW: Int,
    minH: Int,
    /** The settings the next conversion would use; the split estimate is worked out from them. */
    settings: Settings,
    canMoveUp: Boolean = false,
    canMoveDown: Boolean = false,
    onUp: () -> Unit = {},
    onDown: () -> Unit = {},
    onRemove: () -> Unit = {},
    /** Called with the new number of extra splits when the card's - / + is pressed. */
    onExtraParts: (Int) -> Unit = {},
    /** false for cards in the Finished list. */
    showControls: Boolean = true,
) {
    val ctx = LocalContext.current
    val i = item.info

    // Tapping the thumbnail plays the video: the converted file on a Finished card, the original
    // on a queue card. Nothing to play if the file is gone or the original couldn't be read.
    val finishedCard = item.status == ItemStatus.DONE
    val playUri: Uri? = when {
        finishedCard -> item.savedUri
        item.probeFailed || i == null -> null
        else -> item.uri
    }

    Column(
        Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (!item.probeFailed) {
                Thumb(
                    item,
                    onClick = playUri?.let { u ->
                        {
                            // The file type is looked up only when tapped, not on every redraw.
                            val mime = if (finishedCard) "video/webm" else (ctx.contentResolver.getType(u) ?: "video/*")
                            playVideo(ctx, u, mime)
                        }
                    },
                )
                Spacer(Modifier.size(10.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(item.name, style = MaterialTheme.typography.titleSmall)
                when {
                    item.probeFailed -> Text(
                        "Couldn't read this video.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                    i == null -> Text("Reading video…", style = MaterialTheme.typography.bodySmall)
                    else -> {
                        val len = String.format(Locale.US, "%.1f", i.durationSec)
                        val state = when (item.status) {
                            ItemStatus.WAITING -> "Waiting"
                            ItemStatus.CONVERTING -> "Converting…"
                            ItemStatus.DONE -> "Done"
                            ItemStatus.FAILED -> "Failed"
                            ItemStatus.CANCELLED -> "Not converted"
                        }
                        Text(
                            "${i.width}×${i.height}, ${len}s, ${fpsLabel(i, finishedCard)}, ${if (i.hasAudio) "has audio" else "no audio"} · $state",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
            if (showControls) {
                if (!busy) {
                    QueueButton("▲", canMoveUp, onUp)
                    QueueButton("▼", canMoveDown, onDown)
                }
                // Removing is allowed during a run, except for the video being converted.
                QueueButton("✕", item.status != ItemStatus.CONVERTING, onRemove)
            }
        }

        if (i != null && item.status != ItemStatus.DONE) {
            val t = settings.tuning
            val minParts = Converter.partCount(i.durationSec, maxDuration, t)
            val maxParts = Converter.maxPartCount(i.durationSec, maxDuration, t)
            val est = Converter.estimate(i, settings, item.extraParts)
            val numParts = est.parts
            // Only when the board's limit forces the split. A clip already under the limit that
            // was split further with the stepper just shows its part count on the stepper row.
            if (minParts > 1) {
                val names = if (numParts == 2) {
                    "${Converter.partName(1, numParts)}, ${Converter.partName(2, numParts)}"
                } else {
                    "${Converter.partName(1, numParts)}, ${Converter.partName(2, numParts)}, … " +
                        Converter.partName(numParts, numParts)
                }
                Text(
                    if (isCustom) {
                        "Source is over the custom target length. It will be split to fit. ($names)"
                    } else {
                        "Will be split into $numParts files to fit the ${maxDuration}s limit ($names)."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary
                )
            }
            if ((minW > 0 || minH > 0) && (i.width < minW || i.height < minH)) {
                Text(
                    "Below this target's minimum (${minW}×${minH}): it will be scaled up, keeping its aspect ratio.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary
                )
            }

            // Splits: live estimate of what each part would get, and (for queued videos) the
            // buttons to add splits or take them away again, down to the minimum the board needs.
            val estimateText = buildString {
                if (numParts > 1) append("Each part about ${String.format(Locale.US, "%.0f", est.partSec)}s · ")
                append("about ${est.height}p at ${est.videoKbps} kbps video")
            }
            if (showControls) {
                val locked = busy || item.partsDone > 0
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (numParts == 1) "No split" else "$numParts parts",
                            style = MaterialTheme.typography.titleSmall
                        )
                        Text(estimateText, style = MaterialTheme.typography.bodySmall)
                    }
                    QueueButton("−", !locked && numParts > minParts) { onExtraParts(numParts - minParts - 1) }
                    QueueButton("+", !locked && numParts < maxParts) { onExtraParts(numParts - minParts + 1) }
                }
            } else {
                Text(estimateText, style = MaterialTheme.typography.bodySmall)
            }

            // Below the warning bitrate, detail will suffer. Not shown for an invalid custom limit.
            if (settings.limitBytes >= 256 * 1024L && est.videoKbps < t.warnVideoKbps) {
                Text(
                    "High-detail clips (e.g. live action) will look poor at this bitrate." +
                        if (showControls && numParts < maxParts) " Increased splitting recommended." else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary
                )
            }
        }

        item.detail?.let {
            if (item.status == ItemStatus.FAILED) {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            } else {
                Text(it, style = MaterialTheme.typography.bodySmall)
            }
        }

        item.savedUri?.let { saved ->
            TextButton(
                onClick = {
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "video/webm"
                        putExtra(Intent.EXTRA_STREAM, saved)
                        // Explicit ClipData so the read grant reliably reaches the receiving app,
                        // whether saved is a MediaStore Uri or a document Uri from the chosen folder.
                        clipData = ClipData.newRawUri("webm", saved)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    ctx.startActivity(Intent.createChooser(send, "Share webm"))
                },
            ) { Text("Share") }
        }
    }
}

@Composable
private fun TargetRow(
    selected: Boolean,
    enabled: Boolean,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick),
        verticalAlignment = Alignment.Top,
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Column(Modifier.padding(start = 8.dp, top = 2.dp, bottom = 6.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(subtitle, style = MaterialTheme.typography.bodySmall)
        }
    }
}

/**
 * A settings group that opens and closes when its header is tapped. While closed, the header
 * shows [summary] (the current choice), so you can see what's set without opening it. A
 * [summaryIsError] summary is drawn in the error colour, so a setting that blocks Convert is
 * still noticed while closed. The header stays tappable while a batch runs; the controls inside
 * are disabled by their own `enabled` flags as before.
 */
@Composable
private fun CollapsibleSection(
    title: String,
    summary: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    summaryIsError: Boolean = false,
    content: @Composable () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp)),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                if (!expanded) {
                    Text(
                        summary,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (summaryIsError) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text(if (expanded) "▲" else "▼", style = MaterialTheme.typography.titleSmall)
        }
        if (expanded) {
            Column(
                Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) { content() }
        }
    }
}

/** e.g. "/3/ /a/ /aco/ /adv/ /an/ /biz/ … (66 boards)" for the long list, the full list when short. */
private fun boardsSummary(t: Target): String {
    val shown = 6
    return if (t.boards.size <= shown) t.boardsLabel
    else t.boards.take(shown).joinToString(" ") { "/$it/" } + " … (${t.boards.size} boards)"
}

/** One line saying what a running batch is being converted with. */
private fun settingsLine(s: Settings): String {
    val mb = String.format(Locale.US, "%.2f", s.limitBytes / 1048576.0).trimEnd('0').trimEnd('.')
    val sound = if (s.keepAudio) "sound on" else "no sound"
    val effort = if (s.fastest) "Fastest" else if (s.fastEncode) "Speed" else "Quality"
    val codec = if (s.useVp9) "VP9" else "VP8"
    return "Converting with: $mb MB limit, up to ${s.maxDurationSec} s, $sound, up to ${s.targetFps} fps, $effort, $codec"
}

/**
 * The cards of the Holding Pen ([pen] true) or of the batch/queue (false). A video in a running
 * batch shows the settings it is being converted with; anything else follows [liveSettings].
 */
@Composable
private fun QueueCards(pen: Boolean, busy: Boolean, liveSettings: Settings) {
    val queue = BatchRunner.queue
    queue.forEachIndexed { idx, item ->
        if (item.inPen == pen) {
            key(item.id) {
                val s = if (busy && !item.inPen) {
                    item.settings?.copy(tuning = liveSettings.tuning) ?: liveSettings
                } else liveSettings
                QueueRow(
                    item = item,
                    busy = busy && !item.inPen,
                    maxDuration = s.maxDurationSec,
                    isCustom = s.isCustom,
                    minW = s.minWidth,
                    minH = s.minHeight,
                    settings = s,
                    canMoveUp = BatchRunner.canMove(idx, -1),
                    canMoveDown = BatchRunner.canMove(idx, 1),
                    onUp = { BatchRunner.move(idx, -1) },
                    onDown = { BatchRunner.move(idx, 1) },
                    onRemove = { BatchRunner.removeAt(idx) },
                    onExtraParts = { BatchRunner.setExtraParts(item.id, it) },
                )
            }
        }
    }
}

@Composable
fun App(onAbout: () -> Unit = {}) {
    val ctx = LocalContext.current

    // The queue, the finished list, progress and the log all live in BatchRunner (not here), so a
    // running batch survives the screen being closed. Settings below apply to every video in it.
    val queue = BatchRunner.queue
    val finished = BatchRunner.finished
    val busy = BatchRunner.busy
    val probing = BatchRunner.probing
    val copyingTotal = BatchRunner.copyingTotal
    val cancelling = BatchRunner.cancelling
    val paused = BatchRunner.paused
    val pauseRequest = BatchRunner.pauseRequest
    val notice = BatchRunner.notice
    val copyingDone = BatchRunner.copyingDone
    val progress = BatchRunner.progress
    val status = BatchRunner.status
    val summary = BatchRunner.summary
    val runTotal = BatchRunner.runTotal
    val logLines = BatchRunner.logLines

    // Index into TARGETS; TARGETS.size means "Custom".
    // The choices below are remembered between runs.
    val prefs = remember { ctx.getSharedPreferences("webmchan", Context.MODE_PRIVATE) }
    var targetIdx by remember { mutableIntStateOf(prefs.getInt("ui_target_idx", 0).coerceIn(0, TARGETS.size)) }
    var customMb by remember { mutableStateOf(prefs.getString("ui_custom_mb", "4") ?: "4") }
    var removeAudio by remember { mutableStateOf(prefs.getBoolean("ui_remove_audio", true)) }
    var fastEncode by remember { mutableStateOf(prefs.getBoolean("ui_fast_encode", false)) }
    var useVp9 by remember { mutableStateOf(prefs.getBoolean("ui_use_vp9", false)) }
    var fastest by remember { mutableStateOf(prefs.getBoolean("ui_fastest", false)) }
    // Index into FPS_PRESETS; FPS_PRESETS.size means "Custom".
    var fpsChoice by remember {
        mutableIntStateOf(prefs.getInt("ui_fps_choice", FPS_DEFAULT_INDEX).coerceIn(0, FPS_PRESETS.size))
    }
    var customFps by remember { mutableStateOf(prefs.getString("ui_custom_fps", "30") ?: "30") }
    LaunchedEffect(targetIdx, customMb, removeAudio, fastEncode, useVp9, fastest, fpsChoice, customFps) {
        prefs.edit()
            .putInt("ui_target_idx", targetIdx)
            .putString("ui_custom_mb", customMb)
            .putBoolean("ui_remove_audio", removeAudio)
            .putBoolean("ui_fast_encode", fastEncode)
            .putBoolean("ui_use_vp9", useVp9)
            .putBoolean("ui_fastest", fastest)
            .putInt("ui_fps_choice", fpsChoice)
            .putString("ui_custom_fps", customFps)
            .apply()
    }

    // Which settings groups are open. All start closed.
    var boardsOpen by remember { mutableStateOf(false) }
    var audioOpen by remember { mutableStateOf(false) }
    var fpsOpen by remember { mutableStateOf(false) }
    var encodingOpen by remember { mutableStateOf(false) }
    var codecOpen by remember { mutableStateOf(false) }
    var saveOpen by remember { mutableStateOf(false) }

    // Pass 1 has no real progress to report. Compose's built-in indeterminate bar relies on
    // Compose's own animation system, which some phones freeze (the "Animator duration scale"
    // developer option), leaving it static. This sweeps a value by hand instead, the same way
    // pass 2's real progress is just pushed in as plain numbers, so it moves regardless.
    var pass1Sweep by remember { mutableFloatStateOf(0f) }

    // Where output is saved. Empty custom-dir state means the default Download/WebMChan/.
    var outputLabel by remember { mutableStateOf(OutputStorage.displayName(ctx)) }
    var hasCustomDir by remember { mutableStateOf(OutputStorage.savedDirUri(ctx) != null) }
    var folderError by remember { mutableStateOf<String?>(null) }

    val logListState = rememberLazyListState()
    val clipboard = LocalClipboardManager.current

    // Keep the newest line in view.
    LaunchedEffect(logLines) {
        if (logLines.isNotEmpty()) logListState.scrollToItem(logLines.lastIndex)
    }

    // Hand-rolled sweep for pass 1 (see pass1Sweep above). Ping-pongs 0→1→0 every 1.2s.
    LaunchedEffect(progress < 0f || copyingTotal > 0) {
        if (progress < 0f || copyingTotal > 0) {
            var t = 0L
            while (isActive) {
                val phase = (t % 4000L) / 4000f
                pass1Sweep = if (phase < 0.5f) phase * 2f else (1f - phase) * 2f
                delay(16)
                t += 16
            }
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { picked ->
        BatchRunner.addVideos(ctx, picked)
    }

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

    // Android 13+ asks before an app may show notifications. We explain and ask every time you
    // convert until it's granted, then start either way: converting works without it, you just
    // won't see the progress notification. Android stops showing its own prompt after two
    // refusals; at that point the dialog offers to open the app's notification settings instead.
    var pendingSettings by remember { mutableStateOf<Settings?>(null) }
    var showNotificationExplainer by remember { mutableStateOf(false) }
    var explainerBlocked by remember { mutableStateOf(false) }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { _ ->
        pendingSettings?.let { BatchRunner.start(ctx, it) }
        pendingSettings = null
    }
    // Explains why before Android's own permission prompt appears.
    if (showNotificationExplainer) {
        val skip = {
            showNotificationExplainer = false
            pendingSettings?.let { BatchRunner.start(ctx, it) }
            pendingSettings = null
        }
        AlertDialog(
            onDismissRequest = skip,
            title = { Text("Notification permission") },
            text = {
                Text(
                    "Notification permission is required for background conversion. " +
                        "Without it, you'll have to stare at WebMChan until it's done." +
                        if (explainerBlocked) {
                            "\n\nAndroid won't show the permission prompt again, so you'll need to turn " +
                                "notifications on for WebMChan in Android's settings."
                        } else ""
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showNotificationExplainer = false
                    if (explainerBlocked) {
                        try {
                            ctx.startActivity(
                                Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                    .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, ctx.packageName)
                            )
                        } catch (_: Exception) {
                        }
                        // Don't lose the tap: start converting now.
                        pendingSettings?.let { BatchRunner.start(ctx, it) }
                        pendingSettings = null
                    } else {
                        ctx.getSharedPreferences("webmchan", Context.MODE_PRIVATE)
                            .edit().putBoolean("asked_notifications", true).apply()
                        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }) { Text(if (explainerBlocked) "Grant Notification Permission" else "Continue") }
            },
            dismissButton = { TextButton(onClick = skip) { Text("Skip") } },
        )
    }

    val target: Target? = TARGETS.getOrNull(targetIdx)
    val isCustom = target == null
    val limitKb: Int = target?.limitKb ?: ((customMb.toDoubleOrNull() ?: 0.0) * 1024).toInt()
    val maxDuration: Int = target?.maxDurationSec ?: Converter.DEFAULT_MAX_DURATION_SEC
    val minW: Int = target?.minWidth ?: 0
    val minH: Int = target?.minHeight ?: 0
    val audioAllowed: Boolean = target?.audioAllowed ?: true
    val effectiveRemoveAudio = removeAudio || !audioAllowed

    // The frame rate cap. A Custom entry that isn't a whole number in range can't be used: Convert
    // stays disabled until it is fixed, and in the meantime the cards use the default.
    val isCustomFps = fpsChoice >= FPS_PRESETS.size
    val customFpsValue: Int? = customFps.trim().toIntOrNull()?.takeIf { it in Converter.MIN_FPS..Converter.MAX_FPS }
    val fpsOk = !isCustomFps || customFpsValue != null
    val targetFps: Int = if (!isCustomFps) FPS_PRESETS[fpsChoice] else (customFpsValue ?: Converter.DEFAULT_FPS)

    // What the next conversion would use. The queue cards work their split estimates out from it,
    // so they follow the board, size limit, audio, effort, frame rate and Advanced settings as they change.
    val cardSettings = Settings(
        limitKb * 1024L, maxDuration, !effectiveRemoveAudio, minW, minH,
        fastEncode = fastEncode,
        useVp9 = useVp9,
        fastest = fastest,
        targetFps = targetFps,
        tuning = TuningStore.current,
        isCustom = isCustom,
    )

    // What each closed settings group shows as its current choice.
    val limitOk = limitKb >= 256
    val boardsSummaryText = if (target != null) {
        "${target.title}\n${boardsSummary(target)}"
    } else if (limitOk) {
        "Custom · $customMb MB limit, no minimum resolution"
    } else {
        "Custom · enter a limit of at least 0.25 MB"
    }
    val audioSummaryText = when {
        !audioAllowed -> "Off · this target doesn't allow sound"
        removeAudio -> "Off · output has no sound"
        else -> "On · sound is kept"
    }
    val fpsSummaryText = when {
        isCustomFps && customFpsValue == null ->
            "Custom · enter a whole number from ${Converter.MIN_FPS} to ${Converter.MAX_FPS}"
        isCustomFps -> "Custom · up to $customFpsValue fps"
        fpsChoice == FPS_DEFAULT_INDEX -> "${FPS_PRESETS[fpsChoice]} fps (default)"
        else -> "${FPS_PRESETS[fpsChoice]} fps"
    }
    val encodingSummaryText = if (fastest) "Fastest" else if (fastEncode) "Speed" else "Quality (default)"
    val codecSummaryText = if (useVp9) "VP9" else "VP8 (default)"

    // The Holding Pen: videos added while a batch is running (or paused) wait there. While it has
    // videos, the settings on screen are for them; the running batch keeps its own.
    val penAny = queue.any { it.inPen }
    val penCount = queue.count { it.inPen && it.info != null }
    val penMode = busy && penAny
    val settingsLocked = busy && !penMode

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            Text(
                "WebMChan - 4chan WebM converter",
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onAbout) { Text("About") }
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { picker.launch(arrayOf("video/*")) },
                enabled = !cancelling, // allowed during a run: new videos join it
                modifier = Modifier.weight(1f),
            ) { Text(if (queue.isEmpty()) "Choose videos" else "Add videos") }
            if (queue.isNotEmpty()) {
                OutlinedButton(
                    onClick = { if (penMode) BatchRunner.clearPen() else BatchRunner.clearQueue() },
                    enabled = !busy || penMode,
                ) { Text(if (penMode) "Clear pen" else "Clear") }
            }
        }

        // Only while a shared video is being copied in; gone as soon as it's in the queue.
        if (copyingTotal > 0) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    if (copyingTotal == 1) "Copying shared video…"
                    else "Copying shared videos… ${minOf(copyingDone + 1, copyingTotal)} of $copyingTotal",
                    style = MaterialTheme.typography.bodyMedium,
                )
                // Hand-driven (pass1Sweep), not Compose's own indeterminate animation: see pass1Sweep.
                LinearProgressIndicator(progress = { pass1Sweep }, modifier = Modifier.fillMaxWidth())
            }
        }

        if (penMode) {
            Text("Holding pen", style = MaterialTheme.typography.titleMedium)
            Text(
                "Videos added during a run wait here. Change the settings below, then add them to the running batch.",
                style = MaterialTheme.typography.bodySmall
            )
            QueueCards(pen = true, busy = busy, liveSettings = cardSettings)
        } else {
            QueueCards(pen = false, busy = busy, liveSettings = cardSettings)
            if (queue.size > 1) {
                Text(
                    "Videos are converted in this order, all with the settings below.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        CollapsibleSection(
            title = "Target boards",
            summary = boardsSummaryText,
            expanded = boardsOpen,
            onToggle = { boardsOpen = !boardsOpen },
            summaryIsError = isCustom && !limitOk,
        ) {
            TARGETS.forEachIndexed { idx, t ->
                TargetRow(
                    selected = targetIdx == idx,
                    enabled = !settingsLocked,
                    title = t.title,
                    subtitle = t.boardsLabel,
                ) {
                    targetIdx = idx
                    if (t.audioAllowed) removeAudio = false
                }
            }
            TargetRow(
                selected = isCustom,
                enabled = !settingsLocked,
                title = "Custom",
                subtitle = "Your own size limit, no minimum resolution",
            ) {
                targetIdx = TARGETS.size
                removeAudio = false
            }
            if (isCustom) {
                OutlinedTextField(
                    value = customMb,
                    onValueChange = { customMb = it },
                    label = { Text("Limit in MB") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    enabled = !settingsLocked,
                )
            }
        }

        CollapsibleSection(
            title = "Sound",
            summary = audioSummaryText,
            expanded = audioOpen,
            onToggle = { audioOpen = !audioOpen },
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column(Modifier.weight(1f)) {
                    Text(
                        when {
                            !audioAllowed -> "This target doesn't allow sound; the space goes to video quality."
                            removeAudio -> "Sound is off: the output has no sound and the space goes to video quality."
                            else -> "Sound is on: kept in the output (starts at 64 kbps). Only targets marked \"Audio OK\" allow it."
                        },
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                // On = sound kept. (removeAudio is the opposite way round internally.)
                Switch(
                    checked = !effectiveRemoveAudio,
                    onCheckedChange = { removeAudio = !it },
                    enabled = !settingsLocked && audioAllowed,
                )
            }
        }

        CollapsibleSection(
            title = "Frame rate",
            summary = fpsSummaryText,
            expanded = fpsOpen,
            onToggle = { fpsOpen = !fpsOpen },
            summaryIsError = !fpsOk,
        ) {
            Text(
                "The most frames per second the output may have. A video that's already slower keeps its own rate; frame rates are never raised.",
                style = MaterialTheme.typography.bodySmall
            )
            TargetRow(
                selected = fpsChoice == 0,
                enabled = !settingsLocked,
                title = "24 fps",
                subtitle = "Film standard. Fewer frames means more detail in each one on a tight limit. A 30 or 60 fps source loses frames unevenly, so motion can look slightly juddery.",
            ) { fpsChoice = 0 }
            TargetRow(
                selected = fpsChoice == 1,
                enabled = !settingsLocked,
                title = "30 fps (default)",
                subtitle = "Recommended. A good balance of smooth motion and detail.",
            ) { fpsChoice = 1 }
            TargetRow(
                selected = fpsChoice == 2,
                enabled = !settingsLocked,
                title = "60 fps",
                subtitle = "Smoother motion, but each second needs twice the bits for the same detail. On small size limits expect smaller frames and a rougher picture.",
            ) { fpsChoice = 2 }
            TargetRow(
                selected = isCustomFps,
                enabled = !settingsLocked,
                title = "Custom",
                subtitle = "Your own limit, ${Converter.MIN_FPS} to ${Converter.MAX_FPS} fps.",
            ) { fpsChoice = FPS_PRESETS.size }
            if (isCustomFps) {
                OutlinedTextField(
                    value = customFps,
                    onValueChange = { customFps = it },
                    label = { Text("Frame rate limit (fps)") },
                    isError = customFpsValue == null,
                    supportingText = {
                        if (customFpsValue == null) {
                            Text("Enter a whole number from ${Converter.MIN_FPS} to ${Converter.MAX_FPS}.")
                        }
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    enabled = !settingsLocked,
                )
            }
        }

        CollapsibleSection(
            title = "Encoding",
            summary = encodingSummaryText,
            expanded = encodingOpen,
            onToggle = { encodingOpen = !encodingOpen },
        ) {
            TargetRow(
                selected = !fastEncode && !fastest,
                enabled = !settingsLocked,
                title = "Quality (default)",
                subtitle = "Best detail, slowest transcode. Highly recommended for anything 'live action'.",
            ) { fastEncode = false; fastest = false }
            TargetRow(
                selected = fastEncode && !fastest,
                enabled = !settingsLocked,
                title = "Speed",
                subtitle = "Faster than Quality mode, only slightly softer detail.",
            ) { fastEncode = true; fastest = false }
            TargetRow(
                selected = fastest,
                enabled = !settingsLocked,
                title = "Fastest",
                subtitle = "Fastest, softest detail. Lower effort transcoding and smaller frames. Only recommended for low detail videos. ",
            ) { fastest = true }
        }

        CollapsibleSection(
            title = "Codec",
            summary = codecSummaryText,
            expanded = codecOpen,
            onToggle = { codecOpen = !codecOpen },
        ) {
            TargetRow(
                selected = !useVp9,
                enabled = !settingsLocked,
                title = "VP8 (default)",
                subtitle = "Recommended default. Fastest. It just works.",
            ) { useVp9 = false }
            TargetRow(
                selected = useVp9,
                enabled = !settingsLocked,
                title = "VP9",
                subtitle = "Much slower to convert. Newer standard. May look better. Low compatibility on older devices. Mostly included here as 'future proofing'",
            ) { useVp9 = true }
        }

        CollapsibleSection(
            title = "Save location",
            summary = outputLabel,
            expanded = saveOpen,
            onToggle = { saveOpen = !saveOpen },
        ) {
            Text(outputLabel, style = MaterialTheme.typography.bodyMedium)
            Text(
                "Videos are saved in a WebMChan folder inside the location you choose. " +
                    "If it becomes unavailable (e.g. SD card removed), they go to Download/WebMChan instead.",
                style = MaterialTheme.typography.bodySmall
            )
            folderError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { folderPicker.launch(null) },
                    enabled = !busy,
                    modifier = Modifier.weight(1f),
                ) { Text("Choose folder") }
                if (hasCustomDir) {
                    OutlinedButton(
                        onClick = {
                            OutputStorage.clearDir(ctx)
                            outputLabel = OutputStorage.displayName(ctx)
                            hasCustomDir = false
                            folderError = null
                        },
                        enabled = !busy,
                        modifier = Modifier.weight(1f),
                    ) { Text("Use default") }
                }
            }
        }

        val pending = queue.count { it.info != null }
        val canGo = pending > 0 && probing == 0 && copyingTotal == 0 && limitOk && fpsOk && !busy
        val penReady = penCount > 0 && probing == 0 && copyingTotal == 0 && limitOk && fpsOk && !cancelling

        // The running batch's progress and controls (or, when it is paused, the Resume controls).
        val progressBlock: @Composable () -> Unit = {
            if (paused) {
                Text("Paused", style = MaterialTheme.typography.titleSmall)
                Text(
                    notice ?: "Nothing is converting. Press Resume to carry on.",
                    style = MaterialTheme.typography.bodySmall
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { BatchRunner.resume(ctx, cardSettings) },
                        enabled = probing == 0 && !cancelling,
                        modifier = Modifier.weight(1f),
                    ) { Text("Resume") }
                    OutlinedButton(
                        onClick = { BatchRunner.cancelAll() },
                        modifier = Modifier.weight(1f),
                    ) { Text("Cancel all") }
                }
            } else {
            if (progress < 0f) {
                LinearProgressIndicator(progress = { pass1Sweep }, modifier = Modifier.fillMaxWidth())
            } else {
                LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
            }
            Text(status, style = MaterialTheme.typography.bodySmall)
            when (pauseRequest) {
                PauseRequest.NOW -> Text("Pausing…", style = MaterialTheme.typography.bodySmall)
                PauseRequest.AFTER -> Text(
                    "Will pause when this video has finished.",
                    style = MaterialTheme.typography.bodySmall
                )
                PauseRequest.NONE -> {}
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { BatchRunner.pauseNow() },
                    enabled = pauseRequest != PauseRequest.NOW,
                    modifier = Modifier.weight(1f),
                ) { Text("Pause now") }
                OutlinedButton(
                    onClick = { BatchRunner.pauseAfter() },
                    enabled = pauseRequest != PauseRequest.NOW,
                    modifier = Modifier.weight(1f),
                ) { Text(if (pauseRequest == PauseRequest.AFTER) "Undo pause" else "Pause after this conversion") }
            }
            if (runTotal > 1) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { BatchRunner.skipCurrent() },
                        modifier = Modifier.weight(1f),
                    ) { Text("Skip current") }
                    OutlinedButton(
                        onClick = { BatchRunner.cancelAll() },
                        modifier = Modifier.weight(1f),
                    ) { Text("Cancel all") }
                }
            } else {
                OutlinedButton(
                    onClick = { BatchRunner.cancelAll() },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Cancel") }
            }
            Text(
                "You can lock the screen or leave the app while this runs. A notification shows progress.",
                style = MaterialTheme.typography.bodySmall
            )
            }
        }

        if (penMode) {
            Button(
                onClick = { BatchRunner.addPenToRun(cardSettings) },
                enabled = penReady,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (penCount == 1) "Add 1 video to running batch" else "Add $penCount videos to running batch") }

            progressBlock()

            Text("Running batch", style = MaterialTheme.typography.titleMedium)
            queue.firstNotNullOfOrNull { if (!it.inPen) it.settings else null }?.let {
                Text(settingsLine(it), style = MaterialTheme.typography.bodySmall)
            }
            QueueCards(pen = false, busy = busy, liveSettings = cardSettings)
        } else {
        Button(
            onClick = {
                val settings = Settings(
                    limitKb * 1024L, maxDuration, !effectiveRemoveAudio, minW, minH,
                    fastEncode = fastEncode,
                    useVp9 = useVp9,
                    fastest = fastest,
                    targetFps = targetFps,
                    tuning = TuningStore.current,
                    isCustom = isCustom,
                )
                val needsAsk = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) !=
                    PackageManager.PERMISSION_GRANTED
                if (needsAsk) {
                    // Asked before and Android won't show the prompt again = refused twice.
                    val activity = ctx as? Activity
                    explainerBlocked = prefs.getBoolean("asked_notifications", false) &&
                        activity != null &&
                        !ActivityCompat.shouldShowRequestPermissionRationale(
                            activity, Manifest.permission.POST_NOTIFICATIONS
                        )
                    pendingSettings = settings
                    showNotificationExplainer = true
                } else {
                    BatchRunner.start(ctx, settings)
                }
            },
            enabled = canGo,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (pending > 1) "Convert $pending videos" else "Convert") }

            if (busy) progressBlock()
        }

        val lines = logLines
        if (lines.isNotEmpty()) {
            Text("Log", style = MaterialTheme.typography.titleSmall)
            LazyColumn(
                state = logListState,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(220.dp)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
                    .padding(8.dp),
            ) {
                items(lines.size) { idx ->
                    Text(
                        lines[idx],
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        lineHeight = 14.sp,
                    )
                }
            }
            OutlinedButton(
                onClick = { clipboard.setText(AnnotatedString(lines.joinToString("\n"))) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Copy log") }
        }

        if (runTotal > 1) summary?.let { Text(it, style = MaterialTheme.typography.titleSmall) }

        // Finished videos move here from the queue, so results are right where you're looking.
        if (finished.isNotEmpty()) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Finished (${finished.size})",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                OutlinedButton(onClick = { BatchRunner.clearFinished() }) { Text("Clear finished") }
            }
            finished.forEach { item ->
                key(item.id) {
                    QueueRow(
                        item = item,
                        busy = busy,
                        maxDuration = maxDuration,
                        isCustom = isCustom,
                        minW = minW,
                        minH = minH,
                        settings = cardSettings,
                        showControls = false,
                    )
                }
            }
            val savedUris = finished.mapNotNull { it.savedUri }
            if (savedUris.size > 1) {
                OutlinedButton(
                    onClick = {
                        val send = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                            type = "video/webm"
                            putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(savedUris))
                            clipData = ClipData.newRawUri("webm", savedUris[0]).also { cd ->
                                savedUris.drop(1).forEach { cd.addItem(ClipData.Item(it)) }
                            }
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        ctx.startActivity(Intent.createChooser(send, "Share webm files"))
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Share all (${savedUris.size})") }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}
