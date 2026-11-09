// #editor
package app.webmchan

import android.view.TextureView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/*
 * The video editor: a page opened from a Holding Pen card (see BatchRunner.openEditor). It makes an
 * [EditSpec] for the card (cuts and one crop) and never touches the source video. The preview is
 * ExoPlayer playing the clips back to back, so what you see is the edit; the conversion later encodes
 * the same thing with ffmpeg. Everything the edit decides lives in EditSpec, so this file is only
 * the screen: the player, the timeline, the crop frame and the buttons.
 */

private enum class EditorTab { CUTS, CROP }

/** The editor for the Holding Pen card [itemId]. Closes itself if that card is no longer in the pen. */
@Composable
fun EditorScreen(itemId: Long, onClose: () -> Unit) {
    val item = BatchRunner.queue.firstOrNull { it.id == itemId && it.inPen }
    val src = item?.info
    if (item == null || src == null) {
        LaunchedEffect(Unit) { onClose() }
        return
    }
    EditorContent(item, src, onClose)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditorContent(item: QueueItem, src: SourceInfo, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val live = BatchRunner.liveSettings

    // What the card had when the editor opened (the whole video if it had no edit), to tell whether
    // anything has changed.
    val initial = remember(item.id) { item.edit ?: EditSpec.whole(src) }
    var spec by remember(item.id) { mutableStateOf(initial) }
    var tab by remember { mutableStateOf(EditorTab.CUTS) }
    var confirmDiscard by remember { mutableStateOf(false) }
    var previewFailed by remember { mutableStateOf(false) }

    // Where the playhead is: which clip, and how many seconds into it.
    var playIdx by remember { mutableIntStateOf(0) }
    var playSec by remember { mutableDoubleStateOf(0.0) }
    var playing by remember { mutableStateOf(false) }

    // The crop frame as it is dragged, in pixels of the picture (kept apart from spec.crop, which is
    // rounded to even whole pixels, so dragging stays smooth).
    val frameW = src.width
    val frameH = src.height
    var cropF by remember(item.id) {
        mutableStateOf(
            initial.crop?.let { Rect(it.x.toFloat(), it.y.toFloat(), (it.x + it.w).toFloat(), (it.y + it.h).toFloat()) }
                ?: Rect(0f, 0f, frameW.toFloat(), frameH.toFloat())
        )
    }

    // ---- The preview player (see PreviewKit) ----
    val player = rememberPreviewPlayer { previewFailed = true }

    /** The clips as a playlist, each cut to its own stretch of the source: playing it is playing the edit. */
    fun playlist(): List<MediaItem> = spec.clips.map { c ->
        MediaItem.Builder()
            .setUri(item.uri)
            .setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs((c.startSec * 1000.0).toLong())
                    .setEndPositionMs((c.endSec * 1000.0).toLong())
                    .build()
            )
            .build()
    }

    /** Loads the current clips into the player and puts the playhead [offsetSec] into clip [idx]. */
    fun load(idx: Int, offsetSec: Double) {
        val i = idx.coerceIn(0, spec.clips.lastIndex)
        val off = offsetSec.coerceAtLeast(0.0)
        player.setMediaItems(playlist(), i, (off * 1000.0).toLong())
        player.prepare()
        playIdx = i
        playSec = off
    }

    fun seekTo(idx: Int, offsetSec: Double) {
        player.seekTo(idx, (offsetSec * 1000.0).toLong())
        playIdx = idx
        playSec = offsetSec
    }

    fun seekEdited(sec: Double) {
        val (i, o) = spec.locate(sec)
        seekTo(i, o)
    }

    LaunchedEffect(player) { load(0, 0.0) }

    // Follows the player: which clip it is in, how far in, and whether it is playing.
    LaunchedEffect(player) {
        while (true) {
            val idx = player.currentMediaItemIndex
            if (idx in spec.clips.indices) playIdx = idx
            playSec = (player.currentPosition / 1000.0).coerceAtLeast(0.0)
            playing = player.isPlaying
            delay(if (player.isPlaying) 50L else 150L)
        }
    }

    val sel = playIdx.coerceIn(0, spec.clips.lastIndex)
    val clip = spec.clips[sel]
    val offset = playSec.coerceIn(0.0, clip.lengthSec)
    val editedSec = spec.editedStartOf(sel) + offset

    /** Runs an edit: pauses, swaps in the new clips (null = the edit wasn't allowed) and puts the playhead back. */
    fun applyEdit(next: EditSpec?, idx: Int, offsetSec: Double) {
        if (next == null) return
        player.pause()
        spec = next
        load(idx, offsetSec)
    }

    fun togglePlay() {
        if (player.isPlaying) {
            player.pause()
        } else {
            if (player.playbackState == Player.STATE_ENDED) seekTo(0, 0.0)
            player.play()
        }
    }

    fun done() {
        // An edit that changes nothing is no edit: the card goes back to being plain.
        BatchRunner.setEdit(item.id, spec.takeUnless { it.isNoOp(src) })
        onClose()
    }

    fun cancel() {
        if (spec != initial) confirmDiscard = true else onClose()
    }
    BackHandler(onBack = { cancel() })

    if (confirmDiscard) {
        DiscardDialog(
            message = "Leave the editor without saving your changes to this video?",
            onDiscard = { confirmDiscard = false; onClose() },
            onKeep = { confirmDiscard = false },
        )
    }

    // What the edit does to the conversion, worked out the way the card does it.
    val effective = remember(spec) { spec.effectiveInfo(src) }
    val estimate = remember(spec, live) { live?.let { Converter.estimate(effective, it, item.extraParts) } }

    // ---- The page ----
    // The picture is as big as the width allows, up to half the screen's height (a tall video gets narrower).
    val maxVideoHeight = (LocalConfiguration.current.screenHeightDp * 0.5f).dp
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
                onClick = {
                    player.pause()
                    cropF = Rect(0f, 0f, frameW.toFloat(), frameH.toFloat())
                    spec = EditSpec.whole(src)
                    load(0, 0.0)
                },
                enabled = !spec.isNoOp(src),
                kind = P5Kind.Secondary,
            )
            Spacer(Modifier.width(6.dp))
            P5Button("Done", onClick = { done() })
        }
        P5Title("Edit video", size = 26.sp)
        Text(
            item.name,
            style = MaterialTheme.typography.bodySmall,
            color = P5.Grey,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )

        // The picture, in a box exactly the shape of the video. The crop frame is drawn over a larger
        // area (the picture plus CROP_PAD all round), so a frame edge sitting right on the picture's
        // border can still be grabbed from outside it.
        BoxWithConstraints(Modifier.fillMaxWidth()) {
        val fitHeight = minOf((maxWidth - CROP_PAD * 2) * (frameH.toFloat() / frameW), maxVideoHeight)
        Box(Modifier.fillMaxWidth().height(fitHeight + CROP_PAD * 2), contentAlignment = Alignment.Center) {
            Box(Modifier.padding(CROP_PAD).aspectRatio(frameW.toFloat() / frameH).background(Color.Black)) {
                PreviewSurface(player, Modifier.fillMaxSize())
                if (previewFailed) {
                    Text(
                        "This phone can't preview this video. You can still set the cuts and the crop.",
                        style = MaterialTheme.typography.bodySmall,
                        color = P5.Amber,
                        modifier = Modifier.align(Alignment.Center).padding(12.dp),
                    )
                }
            }
            if (tab == EditorTab.CROP || spec.crop != null) {
                CropOverlay(
                    frameW = frameW,
                    frameH = frameH,
                    rect = cropF,
                    interactive = tab == EditorTab.CROP,
                    onRect = { r ->
                        cropF = r
                        spec = spec.withCrop(
                            EditSpec.cleanCrop(
                                r.left.roundToInt(), r.top.roundToInt(),
                                r.width.roundToInt(), r.height.roundToInt(), frameW, frameH,
                            )
                        )
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            P5Button(
                "Cuts",
                onClick = { tab = EditorTab.CUTS },
                modifier = Modifier.weight(1f),
                kind = if (tab == EditorTab.CUTS) P5Kind.Primary else P5Kind.Secondary,
                compact = true,
            )
            P5Button(
                "Crop",
                onClick = { player.pause(); tab = EditorTab.CROP },
                modifier = Modifier.weight(1f),
                kind = if (tab == EditorTab.CROP) P5Kind.Primary else P5Kind.Secondary,
                compact = true,
            )
        }

        if (tab == EditorTab.CUTS) {
            // ---- Cuts: play, the timeline of the edited video, and the buttons ----
            Row(verticalAlignment = Alignment.CenterVertically) {
                P5Button(if (playing) "Pause" else "Play", onClick = { togglePlay() }, compact = true)
                Spacer(Modifier.width(12.dp))
                Text(
                    "${clock(editedSec)} / ${clock(spec.totalSec)}",
                    style = MaterialTheme.typography.titleSmall,
                )
            }
            ClipTimeline(
                clips = spec.clips,
                selected = sel,
                editedSec = editedSec,
                onSeek = { seekEdited(it) },
            )
            Text(
                "Clip ${sel + 1} of ${spec.clips.size} · ${clock(clip.lengthSec)} · " +
                    "from ${clock(clip.startSec)} to ${clock(clip.endSec)} of the original",
                style = MaterialTheme.typography.bodySmall,
                color = P5.Grey,
            )

            // Trim: the clip under the playhead can be shortened, or lengthened back into the space
            // beside it (never over another clip).
            val (lo, hi) = spec.bounds(sel, src.durationSec)
            RangeSlider(
                value = clip.startSec.toFloat()..clip.endSec.toFloat(),
                onValueChange = { range ->
                    player.pause()
                    val startMoved = range.start != clip.startSec.toFloat()
                    val s = if (startMoved) range.start.toDouble() else clip.startSec
                    val e = if (startMoved) clip.endSec else range.endInclusive.toDouble()
                    spec.trim(sel, s, e, src.durationSec)?.let { spec = it }
                },
                onValueChangeFinished = { load(sel, 0.0) },
                valueRange = lo.toFloat()..hi.toFloat(),
                colors = SliderDefaults.colors(
                    thumbColor = P5.White,
                    activeTrackColor = P5.White,
                    inactiveTrackColor = P5.Black,
                ),
            )
            // Moving the playhead: by seconds, tenths of a second or single frames (at the video's own
            // frame rate; 30 if that couldn't be read).
            fun step(delta: Double) = seekTo(sel, (offset + delta).coerceIn(0.0, clip.lengthSec))
            StepButtons(frameSecOf(src)) { step(it) }
            // Trim the clip to the playhead.
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                P5Button(
                    "Start here",
                    onClick = {
                        applyEdit(spec.trim(sel, clip.startSec + offset, clip.endSec, src.durationSec), sel, 0.0)
                    },
                    modifier = Modifier.weight(1f), kind = P5Kind.Secondary, compact = true,
                )
                P5Button(
                    "End here",
                    onClick = {
                        val next = spec.trim(sel, clip.startSec, clip.startSec + offset, src.durationSec)
                        // Shows the last moment of the shortened clip.
                        applyEdit(next, sel, max(0.0, (next?.clips?.get(sel)?.lengthSec ?: 0.0) - 1.0))
                    },
                    modifier = Modifier.weight(1f), kind = P5Kind.Secondary, compact = true,
                )
            }

            // Cut here / delete / reorder.
            val canCut = spec.clips.size < EditSpec.MAX_CLIPS &&
                offset >= EditSpec.MIN_CLIP_SEC && offset <= clip.lengthSec - EditSpec.MIN_CLIP_SEC
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                P5Button(
                    "Cut here",
                    onClick = { applyEdit(spec.split(sel, clip.startSec + offset), sel + 1, 0.0) },
                    enabled = canCut,
                    modifier = Modifier.weight(1f), compact = true,
                )
                P5Button(
                    "Delete clip",
                    onClick = { applyEdit(spec.delete(sel), min(sel, spec.clips.size - 2), 0.0) },
                    enabled = spec.clips.size > 1,
                    modifier = Modifier.weight(1f), kind = P5Kind.Secondary, compact = true,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                P5Button(
                    "◀ Earlier",
                    onClick = { applyEdit(spec.move(sel, -1), sel - 1, offset) },
                    enabled = sel > 0,
                    modifier = Modifier.weight(1f), kind = P5Kind.Secondary, compact = true,
                )
                P5Button(
                    "Later ▶",
                    onClick = { applyEdit(spec.move(sel, 1), sel + 1, offset) },
                    enabled = sel < spec.clips.lastIndex,
                    modifier = Modifier.weight(1f), kind = P5Kind.Secondary, compact = true,
                )
            }
            Text(
                "Cut here splits the clip at the playhead. Delete clip removes it, and Earlier and Later " +
                    "reorder the clips. Up to ${EditSpec.MAX_CLIPS} clips." +
                    if (spec.clips.size >= EditSpec.MAX_CLIPS) " That's the most for this video." else "",
                style = MaterialTheme.typography.bodySmall,
                color = P5.Grey,
            )
        } else {
            // ---- Crop: one frame for the whole video ----
            Text(
                "Drag the frame's corners and edges, or drag inside it to move it.",
                style = MaterialTheme.typography.bodySmall,
                color = P5.Grey,
            )
            val ratios = listOf("1:1" to 1f, "4:3" to 4f / 3f, "3:4" to 3f / 4f, "16:9" to 16f / 9f, "9:16" to 9f / 16f)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for ((label, ratio) in ratios) {
                    P5Button(
                        label,
                        onClick = {
                            val r = aspectCrop(frameW, frameH, cropF, ratio)
                            cropF = r
                            spec = spec.withCrop(
                                EditSpec.cleanCrop(
                                    r.left.roundToInt(), r.top.roundToInt(),
                                    r.width.roundToInt(), r.height.roundToInt(), frameW, frameH,
                                )
                            )
                        },
                        modifier = Modifier.weight(1f), kind = P5Kind.Secondary, dense = true,
                    )
                }
                P5Button(
                    "No crop",
                    onClick = {
                        cropF = Rect(0f, 0f, frameW.toFloat(), frameH.toFloat())
                        spec = spec.withCrop(null)
                    },
                    enabled = spec.crop != null,
                    modifier = Modifier.weight(1f), kind = P5Kind.Secondary, dense = true,
                )
            }
            Text(
                spec.crop?.let { "Crop: ${it.w}×${it.h}, from ${it.x},${it.y} of the original's ${frameW}×$frameH" }
                    ?: "No crop: the whole ${frameW}×$frameH picture.",
                style = MaterialTheme.typography.bodySmall,
            )
        }

        // ---- What this edit does to the conversion ----
        Box(Modifier.fillMaxWidth().height(1.dp).background(P5.White.copy(alpha = 0.4f)))
        Text(
            "Edited video: ${clock(spec.totalSec)} · ${effective.width}×${effective.height} · ${spec.describe()}",
            style = MaterialTheme.typography.titleSmall,
        )
        if (live != null && estimate != null) {
            Text(
                buildString {
                    append("Expected conversion output: ")
                    append(if (estimate.parts == 1) "1 file" else "${estimate.parts} files")
                    if (estimate.parts > 1) append(" of ${String.format(Locale.US, "%.1f", estimate.partSec)}s each")
                    append(" - ${estimate.height}p at ${estimate.videoKbps}kbps")
                },
                style = MaterialTheme.typography.bodySmall,
            )
            if (estimate.videoKbps > 0) {
                val bracket = Converter.qualityBracket(estimate.qualityBpp, live.tuning)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Quality bracket", style = MaterialTheme.typography.labelMedium, color = P5.Grey)
                    P5Tag(bracket.label, if (bracket.poor) P5.Amber else P5.White)
                }
            }
            if ((live.minWidth > 0 || live.minHeight > 0) && (effective.width < live.minWidth || effective.height < live.minHeight)) {
                Text(
                    "Below this target's minimum (${live.minWidth}×${live.minHeight}): it will be scaled up, keeping its aspect ratio.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

/**
 * The edited video as a bar: one block per clip in the order they are joined (the selected one in
 * red), a white line for the playhead. Tap or drag on it to move the playhead.
 */
@Composable
internal fun ClipTimeline(clips: List<EditClip>, selected: Int, editedSec: Double, onSeek: (Double) -> Unit) {
    val total = clips.sumOf { it.lengthSec }.coerceAtLeast(0.001)
    val seek = rememberUpdatedState(onSeek)
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(46.dp)
            .pointerInput(total) {
                detectTapGestures { p -> seek.value((p.x / size.width * total).coerceIn(0.0, total)) }
            }
            .pointerInput(total) {
                detectHorizontalDragGestures { change, _ ->
                    change.consume()
                    seek.value((change.position.x / size.width * total).coerceIn(0.0, total))
                }
            }
    ) {
        val gap = 3.dp.toPx()
        var x = 0f
        for ((i, c) in clips.withIndex()) {
            val w = (c.lengthSec / total * size.width).toFloat()
            drawRect(
                color = if (i == selected) P5.White else P5.GreyDim,
                topLeft = Offset(x + gap / 2, 0f),
                size = Size(max(1f, w - gap), size.height),
            )
            x += w
        }
        val px = (editedSec / total * size.width).toFloat().coerceIn(0f, size.width)
        drawRect(color = P5.Amber, topLeft = Offset(px - 1.5.dp.toPx(), 0f), size = Size(3.dp.toPx(), size.height))
    }
}

// ---- The crop frame ----

private const val G_LEFT = 1
private const val G_RIGHT = 2
private const val G_TOP = 4
private const val G_BOTTOM = 8
private const val G_MOVE = 16

/** How far the crop frame's drawing area reaches beyond the picture on every side. */
internal val CROP_PAD = 28.dp

/** How close to an edge or corner (in dp) a touch has to be to take hold of it. */
private val CROP_GRAB = 36.dp

/** Where the picture sits inside the overlay: its top left corner and how many canvas pixels one picture pixel is. */
private class PictureGeometry(val left: Float, val top: Float, val scale: Float)

private fun pictureGeometry(w: Float, h: Float, frameW: Int, frameH: Int, pad: Float): PictureGeometry {
    val scale = max(0.0001f, min((w - 2 * pad) / frameW, (h - 2 * pad) / frameH))
    return PictureGeometry((w - frameW * scale) / 2f, (h - frameH * scale) / 2f, scale)
}

/**
 * Draws the crop frame over the picture, with the part outside it dimmed. With [interactive] the
 * frame can be dragged: [rect] is in pixels of the picture, [onRect] gets the new one. The overlay is
 * [CROP_PAD] larger than the picture on every side (see where it is placed), so the frame's edges
 * can be grabbed even when they are right on the picture's border.
 */
@Composable
internal fun CropOverlay(
    frameW: Int,
    frameH: Int,
    rect: Rect,
    interactive: Boolean,
    onRect: (Rect) -> Unit,
    modifier: Modifier = Modifier,
) {
    val current = rememberUpdatedState(rect)
    val report = rememberUpdatedState(onRect)
    Canvas(
        modifier.pointerInput(frameW, frameH, interactive) {
            if (!interactive) return@pointerInput
            val pad = CROP_PAD.toPx()
            val grabDistance = CROP_GRAB.toPx()
            awaitEachGesture {
                // Takes hold the moment the finger lands, not after it has moved a little.
                val down = awaitFirstDown(requireUnconsumed = false)
                val g = pictureGeometry(size.width.toFloat(), size.height.toFloat(), frameW, frameH, pad)
                val grab = cropHit(
                    current.value, Offset(down.position.x - g.left, down.position.y - g.top), g.scale, grabDistance,
                )
                // A touch that isn't on the frame is left alone, so the page can still scroll.
                if (grab != 0) {
                    down.consume()
                    drag(down.id) { change ->
                        val d = change.positionChange()
                        change.consume()
                        report.value(dragCrop(current.value, grab, d.x / g.scale, d.y / g.scale, frameW, frameH))
                    }
                }
            }
        }
    ) {
        val g = pictureGeometry(size.width, size.height, frameW, frameH, CROP_PAD.toPx())
        val vl = g.left
        val vt = g.top
        val vr = vl + frameW * g.scale
        val vb = vt + frameH * g.scale
        val l = vl + rect.left * g.scale
        val t = vt + rect.top * g.scale
        val r = vl + rect.right * g.scale
        val b = vt + rect.bottom * g.scale
        // The picture outside the frame, dimmed.
        val dim = Color.Black.copy(alpha = 0.6f)
        drawRect(dim, Offset(vl, vt), Size(vr - vl, max(0f, t - vt)))
        drawRect(dim, Offset(vl, b), Size(vr - vl, max(0f, vb - b)))
        drawRect(dim, Offset(vl, t), Size(max(0f, l - vl), max(0f, b - t)))
        drawRect(dim, Offset(r, t), Size(max(0f, vr - r), max(0f, b - t)))
        drawRect(
            color = P5.White,
            topLeft = Offset(l, t),
            size = Size(max(0f, r - l), max(0f, b - t)),
            style = Stroke(width = 2.5.dp.toPx()),
        )
        if (interactive) {
            // Thirds.
            val faint = Color.White.copy(alpha = 0.35f)
            for (k in 1..2) {
                val gx = l + (r - l) * k / 3f
                val gy = t + (b - t) * k / 3f
                drawLine(faint, Offset(gx, t), Offset(gx, b), strokeWidth = 1.dp.toPx())
                drawLine(faint, Offset(l, gy), Offset(r, gy), strokeWidth = 1.dp.toPx())
            }
            // Handles: a square on each corner and a bar in the middle of each edge, so it is plain
            // where to take hold.
            fun handle(cx: Float, cy: Float, w: Float, h: Float) {
                drawRect(P5.Red, Offset(cx - w / 2, cy - h / 2), Size(w, h))
                drawRect(P5.White, Offset(cx - w / 2, cy - h / 2), Size(w, h), style = Stroke(width = 1.5.dp.toPx()))
            }
            val sq = 16.dp.toPx()
            val barLong = 34.dp.toPx()
            val barShort = 10.dp.toPx()
            val mx = (l + r) / 2f
            val my = (t + b) / 2f
            handle(mx, t, barLong, barShort)
            handle(mx, b, barLong, barShort)
            handle(l, my, barShort, barLong)
            handle(r, my, barShort, barLong)
            for (cx in listOf(l, r)) for (cy in listOf(t, b)) handle(cx, cy, sq, sq)
        }
    }
}

/** Which part of the frame a touch at [p] (in canvas pixels) takes hold of: edges, corners or all of it. 0 = none. */
private fun cropHit(r: Rect, p: Offset, scale: Float, slop: Float): Int {
    val l = r.left * scale
    val t = r.top * scale
    val rr = r.right * scale
    val b = r.bottom * scale
    val inX = p.x >= l - slop && p.x <= rr + slop
    val inY = p.y >= t - slop && p.y <= b + slop
    val nearL = abs(p.x - l) <= slop
    val nearR = abs(p.x - rr) <= slop
    val nearT = abs(p.y - t) <= slop
    val nearB = abs(p.y - b) <= slop
    val horizontal = when {
        !inY -> 0
        nearL && (!nearR || abs(p.x - l) <= abs(p.x - rr)) -> G_LEFT
        nearR -> G_RIGHT
        else -> 0
    }
    val vertical = when {
        !inX -> 0
        nearT && (!nearB || abs(p.y - t) <= abs(p.y - b)) -> G_TOP
        nearB -> G_BOTTOM
        else -> 0
    }
    val edges = horizontal or vertical
    if (edges != 0) return edges
    return if (p.x in l..rr && p.y in t..b) G_MOVE else 0
}

private fun clampF(v: Float, lo: Float, hi: Float): Float = if (hi < lo) lo else v.coerceIn(lo, hi)

/** The frame after a drag of [dx],[dy] (picture pixels) on the parts in [grab]; kept inside the picture and no smaller than the minimum. */
private fun dragCrop(r: Rect, grab: Int, dx: Float, dy: Float, frameW: Int, frameH: Int): Rect {
    val minPx = EditSpec.MIN_CROP_PX.toFloat()
    var l = r.left
    var t = r.top
    var rr = r.right
    var b = r.bottom
    if (grab and G_MOVE != 0) {
        val w = rr - l
        val h = b - t
        l = clampF(l + dx, 0f, frameW - w)
        t = clampF(t + dy, 0f, frameH - h)
        rr = l + w
        b = t + h
    } else {
        if (grab and G_LEFT != 0) l = clampF(l + dx, 0f, rr - minPx)
        if (grab and G_RIGHT != 0) rr = clampF(rr + dx, l + minPx, frameW.toFloat())
        if (grab and G_TOP != 0) t = clampF(t + dy, 0f, b - minPx)
        if (grab and G_BOTTOM != 0) b = clampF(b + dy, t + minPx, frameH.toFloat())
    }
    return Rect(l, t, rr, b)
}

/** The biggest frame of shape [ratio] (width over height) that fits in the picture, centred where [cur] is. */
private fun aspectCrop(frameW: Int, frameH: Int, cur: Rect, ratio: Float): Rect {
    val fw = frameW.toFloat()
    val fh = frameH.toFloat()
    val w: Float
    val h: Float
    if (fw / fh > ratio) {
        h = fh
        w = h * ratio
    } else {
        w = fw
        h = w / ratio
    }
    val cx = (cur.left + cur.right) / 2f
    val cy = (cur.top + cur.bottom) / 2f
    val l = clampF(cx - w / 2f, 0f, fw - w)
    val t = clampF(cy - h / 2f, 0f, fh - h)
    return Rect(l, t, l + w, t + h)
}
