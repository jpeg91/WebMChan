// #previewkit
package app.webmchan

import android.view.TextureView
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import java.util.Locale
import kotlin.math.max

/*
 * The parts the video editor (EditorScreen) and the thumbnail picker (ThumbnailPickerScreen) have in
 * common: the preview player and its picture, the clock, the frame stepping buttons and the "leave
 * without saving?" question. Neither screen decides anything about the video here; that stays in
 * their own files (and, for the edit, in EditSpec).
 */

/**
 * A preview player for the screen that calls this: frame-accurate seeks (what you stop on is exactly
 * the frame you see), no repeat, paused when the app goes to the background, and released when the
 * screen is left. [onError] is called when the phone can't play the video.
 */
@Composable
fun rememberPreviewPlayer(onError: () -> Unit): ExoPlayer {
    val ctx = LocalContext.current
    val player = remember {
        ExoPlayer.Builder(ctx).build().apply {
            // Frame-accurate seeks: a cut (or a picked thumbnail) lands where the playhead is, not on the nearest keyframe.
            setSeekParameters(SeekParameters.EXACT)
            repeatMode = Player.REPEAT_MODE_OFF
        }
    }
    val report = rememberUpdatedState(onError)
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                report.value()
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
    return player
}

/** The picture of [player]. Give it a box exactly the shape of the video. */
@Composable
fun PreviewSurface(player: ExoPlayer, modifier: Modifier = Modifier) {
    AndroidView(
        factory = { c -> TextureView(c).also { player.setVideoTextureView(it) } },
        modifier = modifier,
    )
}

/** Minutes, seconds and tenths: 1:05.3. */
fun clock(sec: Double): String {
    val t = max(0.0, sec)
    val m = (t / 60.0).toInt()
    return String.format(Locale.US, "%d:%04.1f", m, t - m * 60.0)
}

/** How long one frame of the video lasts, at its own frame rate (30 fps if that couldn't be read). */
fun frameSecOf(src: SourceInfo): Double = if (src.fpsReadable && src.fps > 0.0) 1.0 / src.fps else 1.0 / 30.0

/**
 * A row of buttons that move the playhead: by seconds, tenths of a second or single frames. [onStep]
 * gets the change in seconds (negative = back).
 */
@Composable
fun StepButtons(frameSec: Double, onStep: (Double) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        val steps = listOf(
            "−1s" to -1.0, "−0.1s" to -0.1, "−1 frame" to -frameSec,
            "+1 frame" to frameSec, "+0.1s" to 0.1, "+1s" to 1.0,
        )
        for ((label, delta) in steps) {
            P5Button(label, onClick = { onStep(delta) }, modifier = Modifier.weight(1f), kind = P5Kind.Secondary, dense = true)
        }
    }
}

/** "Discard changes?": asked when a screen is left with something unsaved. */
@Composable
fun DiscardDialog(message: String, onDiscard: () -> Unit, onKeep: () -> Unit) {
    AlertDialog(
        onDismissRequest = onKeep,
        shape = CutCornerShape(topStart = 20.dp, bottomEnd = 20.dp),
        containerColor = P5.Ink,
        titleContentColor = P5.White,
        textContentColor = P5.White,
        title = { Text("Discard changes".uppercase(), style = MaterialTheme.typography.titleMedium) },
        text = { Text(message) },
        confirmButton = { P5Button("Discard", onClick = onDiscard) },
        dismissButton = { P5Button("Keep editing", onClick = onKeep, kind = P5Kind.Ghost) },
    )
}
