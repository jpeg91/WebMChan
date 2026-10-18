package app.webmchan

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
                        // App stays composed underneath, so opening About doesn't interrupt a
                        // running conversion or lose the queue.
                        App(onAbout = { showAbout = true })
                        if (showAbout) {
                            Surface(Modifier.fillMaxSize()) { AboutScreen(onBack = { showAbout = false }) }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Thread-safe, size-capped log. ffmpeg's repeating "frame=..." status line updates in place
 * instead of flooding the readout.
 */
private class LogBuffer(private val maxLines: Int = 400) {
    private val lock = Any()
    private var lines: List<String> = emptyList()

    fun clear(publish: (List<String>) -> Unit) = synchronized(lock) {
        lines = emptyList()
        publish(lines)
    }

    fun add(raw: String, publish: (List<String>) -> Unit) = synchronized(lock) {
        var next = lines
        for (piece in raw.split('\n', '\r')) {
            val line = piece.trim()
            if (line.isEmpty()) continue
            next = if (line.startsWith("frame=") && next.lastOrNull()?.startsWith("frame=") == true) {
                next.dropLast(1) + line
            } else {
                next + line
            }
        }
        lines = if (next.size > maxLines) next.takeLast(maxLines) else next
        publish(lines)
    }
}

private enum class ItemStatus { WAITING, CONVERTING, DONE, FAILED, CANCELLED }

/** One video in the batch queue. Immutable: update by replacing the list entry with a copy. */
private data class QueueItem(
    val id: Long,
    val uri: Uri,
    val name: String,
    /** null until probed (see [probeFailed]). */
    val info: SourceInfo? = null,
    val probeFailed: Boolean = false,
    val status: ItemStatus = ItemStatus.WAITING,
    /** Result or error text shown under the item. */
    val detail: String? = null,
    /** Where the converted file was saved, once done. */
    val savedUri: Uri? = null,
)

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
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onUp: () -> Unit,
    onDown: () -> Unit,
    onRemove: () -> Unit,
) {
    val ctx = LocalContext.current
    val i = item.info
    Column(
        Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
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
            if (!busy) {
                QueueButton("▲", canMoveUp, onUp)
                QueueButton("▼", canMoveDown, onDown)
                QueueButton("✕", true, onRemove)
            }
        }

        if (i != null && item.status != ItemStatus.DONE) {
            if (i.durationSec > maxDuration) {
                Text(
                    "Longer than ${maxDuration}s: it will be cut to the first ${maxDuration}s.",
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
    val scope = rememberCoroutineScope()
    val view = LocalView.current

    // The batch queue, in processing order. Settings below apply to every video in it.
    val queue = remember { mutableStateListOf<QueueItem>() }
    val idCounter = remember { AtomicLong(0) }
    // Number of probe passes currently running (new videos are probed one after another).
    var probing by remember { mutableIntStateOf(0) }

    // Index into TARGETS; TARGETS.size means "Custom".
    var targetIdx by remember { mutableIntStateOf(0) }
    var customMb by remember { mutableStateOf("4") }
    var removeAudio by remember { mutableStateOf(true) }
    var fastEncode by remember { mutableStateOf(false) }
    var useVp9 by remember { mutableStateOf(false) }
    var fastest by remember { mutableStateOf(false) }

    var busy by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    // Pass 1 has no real progress to report. Compose's built-in indeterminate bar relies on
    // Compose's own animation system, which some phones freeze (the "Animator duration scale"
    // developer option), leaving it static. This sweeps a value by hand instead, the same way
    // pass 2's real progress is just pushed in as plain numbers, so it moves regardless.
    var pass1Sweep by remember { mutableFloatStateOf(0f) }
    var status by remember { mutableStateOf("") }
    var summary by remember { mutableStateOf<String?>(null) }
    // How many videos the current run is working through.
    var runTotal by remember { mutableIntStateOf(0) }
    // Set by the Skip / Cancel buttons; checked as each video finishes and while encoding.
    val skipFlag = remember { AtomicBoolean(false) }
    val cancelAllFlag = remember { AtomicBoolean(false) }

    // Where output is saved. Empty custom-dir state means the default Download/WebMChan/.
    var outputLabel by remember { mutableStateOf(OutputStorage.displayName(ctx)) }
    var hasCustomDir by remember { mutableStateOf(OutputStorage.savedDirUri(ctx) != null) }
    var folderError by remember { mutableStateOf<String?>(null) }

    val logBuffer = remember { LogBuffer() }
    var logLines by remember { mutableStateOf<List<String>>(emptyList()) }
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

    view.keepScreenOn = busy

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { picked ->
        // Skip anything already in the queue.
        val fresh = picked.filter { u -> queue.none { it.uri == u } }
        if (fresh.isNotEmpty()) {
            summary = null
            val added = fresh.map { u -> QueueItem(idCounter.incrementAndGet(), u, Converter.displayName(ctx, u)) }
            queue.addAll(added)
            scope.launch {
                probing++
                try {
                    for (item in added) {
                        val i = Converter.probe(ctx, item.uri)
                        // The item may have been removed while probing.
                        val at = queue.indexOfFirst { it.id == item.id }
                        if (at >= 0) queue[at] = queue[at].copy(info = i, probeFailed = i == null)
                    }
                } finally {
                    probing--
                }
            }
        }
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
                OutlinedButton(onClick = { queue.clear(); summary = null }, enabled = !busy) { Text("Clear") }
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
                    onUp = { val t = queue[idx]; queue[idx] = queue[idx - 1]; queue[idx - 1] = t },
                    onDown = { val t = queue[idx]; queue[idx] = queue[idx + 1]; queue[idx + 1] = t },
                    onRemove = { queue.removeAt(idx) },
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
            ) { targetIdx = idx }
        }
        TargetRow(
            selected = isCustom,
            enabled = !busy,
            title = "Custom",
            subtitle = "Your own size limit, no minimum resolution",
        ) { targetIdx = TARGETS.size }
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

        val pending = queue.count { it.info != null && it.status != ItemStatus.DONE }
        val canGo = pending > 0 && probing == 0 && limitKb >= 256 && !busy
        Button(
            onClick = {
                val todo = queue.filter { it.info != null && it.status != ItemStatus.DONE }.map { it.id }
                if (todo.isEmpty()) return@Button
                val settings = Settings(
                    limitKb * 1024L, maxDuration, !effectiveRemoveAudio, minW, minH,
                    fastEncode = fastEncode,
                    useVp9 = useVp9,
                    fastest = fastest,
                )
                summary = null
                runTotal = todo.size
                skipFlag.set(false)
                cancelAllFlag.set(false)
                busy = true
                progress = -1f // indeterminate until pass 2 reports real progress
                status = "Starting…"
                logBuffer.clear { logLines = it }
                scope.launch {
                    var okCount = 0
                    var failCount = 0
                    var skipCount = 0
                    try {
                        for ((n, id) in todo.withIndex()) {
                            if (cancelAllFlag.get()) break
                            val idx = queue.indexOfFirst { it.id == id }
                            if (idx < 0) continue
                            val item = queue[idx]
                            val src = item.info ?: continue

                            skipFlag.set(false)
                            queue[idx] = item.copy(status = ItemStatus.CONVERTING, detail = null, savedUri = null)
                            val pos = "Video ${n + 1} of ${todo.size}"
                            progress = -1f
                            status = "$pos: Starting…"
                            logBuffer.add("=== $pos: ${item.name} ===") { logLines = it }

                            val res = Converter.convert(
                                ctx, item.uri, src, settings,
                                onLog = { line -> logBuffer.add(line) { logLines = it } },
                            ) { p, s ->
                                progress = p
                                status = "$pos: $s"
                                // A Skip/Cancel pressed before ffmpeg was running (e.g. while the
                                // video was being copied) would otherwise be missed.
                                if (skipFlag.get() || cancelAllFlag.get()) Converter.cancel()
                            }

                            val at = queue.indexOfFirst { it.id == id }
                            if (at < 0) continue
                            when (res) {
                                is ConvertResult.Ok -> {
                                    val base = item.name.substringBeforeLast('.', item.name)
                                    // Marks the audio and encoding-effort choices used, e.g. "-AQ" for
                                    // audio kept at Quality, "-MF" for muted at Fastest.
                                    val audioMarker = if (res.audioIncluded) "A" else "M"
                                    val effortMarker = if (fastest) "F" else if (fastEncode) "S" else "Q"
                                    val outName = "$base-$audioMarker$effortMarker.webm"
                                    val saved = withContext(Dispatchers.IO) {
                                        OutputStorage.save(ctx, res.file, outName) {
                                            Converter.saveToDownloads(ctx, res.file, outName)
                                        }
                                    }
                                    res.file.delete()
                                    val mb = String.format(Locale.US, "%.2f", res.bytes / 1048576.0)
                                    val detail = buildString {
                                        append("Done: $mb MB, ${res.height}p, ${res.videoKbps} kbps video.\n")
                                        append(saved.message)
                                        if (res.trimmed) append("\nCut to ${maxDuration}s.")
                                        if (res.audioDropped) append("\nSource had no audio.")
                                    }
                                    queue[at] = queue[at].copy(
                                        status = if (saved.uri != null) ItemStatus.DONE else ItemStatus.FAILED,
                                        detail = detail,
                                        savedUri = saved.uri,
                                    )
                                    if (saved.uri != null) okCount++ else failCount++
                                }
                                is ConvertResult.Fail -> {
                                    queue[at] = queue[at].copy(status = ItemStatus.FAILED, detail = res.message)
                                    failCount++
                                }
                                ConvertResult.Cancelled -> {
                                    val all = cancelAllFlag.get()
                                    queue[at] = queue[at].copy(
                                        status = ItemStatus.CANCELLED,
                                        detail = if (all) "Cancelled." else "Skipped.",
                                    )
                                    skipCount++
                                    if (all) break
                                }
                            }
                        }
                    } finally {
                        summary = buildString {
                            append("Finished: $okCount done")
                            if (failCount > 0) append(", $failCount failed")
                            if (skipCount > 0) append(", $skipCount skipped or cancelled")
                            if (cancelAllFlag.get()) append(" (cancelled)")
                        }
                        busy = false
                    }
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
                        onClick = { skipFlag.set(true); Converter.cancel() },
                        modifier = Modifier.weight(1f),
                    ) { Text("Skip current") }
                    OutlinedButton(
                        onClick = { cancelAllFlag.set(true); Converter.cancel() },
                        modifier = Modifier.weight(1f),
                    ) { Text("Cancel all") }
                }
            } else {
                OutlinedButton(
                    onClick = { cancelAllFlag.set(true); Converter.cancel() },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Cancel") }
            }
            Text("Keep the app open while converting. Screen will not sleep until encoding is finished.", style = MaterialTheme.typography.bodySmall)
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

        val savedUris = queue.mapNotNull { it.savedUri }
        if (savedUris.size > 1 && !busy) {
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
        Spacer(Modifier.height(8.dp))
    }
}
