// #audiotools
package app.webmchan

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/*
 * Audio Mode's two extra pages, both opened from a Holding Pen card's /// menu and built from the same
 * parts as the video editor and the thumbnail picker (PreviewKit, ClipTimeline):
 *
 *  - AudioTrimScreen: play the track and keep only the stretch between a start and an end marker. The
 *    result is a one-clip EditSpec on the card, so planning, splitting and the length shown all follow it.
 *  - FramePickScreen: for a video used as the sound source, scrub to a frame; that frame becomes the picture.
 */

// ---------------------------------------------------------------------------------------------------
// Trim
// ---------------------------------------------------------------------------------------------------

/** The trim page for the Audio Mode card [itemId]. Closes itself if that card is no longer in the pen. */
@Composable
fun AudioTrimScreen(itemId: Long, onClose: () -> Unit) {
    val item = BatchRunner.queue.firstOrNull { it.id == itemId && it.inPen && it.isAudio }
    val src = item?.info
    if (item == null || src == null) {
        LaunchedEffect(Unit) { onClose() }
        return
    }
    AudioTrimContent(item, src, onClose)
}

@Composable
private fun AudioTrimContent(item: QueueItem, src: SourceInfo, onClose: () -> Unit) {
    val dur = src.durationSec
    val minLen = min(0.5, dur)
    // What the card had when the page opened (the whole track if it had no trim), to tell whether anything changed.
    val init = remember(item.id) { item.edit?.clips?.firstOrNull()?.let { it.startSec to it.endSec } ?: (0.0 to dur) }
    var startSec by remember(item.id) { mutableDoubleStateOf(init.first.coerceIn(0.0, dur)) }
    var endSec by remember(item.id) { mutableDoubleStateOf(init.second.coerceIn(0.0, dur)) }
    var posSec by remember(item.id) { mutableDoubleStateOf(init.first.coerceIn(0.0, dur)) }
    var playing by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var confirmDiscard by remember { mutableStateOf(false) }
    val changed = abs(startSec - init.first) > 0.0005 || abs(endSec - init.second) > 0.0005

    val player = rememberPreviewPlayer { failed = true }
    LaunchedEffect(player) {
        player.setMediaItem(MediaItem.fromUri(item.uri), (posSec * 1000.0).toLong())
        player.prepare()
    }
    // Follows the player while it plays, and stops it at the end marker.
    LaunchedEffect(player) {
        while (true) {
            val now = player.isPlaying
            if (now) {
                posSec = (player.currentPosition / 1000.0).coerceIn(0.0, dur)
                if (posSec >= endSec) {
                    player.pause()
                    posSec = endSec
                }
            }
            playing = player.isPlaying
            delay(if (now) 50L else 150L)
        }
    }

    fun seekTo(sec: Double) {
        val s = sec.coerceIn(0.0, dur)
        player.seekTo((s * 1000.0).toLong())
        posSec = s
    }

    fun togglePlay() {
        if (player.isPlaying) {
            player.pause()
            posSec = (player.currentPosition / 1000.0).coerceIn(0.0, dur)
        } else {
            // Plays the kept stretch: from the start marker unless the playhead is already inside it.
            if (posSec < startSec || posSec >= endSec - 0.05 || player.playbackState == Player.STATE_ENDED) seekTo(startSec)
            player.play()
        }
    }

    fun setStart(v: Double) {
        startSec = v.coerceIn(0.0, max(0.0, endSec - minLen))
    }

    fun setEnd(v: Double) {
        endSec = v.coerceIn(min(dur, startSec + minLen), dur)
    }

    fun done() {
        player.pause()
        if (changed) BatchRunner.setAudioTrim(item.id, startSec, endSec)
        onClose()
    }

    fun cancel() {
        if (changed) confirmDiscard = true else onClose()
    }
    BackHandler(onBack = { cancel() })

    if (confirmDiscard) {
        DiscardDialog(
            message = "Leave without saving the trim you made for this track?",
            onDiscard = { confirmDiscard = false; onClose() },
            onKeep = { confirmDiscard = false },
        )
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            P5Button("Cancel", onClick = { cancel() }, kind = P5Kind.Secondary)
            Spacer(Modifier.weight(1f))
            P5Button(
                "Reset",
                onClick = { player.pause(); startSec = 0.0; endSec = dur; seekTo(0.0) },
                enabled = startSec > 0.0005 || endSec < dur - 0.0005,
                kind = P5Kind.Secondary,
            )
            Spacer(Modifier.width(6.dp))
            P5Button("Done", onClick = { done() })
        }
        P5Title("Trim audio", size = 26.sp)
        Text(
            item.customName ?: item.name,
            style = MaterialTheme.typography.bodySmall,
            color = P5.Grey,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            "Play the track and move the two markers to keep just the part you want. Nothing is changed in the original file.",
            style = MaterialTheme.typography.bodySmall,
            color = P5.Grey,
        )
        if (failed) {
            Text(
                "This phone can't play this file here, so you can only set the markers by time.",
                style = MaterialTheme.typography.bodySmall,
                color = P5.Amber,
            )
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            P5Button(if (playing) "Pause" else "Play", onClick = { togglePlay() }, compact = true)
            Spacer(Modifier.width(8.dp))
            P5Button(
                "Play the end",
                onClick = {
                    player.pause()
                    seekTo(max(startSec, endSec - 3.0))
                    player.play()
                },
                compact = true,
                kind = P5Kind.Secondary,
            )
            Spacer(Modifier.width(12.dp))
            Text("${clock(posSec)} / ${clock(dur)}", style = MaterialTheme.typography.titleSmall)
        }
        // The kept stretch as a bar: tap or drag to move the playhead inside it.
        ClipTimeline(
            clips = listOf(EditClip(startSec, endSec)),
            selected = 0,
            editedSec = (posSec - startSec).coerceAtLeast(0.0),
            onSeek = { player.pause(); seekTo(startSec + it) },
        )

        // ---- Start ----
        Text("Start: ${clock(startSec)}", style = MaterialTheme.typography.titleSmall)
        Slider(
            value = startSec.toFloat(),
            onValueChange = { setStart(it.toDouble()) },
            onValueChangeFinished = { player.pause(); seekTo(startSec) },
            valueRange = 0f..max(0.001f, dur.toFloat()),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            P5Button("−0.1s", onClick = { setStart(startSec - 0.1); player.pause(); seekTo(startSec) }, modifier = Modifier.weight(1f), kind = P5Kind.Secondary, dense = true)
            P5Button("+0.1s", onClick = { setStart(startSec + 0.1); player.pause(); seekTo(startSec) }, modifier = Modifier.weight(1f), kind = P5Kind.Secondary, dense = true)
            P5Button("Set to playhead", onClick = { setStart(posSec) }, modifier = Modifier.weight(2f), kind = P5Kind.Secondary, dense = true)
        }

        // ---- End ----
        Text("End: ${clock(endSec)}", style = MaterialTheme.typography.titleSmall)
        Slider(
            value = endSec.toFloat(),
            onValueChange = { setEnd(it.toDouble()) },
            onValueChangeFinished = { player.pause(); seekTo(max(startSec, endSec - 2.0)) },
            valueRange = 0f..max(0.001f, dur.toFloat()),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            P5Button("−0.1s", onClick = { setEnd(endSec - 0.1); player.pause(); seekTo(max(startSec, endSec - 2.0)) }, modifier = Modifier.weight(1f), kind = P5Kind.Secondary, dense = true)
            P5Button("+0.1s", onClick = { setEnd(endSec + 0.1); player.pause(); seekTo(max(startSec, endSec - 2.0)) }, modifier = Modifier.weight(1f), kind = P5Kind.Secondary, dense = true)
            P5Button("Set to playhead", onClick = { setEnd(posSec) }, modifier = Modifier.weight(2f), kind = P5Kind.Secondary, dense = true)
        }

        Box(Modifier.fillMaxWidth().height(1.dp).background(P5.White.copy(alpha = 0.4f)))
        Text(
            if (startSec <= 0.0005 && endSec >= dur - 0.0005) "Keeping the whole track (${clock(dur)})."
            else "Keeping ${clock(startSec)} to ${clock(endSec)}: ${clock(endSec - startSec)} of ${clock(dur)}.",
            style = MaterialTheme.typography.titleSmall,
        )
        Spacer(Modifier.height(16.dp))
    }
}

// ---------------------------------------------------------------------------------------------------
// Frame from a video
// ---------------------------------------------------------------------------------------------------

/** The frame picker for the Audio Mode card [itemId] whose source is a video. */
@Composable
fun FramePickScreen(itemId: Long, onClose: () -> Unit) {
    val item = BatchRunner.queue.firstOrNull { it.id == itemId && it.inPen && it.isAudio }
    val src = item?.info
    if (item == null || src == null || src.width <= 0 || src.height <= 0) {
        LaunchedEffect(Unit) { onClose() }
        return
    }
    FramePickContent(item, src, onClose)
}

@Composable
private fun FramePickContent(item: QueueItem, src: SourceInfo, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val frameSec = frameSecOf(src)
    val lastSec = max(0.0, src.durationSec - frameSec)
    var posSec by remember(item.id) { mutableDoubleStateOf(0.0) }
    var playing by remember { mutableStateOf(false) }
    var previewFailed by remember { mutableStateOf(false) }

    val player = rememberPreviewPlayer { previewFailed = true }
    LaunchedEffect(player) {
        player.setMediaItem(MediaItem.fromUri(item.uri))
        player.prepare()
    }
    LaunchedEffect(player) {
        while (true) {
            val now = player.isPlaying
            if (now) posSec = (player.currentPosition / 1000.0).coerceIn(0.0, lastSec)
            playing = now
            delay(if (now) 50L else 150L)
        }
    }

    fun seekTo(sec: Double) {
        val s = sec.coerceIn(0.0, lastSec)
        player.seekTo((s * 1000.0).toLong())
        posSec = s
    }

    fun togglePlay() {
        if (player.isPlaying) {
            player.pause()
            posSec = (player.currentPosition / 1000.0).coerceIn(0.0, lastSec)
        } else {
            if (player.playbackState == Player.STATE_ENDED) seekTo(0.0)
            player.play()
        }
    }

    fun done() {
        if (player.isPlaying) {
            player.pause()
            posSec = (player.currentPosition / 1000.0).coerceIn(0.0, lastSec)
        }
        BatchRunner.setAudioFrame(ctx, item.id, posSec)
        onClose()
    }
    BackHandler(onBack = { onClose() })

    val videoHeight = (LocalConfiguration.current.screenHeightDp * 0.38f).dp
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            P5Button("Cancel", onClick = { onClose() }, kind = P5Kind.Secondary)
            Spacer(Modifier.weight(1f))
            P5Button("Use this frame", onClick = { done() })
        }
        P5Title("Pick frame", size = 26.sp)
        Text(
            item.customName ?: item.name,
            style = MaterialTheme.typography.bodySmall,
            color = P5.Grey,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            "Scrub to the frame you want as the still picture. It replaces the card's picture(s); more can be added afterwards.",
            style = MaterialTheme.typography.bodySmall,
            color = P5.Grey,
        )
        Box(Modifier.fillMaxWidth().height(videoHeight), contentAlignment = Alignment.Center) {
            Box(Modifier.aspectRatio(src.width.toFloat() / src.height).background(Color.Black)) {
                PreviewSurface(player, Modifier.fillMaxSize())
                if (previewFailed) {
                    Text(
                        "This phone can't preview this video, so a frame can't be picked here.",
                        style = MaterialTheme.typography.bodySmall,
                        color = P5.Amber,
                        modifier = Modifier.align(Alignment.Center).padding(12.dp),
                    )
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            P5Button(if (playing) "Pause" else "Play", onClick = { togglePlay() }, compact = true)
            Spacer(Modifier.width(12.dp))
            Text("${clock(posSec)} / ${clock(src.durationSec)}", style = MaterialTheme.typography.titleSmall)
        }
        ClipTimeline(
            clips = listOf(EditClip(0.0, src.durationSec)),
            selected = 0,
            editedSec = posSec,
            onSeek = { player.pause(); seekTo(it) },
        )
        StepButtons(frameSec) { player.pause(); seekTo(posSec + it) }
        Spacer(Modifier.height(16.dp))
    }
}
