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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Bring back the queue and Finished list from last time (does nothing if already loaded).
        BatchRunner.restore(applicationContext)
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
                Surface(Modifier.fillMaxSize()) {
                    Box {
                        // App stays composed underneath, so opening About doesn't lose the scroll
                        // position or the settings.
                        App(onAbout = { showAbout = true })
                        if (showAbout) {
                            Surface(Modifier.fillMaxSize()) { AboutScreen(onBack = { showAbout = false }) }
                        }
                    }
                }
            }
        }
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
    minW: Int,
    minH: Int,
    canMoveUp: Boolean = false,
    canMoveDown: Boolean = false,
    onUp: () -> Unit = {},
    onDown: () -> Unit = {},
    onRemove: () -> Unit = {},
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
                            "${i.width}×${i.height}, ${len}s, ${if (i.hasAudio) "has audio" else "no audio"} · $state",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
            if (showControls && !busy) {
                QueueButton("▲", canMoveUp, onUp)
                QueueButton("▼", canMoveDown, onDown)
                QueueButton("✕", true, onRemove)
            }
        }

        if (i != null && item.status != ItemStatus.DONE) {
            val numParts = Converter.partCount(i.durationSec, maxDuration)
            if (numParts > 1) {
                Text(
                    "Will be split into $numParts files to fit the ${maxDuration}s limit " +
                        "(${Converter.partName(1, numParts)}, ${Converter.partName(2, numParts)}, … " +
                        "${Converter.partName(numParts, numParts)}).",
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

@Composable
fun App(onAbout: () -> Unit = {}) {
    val ctx = LocalContext.current

    // The queue, the finished list, progress and the log all live in BatchRunner (not here), so a
    // running batch survives the screen being closed. Settings below apply to every video in it.
    val queue = BatchRunner.queue
    val finished = BatchRunner.finished
    val busy = BatchRunner.busy
    val probing = BatchRunner.probing
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
    LaunchedEffect(targetIdx, customMb, removeAudio, fastEncode, useVp9, fastest) {
        prefs.edit()
            .putInt("ui_target_idx", targetIdx)
            .putString("ui_custom_mb", customMb)
            .putBoolean("ui_remove_audio", removeAudio)
            .putBoolean("ui_fast_encode", fastEncode)
            .putBoolean("ui_use_vp9", useVp9)
            .putBoolean("ui_fastest", fastest)
            .apply()
    }

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
    LaunchedEffect(progress < 0f) {
        if (progress < 0f) {
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
                enabled = !busy,
                modifier = Modifier.weight(1f),
            ) { Text(if (queue.isEmpty()) "Choose videos" else "Add videos") }
            if (queue.isNotEmpty()) {
                OutlinedButton(onClick = { BatchRunner.clearQueue() }, enabled = !busy) { Text("Clear") }
            }
        }

        queue.forEachIndexed { idx, item ->
            key(item.id) {
                QueueRow(
                    item = item,
                    busy = busy,
                    maxDuration = maxDuration,
                    minW = minW,
                    minH = minH,
                    canMoveUp = idx > 0,
                    canMoveDown = idx < queue.lastIndex,
                    onUp = { BatchRunner.move(idx, -1) },
                    onDown = { BatchRunner.move(idx, 1) },
                    onRemove = { BatchRunner.removeAt(idx) },
                )
            }
        }
        if (queue.size > 1) {
            Text(
                "Videos are converted in this order, all with the settings below.",
                style = MaterialTheme.typography.bodySmall
            )
        }

        Text("Target boards", style = MaterialTheme.typography.titleSmall)
        TARGETS.forEachIndexed { idx, t ->
            TargetRow(
                selected = targetIdx == idx,
                enabled = !busy,
                title = t.title,
                subtitle = t.boardsLabel,
            ) {
                targetIdx = idx
                if (t.audioAllowed) removeAudio = false
            }
        }
        TargetRow(
            selected = isCustom,
            enabled = !busy,
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
                enabled = !busy,
            )
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.weight(1f)) {
                Text("Remove audio", style = MaterialTheme.typography.titleSmall)
                Text(
                    when {
                        !audioAllowed -> "This target doesn't allow audio; the space goes to video quality."
                        removeAudio -> "Output has no sound; the space goes to video quality."
                        else -> "Keeps sound (about 64 kbps). Only targets marked \"Audio OK\" accept it."
                    },
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Switch(
                checked = effectiveRemoveAudio,
                onCheckedChange = { removeAudio = it },
                enabled = !busy && audioAllowed,
            )
        }

        Text("Encoding", style = MaterialTheme.typography.titleSmall)
        TargetRow(
            selected = !fastEncode && !fastest,
            enabled = !busy,
            title = "Quality",
            subtitle = "Best detail, slowest transcode. Highly recommended for anything 'live action'.",
        ) { fastEncode = false; fastest = false }
        TargetRow(
            selected = fastEncode && !fastest,
            enabled = !busy,
            title = "Speed",
            subtitle = "Faster than Quality mode, only slightly softer detail.",
        ) { fastEncode = true; fastest = false }
        TargetRow(
            selected = fastest,
            enabled = !busy,
            title = "Fastest",
            subtitle = "Fastest, softest detail. Lower effort transcoding and smaller frames. Only recommended for low detail videos. ",
        ) { fastest = true }

        Text("Codec", style = MaterialTheme.typography.titleSmall)
        TargetRow(
            selected = !useVp9,
            enabled = !busy,
            title = "VP8",
            subtitle = "Recommended default. Fastest. It just works.",
        ) { useVp9 = false }
        TargetRow(
            selected = useVp9,
            enabled = !busy,
            title = "VP9",
            subtitle = "Much slower to convert. Newer standard. May look better. Low compatibility on older devices. Mostly included here as 'future proofing'",
        ) { useVp9 = true }

        Text("Save location", style = MaterialTheme.typography.titleSmall)
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

        val pending = queue.count { it.info != null }
        val canGo = pending > 0 && probing == 0 && limitKb >= 256 && !busy
        Button(
            onClick = {
                val settings = Settings(
                    limitKb * 1024L, maxDuration, !effectiveRemoveAudio, minW, minH,
                    fastEncode = fastEncode,
                    useVp9 = useVp9,
                    fastest = fastest,
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

        if (busy) {
            if (progress < 0f) {
                LinearProgressIndicator(progress = { pass1Sweep }, modifier = Modifier.fillMaxWidth())
            } else {
                LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
            }
            Text(status, style = MaterialTheme.typography.bodySmall)
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
                        minW = minW,
                        minH = minH,
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
