// #cache
package app.webmchan

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

/** The three things the Cache page can do. */
private enum class CacheAction { SAFE, FULL, RESET }

/**
 * Advanced settings > Cache: what the app keeps in its own storage (never the saved .webm videos), a
 * breakdown by kind of file, and three ways to clear it: a safe wipe, a full cache wipe and a reset.
 * No wipe is allowed while a conversion is running, paused, or videos are being read or copied in.
 */
@Composable
fun CacheScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    BackHandler(onBack = onBack)

    var report by remember { mutableStateOf<CacheReport?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    var working by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var confirm by remember { mutableStateOf<CacheAction?>(null) }

    val locked = BatchRunner.cacheBusy || working
    // Read again when the page opens, after a wipe, and when a run starts or ends.
    LaunchedEffect(refresh, BatchRunner.cacheBusy) { report = BatchRunner.cacheReport(ctx) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        P5Button("← Back", onClick = onBack, kind = P5Kind.Ghost)
        P5Title("Cache", size = 30.sp)
        Text(
            "What WebMChan keeps in its own storage on this phone. Your saved .webm videos are somewhere " +
                "else and are never touched on this page.",
            style = MaterialTheme.typography.bodyMedium,
        )

        val r = report
        if (r == null) {
            Text("Reading…", style = MaterialTheme.typography.bodyMedium)
        } else {
            // ---- Totals ----
            P5SectionTitle("Total")
            StatRow("Everything WebMChan stores", r.total, big = true)
            Text(
                "About ${CacheTools.formatSize(r.total.bytes)} in ${r.total.files} files. It is all listed below.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // ---- Breakdown ----
            P5SectionTitle("Cache folder")
            Text(
                "Working files from conversions. Android's own \"Clear cache\" button empties this folder too. " +
                    "It is rebuilt whenever it is needed.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            for ((label, stat) in r.cacheFolder) StatRow(label, stat)

            P5SectionTitle("Kept analysis")
            Text(
                "The first pass of an encode is kept for each video, so converting the same video again " +
                    "(\"Add to pen again\", other settings) skips it.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            StatRow("For videos still on a card", r.analysisInUse)
            StatRow("Not needed by any card", r.analysisUnused)

            P5SectionTitle("Thumbnails")
            Text(
                "The small pictures on cards.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            StatRow("For cards that exist", r.thumbsInUse)
            StatRow("Left over from removed cards", r.thumbsUnused)

            P5SectionTitle("Shared videos")
            Text(
                "Copies of videos shared into WebMChan from other apps. A card needs its copy to convert " +
                    "the video (or to add it again).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            StatRow("Used by a card", r.sharedInUse)
            StatRow("Not used by any card", r.sharedUnused)

            P5SectionTitle("Saved lists and logs")
            Text(
                "Your Holding Pen, queue, Finished list, History and the Log page's conversions. This is " +
                    "your data, not a cache: only Reset app removes it.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            StatRow("Lists, History and logs", r.savedData)

            // ---- Actions ----
            if (locked) {
                SectionNoteBox(
                    "A conversion is running or paused, or videos are being read, so wiping is off until it is done.",
                )
            }
            message?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = P5.RedBright) }

            P5SectionTitle("Safe wipe")
            Text(
                "Empties the cache folder and deletes the analysis, thumbnails and shared copies that no card " +
                    "uses. Nothing you can see changes. Frees about ${CacheTools.formatSize(r.safeWipe.bytes)}.",
                style = MaterialTheme.typography.bodySmall,
            )
            P5Button(
                "Safe wipe",
                onClick = { confirm = CacheAction.SAFE },
                enabled = !locked,
                kind = P5Kind.Secondary,
                modifier = Modifier.fillMaxWidth(),
            )

            P5SectionTitle("Full cache wipe")
            Text(
                "Also deletes all the kept analysis and every thumbnail. Your cards, lists and shared copies " +
                    "in use stay. Thumbnails are made again the next time the app starts, and a video you " +
                    "convert again redoes its first pass, which takes a little longer. " +
                    "Frees about ${CacheTools.formatSize(r.fullWipe.bytes)}.",
                style = MaterialTheme.typography.bodySmall,
            )
            P5Button(
                "Full cache wipe",
                onClick = { confirm = CacheAction.FULL },
                enabled = !locked,
                kind = P5Kind.Secondary,
                modifier = Modifier.fillMaxWidth(),
            )

            P5SectionTitle("Reset app")
            Text(
                "Everything above, and the Holding Pen, queue, Finished list, History, the Log page's " +
                    "conversions and every shared copy as well: the app starts empty, like a fresh install. " +
                    "Your settings, Advanced numbers, save location and saved .webm videos are kept. " +
                    "Frees about ${CacheTools.formatSize(r.resetApp.bytes)}. This can't be undone.",
                style = MaterialTheme.typography.bodySmall,
            )
            P5Button(
                "Reset app",
                onClick = { confirm = CacheAction.RESET },
                enabled = !locked,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }

    // Every wipe asks first, whatever the Settings page's "Confirm before destructive actions" says.
    val asked = confirm
    val shown = report
    if (asked != null && shown != null) {
        val (title, body, freed) = when (asked) {
            CacheAction.SAFE -> Triple(
                "Safe wipe",
                "This empties the cache folder and deletes the analysis, thumbnails and shared copies that no card uses. " +
                    "Nothing on screen changes.",
                shown.safeWipe,
            )
            CacheAction.FULL -> Triple(
                "Full cache wipe",
                "This is the safe wipe, plus all the kept analysis and every thumbnail. Cards, lists and the " +
                    "shared copies in use stay. Thumbnails come back the next time the app starts, and a video " +
                    "converted again redoes its first pass.",
                shown.fullWipe,
            )
            CacheAction.RESET -> Triple(
                "Reset app",
                "This empties the Holding Pen, queue, Finished list, History and the Log page, and deletes " +
                    "every shared copy, the analysis, thumbnails and cache. Your settings, save location and saved " +
                    ".webm videos are kept. It can't be undone.",
                shown.resetApp,
            )
        }
        AlertDialog(
            onDismissRequest = { confirm = null },
            shape = CutCornerShape(topStart = 20.dp, bottomEnd = 20.dp),
            containerColor = P5.Ink,
            titleContentColor = P5.White,
            textContentColor = P5.White,
            title = { Text(title.uppercase(), style = MaterialTheme.typography.titleMedium) },
            text = { Text("$body\n\nFrees about ${CacheTools.describe(freed)}.") },
            confirmButton = {
                P5Button(
                    title,
                    onClick = {
                        confirm = null
                        working = true
                        message = null
                        scope.launch {
                            val done = when (asked) {
                                CacheAction.SAFE -> BatchRunner.wipeSafe(ctx)
                                CacheAction.FULL -> BatchRunner.wipeFull(ctx)
                                CacheAction.RESET -> BatchRunner.resetApp(ctx)
                            }
                            message = if (done == null) "Not done: a conversion is running."
                            else "Done. Freed ${CacheTools.describe(done)}."
                            working = false
                            refresh++
                        }
                    },
                )
            },
            dismissButton = { P5Button("Cancel", onClick = { confirm = null }, kind = P5Kind.Ghost) },
        )
    }
}

/** One line of the breakdown: what it is on the left, its size and file count on the right. */
@Composable
private fun StatRow(label: String, stat: CacheStat, big: Boolean = false) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(P5.Line))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                label,
                style = if (big) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f).padding(end = 8.dp),
            )
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    CacheTools.formatSize(stat.bytes),
                    style = if (big) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyMedium,
                    color = P5.White,
                )
                Text(
                    "${stat.files} file${if (stat.files == 1) "" else "s"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** A short panel with a red bar, like the notes on the main screen. */
@Composable
private fun SectionNoteBox(text: String) {
    val shape = CutCornerShape(topStart = 8.dp, bottomEnd = 8.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .clip(shape)
            .background(P5.Panel),
    ) {
        Box(Modifier.width(4.dp).fillMaxHeight().background(P5.Red))
        Text(text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(12.dp))
    }
}
