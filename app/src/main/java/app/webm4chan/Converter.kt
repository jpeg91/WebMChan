package app.webmchan

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegKitConfig
import com.arthenica.ffmpegkit.FFprobeKit
import com.arthenica.ffmpegkit.ReturnCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/** What we learned about the picked video. */
data class SourceInfo(
    val durationSec: Double,
    val width: Int,
    val height: Int,
    val fps: Double,
    val hasAudio: Boolean,
)

data class Settings(
    val limitBytes: Long,
    val maxDurationSec: Int,
    val keepAudio: Boolean,
    val minWidth: Int = 0,
    val minHeight: Int = 0,
    /** false = Quality (libvpx -cpu-used 2), true = Speed (-cpu-used 4). */
    val fastEncode: Boolean = false,
    /** false = VP8 (libvpx, default), true = VP9 (libvpx-vp9): slower, usually better detail per bit. */
    val useVp9: Boolean = false,
    /** Fastest: still two-pass, but the highest effort setting, short look-ahead and smaller frames. Overrides [fastEncode]. */
    val fastest: Boolean = false,
)

/**
 * One 4chan upload rule-set. Boards whose WebM rules are identical are merged into one target.
 * Source: https://a.4cdn.org/boards.json (max_webm_filesize, max_webm_duration,
 * min_image_width / min_image_height, webm_audio).
 */
data class Target(
    val limitKb: Int,
    val maxDurationSec: Int,
    val minWidth: Int,
    val minHeight: Int,
    val audioAllowed: Boolean,
    val boards: List<String>,
) {
    /** e.g. "4 MB · Min 500×500 · 120 s" */
    val title: String
        get() = buildString {
            append(if (limitKb % 1024 == 0) "${limitKb / 1024} MB" else "$limitKb KB")
            append(" · ")
            append(if (minWidth > 0 || minHeight > 0) "Min ${minWidth}×${minHeight}" else "No min res")
            append(" · ${maxDurationSec} s")
            if (audioAllowed) append(" · Audio OK")
        }

    /** e.g. "/hc/ /s/" */
    val boardsLabel: String get() = boards.joinToString(" ") { "/$it/" }
}

val TARGETS: List<Target> = listOf(
    Target(
        4096, 120, 0, 0, false,
        listOf(
            "3", "a", "aco", "adv", "an", "biz", "c", "cgl", "ck", "cm", "co", "d", "diy", "e", "f",
            "fa", "fit", "g", "gd", "h", "his", "hm", "i", "ic", "int", "jp", "k", "lgbt", "lit", "m",
            "mlp", "mu", "n", "news", "o", "out", "p", "po", "pol", "pw", "qst", "r9k", "s4s", "sci",
            "soc", "sp", "t", "tg", "toy", "trash", "trv", "tv", "u", "v", "vg", "vip", "vm", "vmg",
            "vp", "vr", "vrpg", "vst", "vt", "x", "xs", "y",
        ),
    ),
    Target(2048, 120, 0, 0, false, listOf("b", "bant")),
    Target(4096, 120, 480, 600, false, listOf("w", "wg")),
    Target(4096, 120, 500, 500, false, listOf("hc", "s")),
    Target(4096, 120, 1000, 1000, false, listOf("hr")),
    Target(4096, 120, 0, 0, true, listOf("r", "wsr")),
    Target(4096, 300, 0, 0, true, listOf("gif")),
    Target(6144, 400, 0, 0, true, listOf("wsg")),
)

sealed class ConvertResult {
    data class Ok(
        val file: File,
        val bytes: Long,
        val height: Int,
        val videoKbps: Int,
        val trimmed: Boolean,
        val audioDropped: Boolean,
        /** Whether the output actually has an audio track (false if removed, or source had none). */
        val audioIncluded: Boolean,
    ) : ConvertResult()

    data class Fail(val message: String) : ConvertResult()
    object Cancelled : ConvertResult()
}

/** Outcome of [Converter.stageSource]. */
sealed class StageResult {
    /** [file] is a private copy of the video; whoever asked for it deletes it when finished. */
    data class Ok(val file: File) : StageResult()
    data class Fail(val message: String) : StageResult()
    object Cancelled : StageResult()
}

object Converter {

    /** Added to the end of every converted file's name, just before ".webm". */
    const val OUTPUT_NAME_TAG = "WebMChan_for_Android"

    /**
     * "Part1" ... "Part9", or zero-padded to the width of the part count ("Part01" ... "Part12")
     * so the files sort in order.
     */
    fun partName(part: Int, numParts: Int): String =
        "Part" + part.toString().padStart(numParts.toString().length, '0')

    /** Max duration used for the Custom option only; board targets carry their own. */
    const val DEFAULT_MAX_DURATION_SEC = 300

    // ---- Length limits (back end only: the UI always shows the board's real limit) ----

    /** Seconds shaved off every target's length limit, in case a board's own check is sloppy. */
    const val LENGTH_GRACE_SEC = 1.0

    /** An overshoot of this many seconds or less is trimmed off rather than made into another part. */
    const val TRIM_TOLERANCE_SEC = 0.5

    /** What the app actually aims for: the target's limit minus [LENGTH_GRACE_SEC] (e.g. 300 -> 299). */
    fun effectiveMaxSec(maxDurationSec: Int): Double =
        (maxDurationSec - LENGTH_GRACE_SEC).coerceAtLeast(1.0)

    /**
     * How many files a video of [durationSec] becomes for a target with this [maxDurationSec]
     * (1 = no split). A video that only just overshoots (by [TRIM_TOLERANCE_SEC] or less, per
     * part) is trimmed to fit instead of getting an extra, almost-empty part.
     */
    fun partCount(durationSec: Double, maxDurationSec: Int): Int {
        val eff = effectiveMaxSec(maxDurationSec)
        return max(1, ceil((durationSec - TRIM_TOLERANCE_SEC) / eff).toInt())
    }

    private const val AUDIO_KBPS = 64
    // Audio is allowed to drop this low (rather than fail outright) when the budget is tight.
    private const val MIN_AUDIO_KBPS = 32
    private const val MIN_VIDEO_KBPS = 40

    // Aim a little under the limit: container overhead + rate-control overshoot.
    private const val INITIAL_TARGET = 0.95
    // Retry headroom: deliberately small. A big fixed cushion here (the old value was 0.97) can
    // overcorrect a near-miss (e.g. 0.5% over the limit) into a much bigger cut than it needed,
    // which was pushing audio+video combinations right up against MIN_VIDEO_KBPS and failing.
    private const val RETRY_MARGIN = 0.99
    private const val MAX_ATTEMPTS = 4

    // Minimum bits-per-pixel before a frame size is accepted; Fastest uses a higher value,
    // which picks smaller frames (fewer pixels to encode).
    private const val NORMAL_BPP = 0.06
    private const val FASTEST_BPP = 0.12

    fun cancel() {
        FFmpegKit.cancel()
    }

    fun displayName(ctx: Context, uri: Uri): String {
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) return c.getString(0) }
        return "video"
    }

    suspend fun probe(ctx: Context, uri: Uri): SourceInfo? =
        probePath(FFmpegKitConfig.getSafParameterForRead(ctx, uri))

    /** Same as [probe] but for a plain file, e.g. a finished conversion in the cache. */
    suspend fun probeFile(file: File): SourceInfo? = probePath(file.absolutePath)

    private suspend fun probePath(path: String): SourceInfo? = withContext(Dispatchers.IO) {
        val info = FFprobeKit.getMediaInformation(path).mediaInformation ?: return@withContext null
        val dur = info.duration?.toDoubleOrNull() ?: return@withContext null

        var w = 0
        var h = 0
        var fps = 30.0
        var audio = false
        for (s in info.streams) {
            when (s.type) {
                "video" -> if (w == 0) {
                    w = s.width?.toInt() ?: 0
                    h = s.height?.toInt() ?: 0
                    fps = parseRate(s.averageFrameRate) ?: 30.0
                }
                "audio" -> audio = true
            }
        }
        if (w == 0 || h == 0) return@withContext null
        SourceInfo(dur, w, h, fps, audio)
    }

    private fun parseRate(r: String?): Double? {
        if (r.isNullOrBlank()) return null
        val parts = r.split("/")
        val v = if (parts.size == 2) {
            val n = parts[0].toDoubleOrNull() ?: return null
            val d = parts[1].toDoubleOrNull() ?: return null
            if (d == 0.0) return null else n / d
        } else r.toDoubleOrNull() ?: return null
        return if (v in 1.0..240.0) v else null
    }

    /**
     * Smallest even height whose width (same aspect as the source) still meets both board
     * minimums. 0 when the target has no minimum.
     */
    private fun minHeightFor(src: SourceInfo, minW: Int, minH: Int): Int {
        if (minW <= 0 && minH <= 0) return 0
        val aspect = src.width.toDouble() / src.height
        val need = ceil(max(minH.toDouble(), minW / aspect)).toInt()
        return (need + 1) / 2 * 2
    }

    /**
     * Biggest height (<=1080, <= source) that still gets a decent bits-per-pixel for VP8,
     * never below [minHeight]. A source smaller than the minimum is scaled up to it.
     */
    private fun chooseHeight(vKbps: Int, src: SourceInfo, minHeight: Int, minBpp: Double): Int {
        val fps = min(src.fps, 30.0)
        val aspect = src.width.toDouble() / src.height
        val ladder = listOf(1080, 720, 480, 360, 240)
        val base = if (src.height <= 1080) {
            listOf(src.height) + ladder.filter { it < src.height }
        } else ladder
        val candidates = base.filter { it >= minHeight }.toMutableList()
        if (minHeight > 0 && (candidates.isEmpty() || candidates.last() > minHeight)) {
            candidates.add(minHeight)
        }
        for (h in candidates) {
            val w = h * aspect
            val bpp = vKbps * 1000.0 / (w * h * fps)
            if (bpp >= minBpp) return h / 2 * 2
        }
        return candidates.last() / 2 * 2
    }

    // Spare room wanted in the cache on top of the source's own size (encoder output, pass logs).
    private const val STAGE_SPARE_BYTES = 64L * 1024 * 1024

    /**
     * Copies the picked video into the app's private cache so ffmpeg can read it as an ordinary
     * file. Call once per video (not once per part of a split video) and delete the file when
     * the video is finished with. Checks for enough free space first, reports progress as it
     * copies, and stops early (deleting the partial copy) when [shouldStop] turns true.
     */
    suspend fun stageSource(
        ctx: Context,
        uri: Uri,
        shouldStop: () -> Boolean,
        onProgress: (fraction: Float) -> Unit, // fraction < 0 = size unknown
    ): StageResult = withContext(Dispatchers.IO) {
        val size = try {
            ctx.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
        } catch (_: Exception) {
            -1L
        }
        if (size > 0 && ctx.cacheDir.usableSpace < size + STAGE_SPARE_BYTES) {
            val needMb = (size + STAGE_SPARE_BYTES) / 1048576
            return@withContext StageResult.Fail(
                "Not enough free storage to prepare this video (about $needMb MB needed). " +
                    "Free up some space and try again."
            )
        }

        val file = File.createTempFile("webmchan-input-", ".video", ctx.cacheDir)
        try {
            val input = ctx.contentResolver.openInputStream(uri)
            if (input == null) {
                file.delete()
                return@withContext StageResult.Fail("Could not open the selected video.")
            }
            var stopped = false
            input.use { src ->
                file.outputStream().use { dst ->
                    val buf = ByteArray(256 * 1024)
                    var copied = 0L
                    var lastPct = -1
                    onProgress(if (size > 0) 0f else -1f)
                    while (true) {
                        if (shouldStop()) { stopped = true; break }
                        val n = src.read(buf)
                        if (n < 0) break
                        dst.write(buf, 0, n)
                        copied += n
                        if (size > 0) {
                            val pct = (copied * 100 / size).toInt().coerceIn(0, 100)
                            if (pct != lastPct) { lastPct = pct; onProgress(pct / 100f) }
                        }
                    }
                }
            }
            if (stopped) {
                file.delete()
                return@withContext StageResult.Cancelled
            }
            StageResult.Ok(file)
        } catch (e: Exception) {
            file.delete()
            StageResult.Fail("Could not copy selected video: ${e.message}")
        }
    }

    /**
     * Deletes working files left in the cache: staged source copies, the encoder's output and
     * the pass-1 logs. Safe between videos; also run once when the app starts, to clear what a
     * killed run left behind.
     */
    fun clearWorkFiles(ctx: Context) {
        ctx.cacheDir.listFiles()?.filter {
            it.name.startsWith("webmchan-input-") || it.name == "out.webm" || it.name.startsWith("ffpass")
        }?.forEach { it.delete() }
    }

    suspend fun convert(
        ctx: Context,
        /** The staged copy of the video (see [stageSource]). Not deleted here: the caller owns it. */
        input: File,
        src: SourceInfo,
        settings: Settings,
        /** Where in the source this clip starts. 0 for a normal (non-split) conversion. */
        startSec: Double = 0.0,
        /** Length of this clip. null = the old behaviour: min(source length, the effective limit). Set by
         *  the caller for a split part, so each part gets an exact slice regardless of the limit. */
        clipDurationSec: Double? = null,
        onLog: (line: String) -> Unit,
        onProgress: (fraction: Float, status: String) -> Unit, // fraction < 0 = indeterminate
    ): ConvertResult = withContext(Dispatchers.IO) {
        FFmpegKitConfig.enableLogCallback { log -> onLog(log.message) }
        try {
            val remaining = (src.durationSec - startSec).coerceAtLeast(0.0)
            val effMax = effectiveMaxSec(settings.maxDurationSec)
            val duration = clipDurationSec ?: min(remaining, effMax)
            val trimmed = clipDurationSec == null && src.durationSec > effMax
            val audioOn = settings.keepAudio && src.hasAudio

            val out = File(ctx.cacheDir, "out.webm")
            val passLog = File(ctx.cacheDir, "ffpass").absolutePath
            var budgetBytes = settings.limitBytes * INITIAL_TARGET
            var lastHeight = 0
            var lastKbps = 0
            val hasMin = settings.minWidth > 0 || settings.minHeight > 0
            val minHeight = minHeightFor(src, settings.minWidth, settings.minHeight)
            // Encoder effort, not CPU cores: higher = faster but slightly lower quality at the
            // same bitrate. The same value is used for both passes.
            val cpuUsed = if (settings.fastest) "5" else if (settings.fastEncode) "4" else "2"
            val lagFrames = if (settings.fastest) "8" else "25"
            val effortName = if (settings.fastest) "Fastest" else if (settings.fastEncode) "Speed" else "Quality"

            for (attempt in 1..MAX_ATTEMPTS) {
                val totalKbps = budgetBytes * 8.0 / duration / 1000.0
                // Audio normally gets a fixed 64 kbps, but on a tight budget that alone can push
                // video under MIN_VIDEO_KBPS and fail a clip that would otherwise fit fine. Let
                // audio flex down (as low as MIN_AUDIO_KBPS) before giving up on the attempt.
                val audioKbps = when {
                    !audioOn -> 0
                    totalKbps - AUDIO_KBPS >= MIN_VIDEO_KBPS -> AUDIO_KBPS
                    totalKbps - MIN_AUDIO_KBPS >= MIN_VIDEO_KBPS -> (totalKbps - MIN_VIDEO_KBPS).toInt()
                    else -> MIN_AUDIO_KBPS
                }
                val vKbps = (totalKbps - audioKbps).toInt()
                if (vKbps < MIN_VIDEO_KBPS) {
                    return@withContext ConvertResult.Fail(
                        "Video is too long for this size limit" +
                            if (audioOn) " with audio. Try removing audio." else "."
                    )
                }
                if (audioOn && audioKbps < AUDIO_KBPS) {
                    onLog("   audio bitrate reduced to ${audioKbps} kbps to fit the limit")
                }
                val height = chooseHeight(vKbps, src, minHeight, if (settings.fastest) FASTEST_BPP else NORMAL_BPP)
                lastHeight = height
                lastKbps = vKbps

                val filters = buildList {
                    if (src.fps > 30.5) add("fps=30")
                    if (hasMin) {
                        // Evaluated by ffmpeg on the (auto-rotated) frame: aspect kept, even
                        // sizes, and never below the board's minimum width/height.
                        add(
                            "scale=w='2*ceil(oh*a/2)':" +
                                "h='2*ceil(max($height,max(${settings.minHeight},${settings.minWidth}/a))/2)'"
                        )
                    } else if (height < src.height || src.height > 1080) {
                        add("scale=-2:$height")
                    }
                }.joinToString(",").ifEmpty { "null" }

                val common = listOf("-y") +
                    // Input seeking: fast (keyframe-based demux seek), and still frame-accurate
                    // because we're re-encoding rather than stream-copying.
                    (if (startSec > 0.0) listOf("-ss", "%.3f".format(java.util.Locale.US, startSec)) else emptyList()) +
                    listOf(
                        "-i", input.absolutePath, "-t", "%.3f".format(java.util.Locale.US, duration),
                        "-map", "0:v:0", "-sn", "-dn", "-map_metadata", "-1",
                        "-vf", filters,
                        "-c:v", if (settings.useVp9) "libvpx-vp9" else "libvpx", "-b:v", "${vKbps}k",
                        "-deadline", "good", "-cpu-used", cpuUsed,
                        "-lag-in-frames", lagFrames,
                        // Tag the output's colour metadata explicitly. Without this, ffmpeg can leave
                        // it unset or carry over a tag from the source (sometimes "gbr"/identity, which
                        // tells a strict player to decode the YUV pixels as if they were RGB, producing
                        // wrong colours) even though the pixels themselves are normal YUV. bt709/tv is
                        // the standard tag for ordinary SDR video and matches what the encoder produces.
                        "-colorspace", "bt709", "-color_primaries", "bt709", "-color_trc", "bt709",
                        "-color_range", "tv",
                    ) + (if (settings.useVp9) {
                    listOf("-row-mt", "1", "-pix_fmt", "yuv420p") +
                        (if (settings.fastest) listOf("-tile-columns", "2") else emptyList())
                } else emptyList()) + listOf(
                    "-passlogfile", passLog,
                )

                // Pass 2 only: libvpx's analysis pass (pass 1) reports no frames or time.
                fun pass2Stats() {
                    FFmpegKitConfig.enableStatisticsCallback { st ->
                        val frac = (st.time.toDouble() / 1000.0 / duration).coerceIn(0.0, 1.0)
                        onProgress(
                            frac.toFloat(),
                            "Attempt $attempt/$MAX_ATTEMPTS, pass 2/2: encoding (${height}p, ${vKbps} kbps)"
                        )
                    }
                }

                onLog(
                    "== Attempt $attempt/$MAX_ATTEMPTS: ${height}p, $vKbps kbps video" +
                        (if (audioOn) " + $audioKbps kbps audio" else ", no audio") +
                        (if (startSec > 0.0) " (from %.1fs)".format(java.util.Locale.US, startSec) else "")
                )
                onLog("   filters: $filters")
                onLog("   codec: ${if (settings.useVp9) "VP9 (libvpx-vp9)" else "VP8 (libvpx)"}")
                onLog("   encoder effort: $effortName (cpu-used $cpuUsed)")
                onLog("-- pass 1/2: analysis only. ffmpeg usually reports no progress here.")
                FFmpegKitConfig.enableStatisticsCallback { }
                onProgress(-1f, "Attempt $attempt/$MAX_ATTEMPTS, pass 1/2: analysing (${height}p, ${vKbps} kbps)")
                val p1 = FFmpegKit.executeWithArguments(
                    (common + listOf("-pass", "1", "-an", "-f", "null", "/dev/null")).toTypedArray()
                )
                if (ReturnCode.isCancel(p1.returnCode)) return@withContext ConvertResult.Cancelled
                if (!ReturnCode.isSuccess(p1.returnCode)) {
                    return@withContext ConvertResult.Fail("Encoding failed (pass 1).\n${tail(p1.allLogsAsString)}")
                }

                onLog("-- pass 2/2: encoding")
                onProgress(0f, "Attempt $attempt/$MAX_ATTEMPTS, pass 2/2: encoding (${height}p, ${vKbps} kbps)")
                pass2Stats()
                val audioArgs = if (audioOn) {
                    listOf("-map", "0:a:0", "-c:a", "libvorbis", "-b:a", "${audioKbps}k", "-ac", "2")
                } else listOf("-an")
                val p2 = FFmpegKit.executeWithArguments(
                    (common + listOf("-pass", "2") + audioArgs + listOf("-f", "webm", out.absolutePath)).toTypedArray()
                )
                if (ReturnCode.isCancel(p2.returnCode)) return@withContext ConvertResult.Cancelled
                if (!ReturnCode.isSuccess(p2.returnCode)) {
                    return@withContext ConvertResult.Fail("Encoding failed (pass 2).\n${tail(p2.allLogsAsString)}")
                }

                val size = out.length()
                onLog("== Attempt $attempt result: $size bytes (limit ${settings.limitBytes})")
                if (size in 1..settings.limitBytes) {
                    onProgress(1f, "Done")
                    cleanupLogs(ctx)
                    return@withContext ConvertResult.Ok(
                        out, size, lastHeight, lastKbps, trimmed, settings.keepAudio && !src.hasAudio, audioOn
                    )
                }
                // Too big: shrink the budget proportionally and retry.
                onLog("   over the limit, retrying with a smaller bitrate")
                budgetBytes *= (settings.limitBytes * RETRY_MARGIN) / size.toDouble()
            }
            cleanupLogs(ctx)
            ConvertResult.Fail("Couldn't get under the size limit after $MAX_ATTEMPTS attempts.")
        } finally {
            // Detach so later ffprobe/ffmpeg calls don't write into a finished run's log.
            FFmpegKitConfig.enableLogCallback { }
            FFmpegKitConfig.enableStatisticsCallback { }
        }
    }

    private fun tail(s: String?): String = s?.takeLast(400)?.trim().orEmpty()

    private fun cleanupLogs(ctx: Context) {
        ctx.cacheDir.listFiles()?.filter { it.name.startsWith("ffpass") }?.forEach { it.delete() }
    }

    /** Copies the result into Downloads/WebMChan and returns its content Uri. */
    fun saveToDownloads(ctx: Context, file: File, name: String): Uri? {
        val resolver = ctx.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, "video/webm")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/WebMChan")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
        try {
            resolver.openOutputStream(uri)?.use { os -> file.inputStream().use { it.copyTo(os) } }
                ?: return null
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            return null
        }
        return uri
    }
}
