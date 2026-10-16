package app.webmchan

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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

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
                Surface(Modifier.fillMaxSize()) { App() }
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
fun App() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val view = LocalView.current

    var uri by remember { mutableStateOf<Uri?>(null) }
    var name by remember { mutableStateOf("") }
    var info by remember { mutableStateOf<SourceInfo?>(null) }
    var probeError by remember { mutableStateOf<String?>(null) }

    // Index into TARGETS; TARGETS.size means "Custom".
    var targetIdx by remember { mutableIntStateOf(0) }
    var customMb by remember { mutableStateOf("4") }
    var removeAudio by remember { mutableStateOf(true) }
    var fastEncode by remember { mutableStateOf(false) }
    var useVp9 by remember { mutableStateOf(false) }
    var fastest by remember { mutableStateOf(false) }

    var busy by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    var status by remember { mutableStateOf("") }
    var resultMessage by remember { mutableStateOf<String?>(null) }
    var savedUri by remember { mutableStateOf<Uri?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }

    val logBuffer = remember { LogBuffer() }
    var logLines by remember { mutableStateOf<List<String>>(emptyList()) }
    val logListState = rememberLazyListState()
    val clipboard = LocalClipboardManager.current

    // Keep the newest line in view.
    LaunchedEffect(logLines) {
        if (logLines.isNotEmpty()) logListState.scrollToItem(logLines.lastIndex)
    }

    view.keepScreenOn = busy

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { picked ->
        if (picked != null) {
            uri = picked
            name = Converter.displayName(ctx, picked)
            info = null
            probeError = null
            resultMessage = null
            savedUri = null
            scope.launch {
                val i = Converter.probe(ctx, picked)
                info = i
                if (i == null) probeError = "Couldn't read this video."
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
        Text("WebMChan 4chan WebM converter", style = MaterialTheme.typography.headlineMedium)

        Button(onClick = { picker.launch(arrayOf("video/*")) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
            Text(if (uri == null) "Choose video" else "Choose another video")
        }

        if (uri != null) {
            Text(name, style = MaterialTheme.typography.titleSmall)
            val i = info
            when {
                probeError != null -> Text(probeError!!, color = MaterialTheme.colorScheme.error)
                i == null -> Text("Reading video…")
                else -> {
                    val len = String.format(Locale.US, "%.1f", i.durationSec)
                    Text("${i.width}×${i.height}, ${len}s, ${if (i.hasAudio) "has audio" else "no audio"}")
                    if (i.durationSec > maxDuration) {
                        Text(
                            "Longer than ${maxDuration}s: it will be cut to the first ${maxDuration}s.",
                            color = MaterialTheme.colorScheme.tertiary
                        )
                    }
                    if ((minW > 0 || minH > 0) && (i.width < minW || i.height < minH)) {
                        Text(
                            "Below this target's minimum (${minW}×${minH}): it will be scaled up, keeping its aspect ratio.",
                            color = MaterialTheme.colorScheme.tertiary
                        )
                    }
                }
            }
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
            subtitle = "Best detail, slowest transcode.",
        ) { fastEncode = false; fastest = false }
        TargetRow(
            selected = fastEncode && !fastest,
            enabled = !busy,
            title = "Speed",
            subtitle = "Faster, slightly softer detail. Same file size limit.",
        ) { fastEncode = true; fastest = false }
        TargetRow(
            selected = fastest,
            enabled = !busy,
            title = "Fastest",
            subtitle = "Fastest, softest detail: lower effort transcoding and smaller frames. Same file size limit.",
        ) { fastest = true }

        Text("Codec", style = MaterialTheme.typography.titleSmall)
        TargetRow(
            selected = !useVp9,
            enabled = !busy,
            title = "VP8",
            subtitle = "Recommended default. Fastest, accepted everywhere.",
        ) { useVp9 = false }
        TargetRow(
            selected = useVp9,
            enabled = !busy,
            title = "VP9",
            subtitle = "Much slower to convert. Newer standard. May look better. Low compatibility on older devices. Mostly included here as 'future proofing'",
        ) { useVp9 = true }

        val canGo = uri != null && info != null && limitKb >= 256 && !busy
        Button(
            onClick = {
                val u = uri ?: return@Button
                val i = info ?: return@Button
                resultMessage = null
                savedUri = null
                busy = true
                progress = -1f // indeterminate until pass 2 reports real progress
                status = "Starting…"
                logBuffer.clear { logLines = it }
                job = scope.launch {
                    val res = Converter.convert(
                        ctx, u, i,
                        Settings(
                            limitKb * 1024L, maxDuration, !effectiveRemoveAudio, minW, minH,
                            fastEncode = fastEncode,
                            useVp9 = useVp9,
                            fastest = fastest,
                        ),
                        onLog = { line -> logBuffer.add(line) { logLines = it } },
                    ) { p, s -> progress = p; status = s }

                    when (res) {
                        is ConvertResult.Ok -> {
                            val base = name.substringBeforeLast('.', name)
                            val saved = withContext(Dispatchers.IO) {
                                Converter.saveToDownloads(ctx, res.file, "$base.webm")
                            }
                            savedUri = saved
                            val mb = String.format(Locale.US, "%.2f", res.bytes / 1048576.0)
                            resultMessage = buildString {
                                append("Done: $mb MB, ${res.height}p, ${res.videoKbps} kbps video.\n")
                                append(if (saved != null) "Saved to Download/WebM4chan/$base.webm" else "Couldn't save to Downloads.")
                                if (res.trimmed) append("\nCut to ${maxDuration}s.")
                                if (res.audioDropped) append("\nSource had no audio.")
                            }
                        }
                        is ConvertResult.Fail -> resultMessage = res.message
                        ConvertResult.Cancelled -> resultMessage = "Cancelled."
                    }
                    busy = false
                }
            },
            enabled = canGo,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Convert") }

        if (busy) {
            if (progress < 0f) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            } else {
                LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
            }
            Text(status, style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = { Converter.cancel(); job?.cancel() }, modifier = Modifier.fillMaxWidth()) {
                Text("Cancel")
            }
            Text("Keep the app open while converting.", style = MaterialTheme.typography.bodySmall)
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

        resultMessage?.let { Text(it) }

        savedUri?.let { s ->
            OutlinedButton(
                onClick = {
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "video/webm"
                        putExtra(Intent.EXTRA_STREAM, s)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    ctx.startActivity(Intent.createChooser(send, "Share webm"))
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Share") }
        }
        Spacer(Modifier.height(8.dp))
    }
}
