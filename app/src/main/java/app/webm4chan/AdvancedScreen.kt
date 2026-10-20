package app.webmchan

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

/** One editable number: how it's labelled, explained and read back. */
private data class FieldSpec(
    val key: String,
    val label: String,
    val help: String,
    val integer: Boolean,
)

private val FIELDS = listOf(
    FieldSpec(
        "firstTargetPct", "First attempt target (% of limit)",
        "Default 97.5. Allowed 80 to 99.5. How full the first encode aims to make the file. " +
            "The rest is margin against overshooting.", false,
    ),
    FieldSpec(
        "retryMarginPct", "Retry target (% of limit)",
        "Default 99. Allowed 80 to 99.9. What a retry aims for after going over.", false,
    ),
    FieldSpec(
        "maxAttempts", "Maximum attempts",
        "Default 4. Allowed 1 to 8. Encodes tried before giving up on a clip.", true,
    ),
    FieldSpec(
        "audioAnalysisAttempt", "Audio analysis from attempt",
        "Default 3. Allowed 2 to 9. From this attempt on, the audio is measured on its own and the " +
            "video gets exactly what's left. Set it above the maximum attempts to turn it off.", true,
    ),
    FieldSpec(
        "audioKbps", "Audio bitrate (kbps)",
        "Default 64. Allowed 16 to 192.", true,
    ),
    FieldSpec(
        "minAudioKbps", "Lowest audio bitrate (kbps)",
        "Default 32. Allowed 8 up to the audio bitrate. Audio may drop this low on a tight budget " +
            "instead of failing the clip.", true,
    ),
    FieldSpec(
        "minVideoKbps", "Lowest video bitrate (kbps)",
        "Default 40. Allowed 10 to 500. Below this a clip is reported as too long for the limit.", true,
    ),
    FieldSpec(
        "normalBpp", "Detail threshold (Quality and Speed)",
        "Default 0.06. Allowed 0.01 to 0.5. Smallest bits-per-pixel a frame size needs to be chosen. " +
            "Higher picks smaller frames.", false,
    ),
    FieldSpec(
        "fastestBpp", "Detail threshold (Fastest)",
        "Default 0.12. Allowed 0.01 to 1.", false,
    ),
    FieldSpec(
        "lengthGraceSec", "Length grace (seconds)",
        "Default 1. Allowed 0 to 10. Taken off every board's length limit (a 300 s board is treated " +
            "as 299 s). The screens still show the board's real limit.", false,
    ),
    FieldSpec(
        "trimToleranceSec", "Trim tolerance (seconds)",
        "Default 0.5. Allowed 0 to 5. An overshoot this small is trimmed off instead of becoming " +
            "another part.", false,
    ),
)

private fun Tuning.asText(): Map<String, String> = mapOf(
    "firstTargetPct" to firstTargetPct.toString(),
    "retryMarginPct" to retryMarginPct.toString(),
    "maxAttempts" to maxAttempts.toString(),
    "audioAnalysisAttempt" to audioAnalysisAttempt.toString(),
    "audioKbps" to audioKbps.toString(),
    "minAudioKbps" to minAudioKbps.toString(),
    "minVideoKbps" to minVideoKbps.toString(),
    "normalBpp" to normalBpp.toString(),
    "fastestBpp" to fastestBpp.toString(),
    "lengthGraceSec" to lengthGraceSec.toString(),
    "trimToleranceSec" to trimToleranceSec.toString(),
)

/**
 * Reads the text fields back into a [Tuning]. An entry that isn't a number keeps its previous
 * value from [base], and [onUnreadable] is called so the screen can say so.
 */
private fun Map<String, String>.toTuning(base: Tuning, onUnreadable: () -> Unit): Tuning {
    fun dbl(key: String, def: Double): Double {
        val v = this[key]?.trim()?.replace(',', '.')?.toDoubleOrNull()
        if (v == null) onUnreadable()
        return v ?: def
    }
    fun int(key: String, def: Int): Int {
        val v = this[key]?.trim()?.toIntOrNull()
        if (v == null) onUnreadable()
        return v ?: def
    }
    return Tuning(
        firstTargetPct = dbl("firstTargetPct", base.firstTargetPct),
        retryMarginPct = dbl("retryMarginPct", base.retryMarginPct),
        maxAttempts = int("maxAttempts", base.maxAttempts),
        audioKbps = int("audioKbps", base.audioKbps),
        minAudioKbps = int("minAudioKbps", base.minAudioKbps),
        minVideoKbps = int("minVideoKbps", base.minVideoKbps),
        normalBpp = dbl("normalBpp", base.normalBpp),
        fastestBpp = dbl("fastestBpp", base.fastestBpp),
        lengthGraceSec = dbl("lengthGraceSec", base.lengthGraceSec),
        trimToleranceSec = dbl("trimToleranceSec", base.trimToleranceSec),
        audioAnalysisAttempt = int("audioAnalysisAttempt", base.audioAnalysisAttempt),
    )
}

/**
 * About > Advanced settings: the numbers that steer how videos are fitted into a size limit, so
 * they can be tried out without rebuilding the app. The defaults are the recommended values.
 */
@Composable
fun AdvancedScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    BackHandler(onBack = onBack)

    val fields = remember { mutableStateMapOf<String, String>().apply { putAll(TuningStore.current.asText()) } }
    var message by remember { mutableStateOf<String?>(null) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        TextButton(onClick = onBack) { Text("← Back") }
        Text("Advanced settings", style = MaterialTheme.typography.headlineMedium)
        Text(
            "For testing. These numbers steer how videos are fitted into a size limit; the defaults " +
                "are the recommended values. Changes apply from the next conversion, and anything " +
                "you've changed is listed at the top of the log.",
            style = MaterialTheme.typography.bodyMedium,
        )

        for (spec in FIELDS) {
            OutlinedTextField(
                value = fields[spec.key] ?: "",
                onValueChange = { fields[spec.key] = it; message = null },
                label = { Text(spec.label) },
                supportingText = { Text(spec.help) },
                keyboardOptions = KeyboardOptions(
                    keyboardType = if (spec.integer) KeyboardType.Number else KeyboardType.Decimal
                ),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        message?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    var unreadable = false
                    val asked = fields.toTuning(TuningStore.current) { unreadable = true }
                    val saved = TuningStore.save(ctx, asked)
                    // Show what was actually kept (values outside their range are pulled back in).
                    fields.putAll(saved.asText())
                    message = when {
                        unreadable -> "Saved. Some entries weren't numbers, so they kept their previous values."
                        saved != asked -> "Saved. Some values were outside their allowed range and were adjusted."
                        else -> "Saved. Applies from the next conversion."
                    }
                },
                modifier = Modifier.weight(1f),
            ) { Text("Save") }
            OutlinedButton(
                onClick = {
                    TuningStore.reset(ctx)
                    fields.putAll(TuningStore.current.asText())
                    message = "Back to the default values."
                },
                modifier = Modifier.weight(1f),
            ) { Text("Reset to defaults") }
        }
    }
}
