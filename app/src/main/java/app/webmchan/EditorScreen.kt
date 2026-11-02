// #editor
package app.webmchan

import android.view.TextureView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
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

    // ---- The preview player ----
    val player = remember {
        ExoPlayer.Builder(ctx).build().apply {
            // Frame-accurate seeks: a cut lands where the playhead is, not on the nearest keyframe.
            setSeekParameters(SeekParameters.EXACT)
            repeatMode = Player.REPEAT_MODE_OFF
        }
    }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                previewFailed = true
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }
    // Stop playing when the app goes to the background.
    DisposableEffect(ctx) {
        val lifecycle = (ctx as? LifecycleOwner)?.lifecycle
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) player.pause() }
        lifecycle?.addObserver(observer)
        onDispose { lifecycle?.removeObserver(observer) }
    }

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
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            shape = CutCornerShape(topStart = 20.dp, bottomEnd = 20.dp),
            containerColor = P5.Ink,
            titleContentColor = P5.White,
            textContentColor = P5.White,
            title = { Text("Discard changes".uppercase(), style = MaterialTheme.typography.titleMedium) },
            text = { Text("Leave the editor without saving your changes to this video?") },
            confirmButton = { P5Button("Discard", onClick = { confirmDiscard = false; onClose() }) },
            dismissButton = { P5Button("Keep editing", onClick = { confirmDiscard = false }, kind = P5Kind.Ghost) },
        )
    }

    // What the edit does to the conversion, worked out the way the card does it.
    val effective = remember(spec) { spec.effectiveInfo(src) }
    val estimate = remember(spec, live) { live?.let { Converter.estimate(effective, it, item.extraParts) } }

    // ---- The page ----
    val videoHeight = (LocalConfiguration.current.screenHeightDp * 0.38f).dp
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            P5Button("Cancel", onClick = { cancel() }, kind = P5Kind.Ghost)
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
                kind = P5Kind.Ghost,
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

        // The picture, in a box exactly the shape of the video so the crop frame lines up with it.
        Box(Modifier.fillMaxWidth().height(videoHeight), contentAlignment = Alignment.Center) {
            Box(Modifier.aspectRatio(frameW.toFloat() / frameH).background(Color.Black)) {
                AndroidView(
                    factory = { c -> TextureView(c).also { player.setVideoTextureView(it) } },
                    modifier = Modifier.fillMaxSize(),
                )
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
                if (previewFailed) {
                    Text(
                        "This phone can't preview this video. You can still set the cuts and the crop.",
                        style = MaterialTheme.typography.bodySmall,
                        color = P5.Amber,
                        modifier = Modifier.align(Alignment.Center).padding(12.dp),
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
                    thumbColor = P5.Red,
                    activeTrackColor = P5.Red,
                    inactiveTrackColor = P5.Line,
                ),
            )
            // Moving the playhead: by seconds, tenths of a second or single frames (at the video's own
            // frame rate; 30 if that couldn't be read).
            val frameSec = if (src.fpsReadable && src.fps > 0.0) 1.0 / src.fps else 1.0 / 30.0
            fun step(delta: Double) = seekTo(sel, (offset + delta).coerceIn(0.0, clip.lengthSec))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                P5Button("−1s", onClick = { step(-1.0) }, modifier = Modifier.weight(1f), kind = P5Kind.Secondary, dense = true)
                P5Button("−0.1s", onClick = { step(-0.1) }, modifier = Modifier.weight(1f), kind = P5Kind.Secondary, dense = true)
                P5Button("−1 frame", onClick = { step(-frameSec) }, modifier = Modifier.weight(1f), kind = P5Kind.Secondary, dense = true)
                P5Button("+1 frame", onClick = { step(frameSec) }, modifier = Modifier.weight(1f), kind = P5Kind.Secondary, dense = true)
                P5Button("+0.1s", onClick = { step(0.1) }, modifier = Modifier.weight(1f), kind = P5Kind.Secondary, dense = true)
                P5Button("+1s", onClick = { step(1.0) }, modifier = Modifier.weight(1f), kind = P5Kind.Secondary, dense = true)
            }
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
        Box(Modifier.fillMaxWidth().height(1.dp).background(P5.Line))
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

/** Minutes, seconds and tenths: 1:05.3. */
private fun clock(sec: Double): String {
    val t = max(0.0, sec)
    val m = (t / 60.0).toInt()
    return String.format(Locale.US, "%d:%04.1f", m, t - m * 60.0)
}

/**
 * The edited video as a bar: one block per clip in the order they are joined (the selected one in
 * red), a white line for the playhead. Tap or drag on it to move the playhead.
 */
@Composable
private fun ClipTimeline(clips: List<EditClip>, selected: Int, editedSec: Double, onSeek: (Double) -> Unit) {
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
                color = if (i == selected) P5.Red else P5.GreyDim,
                topLeft = Offset(x + gap / 2, 0f),
                size = Size(max(1f, w - gap), size.height),
            )
            x += w
        }
        val px = (editedSec / total * size.width).toFloat().coerceIn(0f, size.width)
        drawRect(color = P5.White, topLeft = Offset(px - 1.5.dp.toPx(), 0f), size = Size(3.dp.toPx(), size.height))
    }
}

// ---- The crop frame ----

private const val G_LEFT = 1
private const val G_RIGHT = 2
private const val G_TOP = 4
private const val G_BOTTOM = 8
private const val G_MOVE = 16

/**
 * Draws the crop frame over the picture, with the part outside it dimmed. With [interactive] the
 * frame can be dragged: [rect] is in pixels of the picture, [onRect] gets the new one.
 */
@Composable
private fun CropOverlay(
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
            var grab = 0
            detectDragGestures(
                onDragStart = { at ->
                    grab = cropHit(current.value, at, size.width.toFloat() / frameW, 28.dp.toPx())
                },
                onDragEnd = { grab = 0 },
                onDragCancel = { grab = 0 },
                onDrag = { change, drag ->
                    if (grab != 0) {
                        change.consume()
                        val scale = size.width.toFloat() / frameW
                        report.value(dragCrop(current.value, grab, drag.x / scale, drag.y / scale, frameW, frameH))
                    }
                },
            )
        }
    ) {
        val scale = size.width / frameW
        val l = rect.left * scale
        val t = rect.top * scale
        val r = rect.right * scale
        val b = rect.bottom * scale
        val dim = Color.Black.copy(alpha = 0.6f)
        drawRect(dim, Offset(0f, 0f), Size(size.width, max(0f, t)))
        drawRect(dim, Offset(0f, b), Size(size.width, max(0f, size.height - b)))
        drawRect(dim, Offset(0f, t), Size(max(0f, l), max(0f, b - t)))
        drawRect(dim, Offset(r, t), Size(max(0f, size.width - r), max(0f, b - t)))
        drawRect(
            color = P5.White,
            topLeft = Offset(l, t),
            size = Size(max(0f, r - l), max(0f, b - t)),
            style = Stroke(width = 2.dp.toPx()),
        )
        if (interactive) {
            // Thirds, and a handle on each corner.
            val faint = Color.White.copy(alpha = 0.35f)
            for (k in 1..2) {
                val gx = l + (r - l) * k / 3f
                val gy = t + (b - t) * k / 3f
                drawLine(faint, Offset(gx, t), Offset(gx, b), strokeWidth = 1.dp.toPx())
                drawLine(faint, Offset(l, gy), Offset(r, gy), strokeWidth = 1.dp.toPx())
            }
            val hs = 7.dp.toPx()
            for (cx in listOf(l, r)) for (cy in listOf(t, b)) {
                drawRect(P5.Red, Offset(cx - hs, cy - hs), Size(hs * 2, hs * 2))
            }
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
