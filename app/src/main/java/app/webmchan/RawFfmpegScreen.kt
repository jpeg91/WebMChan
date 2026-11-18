// #rawffmpeg
package app.webmchan

import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Raw FFmpeg: a power-user page that runs a command line typed in by hand, with no help from the
 * rest of the app (no size fitting, no splitting, no presets). [RawFfmpegRunner] does the actual
 * work and holds all of its state, so this screen is just a view onto it: closing the page (by
 * Back, or the activity being recreated) doesn't cancel a run in progress or lose what was typed,
 * and reopening the page shows exactly where things stand, including Cancel, if one is still going.
 *
 * %IN% and %OUT% in the typed command become plain file paths to the staged input and a cache
 * output file, the same way the ordinary converter stages its source. Nothing is validated: a bad
 * command just fails and shows ffmpeg's own log.
 */
@Composable
fun RawFfmpegScreen(onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val ctx = LocalContext.current

    val inputUri = RawFfmpegRunner.inputUri
    val inputName = RawFfmpegRunner.inputName
    val running = RawFfmpegRunner.running
    val busyElsewhere = BatchRunner.busy

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try {
                ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Exception) {
            }
            RawFfmpegRunner.setInput(uri, Converter.displayName(ctx, uri))
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        P5Button("← Back", onClick = onBack, kind = P5Kind.Ghost)
        P5Title("Raw FFmpeg")
        Text(
            "Run your own ffmpeg command line on a file you choose. For people who already know ffmpeg: " +
                "nothing here is checked or fitted to a board's limits, so a bad command just fails and " +
                "shows ffmpeg's own log below. Use %IN% for the input file and %OUT% for the output file " +
                "in the arguments box (don't include \"ffmpeg\" itself, and don't use shell features like " +
                "pipes or redirects). Quote an argument with spaces in it, e.g. \"scale=640:-1\". " +
                "A run keeps going if you leave this page, and this page keeps going if the app is closed " +
                "and reopened (as long as the app's process itself wasn't killed in the meantime).",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        P5Button(
            if (inputUri == null) "Choose input file" else "Change input file",
            onClick = { picker.launch(arrayOf("*/*")) },
            enabled = !running,
            modifier = Modifier.fillMaxWidth(),
            kind = P5Kind.Secondary,
        )
        Text(
            if (inputUri == null) "No input file chosen." else "Input: $inputName",
            style = MaterialTheme.typography.bodySmall,
        )

        OutlinedTextField(
            value = RawFfmpegRunner.argsText,
            onValueChange = { RawFfmpegRunner.argsText = it },
            label = { Text("FFmpeg arguments") },
            enabled = !running,
            shape = CutCornerShape(topStart = 10.dp, bottomEnd = 10.dp),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = RawFfmpegRunner.outName,
            onValueChange = { RawFfmpegRunner.outName = it },
            label = { Text("Output file name") },
            enabled = !running,
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            shape = CutCornerShape(topStart = 10.dp, bottomEnd = 10.dp),
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            "The extension in the output file name decides the container (e.g. .webm, .mp4, .gif, .png). " +
                "It's saved wherever ordinary conversions are saved (see Settings).",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (busyElsewhere) {
            Text(
                "A batch conversion is running, so Run is disabled until it finishes (they'd otherwise " +
                    "interfere with each other).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.tertiary,
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            P5Button(
                if (running) "Running…" else "Run",
                onClick = { RawFfmpegRunner.run(ctx) },
                enabled = !running && !busyElsewhere && inputUri != null,
                modifier = Modifier.weight(1f),
            )
            if (running) {
                // Always reachable while a run is going, from this page or after coming back to it:
                // Cancel stops just this session (see RawFfmpegRunner.cancel), not any other ffmpeg work.
                P5Button(
                    "Cancel",
                    onClick = { RawFfmpegRunner.cancel() },
                    kind = P5Kind.Secondary,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        RawFfmpegRunner.resultMsg?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = if (RawFfmpegRunner.resultFailed) MaterialTheme.colorScheme.error else P5.White,
            )
        }
        RawFfmpegRunner.savedUri?.let { saved ->
            P5Button(
                "Share",
                onClick = {
                    try {
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = RawFfmpegRunner.savedMime
                            putExtra(Intent.EXTRA_STREAM, saved)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        ctx.startActivity(Intent.createChooser(send, "Share file"))
                    } catch (_: Exception) {
                        Toast.makeText(ctx, "Couldn't share this file.", Toast.LENGTH_SHORT).show()
                    }
                },
                kind = P5Kind.Secondary,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        val log = RawFfmpegRunner.log
        if (log.isNotBlank()) {
            Text("Log", style = MaterialTheme.typography.titleSmall)
            val shape = remember { CutCornerShape(topStart = 12.dp, bottomEnd = 12.dp) }
            SelectionContainer {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 360.dp)
                        .background(Color.Black)
                        .border(1.5.dp, P5.Line, shape)
                        .verticalScroll(rememberScrollState())
                        .padding(10.dp),
                ) {
                    Text(log, fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 14.sp)
                }
            }
        }
    }
}

/** A plain guess at the MIME type from the output name's extension, for saving and sharing the file correctly. */
internal fun mimeFor(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
    "webm" -> "video/webm"
    "mp4", "m4v" -> "video/mp4"
    "mkv" -> "video/x-matroska"
    "mov" -> "video/quicktime"
    "avi" -> "video/x-msvideo"
    "gif" -> "image/gif"
    "png" -> "image/png"
    "jpg", "jpeg" -> "image/jpeg"
    "webp" -> "image/webp"
    "mp3" -> "audio/mpeg"
    "m4a", "aac" -> "audio/mp4"
    "opus" -> "audio/opus"
    "ogg", "oga" -> "audio/ogg"
    "flac" -> "audio/flac"
    "wav" -> "audio/wav"
    else -> "application/octet-stream"
}

/**
 * Splits a command line into arguments the way a shell would for this simple case: whitespace
 * separates arguments, and a "..." or '...' run keeps its spaces and loses the quotes. No escaping,
 * no variables, no globbing — just enough for %IN%/%OUT% and the odd quoted filter string.
 */
internal fun tokenizeArgs(input: String): List<String> {
    val out = mutableListOf<String>()
    val cur = StringBuilder()
    var quote: Char? = null
    var any = false
    for (c in input) {
        when {
            quote != null -> {
                if (c == quote) quote = null else cur.append(c)
            }
            c == '"' || c == '\'' -> {
                quote = c
                any = true
            }
            c.isWhitespace() -> {
                if (any || cur.isNotEmpty()) out.add(cur.toString())
                cur.clear()
                any = false
            }
            else -> {
                cur.append(c)
                any = true
            }
        }
    }
    if (any || cur.isNotEmpty()) out.add(cur.toString())
    return out
}
