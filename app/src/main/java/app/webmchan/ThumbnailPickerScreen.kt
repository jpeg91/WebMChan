// #thumbpicker
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
import androidx.compose.ui.geometry.Rect
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

/*
 * The thumbnail picker: a page opened from a Holding Pen card (see BatchRunner.openThumbPicker). Scrub
 * through the source video and stop on the frame the post's thumbnail should be. The pick is only a time
 * in the source video, kept on the card (QueueItem.thumbSec); the conversion later puts that frame in
 * front of the video as its first frame, which is the one a site makes its thumbnail from (see
 * Converter.joinGraph). It is built from the same parts as the editor (PreviewKit, the timeline and the
 * crop frame in EditorScreen) and never touches the source video.
 */

/** The frame at (or within this of) the very start is the video's own first frame: picking it is no pick. */
private const val NO_PICK_SEC = 0.0005

/** The thumbnail picker for the Holding Pen card [itemId]. Closes itself if that card is no longer in the pen. */
@Composable
fun ThumbnailPickerScreen(itemId: Long, onClose: () -> Unit) {
    val item = BatchRunner.queue.firstOrNull { it.id == itemId && it.inPen }
    val src = item?.info
    if (item == null || src == null) {
        LaunchedEffect(Unit) { onClose() }
        return
    }
    ThumbnailPickerContent(item, src, onClose)
}

@Composable
private fun ThumbnailPickerContent(item: QueueItem, src: SourceInfo, onClose: () -> Unit) {
    val ctx = LocalContext.current

    // What the card had when the picker opened (null = no pick), to tell whether anything has changed.
    val initial: Double? = remember(item.id) { item.thumbSec }
    // The last frame there is to stop on.
    val frameSec = frameSecOf(src)
    val lastSec = max(0.0, src.durationSec - frameSec)

    // Where the playhead is, in seconds of the source video. While paused it is exactly where the person
    // put it (the player's own clock is only read while playing).
    var posSec by remember(item.id) { mutableDoubleStateOf((initial ?: 0.0).coerceIn(0.0, lastSec)) }
    var playing by remember { mutableStateOf(false) }
    var previewFailed by remember { mutableStateOf(false) }
    var confirmDiscard by remember { mutableStateOf(false) }

    val chosen: Double? = if (posSec <= NO_PICK_SEC) null else posSec
    val changed = (chosen == null) != (initial == null) ||
        (chosen != null && initial != null && abs(chosen - initial) > NO_PICK_SEC)

    // ---- The preview player (see PreviewKit): the whole source video, paused on the playhead ----
    val player = rememberPreviewPlayer { previewFailed = true }
    LaunchedEffect(player) {
        player.setMediaItem(MediaItem.fromUri(item.uri), (posSec * 1000.0).toLong())
        player.prepare()
    }
    // Follows the player while it plays.
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
        if (changed) BatchRunner.setThumbPick(ctx, item.id, if (posSec <= NO_PICK_SEC) null else posSec)
        onClose()
    }

    fun cancel() {
        if (changed) confirmDiscard = true else onClose()
    }
    BackHandler(onBack = { cancel() })

    if (confirmDiscard) {
        DiscardDialog(
            message = "Leave without saving the thumbnail you picked for this video?",
            onDiscard = { confirmDiscard = false; onClose() },
            onKeep = { confirmDiscard = false },
        )
    }

    // ---- The page ----
    val frameW = src.width
    val frameH = src.height
    val videoHeight = (LocalConfiguration.current.screenHeightDp * 0.38f).dp
    val crop = item.edit?.crop
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
                "Clear",
                onClick = {
                    player.pause()
                    seekTo(0.0)
                },
                enabled = chosen != null,
                kind = P5Kind.Secondary,
            )
            Spacer(Modifier.width(6.dp))
            P5Button("Done", onClick = { done() })
        }
        P5Title("Pick thumbnail", size = 26.sp)
        Text(
            item.name,
            style = MaterialTheme.typography.bodySmall,
            color = P5.Grey,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            "Scrub to the frame you want as the thumbnail. It is added to the very start of the video, " +
                "and the site makes the thumbnail from the first frame.",
            style = MaterialTheme.typography.bodySmall,
            color = P5.Grey,
        )

        // The picture, in a box exactly the shape of the video. If the video is cropped in the editor the
        // crop frame is shown over it (not draggable here), since the thumbnail is cropped the same.
        Box(Modifier.fillMaxWidth().height(videoHeight + CROP_PAD * 2), contentAlignment = Alignment.Center) {
            Box(Modifier.padding(CROP_PAD).aspectRatio(frameW.toFloat() / frameH).background(Color.Black)) {
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
            if (crop != null) {
                CropOverlay(
                    frameW = frameW,
                    frameH = frameH,
                    rect = Rect(crop.x.toFloat(), crop.y.toFloat(), (crop.x + crop.w).toFloat(), (crop.y + crop.h).toFloat()),
                    interactive = false,
                    onRect = {},
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            P5Button(if (playing) "Pause" else "Play", onClick = { togglePlay() }, compact = true)
            Spacer(Modifier.width(12.dp))
            Text(
                "${clock(posSec)} / ${clock(src.durationSec)}",
                style = MaterialTheme.typography.titleSmall,
            )
        }
        // The whole source video as one bar (the same timeline the editor uses): tap or drag to scrub.
        ClipTimeline(
            clips = listOf(EditClip(0.0, src.durationSec)),
            selected = 0,
            editedSec = posSec,
            onSeek = { player.pause(); seekTo(it) },
        )
        StepButtons(frameSec) { player.pause(); seekTo(posSec + it) }

        // ---- What will happen ----
        Box(Modifier.fillMaxWidth().height(1.dp).background(P5.White.copy(alpha = 0.4f)))
        Text(
            if (chosen != null) "Thumbnail: the frame at ${clock(chosen)} of the video"
            else "No thumbnail picked: the site will use the video's own first frame.",
            style = MaterialTheme.typography.titleSmall,
        )
        val edit = item.edit
        if (chosen != null && edit != null && edit.clips.none { chosen >= it.startSec && chosen <= it.endSec }) {
            Text(
                "This frame is from a part you cut out of the video. It still works as the thumbnail.",
                style = MaterialTheme.typography.bodySmall,
                color = P5.Grey,
            )
        }
        Spacer(Modifier.height(16.dp))
    }
}
