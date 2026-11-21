// #livepreview
package app.webmchan

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File
import java.util.Locale

/*
 * The live preview on the converting card: while a video is encoding, the picture box on its card shows the
 * frame that is being encoded right now, refreshed every few seconds.
 *
 * It costs almost nothing because ffmpeg is given a SECOND OUTPUT on the same command: one small JPEG, thinned
 * to one frame every [Tuning.previewIntervalSec] seconds BEFORE it is scaled (so only that one frame is
 * resized and JPEG-coded), constantly overwritten. It shows the real frame being encoded (after the crop,
 * the size change, the tone-mapping and the picked thumbnail frame), because it is made from the same
 * decode. Only the encoding pass has it (pass 1 writes nothing to show).
 *
 * This file holds the pieces with no Android work in them (the ffmpeg arguments and the filter graph change,
 * which unit tests check) and the small bit of state the card reads. The running of ffmpeg is in
 * [Converter.executeWithPreview].
 */
object LivePreview {
    /** The long side of the preview picture, in pixels. The card's box is small, so this is plenty. */
    const val MAX_EDGE = 160

    /** The picture's file name in the cache folder (ffmpeg writes "<name>.tmp" first, then renames it, so a half-written picture is never read). */
    const val FILE_NAME = "live-preview.jpg"

    /** The range the interval can be set to in Advanced settings, in seconds. */
    /** The shortest video-time gap between two pictures (about one frame). */
    const val MIN_MEDIA_SEC = 0.03
    const val MIN_INTERVAL_SEC = 0.3
    const val MAX_INTERVAL_SEC = 30.0
    const val DEFAULT_INTERVAL_SEC = 3.0

    /** The JPEG quality ffmpeg is told to use (2 is best, 31 worst). At this size 8 gives a few KB. */
    private const val JPEG_QUALITY = 8

    // ---- What the card reads ----

    /** True while an encoding pass is writing preview pictures. The card only shows the preview then. */
    var running by mutableStateOf(false)
        private set

    /** The interval of the pass that is running, in seconds (the card polls the file about twice as often). */
    @Volatile
    var intervalSec: Double = DEFAULT_INTERVAL_SEC
        private set

    /** Set if the preview output ever made an encode fail: it is then left off until the app restarts. */
    @Volatile
    private var disabled = false

    fun usable(): Boolean = !disabled

    fun disable() {
        disabled = true
    }

    fun file(ctx: Context): File = File(ctx.cacheDir, FILE_NAME)

    /** Starts a pass: any older picture is thrown away, so the card never shows a frame from an earlier pass. */
    fun begin(ctx: Context, intervalSec: Double) {
        delete(ctx)
        this.intervalSec = intervalSec.coerceIn(MIN_INTERVAL_SEC, MAX_INTERVAL_SEC)
        running = true
    }

    /** Ends a pass (finished, failed, skipped or cancelled): the picture is not in use, so it is deleted. */
    fun end(ctx: Context) {
        running = false
        delete(ctx)
    }

    fun delete(ctx: Context) {
        File(ctx.cacheDir, FILE_NAME).delete()
        File(ctx.cacheDir, "$FILE_NAME.tmp").delete()
    }

    // ---- The ffmpeg side (plain strings, checked by LivePreviewTest) ----

    /**
     * The interval to give ffmpeg's fps filter. ffmpeg counts in VIDEO time, but the setting is in real time, and
     * a phone encodes slower than the video plays (0.08x was seen: 3 s of video = 37 s of waiting). So the real
     * interval is turned into video time with the expected encode [speed] (video seconds per real second). The card shows every
     * picture as soon as it is written, so a wrong guess only makes the pictures a little more or less frequent.
     */
    fun mediaInterval(wallSec: Double, speed: Double): Double =
        (wallSec.coerceIn(MIN_INTERVAL_SEC, MAX_INTERVAL_SEC) * speed.coerceIn(0.01, 20.0)).coerceIn(MIN_MEDIA_SEC, MAX_INTERVAL_SEC)

    /** "1/3.00": ffmpeg's fps filter reads it as one frame every 3 seconds. */
    fun rate(intervalSec: Double): String =
        "1/" + String.format(Locale.US, "%.2f", intervalSec.coerceIn(MIN_MEDIA_SEC, MAX_INTERVAL_SEC))

    /**
     * Makes a frame fit inside [MAX_EDGE] x [MAX_EDGE] (aspect kept, even sides) and ready for JPEG.
     * Fast, not pretty: it is shown in a small box.
     */
    fun shrinkFilters(): String =
        "scale=$MAX_EDGE:$MAX_EDGE:force_original_aspect_ratio=decrease:force_divisible_by=2:flags=fast_bilinear," +
            "format=yuvj420p"

    /**
     * The filters for a source that is read straight (no filter graph): the frames are thinned FIRST, then
     * [pictureFilters] (the crop, size and colour work the real output gets, without its frame-rate cap)
     * are applied to the few that are left, then the picture is shrunk.
     */
    fun plainChain(intervalSec: Double, pictureFilters: String): String =
        "fps=${rate(intervalSec)},$pictureFilters,${shrinkFilters()}"

    /** The tail every preview output has: no sound or extras, one JPEG, written over and over, via a temp name. */
    private fun outputTail(file: File, durationSec: Double): List<String> = listOf(
        "-an", "-sn", "-dn", "-map_metadata", "-1", "-map_chapters", "-1",
        "-t", String.format(Locale.US, "%.3f", durationSec),
        "-c:v", "mjpeg", "-q:v", JPEG_QUALITY.toString(),
        "-fps_mode", "passthrough",
        "-f", "image2", "-update", "1", "-atomic_writing", "1",
        file.absolutePath,
    )

    /**
     * The second output for a source read straight: the first video stream again, through [plainChain].
     * Added to the command AFTER the main output's file name, so its options belong to it alone.
     */
    fun plainOutput(file: File, durationSec: Double, pictureFilters: String, intervalSec: Double): List<String> =
        listOf("-map", "0:v:0", "-vf", plainChain(intervalSec, pictureFilters)) + outputTail(file, durationSec)

    /**
     * For a command that uses a filter graph: the graph with the final picture [vout] split in two. One
     * copy still goes to [vout] (so the main output is unchanged), the other is thinned and shrunk into
     * [vpv]. null if the graph has no [vout] to split (then there is no preview).
     */
    fun splitGraph(graph: String, intervalSec: Double): String? {
        if (graph.indexOf("[vout]") < 0 || graph.contains("[vfull]") || graph.contains("[vpv]")) return null
        return graph.replace("[vout]", "[vfull]") +
            ";[vfull]split[vout][vsrc];[vsrc]fps=${rate(intervalSec)},${shrinkFilters()}[vpv]"
    }

    /** The second output for a filter-graph command: the [vpv] branch made by [splitGraph]. */
    fun graphOutput(file: File, durationSec: Double): List<String> =
        listOf("-map", "[vpv]") + outputTail(file, durationSec)

    /**
     * The first `q=` number of the LAST progress line of an ffmpeg log: the main video's quantiser. With a
     * second video output ffmpeg adds another q= to every progress line (the preview's), so reading just the
     * last q= in the log would read that one. Same answer as before when there is only one.
     */
    fun mainEncoderQ(log: String?): String? =
        STATS_Q.findAll(log.orEmpty()).lastOrNull()?.groupValues?.get(1)

    private val STATS_Q = Regex("""frame=\s*\d+[^\r\n]*?\bq=(-?\d+(?:\.\d+)?)""")
}
