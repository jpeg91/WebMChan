// #logscreen
package app.webmchan

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.DateFormat
import java.util.Date

/** The Menu page: where the Log and About pages are reached from. */
@Composable
fun MenuScreen(onBack: () -> Unit, onLog: () -> Unit, onAbout: () -> Unit) {
    // The system back gesture/button closes this page instead of leaving the app.
    BackHandler(onBack = onBack)

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        P5Button("← Back", onClick = onBack, kind = P5Kind.Ghost)
        P5Title("Menu")
        P5Button("Log", onClick = onLog, modifier = Modifier.fillMaxWidth())
        P5Button("About", onClick = onAbout, kind = P5Kind.Secondary, modifier = Modifier.fillMaxWidth())
    }
}

/**
 * The Log page: the last few conversions, newest first, each with its card details and its
 * complete log. Failed and cancelled conversions are included, since those are the ones whose
 * logs are most wanted. The live log on the main screen is cleared when a new batch starts; these
 * are kept (and saved, so they survive the app being closed).
 */
@Composable
fun LogScreen(onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val logs = BatchRunner.conversionLogs

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        P5Button("← Back", onClick = onBack, kind = P5Kind.Ghost)
        P5Title("Log")
        Text(
            "The last ${ConversionLogStore.MAX_ENTRIES} conversions, newest first, each with its complete log. " +
                "Failed and cancelled ones are included.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (logs.isEmpty()) {
            Text(
                "No conversions yet.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            logs.asReversed().forEach { entry ->
                // The same video tried again gets the same id, so the time is part of the key.
                key(entry.id, entry.whenMillis) { LogCard(entry) }
            }
        }
    }
}

@Composable
private fun LogCard(entry: ConversionLog) {
    val clipboard = LocalClipboardManager.current
    var open by remember { mutableStateOf(false) }

    // The stripe down the edge matches the cards on the main screen: white once done, amber if it failed.
    val accent = when (entry.outcome) {
        ItemStatus.DONE -> P5.White
        ItemStatus.FAILED -> P5.Amber
        else -> P5.GreyDim
    }
    val outcomeText = when (entry.outcome) {
        ItemStatus.DONE -> "Done"
        ItemStatus.FAILED -> "Failed"
        else -> "Not converted"
    }
    val whenText = remember(entry.whenMillis) {
        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(entry.whenMillis))
    }
    // What Copy log puts on the clipboard: the card's details, then the whole log.
    val copyText = remember(entry) {
        buildString {
            append(entry.name).append('\n')
            append(outcomeText).append(" · ").append(whenText).append('\n')
            append(entry.sourceLine).append('\n')
            append("Settings: ").append(entry.settingsLine).append('\n')
            if (entry.detail.isNotBlank()) append(entry.detail).append('\n')
            append('\n').append(entry.log)
        }
    }

    P5Card(Modifier.fillMaxWidth(), accent = accent) {
        Text(entry.name, style = MaterialTheme.typography.titleMedium)
        Text("${entry.sourceLine} · $outcomeText", style = MaterialTheme.typography.bodySmall)
        Text(whenText, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (entry.settingsLine.isNotBlank()) {
            Text(
                "Settings: ${entry.settingsLine}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (entry.detail.isNotBlank()) {
            Text(
                entry.detail,
                style = MaterialTheme.typography.bodySmall,
                color = if (entry.outcome == ItemStatus.FAILED) MaterialTheme.colorScheme.error else Color.Unspecified,
            )
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (open) "Hide log ▴" else "Show log ▾",
                style = MaterialTheme.typography.labelLarge,
                color = P5.RedBright,
                modifier = Modifier
                    .clickable { open = !open }
                    .padding(vertical = 10.dp),
            )
            Spacer(Modifier.weight(1f))
            P5Button(
                "Copy log",
                onClick = { clipboard.setText(AnnotatedString(copyText)) },
                kind = P5Kind.Secondary,
            )
        }
        if (open) {
            val shape = remember { CutCornerShape(topStart = 12.dp, bottomEnd = 12.dp) }
            // Selectable, for copying just part of it. Copy log above takes all of it.
            SelectionContainer {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 360.dp)
                        .clip(shape)
                        .background(Color.Black)
                        .border(1.5.dp, P5.Line, shape)
                        .verticalScroll(rememberScrollState())
                        .padding(10.dp),
                ) {
                    Text(
                        entry.log.ifBlank { "(nothing was logged)" },
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        lineHeight = 14.sp,
                    )
                }
            }
        }
    }
}
