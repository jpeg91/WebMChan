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
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
            // The look (black, white and red) lives in Theme.kt. It is the same whatever the
            // phone's own light/dark or wallpaper colours are.
            P5Theme {
                var showMenu by remember { mutableStateOf(false) }
                var showLog by remember { mutableStateOf(false) }
                var showAbout by remember { mutableStateOf(false) }
                var showAdvanced by remember { mutableStateOf(false) }
                P5Background {
                    Box {
                        // App stays composed underneath, so opening a page doesn't lose the scroll
                        // position or the settings. The pages stack: Menu, then Log or About on top
                        // of it (and Advanced on top of About), so back returns to the one before.
                        App(onMenu = { showMenu = true })
                        if (showMenu) {
                            P5Background {
                                MenuScreen(
                                    onBack = { showMenu = false },
                                    onLog = { showLog = true },
                                    onAbout = { showAbout = true },
                                )
                            }
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
                            P5Background { AdvancedScreen(onBack = { showAdvanced = false }) }
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
    // The remove button (✕) gets the red accent.
    P5SquareButton(label, onClick, enabled, accent = label == "✕")
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
    /** Queue cards offer "Return to holding pen". */
    showReturn: Boolean = false,
    onReturn: () -> Unit = {},
) {
    val ctx = LocalContext.current
    val i = item.info
    // Whether the card's extra detail is open (see "Details" at the bottom of the card).
    var expanded by remember { mutableStateOf(false) }

    // Tapping the thumbnail plays the video: the converted file on a Finished card, the original
    // on a queue card. Nothing to play if the file is gone or the original couldn't be read.
    val finishedCard = item.status == ItemStatus.DONE
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
    P5Card(Modifier.fillMaxWidth(), accent = accent) {
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
            Text(
                "$prefix: ${settingsWithTarget(shownSettings)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
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
                append("Expected conversion output: ")
                if (numParts > 1) append("Each part ${String.format(Locale.US, "%.1f", est.partSec)}s - ")
                append("${est.height}p at ${est.videoKbps}kbps")
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

            // What kind of video the estimated bitrate suits (bits per pixel, so the frame size and
            // frame rate count). The poor brackets also say what could improve it, best first and
            // only what can still be changed; two tips at most. Not shown for an invalid custom limit.
            if (settings.limitBytes >= 256 * 1024L && est.videoKbps > 0) {
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
                Text(
                    "Quality Bracket: ${bracket.label}. ${bracket.suits}" +
                        if (tips.isNotEmpty()) "\nTo improve it: ${tips.joinToString(" or ")}." else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (bracket.poor) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant
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
            if (i != null && !item.probeFailed) {
                val len = String.format(Locale.US, "%.1f", i.durationSec)
                val audio = if (i.hasAudio) {
                    if (i.audioCodec.isNotBlank()) "audio ${prettyCodec(i.audioCodec)}" else "has audio"
                } else "no audio"
                Text(
                    "${if (finishedCard) "Output" else "Source"}: ${i.width}×${i.height} · $len s · ${fpsLabel(i, finishedCard)} · $audio",
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
                    "${if (shownSettings.keepAudio) "Sound on, ${Converter.audioLabel(shownSettings.audioKbps)}" else "Sound off"} · frame rate up to ${shownSettings.targetFps} fps · " +
                        "${effortName(shownSettings)} · ${if (shownSettings.useVp9) "VP9" else "VP8"}",
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

        item.savedUri?.let { saved ->
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
                modifier = Modifier.padding(top = 4.dp, bottom = 4.dp),
            )
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
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    title.uppercase(),
                    style = MaterialTheme.typography.labelLarge.copy(fontSize = 16.sp),
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

private fun effortName(s: Settings) = if (s.fastest) "Fastest" else if (s.fastEncode) "Speed" else "Quality"

/** The settings in one short line, e.g. "6 MB · up to 400 s · sound on · up to 30 fps · Quality · VP8". */
private fun settingsBrief(s: Settings): String =
    "${formatLimit(s.limitBytes)} · " +
        (if (s.maxDurationSec > 0) "up to ${s.maxDurationSec} s · " else "") +
        (customResLabel(s.customWidth, s.customHeight)?.let { "$it · " } ?: "") +
        "${soundLabel(s)} · " +
        "up to ${s.targetFps} fps · ${effortName(s)} · ${if (s.useVp9) "VP9" else "VP8"}"

/** "no sound" or e.g. "sound 64 kbps" (always with the bitrate, so every card reads the same). */
private fun soundLabel(s: Settings): String = when {
    !s.keepAudio -> "no sound"
    else -> "sound ${Converter.audioLabel(s.audioKbps)}"
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
private fun settingsLine(s: Settings): String = "Converting with: " + settingsWithTarget(s)

private enum class CardSection { PEN, QUEUE, CONVERTING }

/** A short grey line shown in a section that has nothing in it. */
@Composable
private fun EmptyNote(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/**
 * The cards of one section, newest at the top. A video in the queue shows the settings it was sent
 * with; one in the Holding Pen follows [liveSettings]. "Up" on a card moves it up the screen.
 */
@Composable
private fun QueueCards(section: CardSection, busy: Boolean, liveSettings: Settings) {
    val queue = BatchRunner.queue
    for (idx in queue.indices.reversed()) {
        val item = queue[idx]
        val show = when (section) {
            CardSection.PEN -> item.inPen
            CardSection.QUEUE -> !item.inPen && item.status != ItemStatus.CONVERTING
            CardSection.CONVERTING -> !item.inPen && item.status == ItemStatus.CONVERTING
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
            )
        }
    }
}

@Composable
fun App(onMenu: () -> Unit = {}) {
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
    val currentParts = BatchRunner.currentParts
    val logLines = BatchRunner.logLines

    // Index into TARGETS; TARGETS.size means "Custom".
    // The choices below are remembered between runs.
    val prefs = remember { ctx.getSharedPreferences("webmchan", Context.MODE_PRIVATE) }
    var targetIdx by remember { mutableIntStateOf(prefs.getInt("ui_target_idx", 0).coerceIn(0, TARGETS.size)) }
    var customMb by remember { mutableStateOf(prefs.getString("ui_custom_mb", "4") ?: "4") }
    // Custom resolution, in pixels. Empty = not set (automatic).
    var customW by remember { mutableStateOf(prefs.getString("ui_custom_w", "") ?: "") }
    var customH by remember { mutableStateOf(prefs.getString("ui_custom_h", "") ?: "") }
    var removeAudio by remember { mutableStateOf(prefs.getBoolean("ui_remove_audio", true)) }
    // One of Converter.AUDIO_KBPS_OPTIONS; anything else saved falls back to the default.
    var audioKbps by remember {
        mutableIntStateOf(
            prefs.getInt("ui_audio_kbps", Converter.DEFAULT_AUDIO_KBPS)
                .takeIf { it in Converter.AUDIO_KBPS_OPTIONS } ?: Converter.DEFAULT_AUDIO_KBPS
        )
    }
    var fastEncode by remember { mutableStateOf(prefs.getBoolean("ui_fast_encode", false)) }
    var useVp9 by remember { mutableStateOf(prefs.getBoolean("ui_use_vp9", false)) }
    var fastest by remember { mutableStateOf(prefs.getBoolean("ui_fastest", false)) }
    // Index into FPS_PRESETS; FPS_PRESETS.size means "Custom".
    var fpsChoice by remember {
        mutableIntStateOf(prefs.getInt("ui_fps_choice", FPS_DEFAULT_INDEX).coerceIn(0, FPS_PRESETS.size))
    }
    var customFps by remember { mutableStateOf(prefs.getString("ui_custom_fps", "30") ?: "30") }
    LaunchedEffect(targetIdx, customMb, customW, customH, removeAudio, audioKbps, fastEncode, useVp9, fastest, fpsChoice, customFps) {
        prefs.edit()
            .putInt("ui_target_idx", targetIdx)
            .putString("ui_custom_mb", customMb)
            .putString("ui_custom_w", customW)
            .putString("ui_custom_h", customH)
            .putBoolean("ui_remove_audio", removeAudio)
            .putInt("ui_audio_kbps", audioKbps)
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
            shape = CutCornerShape(topStart = 20.dp, bottomEnd = 20.dp),
            containerColor = P5.Ink,
            titleContentColor = P5.White,
            textContentColor = P5.White,
            title = { Text("Notification permission".uppercase(), style = MaterialTheme.typography.titleMedium) },
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
                P5Button(
                    if (explainerBlocked) "Grant Notification Permission" else "Continue",
                    onClick = {
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
                    },
                )
            },
            dismissButton = { P5Button("Skip", onClick = skip, kind = P5Kind.Ghost) },
        )
    }

    val target: Target? = TARGETS.getOrNull(targetIdx)
    val isCustom = target == null
    val limitKb: Int = target?.limitKb ?: ((customMb.toDoubleOrNull() ?: 0.0) * 1024).toInt()
    val maxDuration: Int = target?.maxDurationSec ?: Converter.NO_LENGTH_LIMIT
    val minW: Int = target?.minWidth ?: 0
    val minH: Int = target?.minHeight ?: 0
    val audioAllowed: Boolean = target?.audioAllowed ?: true
    val effectiveRemoveAudio = removeAudio || !audioAllowed

    // The frame rate cap. A Custom entry that isn't a whole number in range can't be used: Convert
    // stays disabled until it is fixed, and in the meantime the cards use the default.
    val isCustomFps = fpsChoice >= FPS_PRESETS.size
    val customFpsValue: Int? = customFps.trim().toIntOrNull()?.takeIf { it in Converter.MIN_FPS..Converter.MAX_FPS }
    val fpsOk = !isCustomFps || customFpsValue != null

    // The Custom resolution. Each side may be left empty; one that is filled in has to be a whole
    // number in range, or Convert stays disabled until it is fixed.
    val customWValue: Int? = customW.trim().toIntOrNull()?.takeIf { it in Converter.MIN_CUSTOM_PX..Converter.MAX_CUSTOM_PX }
    val customHValue: Int? = customH.trim().toIntOrNull()?.takeIf { it in Converter.MIN_CUSTOM_PX..Converter.MAX_CUSTOM_PX }
    val widthOk = !isCustom || customW.isBlank() || customWValue != null
    val heightOk = !isCustom || customH.isBlank() || customHValue != null
    val resOk = widthOk && heightOk
    val customWidth: Int = if (isCustom) customWValue ?: 0 else 0
    val customHeight: Int = if (isCustom) customHValue ?: 0 else 0
    val targetFps: Int = if (!isCustomFps) FPS_PRESETS[fpsChoice] else (customFpsValue ?: Converter.DEFAULT_FPS)

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
        fastest = fastest,
        targetFps = targetFps,
        tuning = TuningStore.current,
        isCustom = isCustom,
        targetLabel = targetLabel,
        customWidth = customWidth,
        customHeight = customHeight,
        audioKbps = audioKbps,
    )

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
        isCustomFps && customFpsValue == null ->
            "Custom · enter a whole number from ${Converter.MIN_FPS} to ${Converter.MAX_FPS}"
        isCustomFps -> "Custom · up to $customFpsValue fps"
        fpsChoice == FPS_DEFAULT_INDEX -> "${FPS_PRESETS[fpsChoice]} fps (default)"
        else -> "${FPS_PRESETS[fpsChoice]} fps"
    }
    val encodingSummaryText = if (fastest) "Fastest" else if (fastEncode) "Speed" else "Quality (default)"
    val codecSummaryText = if (useVp9) "VP9" else "VP8 (default)"

    // The Holding Pen holds videos you've added until the button below sends them to the queue.
    val penAny = queue.any { it.inPen }
    val penCount = queue.count { it.inPen && it.info != null }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                P5Title("WebMChan")
                Text(
                    "4chan WebM converter".uppercase(),
                    style = MaterialTheme.typography.labelMedium,
                    color = P5.RedBright,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            P5Button("Menu", onClick = onMenu, kind = P5Kind.Secondary)
        }

        P5Button(
            if (queue.isEmpty()) "Choose videos" else "Add videos",
            onClick = { picker.launch(arrayOf("video/*")) },
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
            P5SectionTitle(if (penAny) "Holding pen (${queue.count { it.inPen }})" else "Holding pen")
            Spacer(Modifier.weight(1f))
            if (penAny) P5Button("Clear pen", onClick = { BatchRunner.clearPen() }, kind = P5Kind.Secondary)
        }
        if (penAny) {
            Text(
                "Newest at the top. The settings below apply to these videos when you send them to the queue.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            QueueCards(section = CardSection.PEN, busy = busy, liveSettings = cardSettings)
        } else {
            EmptyNote("Nothing waiting. Choose or share videos to add them here.")
        }

        CollapsibleSection(
            title = "Target boards",
            summary = boardsSummaryText,
            expanded = boardsOpen,
            onToggle = { boardsOpen = !boardsOpen },
            summaryIsError = isCustom && (!limitOk || !resOk),
        ) {
            TARGETS.forEachIndexed { idx, t ->
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
                enabled = true,
                title = "24 fps",
                subtitle = "Film standard. Fewer frames means more detail in each one on a tight limit. A 30 or 60 fps source loses frames unevenly, so motion can look slightly juddery.",
            ) { fpsChoice = 0 }
            TargetRow(
                selected = fpsChoice == 1,
                enabled = true,
                title = "30 fps (default)",
                subtitle = "Recommended. A good balance of smooth motion and detail.",
            ) { fpsChoice = 1 }
            TargetRow(
                selected = fpsChoice == 2,
                enabled = true,
                title = "60 fps",
                subtitle = "Smoother motion, but each second needs twice the bits for the same detail. On small size limits expect smaller frames and a rougher picture.",
            ) { fpsChoice = 2 }
            TargetRow(
                selected = isCustomFps,
                enabled = true,
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
                    enabled = true,
                    shape = CutCornerShape(topStart = 10.dp, bottomEnd = 10.dp),
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
                enabled = true,
                title = "Quality (default)",
                subtitle = "Best detail, slowest transcode. Highly recommended for anything 'live action'.",
            ) { fastEncode = false; fastest = false }
            TargetRow(
                selected = fastEncode && !fastest,
                enabled = true,
                title = "Speed",
                subtitle = "Faster than Quality mode, only slightly softer detail.",
            ) { fastEncode = true; fastest = false }
            TargetRow(
                selected = fastest,
                enabled = true,
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
                enabled = true,
                title = "VP8 (default)",
                subtitle = "Recommended default. Fastest. It just works.",
            ) { useVp9 = false }
            TargetRow(
                selected = useVp9,
                enabled = true,
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

        val queueReady = queue.count { !it.inPen && it.info != null && it.status != ItemStatus.CONVERTING }
        val pending = penCount + queueReady
        val canGo = pending > 0 && probing == 0 && copyingTotal == 0 && limitOk && fpsOk && resOk && !busy
        val penReady = penCount > 0 && probing == 0 && copyingTotal == 0 && limitOk && fpsOk && resOk && !cancelling

        // The running batch's progress and controls (or, when it is paused, the Resume controls).
        val progressBlock: @Composable () -> Unit = {
            if (paused) {
                Text("Paused", style = MaterialTheme.typography.titleSmall)
                Text(
                    notice ?: "Nothing is converting. Press Resume to carry on.",
                    style = MaterialTheme.typography.bodySmall
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    P5Button(
                        "Resume",
                        onClick = { BatchRunner.resume(ctx, cardSettings) },
                        enabled = probing == 0 && !cancelling,
                        modifier = Modifier.weight(1f),
                    )
                    P5Button(
                        "Cancel all",
                        onClick = { BatchRunner.cancelAll() },
                        kind = P5Kind.Secondary,
                        modifier = Modifier.weight(1f),
                    )
                }
            } else {
            if (progress < 0f) {
                P5Progress(progress = { pass1Sweep })
            } else {
                P5Progress(progress = { progress })
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
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                P5Button(
                    "Pause now",
                    onClick = { BatchRunner.pauseNow() },
                    enabled = pauseRequest != PauseRequest.NOW,
                    kind = P5Kind.Secondary,
                    modifier = Modifier.weight(1f),
                )
                P5Button(
                    if (pauseRequest == PauseRequest.AFTER) "Undo pause" else "Pause after this conversion",
                    onClick = { BatchRunner.pauseAfter() },
                    enabled = pauseRequest != PauseRequest.NOW,
                    kind = P5Kind.Secondary,
                    modifier = Modifier.weight(1f),
                )
            }
            if (currentParts > 1) {
                // A split video: Skip part drops just the part being encoded and carries on with the
                // next one. With other videos waiting there is also Skip video (the whole video).
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    P5Button(
                        "Skip part",
                        onClick = { BatchRunner.skipPart() },
                        kind = P5Kind.Secondary,
                        modifier = Modifier.weight(1f),
                    )
                    if (runTotal > 1) {
                        P5Button(
                            "Skip video",
                            onClick = { BatchRunner.skipCurrent() },
                            kind = P5Kind.Secondary,
                            modifier = Modifier.weight(1f),
                        )
                    } else {
                        P5Button(
                            "Cancel",
                            onClick = { BatchRunner.cancelAll() },
                            kind = P5Kind.Secondary,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                if (runTotal > 1) {
                    P5Button(
                        "Cancel all",
                        onClick = { BatchRunner.cancelAll() },
                        kind = P5Kind.Secondary,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            } else if (runTotal > 1) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    P5Button(
                        "Skip current",
                        onClick = { BatchRunner.skipCurrent() },
                        kind = P5Kind.Secondary,
                        modifier = Modifier.weight(1f),
                    )
                    P5Button(
                        "Cancel all",
                        onClick = { BatchRunner.cancelAll() },
                        kind = P5Kind.Secondary,
                        modifier = Modifier.weight(1f),
                    )
                }
            } else {
                P5Button(
                    "Cancel",
                    onClick = { BatchRunner.cancelAll() },
                    kind = P5Kind.Secondary,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Text(
                "You can lock the screen or leave the app while this runs. A notification shows progress.",
                style = MaterialTheme.typography.bodySmall
            )
            }
        }

        // One button: with nothing running it sends the Holding Pen to the queue and starts converting;
        // while a batch runs it sends the Holding Pen to that batch.
        if (busy) {
            P5Button(
                when (penCount) {
                    0 -> "Add to running batch"
                    1 -> "Add 1 video to running batch"
                    else -> "Add $penCount videos to running batch"
                },
                onClick = { BatchRunner.addPenToRun(cardSettings) },
                enabled = penReady,
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
        P5Button(
            if (pending > 1) "Convert $pending videos" else "Convert",
            onClick = {
                val settings = Settings(
                    limitKb * 1024L, maxDuration, !effectiveRemoveAudio, minW, minH,
                    fastEncode = fastEncode,
                    useVp9 = useVp9,
                    fastest = fastest,
                    targetFps = targetFps,
                    tuning = TuningStore.current,
                    isCustom = isCustom,
                    targetLabel = targetLabel,
                    customWidth = customWidth,
                    customHeight = customHeight,
                    audioKbps = audioKbps,
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
        )

        }

        // ---- Queue ----
        val queueCount = queue.count { !it.inPen && it.status != ItemStatus.CONVERTING }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            P5SectionTitle(if (queueCount > 0) "Queue ($queueCount)" else "Queue")
            Spacer(Modifier.weight(1f))
            if (queueCount > 0 && !busy) {
                P5Button("Clear queue", onClick = { BatchRunner.clearQueue() }, kind = P5Kind.Secondary)
            }
        }
        if (queueCount > 0) {
            Text(
                "Converted from the bottom up. Videos sent from the holding pen join at the top.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            QueueCards(section = CardSection.QUEUE, busy = busy, liveSettings = cardSettings)
        } else {
            EmptyNote("Nothing queued. Videos you send from the holding pen wait here for their turn.")
        }

        // ---- Converting ----
        Row(Modifier.fillMaxWidth()) { P5SectionTitle("Converting") }
        if (busy) {
            queue.firstNotNullOfOrNull { if (!it.inPen) it.settings else null }?.let {
                Text(
                    settingsLine(it),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            QueueCards(section = CardSection.CONVERTING, busy = busy, liveSettings = cardSettings)
            progressBlock()
        } else {
            EmptyNote("Nothing is converting.")
            if (runTotal > 1) summary?.let { Text(it, style = MaterialTheme.typography.titleSmall) }
        }

        val lines = logLines
        if (lines.isNotEmpty()) {
            Row(Modifier.fillMaxWidth()) { P5SectionTitle("Log") }
            LazyColumn(
                state = logListState,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(150.dp)
                    .clip(CutCornerShape(topStart = 12.dp, bottomEnd = 12.dp))
                    .background(Color.Black)
                    .border(1.5.dp, P5.Line, CutCornerShape(topStart = 12.dp, bottomEnd = 12.dp))
                    .padding(10.dp),
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
            P5Button(
                "Copy log",
                onClick = { clipboard.setText(AnnotatedString(lines.joinToString("\n"))) },
                kind = P5Kind.Secondary,
                modifier = Modifier.fillMaxWidth(),
            )
        }


        // ---- Finished ----
        // Finished videos land here from the converting section, newest at the top.
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            P5SectionTitle(if (finished.isNotEmpty()) "Finished (${finished.size})" else "Finished")
            Spacer(Modifier.weight(1f))
            if (finished.isNotEmpty()) {
                P5Button("Clear finished", onClick = { BatchRunner.clearFinished() }, kind = P5Kind.Secondary)
            }
        }
        if (finished.isEmpty()) {
            EmptyNote("No finished videos yet.")
        } else {
            finished.asReversed().forEach { item ->
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
        Spacer(Modifier.height(8.dp))
    }
}
