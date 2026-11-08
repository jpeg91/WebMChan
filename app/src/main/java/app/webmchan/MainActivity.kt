// #main
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
import android.os.SystemClock
import android.os.Bundle
import android.os.PowerManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.sin
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/** The frame rate caps offered as presets. Custom follows them in the radio list. */
private val FPS_PRESETS = listOf(10, 15, 24, 30, 60)

/** Audio Mode: how often the (still) picture is drawn. */
private val AUDIO_FPS_PRESETS = listOf(1, 2, 5, 10, 24)
private const val AUDIO_FPS_DEFAULT_INDEX = 1

/** Index into [FPS_PRESETS] of the default (30). */
private const val FPS_DEFAULT_INDEX = 3

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Crash viewer: a crash is written to a file, and the next launch shows it as plain text instead of
        // starting the app (open the app twice to see it), so it can be copied and reported without adb.
        val crashFile = java.io.File(filesDir, "last_crash.txt")
        if (crashFile.exists()) {
            val report = try { crashFile.readText() } catch (_: Exception) { "(couldn't read the crash file)" }
            crashFile.delete()
            val view = android.widget.TextView(this).apply {
                text = "WebMChan crashed last time.\n\n" +
                    "Open the app again to carry on. If it keeps crashing, uninstall and reinstall it. " +
                    "If it still won't stop, please report it on GitHub and include the text below " +
                    "(long-press it to select and copy).\n\n$report"
                textSize = 11f
                setTextIsSelectable(true)
                setTextColor(android.graphics.Color.WHITE)
                setPadding(24, 64, 24, 24)
            }
            setContentView(android.widget.ScrollView(this).apply {
                setBackgroundColor(android.graphics.Color.BLACK)
                addView(view)
            })
            return
        }
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try { crashFile.writeText(android.util.Log.getStackTraceString(error)) } catch (_: Throwable) { }
            previousHandler?.uncaughtException(thread, error)
        }
        // Bring back the queue and Finished list from last time (does nothing if already loaded).
        BatchRunner.restore(applicationContext)
        // The on/off switches from the Settings page.
        AppSettings.init(applicationContext)
        // The fitting numbers from About > Advanced (defaults unless changed).
        TuningStore.load(applicationContext)
        // Videos shared to us from another app. Skipped when the system recreates the activity,
        // so videos the user already removed don't come back.
        if (savedInstanceState == null) handleShare(intent)
        setContent {
            // The look (black, white and red) lives in Theme.kt. It is the same whatever the
            // phone's own light/dark or wallpaper colours are.
            P5Theme {
                var showMenu by remember { mutableStateOf(false) }
                var showHistory by remember { mutableStateOf(false) }
                var showSettings by remember { mutableStateOf(false) }
                var showLog by remember { mutableStateOf(false) }
                var showAbout by remember { mutableStateOf(false) }
                var showAdvanced by remember { mutableStateOf(false) }
                var showCache by remember { mutableStateOf(false) }
                // Keeps the screen on while a run is going (and the app is open), if the Settings page says so.
                val keepOn = AppSettings.get(AppToggle.KEEP_SCREEN_ON) && BatchRunner.busy && !BatchRunner.paused
                androidx.compose.runtime.DisposableEffect(keepOn) {
                    if (keepOn) window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    onDispose { window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
                }
                // Every screen, including the main one, wraps itself in its own P5Background, which
                // also keeps it clear of the status and navigation bars.
                Box {
                    // App stays composed underneath, so opening a page doesn't lose the scroll
                    // position or the settings. The pages stack: Menu, then History, Log or About on
                    // top of it (and Advanced on top of About), so back returns to the one before.
                    P5Background { App(onMenu = { showMenu = true }, onOtherMode = { AccentMode.audio = true }) }
                    // Audio Mode: the same page, adapted, on top of the video one. AccentMode.audio is
                    // both "this screen is showing" and the switch that turns the theme purple (princetheme).
                    if (AccentMode.audio) {
                        P5Background {
                            App(
                                onMenu = { showMenu = true },
                                audio = true,
                                onExitAudio = { AccentMode.audio = false },
                                onOtherMode = { AccentMode.audio = false },
                            )
                        }
                    }
                    if (showMenu) {
                        P5Background {
                            MenuScreen(
                                onBack = { showMenu = false },
                                onHistory = { showHistory = true },
                                onSettings = { showSettings = true },
                                onLog = { showLog = true },
                                onAbout = { showAbout = true },
                                onSwitchMode = {
                                    AccentMode.audio = !AccentMode.audio
                                    showMenu = false
                                },
                            )
                        }
                    }
                    if (showHistory) {
                        P5Background { HistoryScreen(onBack = { showHistory = false }) }
                    }
                    if (showSettings) {
                        P5Background { SettingsScreen(onBack = { showSettings = false }) }
                    }
                    if (showLog) {
                        P5Background { LogScreen(onBack = { showLog = false }) }
                    }
                    if (showAbout) {
                        P5Background {
                            AboutScreen(onBack = { showAbout = false }, onAdvanced = { showAdvanced = true })
                        }
                    }
                    if (showAdvanced) {
                        P5Background { AdvancedScreen(onBack = { showAdvanced = false }, onCache = { showCache = true }) }
                    }
                    if (showCache) {
                        P5Background { CacheScreen(onBack = { showCache = false }) }
                    }
                    // The video editor, opened from a Holding Pen card. A page of its own, on top.
                    val editingId = BatchRunner.editingId
                    if (editingId >= 0L) {
                        P5Background(base = P5.RedDeep) { EditorScreen(itemId = editingId, onClose = { BatchRunner.closeEditor() }) }
                    }
                    // Audio Mode's trim page and video-frame picker, opened from an audio card's /// menu.
                    val audioTrimId = BatchRunner.audioTrimId
                    if (audioTrimId >= 0L) {
                        P5Background(base = P5.RedDeep) { AudioTrimScreen(itemId = audioTrimId, onClose = { BatchRunner.closeAudioTrim() }) }
                    }
                    val framePickId = BatchRunner.framePickId
                    if (framePickId >= 0L) {
                        P5Background(base = P5.RedDeep) { FramePickScreen(itemId = framePickId, onClose = { BatchRunner.closeFramePicker() }) }
                    }
                    // The thumbnail picker, opened from the same card button. Also a page of its own, on top.
                    val thumbPickId = BatchRunner.thumbPickId
                    if (thumbPickId >= 0L) {
                        P5Background(base = P5.RedDeep) {
                            ThumbnailPickerScreen(itemId = thumbPickId, onClose = { BatchRunner.closeThumbPicker() })
                        }
                    }
                }
            }
        }
    }

    // Sharing again while the app is open arrives here (launchMode singleTask).
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
        // Shared audio opens Audio Mode (its own screen); videos go to the video screen as before.
        val audio = intent.type?.startsWith("audio/") == true
        AccentMode.audio = audio
        BatchRunner.addShared(this, uris.toList(), audio = audio)
        // Handled; stops a later recreation from adding them again.
        intent.action = null
    }

    // BatchRunner skips the "finished" notification if the screen is already visible.
    override fun onStart() {
        super.onStart()
        BatchRunner.uiVisible = true
        // Opening the app is seeing the last run's finished and failed notifications.
        ConvertService.clearResults(this)
    }

    override fun onStop() {
        BatchRunner.uiVisible = false
        super.onStop()
    }
}

/** Opens [uri] in whatever video player the phone offers (its default, or a chooser). */
private fun playVideo(ctx: Context, uri: Uri, mime: String) {
    // A shared video's copy in app storage has a file:// address, which Android won't pass to another
    // app; the FileProvider turns it into a temporary content:// link to the same file (no copy made).
    val shareable = if (uri.scheme == "file") {
        try {
            androidx.core.content.FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", java.io.File(uri.path ?: ""))
        } catch (_: Exception) {
            Toast.makeText(ctx, "Couldn't open this video.", Toast.LENGTH_SHORT).show()
            return
        }
    } else uri
    val view = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(shareable, mime)
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
    // The pick is a key too: choosing another frame rewrites the same file, and the card has to reload it.
    // An Audio Mode card that isn't converted yet shows its own picture (the first one), not a video frame.
    val picture = if (item.status != ItemStatus.DONE) item.audio?.images?.firstOrNull()?.path else null
    val bitmap by produceState<ImageBitmap?>(null, item.id, item.hasThumb, item.thumbSec, picture) {
        value = if (item.audio != null && item.status != ItemStatus.DONE) {
            picture?.let { path -> withContext(Dispatchers.IO) { BitmapFactory.decodeFile(path)?.asImageBitmap() } }
        } else if (item.hasThumb) {
            withContext(Dispatchers.IO) {
                BitmapFactory.decodeFile(Thumbnails.file(ctx, item.id).absolutePath)?.asImageBitmap()
            }
        } else null
    }
    Box(
        Modifier
            .size(width = 112.dp, height = 72.dp)
            .clip(CutCornerShape(topStart = 10.dp, bottomEnd = 10.dp))
            .background(Color.Black)
            .border(1.5.dp, P5.Grey, CutCornerShape(topStart = 10.dp, bottomEnd = 10.dp))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        bitmap?.let {
            // Fit, not Crop: shows the whole frame, so a portrait clip isn't cut off.
            Image(it, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
        }
        // A small play mark in the bottom-left corner says the thumbnail can be tapped. ► is a
        // plain text symbol (not the ▶ emoji), so it stays white-on-black on every device.
        if (onClick != null) {
            Text(
                "►",
                color = P5.White,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 6.dp, bottom = 5.dp)
                    .background(Color.Black.copy(alpha = 0.65f), CutCornerShape(3.dp))
                    .padding(horizontal = 4.dp, vertical = 1.dp),
            )
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

/**
 * Which stage the running video is at, worked out from the status text the converter reports
 * ("Attempt 1/4, pass 2/2: encoding ...") and the progress (below 0 = the stage can't say).
 */
private fun runStage(status: String, progress: Float): RunLook = when {
    status.contains("Encoding audio") -> if (progress >= 1f) RunLook.FINISHING else RunLook.ENCODING
    status.contains("pass 1/2") -> RunLook.ANALYSING
    status.contains("pass 2/2") -> if (progress >= 1f) RunLook.FINISHING else RunLook.ENCODING
    status.endsWith("Done") -> RunLook.FINISHING
    else -> RunLook.PREPARING // Starting, copying the video, checking the audio
}

/** How fast the light runs round the border at each stage, in laps per second. */
private fun lapsPerSecond(look: RunLook): Float = when (look) {
    RunLook.PREPARING -> 1f / 6f
    RunLook.ANALYSING -> 1f / 4.4f
    RunLook.ENCODING -> 1f / 3f
    RunLook.FINISHING -> 1f / 1.2f
    RunLook.PAUSING -> 1f / 9f
}

/** Plays a card's one-off effect by hand (a timer, not Compose animation, so it ignores the animation-scale setting). */
private suspend fun playCardEvent(fx: CardFx, kind: CardEventKind) {
    val done = kind == CardEventKind.DONE
    val seconds = if (done) 0.8f else 0.9f
    fx.flashColor = if (done) P5.White else P5.Amber
    val start = System.nanoTime()
    try {
        while (true) {
            val t = (System.nanoTime() - start) / 1_000_000_000f
            if (t >= seconds) break
            val k = t / seconds
            if (done) {
                fx.flash = (1f - k) * (1f - k) * 0.85f
            } else {
                fx.flash = (1f - k) * 0.5f
                // Shakes sideways six times, dying away.
                fx.shakeDp = sin(t * 2f * PI.toFloat() * 6f) * 12f * (1f - k)
            }
            delay(16)
        }
    } finally {
        fx.flash = 0f
        fx.shakeDp = 0f
    }
}

/** The big percentage on the card being converted. Reads the progress itself, so only this redraws as it changes. */
@Composable
private fun RunPercent(item: QueueItem) {
    val stage = runStage(BatchRunner.status, BatchRunner.progress)
    val value: Int? = if (stage == RunLook.ENCODING || stage == RunLook.FINISHING) {
        // Just the part being encoded (a split video starts again from 0% for each part).
        (BatchRunner.progress.coerceIn(0f, 1f) * 100f).toInt()
    } else null
    // The hourglass (no percentage yet) stands still while the video is paused.
    P5Percent(value, frozen = BatchRunner.pausedItemId == item.id || AppSettings.get(AppToggle.ANIMATIONS_OFF))
}

@Composable
private fun QueueButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    // The remove button (✕) gets the red accent.
    P5SquareButton(label, onClick, enabled, accent = label == "✕", size = 38.dp)
}

@Composable
internal fun QueueRow(
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
    /** Queue cards offer "Return to holding pen". */
    showReturn: Boolean = false,
    onReturn: () -> Unit = {},
    /** Opens the editor for this card. Only Holding Pen cards offer it (see [QueueItem.inPen]). */
    onEdit: () -> Unit = {},
    /** Opens the thumbnail picker for this card. Also only for Holding Pen cards, from the same menu as Edit. */
    onThumbnail: () -> Unit = {},
    /** The video that will be converted next: gets a NEXT tag. */
    isNext: Boolean = false,
    /**
     * Waiting videos while a batch runs: where the soft band moving down the card is (0..1 per
     * pass). One shared clock for all the cards, so the bands move down together.
     */
    sweep: (() -> Float)? = null,
    /** A card on the History page: its Remove button takes it out of History, not out of Finished. */
    inHistory: Boolean = false,
    /** History cards only: whether the source video can still be read (see [BatchRunner.historySourceOk]). */
    sourceAvailable: Boolean = true,
) {
    val ctx = LocalContext.current
    val isAudio = item.audio != null
    // Audio Mode: picking pictures for the card (added to its pictures, or replacing them).
    var appendPictures by remember { mutableStateOf(true) }
    // Audio Mode: the dialog that edits the words of the card's title card.
    var showCardText by remember { mutableStateOf(false) }
    var cardTextEdit by remember { mutableStateOf("") }
    val picturePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (uris.isNotEmpty()) BatchRunner.setAudioImages(ctx, item.id, uris, appendPictures)
    }
    // The video as it will be converted: an edited card (cuts, crop) shows, and is planned with, the
    // edited length and size. The source's own are under Details. Finished cards never have an edit.
    val sourceInfo = item.info
    val i = if (item.edit != null) sourceInfo?.let { item.edit.effectiveInfo(it) } else sourceInfo
    // Whether the card's extra detail is open (see "Details" at the bottom of the card).
    var expanded by remember { mutableStateOf(false) }
    // The Rename and Delete file dialogs of a Finished card.
    val scope = rememberCoroutineScope()
    var showRename by remember { mutableStateOf(false) }
    var renameText by remember { mutableStateOf("") }
    var renameError by remember { mutableStateOf<String?>(null) }
    var renaming by remember { mutableStateOf(false) }
    var showDelete by remember { mutableStateOf(false) }
    var showRemove by remember { mutableStateOf(false) }
    // The dialog that sets the name the converted file will be saved under (Holding Pen, queue, converting).
    var showNameEdit by remember { mutableStateOf(false) }
    var nameEditText by remember { mutableStateOf("") }
    val removeCard = { if (inHistory) BatchRunner.removeFromHistory(item.id) else BatchRunner.removeFinished(item.id) }

    // Tapping the thumbnail plays the video: the converted file on a Finished card, the original
    // on a queue card. Nothing to play if the file is gone or the original couldn't be read.
    val finishedCard = item.status == ItemStatus.DONE
    // Only a card in the Holding Pen can be edited or given a thumbnail; it is locked once it is sent to the
    // queue. The "///" button (a small menu: Edit, Thumbnail) sits where a converting card shows its percentage.
    val canEdit = item.inPen && !finishedCard && !inHistory && i != null && !item.probeFailed
    val playUri: Uri? = when {
        finishedCard -> item.savedUri
        item.probeFailed || i == null -> null
        else -> item.uri
    }

    // The stripe down the card's edge says where the video is: red while converting, white once done.
    val accent = when (item.status) {
        ItemStatus.CONVERTING -> P5.Red
        ItemStatus.DONE -> P5.White
        ItemStatus.FAILED -> P5.Amber
        ItemStatus.CANCELLED -> P5.GreyDim
        ItemStatus.WAITING -> P5.RedDeep
    }
    // The card being converted has light running round its border, so it stands out from the
    // queue and shows what stage the work is at (see RunLook). Everything here is driven by hand
    // with plain timers (like the progress sweep, see pass1Sweep) rather than Compose's animation
    // system, so it runs even with the phone's animations turned off.
    val converting = item.status == ItemStatus.CONVERTING
    // After Pause Now the card stays up here with the light held still, until the batch resumes.
    val pausedHere = item.id == BatchRunner.pausedItemId
    val look by remember {
        derivedStateOf {
            if (BatchRunner.pauseRequest == PauseRequest.NOW) RunLook.PAUSING
            else runStage(BatchRunner.status, BatchRunner.progress)
        }
    }
    var runPhase by remember { mutableFloatStateOf(0f) }
    // Moves only while converting, so when paused it stops where it is and carries on from there.
    // The Settings page's "Animations off": no running light, no flash or shake, a still hourglass.
    val still = AppSettings.get(AppToggle.ANIMATIONS_OFF)
    LaunchedEffect(converting, still) {
        if (converting && !still) {
            var last = System.nanoTime()
            while (isActive) {
                delay(16)
                val now = System.nanoTime()
                runPhase = (runPhase + (now - last) / 1_000_000_000f * lapsPerSecond(look)) % 1f
                last = now
            }
        }
    }
    // A new attempt (the last one came out over the size limit) flashes the light amber.
    val attempt by remember {
        derivedStateOf { Regex("Attempt (\\d+)/").find(BatchRunner.status)?.groupValues?.get(1)?.toIntOrNull() ?: 0 }
    }
    var amber by remember { mutableFloatStateOf(0f) }
    var lastAttempt by remember { mutableIntStateOf(0) }
    LaunchedEffect(attempt, converting, still) {
        if (converting && !still && attempt > 1 && attempt > lastAttempt) {
            val start = System.nanoTime()
            try {
                while (true) {
                    val t = (System.nanoTime() - start) / 1_300_000_000f
                    if (t >= 1f) break
                    amber = 1f - t
                    delay(16)
                }
            } finally {
                amber = 0f
            }
        }
        if (attempt > 0) lastAttempt = attempt
    }
    val runBorder = remember { RunBorder(phase = { runPhase }, look = { look }, amber = { amber }) }

    // A flash when this card shows up as a finished video, a shake when it shows up as a failure.
    val fx = remember { CardFx() }
    LaunchedEffect(item.id) {
        val ev = BatchRunner.cardEvents[item.id] ?: return@LaunchedEffect
        BatchRunner.cardEvents.remove(item.id)
        if (!still && SystemClock.elapsedRealtime() - ev.at < 4000L) playCardEvent(fx, ev.kind)
    }

    val warnColor = MaterialTheme.colorScheme.tertiary
    P5Card(
        Modifier.fillMaxWidth(),
        accent = accent,
        runBorder = if ((converting || pausedHere) && !still) runBorder else null,
        fx = fx,
        sweep = sweep,
    ) {
        // Header: the file name gets the whole width but for the buttons (it wrapped to four lines
        // when the thumbnail sat beside it too). Long names stop at two lines; the full name is
        // under Details.
        Row(verticalAlignment = Alignment.Top) {
            // A finished card names the saved file (always ending in .webm). A card that is still to be
            // converted names the source video, or the name typed for the output; tapping it edits that.
            val title = when {
                finishedCard -> webmFileName(item.name)
                item.customName != null -> item.customName
                else -> item.name
            }
            val canNameOutput = showControls && !finishedCard && !inHistory && !item.probeFailed
            Row(
                Modifier
                    .weight(1f)
                    .clickable(enabled = canNameOutput) {
                        nameEditText = item.customName ?: item.name.substringBeforeLast('.', item.name)
                        showNameEdit = true
                    }
                    .padding(top = 8.dp, end = 8.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (canNameOutput) {
                    Text(
                        "✎",
                        style = MaterialTheme.typography.titleSmall,
                        color = P5.RedBright,
                        modifier = Modifier.padding(start = 6.dp),
                    )
                }
            }
            if (showControls) {
                // While a batch runs, a video still waiting its turn can be moved; the one being converted can't.
                if (!busy || (item.status == ItemStatus.WAITING && item.id != BatchRunner.pausedItemId)) {
                    QueueButton("▲", canMoveUp, onUp)
                    QueueButton("▼", canMoveDown, onDown)
                }
                // Removing is allowed during a run, except for the video being converted.
                QueueButton("✕", item.status != ItemStatus.CONVERTING, onRemove)
            }
        }

        // Body: the thumbnail, with what the video is and where it stands beside it.
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (!item.probeFailed) {
                Thumb(
                    item,
                    onClick = if (item.edit != null && !finishedCard) {
                        // An edited video plays in the editor, which can only be opened from the Holding Pen.
                        if (item.inPen) onEdit else {
                            { Toast.makeText(ctx, "Edited videos can't be previewed from the queue.", Toast.LENGTH_SHORT).show() }
                        }
                    } else playUri?.let { u ->
                        {
                            // The file type is looked up only when tapped, not on every redraw.
                            val mime = if (finishedCard) "video/webm" else (ctx.contentResolver.getType(u) ?: if (isAudio) "audio/*" else "video/*")
                            playVideo(ctx, u, mime)
                        }
                    },
                )
                Spacer(Modifier.size(12.dp))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                when {
                    item.probeFailed -> Text(
                        if (isAudio) "Couldn't read this audio." else "Couldn't read this video.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                    i == null -> Text(if (isAudio) "Reading audio…" else "Reading video…", style = MaterialTheme.typography.bodySmall)
                    else -> {
                        val len = String.format(Locale.US, "%.1f", i.durationSec)
                        val (state, tagColor) = if (pausedHere) "Paused" to P5.Amber else when (item.status) {
                            ItemStatus.WAITING -> "Waiting" to P5.Grey
                            ItemStatus.CONVERTING -> "Converting…" to P5.Red
                            ItemStatus.DONE -> "Done" to P5.White
                            ItemStatus.FAILED -> "Failed" to P5.Amber
                            ItemStatus.CANCELLED -> "Not converted" to P5.GreyDim
                        }
                        if (isAudio && !finishedCard) {
                            val n = item.audio?.images?.size ?: 0
                            Text(
                                if (n == 0) "No image (black)" else if (n == 1) "1 image" else "$n images",
                                style = MaterialTheme.typography.bodySmall
                            )
                            Text(
                                "${len}s · ${if (i.audioCodec.isNotBlank()) prettyCodec(i.audioCodec) else "audio"}",
                                style = MaterialTheme.typography.bodySmall
                            )
                        } else {
                            Text(
                                "${i.width}×${i.height} · ${fpsLabel(i, finishedCard)}",
                                style = MaterialTheme.typography.bodySmall
                            )
                            Text(
                                "${len}s · ${if (i.hasAudio) "has audio" else "no audio"}",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        // Every tag gets its own line, in one vertical stack, left-aligned: never side by side.
                        Column(
                            Modifier.padding(top = 2.dp),
                            horizontalAlignment = Alignment.Start,
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            if (item.edit != null && !finishedCard) P5Tag("Edited", P5.RedBright)
                            if (item.thumbSec != null && !finishedCard) P5Tag("Thumbnail", P5.RedBright)
                            P5Tag(
                                state,
                                tagColor,
                                filled = item.status == ItemStatus.CONVERTING || item.status == ItemStatus.DONE,
                            )
                            if (isNext) P5Tag("Next ▼", P5.White, filled = true)
                            // Which mode made (or will make) this card; always in its own colour, so History tells them apart.
                            if (isAudio) P5Tag("Audio", Prince.RedBright) else P5Tag("Video", P5.Grey)
                        }
                    }
                }
            }
            // The percentage of the video being converted, big and red, in the style of 💯.
            if (converting && i != null && !item.probeFailed) {
                Spacer(Modifier.size(8.dp))
                Column(horizontalAlignment = Alignment.End) {
                    // A split video also says which part this is, e.g. "1/3" (partsDone counts the parts
                    // already finished or skipped, so the one being encoded is the next).
                    if (item.partsTotal > 1) {
                        P5PartCount(minOf(item.partsDone + 1, item.partsTotal), item.partsTotal)
                    }
                    RunPercent(item)
                }
            }
            // A Holding Pen card has no percentage, so its "///" button takes that place. It opens a small
            // light menu of the pages a card has: Edit and Thumbnail.
            if (canEdit) {
                Spacer(Modifier.size(8.dp))
                var showCardMenu by remember { mutableStateOf(false) }
                Box {
                    P5Button("///", onClick = { showCardMenu = true }, kind = P5Kind.Secondary)
                    DropdownMenu(
                        expanded = showCardMenu,
                        onDismissRequest = { showCardMenu = false },
                        modifier = Modifier.background(P5.White),
                    ) {
                        if (isAudio) {
                            DropdownMenuItem(
                                text = { Text("Trim audio", color = P5.Black) },
                                onClick = { showCardMenu = false; onEdit() },
                            )
                            if ((i?.videoCodec ?: "").isNotEmpty()) {
                                DropdownMenuItem(
                                    text = { Text("Pick frame from video", color = P5.Black) },
                                    onClick = { showCardMenu = false; BatchRunner.openFramePicker(item.id) },
                                )
                            }
                            DropdownMenuItem(
                                text = { Text("Add image", color = P5.Black) },
                                onClick = { showCardMenu = false; appendPictures = true; picturePicker.launch("image/*") },
                            )
                            DropdownMenuItem(
                                text = { Text("Change image", color = P5.Black) },
                                onClick = { showCardMenu = false; appendPictures = false; picturePicker.launch("image/*") },
                            )
                            if (item.audio?.images?.isNotEmpty() == true) {
                                DropdownMenuItem(
                                    text = { Text(if ((item.audio?.images?.size ?: 0) > 1) "Remove all images" else "Remove image", color = P5.Black) },
                                    onClick = { showCardMenu = false; BatchRunner.clearAudioImages(ctx, item.id) },
                                )
                            }
                            DropdownMenuItem(
                                text = { Text("Title card text", color = P5.Black) },
                                onClick = {
                                    showCardMenu = false
                                    cardTextEdit = item.audio?.let { AudioArt.cardText(it, OutputName.baseFor(item.name, null)) } ?: ""
                                    showCardText = true
                                },
                            )
                        } else {
                            DropdownMenuItem(
                                text = { Text("Edit", color = P5.Black) },
                                onClick = { showCardMenu = false; onEdit() },
                            )
                            DropdownMenuItem(
                                text = { Text("Thumbnail", color = P5.Black) },
                                onClick = { showCardMenu = false; onThumbnail() },
                            )
                        }
                    }
                }
            }
        }

        // Audio Mode: with several images, a strip of them, each with a button to take it off. They are spread
        // equally across the video in this order.
        val stripImages = item.audio?.images.orEmpty()
        if (isAudio && item.inPen && !finishedCard && !inHistory && stripImages.size > 1) {
            Row(
                Modifier.fillMaxWidth().padding(top = 6.dp).horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                stripImages.forEachIndexed { n, im ->
                    val bmp by produceState<ImageBitmap?>(null, im.path) {
                        value = withContext(Dispatchers.IO) {
                            BitmapFactory.decodeFile(im.path, BitmapFactory.Options().apply { inSampleSize = 4 })?.asImageBitmap()
                        }
                    }
                    Box(Modifier.size(width = 56.dp, height = 44.dp).clip(CutCornerShape(topStart = 6.dp, bottomEnd = 6.dp)).background(Color.Black)) {
                        bmp?.let { Image(it, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize()) }
                        Text(
                            "${n + 1}",
                            style = MaterialTheme.typography.labelSmall,
                            color = P5.White,
                            modifier = Modifier.align(Alignment.BottomStart).background(Color.Black.copy(alpha = 0.65f)).padding(horizontal = 3.dp),
                        )
                        if (im.animated) {
                            Text(
                                "GIF",
                                style = MaterialTheme.typography.labelSmall,
                                color = P5.White,
                                modifier = Modifier.align(Alignment.BottomEnd).background(Color.Black.copy(alpha = 0.65f)).padding(horizontal = 3.dp),
                            )
                        }
                        Text(
                            "✕",
                            style = MaterialTheme.typography.labelMedium,
                            color = P5.White,
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .background(Color.Black.copy(alpha = 0.65f))
                                .clickable { BatchRunner.removeAudioImage(ctx, item.id, n) }
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                }
            }
            Text(
                "Shown in this order, each for an equal share of the length.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        if (showCardText) {
            AlertDialog(
                onDismissRequest = { showCardText = false },
                shape = CutCornerShape(topStart = 20.dp, bottomEnd = 20.dp),
                containerColor = P5.Ink,
                titleContentColor = P5.White,
                textContentColor = P5.White,
                title = { Text("Title card text".uppercase(), style = MaterialTheme.typography.titleMedium) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            "The words shown in the bottom-right corner when the title card is on (see Encoding settings > Title card). " +
                                "Leave it empty to go back to the artist and title.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        OutlinedTextField(
                            value = cardTextEdit,
                            onValueChange = { cardTextEdit = it },
                            singleLine = true,
                            shape = CutCornerShape(topStart = 10.dp, bottomEnd = 10.dp),
                        )
                    }
                },
                confirmButton = {
                    P5Button("Save", onClick = {
                        showCardText = false
                        // Typing the default back in is the same as leaving it empty.
                        val default = item.audio?.let { AudioArt.cardText(it.copy(cardText = ""), OutputName.baseFor(item.name, null)) }
                        BatchRunner.setAudioCardText(item.id, if (cardTextEdit.trim() == default) "" else cardTextEdit)
                    })
                },
                dismissButton = { P5Button("Cancel", onClick = { showCardText = false }, kind = P5Kind.Ghost) },
            )
        }

        // What this video is (or was) converted with. Once it's been sent to the queue it is fixed
        // (return it to the Holding Pen to change it); in the pen it follows the settings on
        // screen. A finished card shows what it was made for.
        val shownSettings: Settings? = if (finishedCard) item.settings else (item.settings ?: settings)
        if (shownSettings != null && !item.probeFailed) {
            val prefix = when {
                finishedCard -> "Made for"
                item.status == ItemStatus.CONVERTING -> "Converting with"
                item.settings != null -> "Will convert with"
                else -> "Current settings"
            }
            Box(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 2.dp).height(1.dp).background(P5.Line))
            Text(
                buildAnnotatedString {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = P5.RedBright)) { append("$prefix: ") }
                    append(settingsWithTarget(shownSettings))
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (i != null && item.status != ItemStatus.DONE) {
            val t = settings.tuning
            val minParts = Converter.partCount(i.durationSec, maxDuration, t)
            val maxParts = Converter.maxPartCount(i.durationSec, maxDuration, t)
            val est = if (isAudio) AudioFit.estimate(i, settings, item.extraParts, item.audio) else Converter.estimate(i, settings, item.extraParts)
            // Audio Mode: a card with an animated GIF (made by its own encoder, see GifFit).
            val gifSpec = item.audio?.takeIf { it.hasGif }
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
                    color = warnColor
                )
            }
            if (!isAudio && (minW > 0 || minH > 0) && (i.width < minW || i.height < minH)) {
                Text(
                    "Below this target's minimum (${minW}×${minH}): it will be scaled up, keeping its aspect ratio.",
                    style = MaterialTheme.typography.bodySmall,
                    color = warnColor
                )
            }

            // Splits: live estimate of what each part would get, and (for queued videos) the
            // buttons to add splits or take them away again, down to the minimum the board needs.
            val partSecText = String.format(Locale.US, "%.1f", est.partSec)
            val estimateText = buildString {
                append("Expected conversion output: ")
                if (gifSpec != null) append("sound at ${est.audioKbps}kbps, GIF video at ${est.videoKbps}kbps, picture ${est.height}p at ${GifFit.frameRate(gifSpec.images)} fps")
                else if (isAudio) append("sound at ${est.audioKbps}kbps, picture ${est.height}p")
                else append("${est.height}p at ${est.videoKbps}kbps")
            }
            val panelShape = CutCornerShape(topStart = 8.dp, bottomEnd = 8.dp)
            // In the Holding Pen the box is always there (it's where splits are added). Once the card has
            // left the pen it only stays if the video is split, to show the part count and length.
            if (showControls && (item.inPen || numParts > 1)) {
                val locked = busy || item.partsDone > 0
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp)
                        .clip(panelShape)
                        .background(P5.Panel)
                        .padding(start = 10.dp, top = 2.dp, bottom = 2.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (numParts == 1) "No split" else "$numParts parts - ${partSecText}s each",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    QueueButton("−", !locked && numParts > minParts) { onExtraParts(numParts - minParts - 1) }
                    QueueButton("+", !locked && numParts < maxParts) { onExtraParts(numParts - minParts + 1) }
                }
            }
            // The important line, under the split box: amber with an amber bar, so it stands out.
            Row(
                Modifier.fillMaxWidth().padding(top = 6.dp).height(IntrinsicSize.Min),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.width(3.dp).fillMaxHeight().background(P5.Amber))
                Text(
                    estimateText,
                    style = MaterialTheme.typography.bodySmall,
                    color = P5.Amber,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            if (gifSpec != null) {
                // How much of the size limit the GIF video is planned to take, and a warning when that is very little for the picture.
                val share = (GifFit.videoShare(est.videoKbps, est.partSec, settings.limitBytes) * 100).toInt()
                val rough = est.bitsPerPixel < GifFit.ROUGH_BPP
                Text(
                    "The GIF loops for its whole share of the length and takes about $share% of the size limit " +
                        "(sound keeps its quality first; if the file comes out too big, the GIF's quality is lowered before the sound's)." +
                        (if (rough) " That is very little for this picture size, so the GIF will look rough. More parts would give it more room." else ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (rough || share >= 60) warnColor else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            if (isAudio && est.audioKbps < AudioFit.WARN_KBPS) {
                Text(
                    "Low sound quality: only ${est.audioKbps} kbps fits. Splitting into more parts would raise it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = warnColor,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

        }

        // Not the video being converted, a split video with parts already saved, or (during a run)
        // one the batch has already tried.
        val canReturn = showReturn && item.status != ItemStatus.CONVERTING && item.partsDone == 0 &&
            (!busy || item.status == ItemStatus.WAITING)
        val canExpand = (i != null && !item.probeFailed) || shownSettings != null
        if (canExpand || showReturn) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if (canExpand) {
                    Text(
                        if (expanded) "Hide details ▴" else "Details ▾",
                        style = MaterialTheme.typography.labelMedium,
                        color = P5.RedBright,
                        modifier = Modifier
                            .clickable { expanded = !expanded }
                            .padding(vertical = 8.dp),
                    )
                }
                Spacer(Modifier.weight(1f))
                if (showReturn) {
                    P5Button("Return to holding pen", onClick = onReturn, enabled = canReturn, kind = P5Kind.Ghost)
                }
            }
        }
        if (expanded) {
            // What kind of video the estimated bitrate suits (bits per pixel, so the frame size and
            // frame rate count). The poor brackets also say what could improve it, best first and
            // only what can still be changed; two tips at most. Not shown for an invalid custom limit.
            if (!isAudio && i != null && item.status != ItemStatus.DONE && settings.limitBytes >= 256 * 1024L) {
                val t = settings.tuning
                val maxParts = Converter.maxPartCount(i.durationSec, maxDuration, t)
                val est = Converter.estimate(i, settings, item.extraParts)
                val numParts = est.parts
                if (est.videoKbps > 0) {
                    val bracket = Converter.qualityBracket(est.qualityBpp, t)
                    val tips = if (bracket.poor) {
                        val outFps = Converter.effectiveFps(i, settings.targetFps)
                        buildList {
                            if (showControls && !(busy || item.partsDone > 0) && numParts < maxParts) add("split into more parts")
                            if (outFps > 27.0) add("lower the frame rate")
                            if (est.audioKbps > Converter.MONO_AUDIO_MAX_KBPS &&
                                est.audioKbps >= Converter.AUDIO_TIP_SHARE * (est.videoKbps + est.audioKbps)
                            ) add("decrease sound quality")
                            if (!settings.useVp9) add("try VP9 (slower)")
                            if (settings.hasCustomSize) add("lower the custom resolution")
                        }.take(2)
                    } else emptyList()
                    val bracketColor = if (bracket.poor) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Quality bracket", style = MaterialTheme.typography.labelMedium, color = P5.Grey)
                        P5Tag(bracket.label, if (bracket.poor) P5.Amber else P5.White)
                    }
                    Text(
                        bracket.suits + if (tips.isNotEmpty()) "\nTo improve it: ${tips.joinToString(" or ")}." else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = bracketColor
                    )
                }
            }
            Text(
                "File: ${if (finishedCard) webmFileName(item.name) else item.name}",
                style = MaterialTheme.typography.bodySmall
            )
            if (item.edit != null && sourceInfo != null && !finishedCard) {
                Text(
                    "Edited: ${item.edit.describe()}. The original is ${sourceInfo.width}×${sourceInfo.height}, " +
                        "${String.format(Locale.US, "%.1f", sourceInfo.durationSec)}s; it isn't changed.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (item.thumbSec != null && !finishedCard) {
                Text(
                    "Thumbnail: the frame at ${String.format(Locale.US, "%.2f", item.thumbSec)}s of the original is added " +
                        "as the first frame of each converted file.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (i != null && !item.probeFailed) {
                val len = String.format(Locale.US, "%.1f", i.durationSec)
                val audio = if (i.hasAudio) {
                    if (i.audioCodec.isNotBlank()) "audio ${prettyCodec(i.audioCodec)}" else "has audio"
                } else "no audio"
                Text(
                    if (isAudio && !finishedCard) "Source: $len s · $audio"
                    else "${if (finishedCard) "Output" else "Source"}: ${i.width}×${i.height} · $len s · ${fpsLabel(i, finishedCard)} · $audio",
                    style = MaterialTheme.typography.bodySmall
                )
                val fileBits = listOfNotNull(
                    i.container.takeIf { it.isNotBlank() }?.let { "Container $it" },
                    i.videoCodec.takeIf { it.isNotBlank() }?.let { c ->
                        "Video ${prettyCodec(c)}" + if (i.videoKbps > 0) " at ${formatKbps(i.videoKbps)}" else ""
                    },
                    i.fileBytes.takeIf { it > 0 }?.let { "Size ${formatBytes(it)}" },
                )
                Text(
                    if (fileBits.isEmpty()) "No more file details were recorded for this video." else fileBits.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall
                )
            }
            if (shownSettings != null) {
                if (shownSettings.targetLabel.isNotBlank()) {
                    Text("Target: ${shownSettings.targetLabel}", style = MaterialTheme.typography.bodySmall)
                }
                val minRes = if (shownSettings.minWidth > 0 || shownSettings.minHeight > 0) {
                    "${shownSettings.minWidth}×${shownSettings.minHeight}"
                } else "none"
                val lengthPart = if (shownSettings.maxDurationSec > 0) "max length ${shownSettings.maxDurationSec} s" else "no length limit"
                val resPart = customResLabel(shownSettings.customWidth, shownSettings.customHeight)
                    ?.let { "output size: $it" } ?: "min resolution $minRes"
                Text(
                    "Size limit ${formatLimit(shownSettings.limitBytes)} · $lengthPart · $resPart",
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    if (shownSettings.audioMode) {
                        "Best sound that fits · picture ${shownSettings.targetFps} fps · ${if (shownSettings.useVp9) "VP9" else "VP8"}"
                    } else {
                        "${if (shownSettings.keepAudio) "Sound on, ${Converter.audioLabel(shownSettings.audioKbps)}" else "Sound off"} · frame rate ≤ ${shownSettings.targetFps} fps · " +
                            "${effortName(shownSettings)} · ${if (shownSettings.useVp9) "VP9" else "VP8"}"
                    },
                    style = MaterialTheme.typography.bodySmall
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

        // A finished card's file buttons: Share and Rename, then Delete file and Remove card.
        // Once the file is gone (deleted here, or outside the app) only Remove card is left.
        if (finishedCard) {
            val saved = item.savedUri
            Column(
                Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 4.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (saved != null) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        P5Button(
                            "Share",
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
                            kind = P5Kind.Secondary,
                            modifier = Modifier.weight(1f),
                        )
                        P5Button(
                            "Rename",
                            onClick = {
                                // Starts from the file's current name, without the .webm ending.
                                renameText = (OutputStorage.fileName(ctx, saved) ?: "").removeSuffix(".webm")
                                renameError = null
                                showRename = true
                            },
                            kind = P5Kind.Secondary,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                if (sourceAvailable) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        P5Button(
                            "Add to pen again",
                            onClick = { BatchRunner.addAgain(ctx, item.id) },
                            kind = P5Kind.Secondary,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                } else {
                    // A shared video's copy is deleted once no queue or Finished card uses it.
                    Text(
                        if (item.uri.scheme == "file") "Shared into app: Source lost"
                        else "Source video not available any more",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (saved != null) {
                        P5Button(
                            "Delete file",
                            onClick = {
                                if (AppSettings.get(AppToggle.CONFIRM_DESTRUCTIVE)) showDelete = true
                                else scope.launch {
                                    val err = BatchRunner.deleteFinishedFile(ctx, item.id)
                                    if (err != null) Toast.makeText(ctx, err, Toast.LENGTH_SHORT).show()
                                }
                            },
                            kind = P5Kind.Secondary,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    P5Button(
                        if (inHistory) "Remove from history" else "Remove card",
                        onClick = { if (AppSettings.get(AppToggle.CONFIRM_DESTRUCTIVE)) showRemove = true else removeCard() },
                        kind = P5Kind.Secondary,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }

    if (showRename) {
        val cleaned = OutputStorage.cleanBaseName(renameText)
        val rename = {
            if (cleaned != null && !renaming) {
                renaming = true
                scope.launch {
                    val err = BatchRunner.renameFinished(ctx, item.id, cleaned)
                    renaming = false
                    if (err == null) showRename = false else renameError = err
                }
            }
        }
        AlertDialog(
            onDismissRequest = { if (!renaming) showRename = false },
            shape = CutCornerShape(topStart = 20.dp, bottomEnd = 20.dp),
            containerColor = P5.Ink,
            titleContentColor = P5.White,
            textContentColor = P5.White,
            title = { Text("Rename file".uppercase(), style = MaterialTheme.typography.titleMedium) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = renameText,
                        onValueChange = { renameText = it; renameError = null },
                        label = { Text("File name") },
                        suffix = { Text(".webm") },
                        isError = cleaned == null || renameError != null,
                        supportingText = {
                            when {
                                renameError != null -> Text(renameError!!)
                                cleaned == null -> Text("Enter a name without / \\ : * ? \" < > |")
                            }
                        },
                        singleLine = true,
                        shape = CutCornerShape(topStart = 10.dp, bottomEnd = 10.dp),
                    )
                }
            },
            confirmButton = {
                P5Button("Rename", onClick = rename, enabled = cleaned != null && !renaming)
            },
            dismissButton = {
                P5Button("Cancel", onClick = { showRename = false }, enabled = !renaming, kind = P5Kind.Ghost)
            },
        )
    }

    if (showNameEdit) {
        val cleaned = OutputStorage.cleanBaseName(nameEditText)
        val blank = nameEditText.isBlank()
        val save = {
            if (blank || cleaned != null) {
                BatchRunner.setCustomName(item.id, nameEditText)
                showNameEdit = false
            }
        }
        AlertDialog(
            onDismissRequest = { showNameEdit = false },
            shape = CutCornerShape(topStart = 20.dp, bottomEnd = 20.dp),
            containerColor = P5.Ink,
            titleContentColor = P5.White,
            textContentColor = P5.White,
            title = { Text("Output file name".uppercase(), style = MaterialTheme.typography.titleMedium) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = nameEditText,
                        onValueChange = { nameEditText = it },
                        label = { Text("File name") },
                        isError = !blank && cleaned == null,
                        supportingText = {
                            if (!blank && cleaned == null) Text("Enter a name without / \\ : * ? \" < > |")
                        },
                        singleLine = true,
                        shape = CutCornerShape(topStart = 10.dp, bottomEnd = 10.dp),
                    )
                }
            },
            confirmButton = { P5Button("Save", onClick = save, enabled = blank || cleaned != null) },
            dismissButton = { P5Button("Cancel", onClick = { showNameEdit = false }, kind = P5Kind.Ghost) },
        )
    }

    if (showDelete) {
        val fileLabel = remember(item.savedUri) {
            item.savedUri?.let { OutputStorage.fileName(ctx, it) } ?: "this file"
        }
        AlertDialog(
            onDismissRequest = { showDelete = false },
            shape = CutCornerShape(topStart = 20.dp, bottomEnd = 20.dp),
            containerColor = P5.Ink,
            titleContentColor = P5.White,
            textContentColor = P5.White,
            title = { Text("Delete file".uppercase(), style = MaterialTheme.typography.titleMedium) },
            text = {
                Text(
                    "Delete $fileLabel from your device? This can't be undone. " +
                        if (inHistory) "The card stays in History until you remove it."
                        else "The card stays in the Finished list until you remove it."
                )
            },
            confirmButton = {
                P5Button(
                    "Delete",
                    onClick = {
                        showDelete = false
                        scope.launch {
                            val err = BatchRunner.deleteFinishedFile(ctx, item.id)
                            if (err != null) Toast.makeText(ctx, err, Toast.LENGTH_SHORT).show()
                        }
                    },
                )
            },
            dismissButton = { P5Button("Cancel", onClick = { showDelete = false }, kind = P5Kind.Ghost) },
        )
    }

    if (showRemove) {
        AlertDialog(
            onDismissRequest = { showRemove = false },
            shape = CutCornerShape(topStart = 20.dp, bottomEnd = 20.dp),
            containerColor = P5.Ink,
            titleContentColor = P5.White,
            textContentColor = P5.White,
            title = { Text((if (inHistory) "Remove from history" else "Remove card").uppercase(), style = MaterialTheme.typography.titleMedium) },
            // Only worth saying while there is a saved video; after "Delete file" there isn't one.
            text = if (item.savedUri != null) {
                { Text("The saved video isn't touched.") }
            } else null,
            confirmButton = { P5Button("Remove", onClick = { showRemove = false; removeCard() }) },
            dismissButton = { P5Button("Cancel", onClick = { showRemove = false }, kind = P5Kind.Ghost) },
        )
    }
}

/** The battery nudge is shown at most once per app launch (a plain flag: it resets when the app restarts). */
private var batteryNagShownThisLaunch = false

/** True when the nudge should show now: not shown yet this launch, not switched off, and the app not already allowed. */
private fun batteryNagDue(ctx: Context, prefs: android.content.SharedPreferences): Boolean {
    if (batteryNagShownThisLaunch || prefs.getBoolean("battery_nag_off", false)) return false
    val pm = ctx.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
    return !pm.isIgnoringBatteryOptimizations(ctx.packageName)
}

/**
 * A Finished card's title: the saved file's name, which always ends in .webm. Cards made by older
 * versions hold the source video's name instead, so for those the extension is swapped.
 */
private fun webmFileName(name: String): String =
    if (name.endsWith(".webm", ignoreCase = true)) name else name.substringBeforeLast('.', name) + ".webm"

@Composable
private fun TargetRow(
    selected: Boolean,
    enabled: Boolean,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    // The picked choice is a white slanted bar with black text, like a highlighted menu entry.
    val shape = remember { SlantShape(8.dp) }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (selected) P5.White else Color.Transparent)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        P5Diamond(selected)
        Column(Modifier.padding(start = 8.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                color = if (selected) P5.Black else P5.White,
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = if (selected) Color(0xFF2B2B2B) else P5.Grey,
            )
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
    /** A thinner header with slightly smaller text, for a group that holds other groups. */
    slim: Boolean = false,
    content: @Composable () -> Unit,
) {
    val shape = remember { CutCornerShape(topEnd = 16.dp, bottomStart = 16.dp) }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(P5.Ink)
            .border(1.5.dp, if (expanded) P5.Red else P5.Line, shape),
    ) {
        // An open group's header turns solid red, like the picked entry in a menu.
        Row(
            Modifier
                .fillMaxWidth()
                .background(if (expanded) P5.Red else Color.Transparent)
                .clickable(onClick = onToggle)
                .padding(horizontal = 16.dp, vertical = if (slim) 7.dp else 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    title.uppercase(),
                    style = MaterialTheme.typography.labelLarge.copy(fontSize = if (slim) 13.sp else 16.sp),
                    color = P5.White,
                )
                if (!expanded) {
                    Text(
                        summary,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (summaryIsError) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text(
                if (expanded) "▲" else "▼",
                style = MaterialTheme.typography.titleSmall,
                color = if (expanded) P5.White else P5.RedBright,
            )
        }
        if (expanded) {
            Column(
                Modifier.padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
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

/** e.g. "6 MB". */
private fun formatLimit(bytes: Long): String =
    String.format(Locale.US, "%.2f", bytes / 1048576.0).trimEnd('0').trimEnd('.') + " MB"

private fun formatBytes(b: Long): String = when {
    b >= 1048576L -> String.format(Locale.US, "%.1f MB", b / 1048576.0)
    else -> String.format(Locale.US, "%.0f KB", b / 1024.0)
}

private fun formatKbps(k: Int): String =
    if (k >= 1000) String.format(Locale.US, "%.1f Mbps", k / 1000.0) else "$k kbps"

/** ffprobe's codec name as people write it. */
private fun prettyCodec(c: String): String = when (c.lowercase()) {
    "h264" -> "H.264"
    "hevc" -> "H.265 (HEVC)"
    "vp8" -> "VP8"
    "vp9" -> "VP9"
    "av1" -> "AV1"
    "mpeg4" -> "MPEG-4"
    "mpeg2video" -> "MPEG-2"
    "aac" -> "AAC"
    "mp3" -> "MP3"
    "opus" -> "Opus"
    "vorbis" -> "Vorbis"
    "ac3" -> "AC-3"
    "eac3" -> "E-AC-3"
    else -> c.uppercase()
}

private fun effortName(s: Settings) =
    if (s.bestQuality) "Best quality" else if (s.fastest) "Fastest" else if (s.fastEncode) "Speed" else "Quality"

/** The settings in one short line, e.g. "6 MB · ≤ 400 s · ♪ 64 kbps · ≤ 30 fps · Quality · VP8". */
private fun settingsBrief(s: Settings): String =
    if (s.audioMode) {
        "${formatLimit(s.limitBytes)} · " +
            (if (s.maxDurationSec > 0) "≤ ${s.maxDurationSec} s · " else "") +
            "best sound that fits · picture ${s.targetFps} fps · ${if (s.useVp9) "VP9" else "VP8"}" +
            (if (s.titleCardSec != 0) " · title card: ${AudioFit.cardLabel(s.titleCardSec).lowercase()}" else "")
    } else
    "${formatLimit(s.limitBytes)} · " +
        (if (s.maxDurationSec > 0) "≤ ${s.maxDurationSec} s · " else "") +
        (customResLabel(s.customWidth, s.customHeight)?.let { "$it · " } ?: "") +
        "${soundLabel(s)} · " +
        "≤ ${s.targetFps} fps · ${effortName(s)} · ${if (s.useVp9) "VP9" else "VP8"}"

/** "no sound" or e.g. "♪ 64 kbps" (a plain text music note, not an emoji; always with the bitrate, so every card reads the same). */
private fun soundLabel(s: Settings): String = when {
    !s.keepAudio -> "no sound"
    else -> "♪ ${Converter.audioLabel(s.audioKbps)}"
}

/** A Custom resolution in words (e.g. "scaled to fit 640×480"), or null when none is set. */
private fun customResLabel(w: Int, h: Int): String? = when {
    w > 0 && h > 0 -> "scaled to fit ${w}×${h}"
    w > 0 -> "${w} px wide"
    h > 0 -> "${h} px tall"
    else -> null
}

/** The board name (if there is one) followed by [settingsBrief]. */
internal fun settingsWithTarget(s: Settings): String =
    (if (s.targetLabel.isNotBlank()) "${s.targetLabel} · " else "") + settingsBrief(s)

/** One line saying what a running batch is being converted with. */

private enum class CardSection { PEN, QUEUE, CONVERTING }

/**
 * A note under a section heading: a dark cut-corner panel with a red bar down its edge, an optional
 * bold capitals headline and a grey line of text. Used for the empty states and the short
 * explanations of the Holding pen, Queue and Converting sections.
 */
@Composable
private fun SectionNote(text: String, headline: String? = null) {
    val shape = CutCornerShape(topStart = 8.dp, bottomEnd = 8.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .clip(shape)
            .background(P5.Panel),
    ) {
        Box(Modifier.width(4.dp).fillMaxHeight().background(P5.Red))
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            if (headline != null) {
                Text(headline.uppercase(), style = MaterialTheme.typography.titleSmall, color = P5.White)
            }
            Text(text, style = MaterialTheme.typography.bodySmall, color = P5.Grey)
        }
    }
}

/**
 * The cards of one section, newest at the top. A video in the queue shows the settings it was sent
 * with; one in the Holding Pen follows [liveSettings]. "Up" on a card moves it up the screen.
 */
@Composable
private fun QueueCards(section: CardSection, busy: Boolean, liveSettings: Settings, audio: Boolean = false) {
    val queue = BatchRunner.queue
    // Conversion runs from the bottom of the list up, so the next video is the first waiting one
    // in list order (the one lowest on screen).
    // While a batch runs, every waiting video has a soft band moving down its background (one
    // shared clock, so they move together); the next one also has the NEXT tag.
    val sweeping = section == CardSection.QUEUE && busy && !BatchRunner.paused && !AppSettings.get(AppToggle.ANIMATIONS_OFF)
    var sweepPhase by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(sweeping) {
        if (sweeping) {
            val start = System.nanoTime()
            while (isActive) {
                sweepPhase = ((System.nanoTime() - start) / 2_400_000_000.0).toFloat() % 1f
                delay(16)
            }
        }
    }
    val nextId = if (section == CardSection.QUEUE) {
        queue.firstOrNull {
            it.isAudio == audio && !it.inPen && it.info != null && !it.probeFailed && it.status == ItemStatus.WAITING &&
                it.id != BatchRunner.pausedItemId
        }?.id
    } else null
    for (idx in queue.indices.reversed()) {
        val item = queue[idx]
        val show = item.isAudio == audio && when (section) {
            CardSection.PEN -> item.inPen
            CardSection.QUEUE -> !item.inPen && item.status != ItemStatus.CONVERTING && item.id != BatchRunner.pausedItemId
            // Includes the video Pause Now interrupted, which keeps its card here while paused.
            CardSection.CONVERTING -> !item.inPen && (item.status == ItemStatus.CONVERTING || item.id == BatchRunner.pausedItemId)
        }
        if (show) key(item.id) {
            // A queued video shows (and is estimated with) the settings it was sent with; only the
            // Holding Pen follows the settings on screen.
            val s = if (!item.inPen) {
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
                // The list is shown newest first, so a later place in the list is higher on screen.
                canMoveUp = BatchRunner.canMove(idx, 1),
                canMoveDown = BatchRunner.canMove(idx, -1),
                onUp = { BatchRunner.move(idx, 1) },
                onDown = { BatchRunner.move(idx, -1) },
                onRemove = { BatchRunner.removeAt(idx) },
                onExtraParts = { BatchRunner.setExtraParts(item.id, it) },
                showReturn = section == CardSection.QUEUE,
                onReturn = { BatchRunner.returnToPen(item.id) },
                onEdit = { if (item.isAudio) BatchRunner.openAudioTrim(item.id) else BatchRunner.openEditor(item.id) },
                onThumbnail = { BatchRunner.openThumbPicker(item.id) },
                isNext = item.id == nextId,
                sweep = if (sweeping && item.status == ItemStatus.WAITING && item.info != null && !item.probeFailed) {
                    { sweepPhase }
                } else null,
            )
        }
    }
}

/**
 * What the Converting section shows under the card being converted: the speech bubble (with the run's
 * status line above its message), the log, and the progress bar directly under the log. Kept out of [App]
 * on purpose: that function is so long that adding to it made the Android verifier reject it (a
 * VerifyError at start-up).
 */
@Composable
private fun LogPanel(
    lines: List<String>,
    listState: androidx.compose.foundation.lazy.LazyListState,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    busy: Boolean,
    paused: Boolean,
    pauseRequest: PauseRequest,
    notice: String?,
    status: String,
    progress: Float,
    sweep: () -> Float,
) {
    val clipboard = LocalClipboardManager.current
    // What the buttons are doing right now is said in the bubble too, in place of the latest message.
    val live: String? = when {
        !busy -> null
        paused -> "Paused. " + (notice ?: "Nothing is converting. Press Resume to carry on.")
        pauseRequest == PauseRequest.NOW -> "Pausing…"
        pauseRequest == PauseRequest.AFTER -> "Will pause when this video has finished."
        else -> null
    }
    LogBubble(BatchRunner.notes, header = if (busy) status else null, live = live)
    if (lines.isNotEmpty()) {
        // 3 lines tall (64 when expanded): the line height below times the lines, plus the padding.
        val logBoxHeight = with(LocalDensity.current) { (14.sp * (if (expanded) 64 else 3)).toDp() } + 20.dp
        val logShape = CutCornerShape(topStart = 12.dp, bottomEnd = 12.dp)
        Box(
            Modifier
                .fillMaxWidth()
                .height(logBoxHeight)
                .clip(logShape)
                .background(Color.Black)
                .border(1.5.dp, P5.Line, logShape),
        ) {
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize().padding(10.dp)) {
                items(lines.size) { idx ->
                    Text(
                        lines[idx],
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        lineHeight = 14.sp,
                    )
                }
            }
            Row(
                Modifier
                    .align(Alignment.TopEnd)
                    .background(Color.Black.copy(alpha = 0.75f)),
            ) {
                LogCopyButton(onClick = { clipboard.setText(AnnotatedString(lines.joinToString("\n"))) })
                LogSizeButton(expanded = expanded, onClick = onToggleExpanded)
            }
        }
    }
    if (busy && !paused) {
        if (progress < 0f) P5Progress(progress = sweep) else P5Progress(progress = { progress })
    }
}

/** Pause, skip and cancel, directly under the card being converted (or Resume / Cancel all when paused). */
@Composable
private fun ConvertingControls(
    paused: Boolean,
    pauseRequest: PauseRequest,
    currentParts: Int,
    runTotal: Int,
    canResume: Boolean,
    onResume: () -> Unit,
    onCancel: () -> Unit,
) {
    if (paused) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            P5Button("Resume", onClick = onResume, enabled = canResume, modifier = Modifier.weight(1f))
            P5Button("Cancel all", onClick = { BatchRunner.cancelAll() }, kind = P5Kind.Secondary, modifier = Modifier.weight(1f))
        }
        return
    }
    // However many buttons there are, they share the full width equally (each text shrinks to fit if needed).
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        P5Button(
            "Pause now",
            onClick = { BatchRunner.pauseNow() },
            enabled = pauseRequest != PauseRequest.NOW,
            kind = P5Kind.Secondary,
            dense = true,
            modifier = Modifier.weight(1f),
        )
        P5Button(
            if (pauseRequest == PauseRequest.AFTER) "Undo pause" else "Pause after",
            onClick = { BatchRunner.pauseAfter() },
            enabled = pauseRequest != PauseRequest.NOW,
            kind = P5Kind.Secondary,
            dense = true,
            modifier = Modifier.weight(1f),
        )
        // A split video: Skip part drops just the part being encoded and carries on with the next one.
        // With other videos waiting there is also Skip video (the whole video).
        if (currentParts > 1) {
            P5Button("Skip part", onClick = { BatchRunner.skipPart() }, kind = P5Kind.Secondary, dense = true, modifier = Modifier.weight(1f))
        }
        if (runTotal > 1) {
            P5Button("Skip video", onClick = { BatchRunner.skipCurrent() }, kind = P5Kind.Secondary, dense = true, modifier = Modifier.weight(1f))
        }
        P5Button(
            if (runTotal > 1) "Cancel all" else "Cancel",
            onClick = onCancel,
            kind = P5Kind.Secondary,
            dense = true,
            modifier = Modifier.weight(1f),
        )
    }
}

/** Why notifications are wanted, shown before Android's own permission prompt. */
@Composable
private fun NotificationExplainerDialog(
    blocked: Boolean,
    onSkip: () -> Unit,
    onOpenSettings: () -> Unit,
    onAskPermission: () -> Unit,
) {
    val ctx = LocalContext.current
    AlertDialog(
        onDismissRequest = onSkip,
        shape = CutCornerShape(topStart = 20.dp, bottomEnd = 20.dp),
        containerColor = P5.Ink,
        titleContentColor = P5.White,
        textContentColor = P5.White,
        title = { Text("Notification permission".uppercase(), style = MaterialTheme.typography.titleMedium) },
        text = {
            Text(
                "Notification permission is required for background conversion. " +
                    "Without it, you'll have to stare at WebMChan until it's done." +
                    if (blocked) {
                        "\n\nAndroid won't show the permission prompt again, so you'll need to turn " +
                            "notifications on for WebMChan in Android's settings."
                    } else ""
            )
        },
        confirmButton = {
            P5Button(
                if (blocked) "Grant Notification Permission" else "Continue",
                onClick = {
                    if (blocked) {
                        try {
                            ctx.startActivity(
                                Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                    .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, ctx.packageName)
                            )
                        } catch (_: Exception) {
                        }
                        onOpenSettings()
                    } else {
                        ctx.getSharedPreferences("webmchan", Context.MODE_PRIVATE)
                            .edit().putBoolean("asked_notifications", true).apply()
                        onAskPermission()
                    }
                },
            )
        },
        dismissButton = { P5Button("Skip", onClick = onSkip, kind = P5Kind.Ghost) },
    )
}

/**
 * Android may stop an app in the background to save battery, which would end a long conversion part-way.
 * We explain, then open Android's own battery screen for the person to allow it (this needs no permission).
 * The run starts either way ([onProceed]), so the tap is never lost.
 */
@Composable
private fun BatteryNagDialog(onProceed: () -> Unit) {
    val ctx = LocalContext.current
    AlertDialog(
        onDismissRequest = onProceed,
        shape = CutCornerShape(topStart = 20.dp, bottomEnd = 20.dp),
        containerColor = P5.Ink,
        titleContentColor = P5.White,
        textContentColor = P5.White,
        title = { Text("Unrestricted battery use".uppercase(), style = MaterialTheme.typography.titleMedium) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "To save battery, Android can stop WebMChan while it is in the background, which ends a " +
                        "long conversion part-way. Allowing unrestricted battery use stops that.\n\n" +
                        "The button below opens Android's battery settings. Then:\n" +
                        "1. Find WebMChan (switch the list to \"All apps\" if you can't see it).\n" +
                        "2. Choose \"Don't optimise\" (some phones say \"Unrestricted\").\n" +
                        "3. Come back here. Your conversion carries on meanwhile.\n\n" +
                        "On some phones it is under Settings > Apps > WebMChan > Battery instead."
                )
                P5Button(
                    "Don't ask again",
                    onClick = {
                        ctx.getSharedPreferences("webmchan", Context.MODE_PRIVATE)
                            .edit().putBoolean("battery_nag_off", true).apply()
                        onProceed()
                    },
                    kind = P5Kind.Ghost,
                )
            }
        },
        confirmButton = {
            P5Button(
                "Open battery settings",
                onClick = {
                    try {
                        ctx.startActivity(Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                    } catch (_: Exception) {
                        // Not on every phone: the app's own info page has the same Battery entry.
                        try {
                            ctx.startActivity(
                                Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                                    .setData(Uri.parse("package:${ctx.packageName}"))
                            )
                        } catch (_: Exception) {
                        }
                    }
                    onProceed()
                },
            )
        },
        dismissButton = { P5Button("Later", onClick = onProceed, kind = P5Kind.Ghost) },
    )
}

/** Shown once, the first time Best quality is chosen. */
@Composable
private fun BestQualityNoticeDialog(onGotIt: () -> Unit) {
    val ctx = LocalContext.current
    val gotIt = {
        ctx.getSharedPreferences("webmchan", Context.MODE_PRIVATE).edit().putBoolean("best_notice_seen", true).apply()
        onGotIt()
    }
    AlertDialog(
        onDismissRequest = gotIt,
        shape = CutCornerShape(topStart = 20.dp, bottomEnd = 20.dp),
        containerColor = P5.Ink,
        titleContentColor = P5.White,
        textContentColor = P5.White,
        title = { Text("Best quality".uppercase(), style = MaterialTheme.typography.titleMedium) },
        text = {
            Text(
                "This mode can take a very long time. It encodes each video up to " +
                    "${TuningStore.current.bestMaxAttempts} times with slower settings, so expect a warm phone " +
                    "that may slow itself down to cool off, and heavy use of battery and memory.\n\n" +
                    "Plug the phone in and keep it somewhere cool. Long or high resolution videos are " +
                    "the heaviest, and VP9 is slower again.\n\n" +
                    "This notice is only shown once."
            )
        },
        confirmButton = { P5Button("Got it", onClick = gotIt) },
    )
}

/** The Finished list: converted videos newest first, and Share all. */
@Composable
private fun FinishedSection(
    finished: List<QueueItem>,
    audio: Boolean,
    busy: Boolean,
    maxDuration: Int,
    isCustom: Boolean,
    minW: Int,
    minH: Int,
    settings: Settings,
) {
    val ctx = LocalContext.current
    // Finished videos land here from the converting section, newest at the top.
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        P5SectionTitle(if (finished.isNotEmpty()) "Finished (${finished.size})" else "Finished")
        Spacer(Modifier.weight(1f))
        if (finished.isNotEmpty()) {
            P5Button("Clear finished", onClick = { BatchRunner.clearFinished(audio) }, kind = P5Kind.Secondary)
        }
    }
    if (finished.isEmpty()) {
        SectionNote(if (audio) "Converted audio lands here." else "Converted videos land here.", headline = if (audio) "No finished audio yet" else "No finished videos yet")
        return
    }
    finished.asReversed().forEach { item ->
        key(item.id) {
            QueueRow(
                item = item,
                busy = busy,
                maxDuration = maxDuration,
                isCustom = isCustom,
                minW = minW,
                minH = minH,
                settings = settings,
                showControls = false,
            )
        }
    }
    val savedUris = finished.mapNotNull { it.savedUri }
    if (savedUris.size > 1) {
        P5Button(
            "Share all (${savedUris.size})",
            kind = P5Kind.Secondary,
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
        )
    }
}

/** The Encoding (Best quality / Quality / Speed / Fastest) and Codec groups of the settings. */
@Composable
private fun EncodingAndCodecSections(
    audio: Boolean,
    bestQuality: Boolean,
    fastEncode: Boolean,
    fastest: Boolean,
    useVp9: Boolean,
    fastestOffered: Boolean,
    encodingOpen: Boolean,
    onToggleEncoding: () -> Unit,
    encodingSummary: String,
    codecOpen: Boolean,
    onToggleCodec: () -> Unit,
    codecSummary: String,
    onPickBest: () -> Unit,
    onPickQuality: () -> Unit,
    onPickSpeed: () -> Unit,
    onPickFastest: () -> Unit,
    onPickVp8: () -> Unit,
    onPickVp9: () -> Unit,
) {
    if (!audio) CollapsibleSection(
        title = "Encoding",
        summary = encodingSummary,
        expanded = encodingOpen,
        onToggle = onToggleEncoding,
    ) {
        TargetRow(
            selected = bestQuality,
            enabled = true,
            title = "Best quality",
            subtitle = "The highest quality that fits, however long it takes. Much slower than Quality: it " +
                "encodes again and again until the file is within about 2% of the size limit. " +
                "VP9 (under Codec) gives better quality still, but is slower again.",
            onClick = onPickBest,
        )
        TargetRow(
            selected = !bestQuality && !fastEncode && !fastest,
            enabled = true,
            title = "Quality (default)",
            subtitle = "Targets the ideal balance between image quality and encode speed. Highly recommended for anything 'live action'.",
            onClick = onPickQuality,
        )
        TargetRow(
            selected = !bestQuality && fastEncode && !fastest,
            enabled = true,
            title = "Speed",
            subtitle = "Faster than Quality mode, only slightly softer detail.",
            onClick = onPickSpeed,
        )
        if (fastestOffered) {
            TargetRow(
                selected = !bestQuality && fastest,
                enabled = true,
                title = "Fastest",
                subtitle = "Fastest, softest detail. Lower effort transcoding and smaller frames. Only recommended for low detail videos. ",
                onClick = onPickFastest,
            )
        }
    }

    CollapsibleSection(
        title = "Codec",
        summary = codecSummary,
        expanded = codecOpen,
        onToggle = onToggleCodec,
    ) {
        TargetRow(
            selected = !useVp9,
            enabled = true,
            title = "VP8 (default)",
            subtitle = "Recommended default. Fastest. It just works.",
            onClick = onPickVp8,
        )
        TargetRow(
            selected = useVp9,
            enabled = true,
            title = "VP9",
            subtitle = "Much slower to convert. Newer standard. Should look better. Low compatibility on older devices. Mostly included here as 'future proofing'",
            onClick = onPickVp9,
        )
    }
}

@Composable
fun App(
    onMenu: () -> Unit = {},
    /** True for the Audio Mode screen: the same page, adapted (see AudioMode.kt). Its cards, settings and saved choices are its own. */
    audio: Boolean = false,
    /** Audio Mode: back to the video screen (system back). */
    onExitAudio: () -> Unit = {},
    /** Jumps to the other mode's screen (from the "in use" note). */
    onOtherMode: () -> Unit = {},
) {
    val ctx = LocalContext.current
    if (audio) androidx.activity.compose.BackHandler(onBack = onExitAudio)
    val noun = if (audio) "tracks" else "videos"

    // The queue, the finished list, progress and the log all live in BatchRunner (not here), so a
    // running batch survives the screen being closed. Settings below apply to every video in it.
    val queue = BatchRunner.queue
    val finished = BatchRunner.finished.filter { it.isAudio == audio }
    // Only one mode converts at a time: while the other one does, this screen says so instead of offering Convert.
    val otherBusy = BatchRunner.busy && BatchRunner.runAudio != audio
    val busy = BatchRunner.busy && !otherBusy
    val probing = BatchRunner.probing
    val copyingTotal = BatchRunner.copyingTotal
    val cancelling = BatchRunner.cancelling
    // Cancel / Cancel all asks first, unless Settings says not to (see AppToggle.CONFIRM_DESTRUCTIVE).
    var showCancelConfirm by remember { mutableStateOf(false) }
    if (showCancelConfirm) {
        AlertDialog(
            onDismissRequest = { showCancelConfirm = false },
            shape = CutCornerShape(topStart = 20.dp, bottomEnd = 20.dp),
            containerColor = P5.Ink,
            titleContentColor = P5.White,
            textContentColor = P5.White,
            title = { Text("Cancel conversion".uppercase(), style = MaterialTheme.typography.titleMedium) },
            text = { Text("Stop converting? The video being converted stops and the rest of the run is dropped. Parts already saved are kept.") },
            confirmButton = { P5Button("Cancel it", onClick = { showCancelConfirm = false; BatchRunner.cancelAll() }) },
            dismissButton = { P5Button("Keep going", onClick = { showCancelConfirm = false }, kind = P5Kind.Ghost) },
        )
    }
    val paused = BatchRunner.paused
    val pauseRequest = BatchRunner.pauseRequest
    val notice = BatchRunner.notice
    val copyingDone = BatchRunner.copyingDone
    val progress = BatchRunner.progress
    val status = BatchRunner.status
    val summary = BatchRunner.summary
    val runTotal = BatchRunner.runTotal
    val currentParts = BatchRunner.currentParts
    val logLines = BatchRunner.logLines

    // Index into TARGETS; TARGETS.size means "Custom".
    // The choices below are remembered between runs.
    val prefs = remember { ctx.getSharedPreferences("webmchan", Context.MODE_PRIVATE) }
    // Audio Mode remembers its own choices.
    val pre = if (audio) "uia_" else "ui_"
    val targets = remember(audio) { if (audio) TARGETS.filter { it.audioAllowed } else TARGETS }
    val fpsPresets = if (audio) AUDIO_FPS_PRESETS else FPS_PRESETS
    val fpsDefaultIndex = if (audio) AUDIO_FPS_DEFAULT_INDEX else FPS_DEFAULT_INDEX
    var targetIdx by remember { mutableIntStateOf(prefs.getInt("${pre}target_idx", 0).coerceIn(0, targets.size)) }
    var customMb by remember { mutableStateOf(prefs.getString("${pre}custom_mb", "4") ?: "4") }
    // Custom resolution, in pixels. Empty = not set (automatic).
    var customW by remember { mutableStateOf(prefs.getString("${pre}custom_w", "") ?: "") }
    var customH by remember { mutableStateOf(prefs.getString("${pre}custom_h", "") ?: "") }
    var removeAudio by remember { mutableStateOf(prefs.getBoolean("${pre}remove_audio", true)) }
    // One of Converter.AUDIO_KBPS_OPTIONS; anything else saved falls back to the default.
    var audioKbps by remember {
        mutableIntStateOf(
            prefs.getInt("${pre}audio_kbps", Converter.DEFAULT_AUDIO_KBPS)
                .takeIf { it in Converter.AUDIO_KBPS_OPTIONS } ?: Converter.DEFAULT_AUDIO_KBPS
        )
    }
    var fastEncode by remember { mutableStateOf(prefs.getBoolean("${pre}fast_encode", false)) }
    var useVp9 by remember { mutableStateOf(prefs.getBoolean("${pre}use_vp9", false)) }
    // Fastest is only offered while Settings > Offer Fastest mode is on; with it off it is never in use.
    val fastestOffered = AppSettings.get(AppToggle.FASTEST_MODE)
    var fastest by remember { mutableStateOf(prefs.getBoolean("${pre}fastest", false)) }
    LaunchedEffect(fastestOffered) { if (!fastestOffered) fastest = false }
    // Best quality, one of the three exclusive encoding choices (with Quality/Speed and Fastest).
    var bestQuality by remember { mutableStateOf(prefs.getBoolean("${pre}best_quality", false)) }
    // Index into FPS_PRESETS; FPS_PRESETS.size means "Custom".
    var fpsChoice by remember {
        // Video mode used to offer 24/30/60 (indexes 0-2, Custom 3); 10 and 15 now come first, so an older saved
        // index moves up by two. The "_v2" flag records that the saved index is already in the new numbering.
        val saved = prefs.getInt("${pre}fps_choice", fpsDefaultIndex)
        val migrated = if (!audio && !prefs.getBoolean("${pre}fps_choice_v2", false) && prefs.contains("${pre}fps_choice")) saved + 2 else saved
        mutableIntStateOf(migrated.coerceIn(0, fpsPresets.size))
    }
    // Audio Mode: how long the title card stays on screen (an index into AudioFit.CARD_OPTIONS; 0 = off).
    var cardChoice by remember { mutableIntStateOf(prefs.getInt("${pre}title_card", 0).coerceIn(0, AudioFit.CARD_OPTIONS.lastIndex)) }
    var cardOpen by remember { mutableStateOf(false) }
    var customFps by remember { mutableStateOf(prefs.getString("${pre}custom_fps", if (audio) "2" else "30") ?: (if (audio) "2" else "30")) }
    LaunchedEffect(targetIdx, customMb, customW, customH, removeAudio, audioKbps, fastEncode, useVp9, fastest, bestQuality, fpsChoice, customFps, cardChoice) {
        prefs.edit()
            .putInt("${pre}target_idx", targetIdx)
            .putString("${pre}custom_mb", customMb)
            .putString("${pre}custom_w", customW)
            .putString("${pre}custom_h", customH)
            .putBoolean("${pre}remove_audio", removeAudio)
            .putInt("${pre}audio_kbps", audioKbps)
            .putBoolean("${pre}fast_encode", fastEncode)
            .putBoolean("${pre}use_vp9", useVp9)
            .putBoolean("${pre}fastest", fastest)
            .putBoolean("${pre}best_quality", bestQuality)
            .putInt("${pre}fps_choice", fpsChoice)
            .putBoolean("${pre}fps_choice_v2", true)
            .putString("${pre}custom_fps", customFps)
            .putInt("${pre}title_card", cardChoice)
            .apply()
    }

    // Which settings groups are open. All start closed.
    var boardsOpen by remember { mutableStateOf(false) }
    var audioOpen by remember { mutableStateOf(false) }
    var fpsOpen by remember { mutableStateOf(false) }
    var encodingOpen by remember { mutableStateOf(false) }
    // The one-time heads-up shown the first time Best quality is picked.
    var showBestNotice by remember { mutableStateOf(false) }
    var codecOpen by remember { mutableStateOf(false) }
    var deepOpen by remember { mutableStateOf(false) }

    // Pass 1 has no real progress to report. Compose's built-in indeterminate bar relies on
    // Compose's own animation system, which some phones freeze (the "Animator duration scale"
    // developer option), leaving it static. This sweeps a value by hand instead, the same way
    // pass 2's real progress is just pushed in as plain numbers, so it moves regardless.
    var pass1Sweep by remember { mutableFloatStateOf(0f) }

    // Where output is saved. Empty custom-dir state means the default Download/WebMChan/.
    val logListState = rememberLazyListState()
    // The log box is 6 lines tall, or 64 when expanded.
    var logExpanded by remember { mutableStateOf(false) }

    // Keep the newest line in view.
    LaunchedEffect(logLines, logExpanded) {
        if (logLines.isNotEmpty()) logListState.scrollToItem(logLines.lastIndex)
    }

    // Hand-rolled sweep for pass 1 (see pass1Sweep above). Ping-pongs 0→1→0 every 4s.
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
        if (audio) BatchRunner.addAudio(ctx, picked) else BatchRunner.addVideos(ctx, picked)
    }

    // Android 13+ asks before an app may show notifications. We explain and ask every time you
    // convert until it's granted, then start either way: converting works without it, you just
    // won't see the progress notification. Android stops showing its own prompt after two
    // refusals; at that point the dialog offers to open the app's notification settings instead.
    var pendingSettings by remember { mutableStateOf<Settings?>(null) }
    var showNotificationExplainer by remember { mutableStateOf(false) }
    var explainerBlocked by remember { mutableStateOf(false) }

    // After the notification step, and before a run starts: a one-off nudge to allow unrestricted
    // battery use (see the dialog below). Everything that used to start the run now goes through this.
    var showBatteryNag by remember { mutableStateOf(false) }
    var batterySettings by remember { mutableStateOf<Settings?>(null) }
    val startRun: (Settings) -> Unit = { s ->
        if (batteryNagDue(ctx, prefs)) {
            batteryNagShownThisLaunch = true
            batterySettings = s
            showBatteryNag = true
        } else {
            BatchRunner.start(ctx, s, audio)
        }
    }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { _ ->
        pendingSettings?.let { startRun(it) }
        pendingSettings = null
    }
    // Explains why before Android's own permission prompt appears.
    if (showNotificationExplainer) {
        val skip = {
            showNotificationExplainer = false
            pendingSettings?.let { startRun(it) }
            pendingSettings = null
        }
        NotificationExplainerDialog(
            blocked = explainerBlocked,
            onSkip = skip,
            onOpenSettings = {
                showNotificationExplainer = false
                // Don't lose the tap: start converting now.
                pendingSettings?.let { startRun(it) }
                pendingSettings = null
            },
            onAskPermission = {
                showNotificationExplainer = false
                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            },
        )
    }

    // Android may stop an app in the background to save battery, which would end a long conversion
    // part-way. We explain, then open Android's own battery screen for the person to allow it (this
    // needs no permission). The run starts either way, so the tap is never lost. Shown at most once
    // per app launch, never when the app is already allowed, and never again after "Don't ask again".
    if (showBatteryNag) {
        val proceed = {
            showBatteryNag = false
            batterySettings?.let { BatchRunner.start(ctx, it, audio) }
            batterySettings = null
        }
        BatteryNagDialog(onProceed = proceed)
    }

    // Shown once, the first time Best quality is chosen.
    if (showBestNotice) {
        BestQualityNoticeDialog(onGotIt = { showBestNotice = false })
    }

    val target: Target? = targets.getOrNull(targetIdx)
    val isCustom = target == null
    val limitKb: Int = target?.limitKb ?: ((customMb.toDoubleOrNull() ?: 0.0) * 1024).toInt()
    val maxDuration: Int = target?.maxDurationSec ?: Converter.NO_LENGTH_LIMIT
    val minW: Int = target?.minWidth ?: 0
    val minH: Int = target?.minHeight ?: 0
    val audioAllowed: Boolean = target?.audioAllowed ?: true
    val effectiveRemoveAudio = if (audio) false else removeAudio || !audioAllowed

    // The frame rate cap. A Custom entry that isn't a whole number in range can't be used: Convert
    // stays disabled until it is fixed, and in the meantime the cards use the default.
    // Audio Mode: a card with an animated GIF always uses its GIF's own frame rate (the highest of its GIFs), whatever is chosen
    // here. When every card in the Holding Pen has one, the choices are locked.
    val gifInPen = audio && queue.any { it.inPen && it.isAudio && it.audio?.hasGif == true }
    val plainInPen = audio && queue.any { it.inPen && it.isAudio && it.audio?.hasGif != true }
    val fpsLocked = gifInPen && !plainInPen
    val isCustomFps = fpsChoice >= fpsPresets.size
    val customFpsValue: Int? = customFps.trim().toIntOrNull()?.takeIf { it in Converter.MIN_FPS..Converter.MAX_FPS }
    val fpsOk = fpsLocked || !isCustomFps || customFpsValue != null

    // The Custom resolution. Each side may be left empty; one that is filled in has to be a whole
    // number in range, or Convert stays disabled until it is fixed.
    val customWValue: Int? = customW.trim().toIntOrNull()?.takeIf { it in Converter.MIN_CUSTOM_PX..Converter.MAX_CUSTOM_PX }
    val customHValue: Int? = customH.trim().toIntOrNull()?.takeIf { it in Converter.MIN_CUSTOM_PX..Converter.MAX_CUSTOM_PX }
    val widthOk = !isCustom || customW.isBlank() || customWValue != null
    val heightOk = !isCustom || customH.isBlank() || customHValue != null
    val resOk = widthOk && heightOk
    val customWidth: Int = if (isCustom) customWValue ?: 0 else 0
    val customHeight: Int = if (isCustom) customHValue ?: 0 else 0
    val targetFps: Int = if (!isCustomFps) fpsPresets[fpsChoice] else (customFpsValue ?: if (audio) AUDIO_FPS_PRESETS[AUDIO_FPS_DEFAULT_INDEX] else Converter.DEFAULT_FPS)

    // What the next conversion would use. The queue cards work their split estimates out from it,
    // so they follow the board, size limit, audio, effort, frame rate and Advanced settings as they change.
    // Short enough to sit on every card: up to three boards, then a count.
    val targetLabel = when {
        target == null -> "Custom"
        target.boards.size <= 3 -> target.boardsLabel
        else -> target.boards.take(3).joinToString(" ") { "/$it/" } + " … (${target.boards.size} boards)"
    }
    val cardSettings = Settings(
        limitKb * 1024L, maxDuration, !effectiveRemoveAudio, minW, minH,
        fastEncode = fastEncode,
        useVp9 = useVp9,
        fastest = fastest && fastestOffered,
        bestQuality = bestQuality,
        targetFps = targetFps,
        tuning = TuningStore.current,
        isCustom = isCustom,
        targetLabel = targetLabel,
        customWidth = customWidth,
        customHeight = customHeight,
        audioKbps = audioKbps,
        audioMode = audio,
        titleCardSec = if (audio) AudioFit.CARD_OPTIONS[cardChoice] else 0,
    )
    // The editor (a page of its own) shows what an edit does to the conversion with these.
    if (!audio) SideEffect { BatchRunner.liveSettings = cardSettings }

    // What each closed settings group shows as its current choice.
    val limitOk = limitKb >= 256
    val boardsSummaryText = if (target != null) {
        "${target.title}\n${boardsSummary(target)}"
    } else if (!limitOk) {
        "Custom · enter a limit of at least 0.25 MB"
    } else if (!resOk) {
        "Custom · enter a resolution from ${Converter.MIN_CUSTOM_PX} to ${Converter.MAX_CUSTOM_PX} px, or leave it empty"
    } else {
        "Custom · $customMb MB limit · no length limit · " +
            (customResLabel(customWidth, customHeight) ?: "resolution picked automatically")
    }
    val audioSummaryText = when {
        !audioAllowed -> "Off · this target doesn't allow sound"
        removeAudio -> "Off · output has no sound"
        else -> "On · sound is kept at ${Converter.audioLabel(audioKbps)}"
    }
    val fpsSummaryText = when {
        fpsLocked -> "Locked · each card uses its GIF's frame rate"
        isCustomFps && customFpsValue == null ->
            "Custom · enter a whole number from ${Converter.MIN_FPS} to ${Converter.MAX_FPS}"
        isCustomFps -> "Custom · up to $customFpsValue fps"
        fpsChoice == fpsDefaultIndex -> "${fpsPresets[fpsChoice]} fps (default)"
        else -> "${fpsPresets[fpsChoice]} fps"
    }
    val encodingSummaryText =
        if (bestQuality) "Best quality" else if (fastest) "Fastest" else if (fastEncode) "Speed" else "Quality (default)"
    val codecSummaryText = if (useVp9) "VP9" else "VP8 (default)"

    // The Holding Pen holds videos you've added until the button below sends them to the queue.
    val penAny = queue.any { it.inPen && it.isAudio == audio }
    val penCount = queue.count { it.inPen && it.info != null && it.isAudio == audio }

    // Starts a run: asks for notification permission first when that is still needed (see startRun).
    val beginConvert: () -> Unit = {
        val settings = cardSettings
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
            startRun(settings)
        }
    }

    // Audio Mode: pressing Convert with cards that have no image asks first (Add image / Continue anyway),
    // so there is a chance to set it right. Each card is asked about once; continuing makes it with a black picture.
    val askedNoImage = remember { mutableStateListOf<Long>() }
    var showNoImage by remember { mutableStateOf(false) }
    var noImagePickFor by remember { mutableStateOf(-1L) }
    val noImagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (noImagePickFor >= 0L && uris.isNotEmpty()) BatchRunner.setAudioImages(ctx, noImagePickFor, uris, false)
        noImagePickFor = -1L
    }
    val withoutImage = queue.filter {
        it.isAudio && it.info != null && !it.probeFailed && it.status == ItemStatus.WAITING &&
            it.audio?.images.isNullOrEmpty() && it.id !in askedNoImage
    }
    if (audio && showNoImage) {
        val first = withoutImage.firstOrNull()
        if (first == null) {
            showNoImage = false
        } else {
            val more = withoutImage.size - 1
            AlertDialog(
                onDismissRequest = { showNoImage = false },
                shape = CutCornerShape(topStart = 20.dp, bottomEnd = 20.dp),
                containerColor = P5.Ink,
                titleContentColor = P5.White,
                textContentColor = P5.White,
                title = { Text("No image".uppercase(), style = MaterialTheme.typography.titleMedium) },
                text = {
                    Text(
                        "\"${first.customName ?: first.name}\"" + (if (more > 0) " and $more more have" else " has") +
                            " no image. Add one to show while it plays, or continue and the placeholder picture is used (black, unless you chose one in Settings). " +
                            "An image can also be added or changed from a card's /// button."
                    )
                },
                confirmButton = {
                    P5Button("Add image", onClick = {
                        showNoImage = false
                        noImagePickFor = first.id
                        noImagePicker.launch("image/*")
                    })
                },
                dismissButton = {
                    P5Button("Continue anyway", onClick = {
                        showNoImage = false
                        askedNoImage.addAll(withoutImage.map { it.id })
                        beginConvert()
                    }, kind = P5Kind.Ghost)
                },
            )
        }
    }

    // The Menu button floats over the scrolling page (see the end of this function), so it stays in the
    // top right wherever the page is scrolled to.
    Box(Modifier.fillMaxSize()) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            // Right padding keeps the title clear of the floating Menu button.
            Column(Modifier.weight(1f).padding(end = 96.dp)) {
                P5Title("WebMChan", upperCase = false)
                Text(
                    (if (audio) "Audio Mode" else "4chan WebM converter").uppercase(),
                    style = MaterialTheme.typography.labelMedium,
                    color = P5.RedBright,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }

        P5Button(
            if (audio) (if (queue.none { it.isAudio }) "Choose audio files" else "Add audio files") else if (queue.none { !it.isAudio }) "Choose videos" else "Add videos",
            onClick = { picker.launch(if (audio) arrayOf("audio/*", "video/*") else arrayOf("video/*")) },
            enabled = !cancelling, // allowed any time: new videos wait in the Holding Pen
            modifier = Modifier.fillMaxWidth(),
        )

        // Only while a shared video is being copied in; gone as soon as it's in the queue.
        if (copyingTotal > 0) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    if (copyingTotal == 1) "Copying shared video…"
                    else "Copying shared videos… ${minOf(copyingDone + 1, copyingTotal)} of $copyingTotal",
                    style = MaterialTheme.typography.bodyMedium,
                )
                // Hand-driven (pass1Sweep), not Compose's own indeterminate animation: see pass1Sweep.
                P5Progress(progress = { pass1Sweep })
            }
        }

        // ---- Holding pen ----
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            P5SectionTitle(if (penAny) "Holding pen (${queue.count { it.inPen && it.isAudio == audio }})" else "Holding pen")
            Spacer(Modifier.weight(1f))
            if (penAny) P5Button("Clear pen", onClick = { BatchRunner.clearPen(audio) }, kind = P5Kind.Secondary)
        }
        if (penAny) {
            SectionNote("Newest at the top. The settings below apply to these $noun when you send them to the queue.")
            QueueCards(section = CardSection.PEN, busy = busy, liveSettings = cardSettings, audio = audio)
        } else {
            SectionNote(if (audio) "Choose or share audio files to add them here." else "Choose or share videos to add them here.", headline = "Nothing waiting")
        }

        CollapsibleSection(
            title = "Target boards",
            summary = boardsSummaryText,
            expanded = boardsOpen,
            onToggle = { boardsOpen = !boardsOpen },
            summaryIsError = isCustom && (!limitOk || !resOk),
        ) {
            targets.forEachIndexed { idx, t ->
                TargetRow(
                    selected = targetIdx == idx,
                    enabled = true,
                    title = t.title,
                    subtitle = t.boardsLabel,
                ) {
                    targetIdx = idx
                    if (t.audioAllowed) removeAudio = false
                }
            }
            TargetRow(
                selected = isCustom,
                enabled = true,
                title = "Custom",
                subtitle = "Your own size limit and resolution, with no length limit",
            ) {
                targetIdx = targets.size
                removeAudio = false
            }
            if (isCustom) {
                OutlinedTextField(
                    value = customMb,
                    onValueChange = { customMb = it },
                    label = { Text("Limit in MB") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    enabled = true,
                    shape = CutCornerShape(topStart = 10.dp, bottomEnd = 10.dp),
                )
                Text(
                    "Resolution (optional). Fill in one side and the other follows the video's shape. " +
                        "Fill in both and the picture is scaled to fit inside that size. Either way " +
                        "the aspect ratio is kept, so nothing is stretched. Leave both empty to let the " +
                        "app pick a size that suits the limit.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = customW,
                        onValueChange = { customW = it },
                        label = { Text("Width (px)") },
                        isError = !widthOk,
                        supportingText = {
                            if (!widthOk) Text("${Converter.MIN_CUSTOM_PX} to ${Converter.MAX_CUSTOM_PX}")
                        },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        enabled = true,
                        shape = CutCornerShape(topStart = 10.dp, bottomEnd = 10.dp),
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = customH,
                        onValueChange = { customH = it },
                        label = { Text("Height (px)") },
                        isError = !heightOk,
                        supportingText = {
                            if (!heightOk) Text("${Converter.MIN_CUSTOM_PX} to ${Converter.MAX_CUSTOM_PX}")
                        },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        enabled = true,
                        shape = CutCornerShape(topStart = 10.dp, bottomEnd = 10.dp),
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        // Sound, frame rate, encoding and codec live in one group that starts closed: the defaults suit
        // most videos, so a newcomer can ignore them and an advanced user still has every choice.
        CollapsibleSection(
            title = "Encoding settings",
            summary = if (fpsOk) (if (audio) "Defaults are fine for most tracks" else "Defaults are fine for most videos") else "Frame rate: enter a whole number from ${Converter.MIN_FPS} to ${Converter.MAX_FPS}",
            expanded = deepOpen,
            onToggle = { deepOpen = !deepOpen },
            summaryIsError = !fpsOk,
            slim = true,
        ) {
            if (!audio) CollapsibleSection(
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
                                else -> "Sound is on: kept in the output at the quality chosen below. Only targets marked \"Audio OK\" allow it."
                            },
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    // On = sound kept. (removeAudio is the opposite way round internally.)
                    Switch(
                        checked = !effectiveRemoveAudio,
                        onCheckedChange = { removeAudio = !it },
                        enabled = audioAllowed,
                        colors = p5SwitchColors(),
                    )
                }
                // Only while sound is on. Lower rates leave more of the size limit for the picture.
                if (!effectiveRemoveAudio) {
                    Text(
                        "Sound quality. A tight size limit may lower it a little automatically; it never goes above the choice here.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    TargetRow(
                        selected = audioKbps == 16,
                        enabled = true,
                        title = "16 kbps (mono)",
                        subtitle = "Poor quality, mono. Frees the most space for the picture.",
                    ) { audioKbps = 16 }
                    TargetRow(
                        selected = audioKbps == 32,
                        enabled = true,
                        title = "32 kbps",
                        subtitle = "Noticeably thinner sound. Frees space for the picture.",
                    ) { audioKbps = 32 }
                    TargetRow(
                        selected = audioKbps == 64,
                        enabled = true,
                        title = "64 kbps (default)",
                        subtitle = "Recommended. Good sound for the space it takes.",
                    ) { audioKbps = 64 }
                    TargetRow(
                        selected = audioKbps == 128,
                        enabled = true,
                        title = "128 kbps",
                        subtitle = "High quality. Worth it when the size limit is generous.",
                    ) { audioKbps = 128 }
                    TargetRow(
                        selected = audioKbps == 256,
                        enabled = true,
                        title = "256 kbps",
                        subtitle = "Highest quality. Takes a big share of the size limit.",
                    ) { audioKbps = 256 }
                }
            }

            CollapsibleSection(
                title = if (audio) "Image frame rate" else "Frame rate",
                summary = fpsSummaryText,
                expanded = fpsOpen,
                onToggle = { fpsOpen = !fpsOpen },
                summaryIsError = !fpsOk,
            ) {
                if (audio) {
                    Text(
                        "How many times a second the picture is drawn. The picture never changes, so a low rate " +
                            "costs almost nothing and leaves the most room for the sound. Some players and boards " +
                            "behave better with a higher rate; the sound is the same either way.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    if (gifInPen) {
                        Text(
                            if (fpsLocked) {
                                "Every card in the Holding Pen has an animated GIF, so each one uses its GIFs' own frame rate: " +
                                    "the highest of the GIFs on that card. These choices are locked."
                            } else {
                                "A card with an animated GIF always uses its GIFs' own frame rate (the highest of the GIFs on that card). " +
                                    "These choices only apply to cards without one."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = P5.Amber,
                        )
                    }
                    fpsPresets.forEachIndexed { idx, f ->
                        TargetRow(
                            selected = fpsChoice == idx,
                            enabled = !fpsLocked,
                            title = if (idx == fpsDefaultIndex) "$f fps (default)" else "$f fps",
                            subtitle = if (idx == fpsDefaultIndex) "Recommended. Keeps the picture cost very small." else "A higher rate costs a little more of the size limit.",
                        ) { fpsChoice = idx }
                    }
                } else {
                Text(
                    "The most frames per second the output may have. A video that's already slower keeps its own rate; frame rates are never raised.",
                    style = MaterialTheme.typography.bodySmall
                )
                TargetRow(
                    selected = fpsChoice == 0,
                    enabled = true,
                    title = "10 fps",
                    subtitle = "Good for slide shows, where the picture changes only now and then and every frame can carry far more detail.",
                ) { fpsChoice = 0 }
                TargetRow(
                    selected = fpsChoice == 1,
                    enabled = true,
                    title = "15 fps",
                    subtitle = "Good for low frame rate animation, where each drawing is held for a few frames anyway.",
                ) { fpsChoice = 1 }
                TargetRow(
                    selected = fpsChoice == 2,
                    enabled = true,
                    title = "24 fps",
                    subtitle = "Film standard. Fewer frames means more detail in each one on a tight limit. A 30 or 60 fps source loses frames unevenly, so motion can look slightly juddery.",
                ) { fpsChoice = 2 }
                TargetRow(
                    selected = fpsChoice == 3,
                    enabled = true,
                    title = "30 fps (default)",
                    subtitle = "Recommended. A good balance of smooth motion and detail.",
                ) { fpsChoice = 3 }
                TargetRow(
                    selected = fpsChoice == 4,
                    enabled = true,
                    title = "60 fps",
                    subtitle = "Smoother motion, but each second needs twice the bits for the same detail. On small size limits expect smaller frames and a rougher picture.",
                ) { fpsChoice = 4 }
                }
                TargetRow(
                    selected = isCustomFps,
                    enabled = !fpsLocked,
                    title = "Custom",
                    subtitle = "Your own limit, ${Converter.MIN_FPS} to ${Converter.MAX_FPS} fps.",
                ) { fpsChoice = fpsPresets.size }
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
                        enabled = !fpsLocked,
                        shape = CutCornerShape(topStart = 10.dp, bottomEnd = 10.dp),
                    )
                }
            }

            if (audio) CollapsibleSection(
                title = "Title card",
                summary = AudioFit.cardLabel(AudioFit.CARD_OPTIONS[cardChoice]),
                expanded = cardOpen,
                onToggle = { cardOpen = !cardOpen },
            ) {
                Text(
                    "Shows the artist and title (or the file name) in the bottom-right corner of the picture. " +
                        "Each card's /// menu can change its words. Off by default.",
                    style = MaterialTheme.typography.bodySmall
                )
                AudioFit.CARD_OPTIONS.forEachIndexed { idx, sec ->
                    TargetRow(
                        selected = cardChoice == idx,
                        enabled = true,
                        title = if (sec == 0) "Off (default)" else if (sec == AudioFit.CARD_WHOLE) "Whole track" else "First $sec seconds",
                        subtitle = when (sec) {
                            0 -> "No title on the picture."
                            AudioFit.CARD_WHOLE -> "Stays on screen for the whole file."
                            else -> "Shown at the start of each file, then it goes away."
                        },
                    ) { cardChoice = idx }
                }
            }

            EncodingAndCodecSections(
                audio = audio,
                bestQuality = bestQuality,
                fastEncode = fastEncode,
                fastest = fastest,
                useVp9 = useVp9,
                fastestOffered = fastestOffered,
                encodingOpen = encodingOpen,
                onToggleEncoding = { encodingOpen = !encodingOpen },
                encodingSummary = encodingSummaryText,
                codecOpen = codecOpen,
                onToggleCodec = { codecOpen = !codecOpen },
                codecSummary = codecSummaryText,
                onPickBest = {
                    bestQuality = true; fastEncode = false; fastest = false
                    // A heads-up the first time only: this mode runs hot and long.
                    if (!prefs.getBoolean("best_notice_seen", false)) showBestNotice = true
                },
                onPickQuality = { bestQuality = false; fastEncode = false; fastest = false },
                onPickSpeed = { bestQuality = false; fastEncode = true; fastest = false },
                onPickFastest = { bestQuality = false; fastEncode = false; fastest = true },
                onPickVp8 = { useVp9 = false },
                onPickVp9 = { useVp9 = true },
            )
        }

        val queueReady = queue.count { !it.inPen && it.info != null && it.status != ItemStatus.CONVERTING && it.isAudio == audio }
        val pending = penCount + queueReady
        val canGo = !otherBusy && pending > 0 && probing == 0 && copyingTotal == 0 && limitOk && fpsOk && resOk && !busy
        val penReady = penCount > 0 && probing == 0 && copyingTotal == 0 && limitOk && fpsOk && resOk && !cancelling

        // One button: with nothing running it sends the Holding Pen to the queue and starts converting;
        // while a batch runs it sends the Holding Pen to that batch.
        if (otherBusy) {
            SectionNote(
                if (audio) "Video Mode is converting right now. Only one mode can run at a time, so Audio Mode waits until it is done. You can still add audio files to the holding pen."
                else "Audio Mode is converting right now. Only one mode can run at a time, so Video Mode waits until it is done. You can still add videos to the holding pen.",
                headline = if (audio) "Video Mode in use" else "Audio Mode in use",
            )
            P5Button(
                if (audio) "Go to Video Mode" else "Go to Audio Mode",
                onClick = onOtherMode,
                kind = P5Kind.Secondary,
                modifier = Modifier.fillMaxWidth(),
            )
        } else if (busy) {
            P5Button(
                when (penCount) {
                    0 -> "Add to running batch"
                    1 -> "Add 1 ${if (audio) "track" else "video"} to running batch"
                    else -> "Add $penCount $noun to running batch"
                },
                onClick = { BatchRunner.addPenToRun(cardSettings) },
                enabled = penReady,
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
        P5Button(
            if (pending > 1) "Convert $pending $noun" else "Convert",
            onClick = {
                // Audio Mode: cards that have no image (the pen's, not yet asked about) get the warning first.
                if (audio && withoutImage.any { c -> c.inPen }) showNoImage = true else beginConvert()
            },
            enabled = canGo,
            modifier = Modifier.fillMaxWidth(),
        )

        }

        // ---- Queue ----
        // Only shown while a video is waiting for its turn; gone entirely when there is none.
        val queueCount = queue.count { !it.inPen && it.status != ItemStatus.CONVERTING && it.isAudio == audio }
        if (queueCount > 0) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                P5SectionTitle("Queue ($queueCount)")
                Spacer(Modifier.weight(1f))
                if (!busy) {
                    P5Button("Clear queue", onClick = { BatchRunner.clearQueue(audio) }, kind = P5Kind.Secondary)
                }
            }
            SectionNote("Converted from the bottom up. ${if (audio) "Tracks" else "Videos"} sent from the holding pen join at the top.")
            QueueCards(section = CardSection.QUEUE, busy = busy, liveSettings = cardSettings, audio = audio)
        }

        // ---- Converting ----
        Row(Modifier.fillMaxWidth()) { P5SectionTitle("Converting") }
        if (otherBusy) {
            SectionNote("Nothing is converting here.", headline = "Idle")
        } else if (busy) {
            QueueCards(section = CardSection.CONVERTING, busy = busy, liveSettings = cardSettings, audio = audio)
            ConvertingControls(
                paused = paused,
                pauseRequest = pauseRequest,
                currentParts = currentParts,
                runTotal = runTotal,
                canResume = probing == 0 && !cancelling,
                onResume = { BatchRunner.resume(ctx, cardSettings) },
                onCancel = { if (AppSettings.get(AppToggle.CONFIRM_DESTRUCTIVE)) showCancelConfirm = true else BatchRunner.cancelAll() },
            )
        } else {
            SectionNote("Nothing is converting.", headline = "Idle")
            if (runTotal > 1) summary?.let { Text(it, style = MaterialTheme.typography.titleSmall) }
        }

        if (!otherBusy) {
            LogPanel(
                lines = logLines,
                listState = logListState,
                expanded = logExpanded,
                onToggleExpanded = { logExpanded = !logExpanded },
                busy = busy,
                paused = paused,
                pauseRequest = pauseRequest,
                notice = notice,
                status = status,
                progress = progress,
                sweep = { pass1Sweep },
            )
        }

        // ---- Finished ----
        FinishedSection(
            finished = finished,
            audio = audio,
            busy = busy,
            maxDuration = maxDuration,
            isCustom = isCustom,
            minW = minW,
            minH = minH,
            settings = cardSettings,
        )
        Spacer(Modifier.height(8.dp))
    }
    P5Button(
        "Menu",
        onClick = onMenu,
        kind = P5Kind.Secondary,
        modifier = Modifier.align(Alignment.TopEnd).padding(top = 20.dp, end = 20.dp),
    )
    }
}