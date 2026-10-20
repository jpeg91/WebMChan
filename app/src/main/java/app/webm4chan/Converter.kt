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
    /** The fitting numbers (targets, attempts, audio bitrate...). See [Tuning]. */
    val tuning: Tuning = Tuning(),
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
        /** How many encodes it took to fit (1 = first try). */
        val attempts: Int = 1,
        /** True if the audio had to be measured on its own to get a close fit. */
        val audioAnalysed: Boolean = false,
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

    /** What the app actually aims for: the target's limit minus the length grace (e.g. 300 -> 299). */
    fun effectiveMaxSec(maxDurationSec: Int, tuning: Tuning): Double =
        (maxDurationSec - tuning.lengthGraceSec).coerceAtLeast(1.0)

    /**
     * How many files a video of [durationSec] becomes for a target with this [maxDurationSec]
     * (1 = no split). A video that only just overshoots (by the trim tolerance or less, per
     * part) is trimmed to fit instead of getting an extra, almost-empty part.
     */
    fun partCount(durationSec: Double, maxDurationSec: Int, tuning: Tuning): Int {
        val eff = effectiveMaxSec(maxDurationSec, tuning)
        return max(1, ceil((durationSec - tuning.trimToleranceSec) / eff).toInt())
    }

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
            it.name.startsWith("webmchan-input-") || it.name == "out.webm" || it.name == "audioprobe.webm" ||
                it.name.startsWith("ffpass")
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
            val t = settings.tuning
            val effMax = effectiveMaxSec(settings.maxDurationSec, t)
            val duration = clipDurationSec ?: min(remaining, effMax)
            val trimmed = clipDurationSec == null && src.durationSec > effMax
            val audioOn = settings.keepAudio && src.hasAudio

            val out = File(ctx.cacheDir, "out.webm")
            val passLog = File(ctx.cacheDir, "ffpass").absolutePath
            var budgetBytes = settings.limitBytes * (t.firstTargetPct / 100.0)
            var lastHeight = 0
            var lastKbps = 0
            val hasMin = settings.minWidth > 0 || settings.minHeight > 0
            val minHeight = minHeightFor(src, settings.minWidth, settings.minHeight)
            // Encoder effort, not CPU cores: higher = faster but slightly lower quality at the
            // same bitrate. The same value is used for both passes.
            val cpuUsed = if (settings.fastest) "5" else if (settings.fastEncode) "4" else "2"
            val lagFrames = if (settings.fastest) "8" else "25"
            val effortName = if (settings.fastest) "Fastest" else if (settings.fastEncode) "Speed" else "Quality"

            // Carried from one attempt to the next, for the audio analysis (see below).
            var lastSize = 0L
            var lastVideoKbps = 0
            var lastAudioKbps = 0
            // Size of this clip's audio on its own at the normal audio bitrate: -1 = not measured
            // yet, 0 = tried and couldn't.
            var audioBytes = -1L
            var audioAnalysed = false

            for (attempt in 1..t.maxAttempts) {
                val totalKbps = budgetBytes * 8.0 / duration / 1000.0
                // Audio normally gets its full bitrate, but on a tight budget that alone can push
                // video under the minimum and fail a clip that would otherwise fit fine. Let audio
                // flex down (as low as the minimum audio bitrate) before giving up on the attempt.
                var audioKbps = when {
                    !audioOn -> 0
                    totalKbps - t.audioKbps >= t.minVideoKbps -> t.audioKbps
                    totalKbps - t.minAudioKbps >= t.minVideoKbps -> (totalKbps - t.minVideoKbps).toInt()
                    else -> t.minAudioKbps
                }
                var vKbps = (totalKbps - audioKbps).toInt()

                // AUDIO ANALYSIS. Normally the audio is simply assumed to cost its nominal bitrate,
                // but Vorbis often comes out under that, and the usual retry (which scales the whole
                // file) can't tell a video overshoot from an audio one. If the earlier attempts
                // still went over, measure the audio on its own (a quick audio-only encode), work
                // out how the video encoder really responded to its bitrate, and give the video
                // exactly what's left of the limit. Only when the earlier attempt used the normal
                // audio bitrate, so that the measurement matches the audio inside its file.
                if (audioOn && attempt >= t.audioAnalysisAttempt &&
                    lastSize > 0 && lastVideoKbps > 0 && lastAudioKbps == t.audioKbps
                ) {
                    if (audioBytes < 0) {
                        onLog("   still over the limit after ${attempt - 1} attempts: measuring the audio on its own for a closer fit")
                        onProgress(-1f, "Attempt $attempt/${t.maxAttempts}: still too big, analysing the audio for a closer fit…")
                        audioBytes = measureAudioBytes(input, startSec, duration, t.audioKbps, File(ctx.cacheDir, "audioprobe.webm"))
                        if (audioBytes == MEASURE_CANCELLED) return@withContext ConvertResult.Cancelled
                        if (audioBytes > 0) {
                            onLog("   audio at ${t.audioKbps} kbps comes out at %.1f kbps for this clip".format(java.util.Locale.US, audioBytes * 8.0 / duration / 1000.0))
                        } else {
                            onLog("   couldn't measure the audio; carrying on with the usual retry")
                        }
                    }
                    if (audioBytes > 0) {
                        val videoObserved = lastSize - audioBytes
                        val videoAsked = lastVideoKbps * 1000.0 / 8.0 * duration
                        if (videoObserved > 0 && videoAsked > 0) {
                            val response = videoObserved / videoAsked // how much video it actually produced per bit asked for
                            val wanted = settings.limitBytes * (t.retryMarginPct / 100.0) - audioBytes
                            val analysedKbps = (wanted / response * 8.0 / duration / 1000.0).toInt()
                            if (analysedKbps >= t.minVideoKbps) {
                                onLog("   video came out at %.0f%% of its bitrate target; now %d kbps video".format(java.util.Locale.US, response * 100, analysedKbps))
                                audioKbps = t.audioKbps
                                vKbps = analysedKbps
                                audioAnalysed = true
                            }
                        }
                    }
                }

                if (vKbps < t.minVideoKbps) {
                    return@withContext ConvertResult.Fail(
                        "Video is too long for this size limit" +
                            if (audioOn) " with audio. Try removing audio." else "."
                    )
                }
                if (audioOn && audioKbps < t.audioKbps) {
                    onLog("   audio bitrate reduced to ${audioKbps} kbps to fit the limit")
                }
                val height = chooseHeight(vKbps, src, minHeight, if (settings.fastest) t.fastestBpp else t.normalBpp)
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
                            "Attempt $attempt/${t.maxAttempts}, pass 2/2: encoding (${height}p, ${vKbps} kbps)"
                        )
                    }
                }

                onLog(
                    "== Attempt $attempt/${t.maxAttempts}: ${height}p, $vKbps kbps video" +
                        (if (audioOn) " + $audioKbps kbps audio" else ", no audio") +
                        (if (startSec > 0.0) " (from %.1fs)".format(java.util.Locale.US, startSec) else "")
                )
                onLog("   filters: $filters")
                onLog("   codec: ${if (settings.useVp9) "VP9 (libvpx-vp9)" else "VP8 (libvpx)"}")
                onLog("   encoder effort: $effortName (cpu-used $cpuUsed)")
                onLog("-- pass 1/2: analysis only. ffmpeg usually reports no progress here.")
                FFmpegKitConfig.enableStatisticsCallback { }
                onProgress(-1f, "Attempt $attempt/${t.maxAttempts}, pass 1/2: analysing (${height}p, ${vKbps} kbps)")
                val p1 = FFmpegKit.executeWithArguments(
                    (common + listOf("-pass", "1", "-an", "-f", "null", "/dev/null")).toTypedArray()
                )
                if (ReturnCode.isCancel(p1.returnCode)) return@withContext ConvertResult.Cancelled
                if (!ReturnCode.isSuccess(p1.returnCode)) {
                    return@withContext ConvertResult.Fail("Encoding failed (pass 1).\n${tail(p1.allLogsAsString)}")
                }

                onLog("-- pass 2/2: encoding")
                onProgress(0f, "Attempt $attempt/${t.maxAttempts}, pass 2/2: encoding (${height}p, ${vKbps} kbps)")
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
                lastSize = size
                lastVideoKbps = vKbps
                lastAudioKbps = audioKbps
                onLog("== Attempt $attempt result: $size bytes (limit ${settings.limitBytes})")
                if (size in 1..settings.limitBytes) {
                    onProgress(1f, "Done")
                    cleanupLogs(ctx)
                    return@withContext ConvertResult.Ok(
                        out, size, lastHeight, lastKbps, trimmed, settings.keepAudio && !src.hasAudio, audioOn,
                        attempts = attempt, audioAnalysed = audioAnalysed,
                    )
                }
                // Too big: shrink the budget proportionally and retry.
                onLog("   over the limit, retrying with a smaller bitrate")
                budgetBytes *= (settings.limitBytes * (t.retryMarginPct / 100.0)) / size.toDouble()
            }
            cleanupLogs(ctx)
            ConvertResult.Fail("Couldn't get under the size limit after ${t.maxAttempts} attempts.")
        } finally {
            // Detach so later ffprobe/ffmpeg calls don't write into a finished run's log.
            FFmpegKitConfig.enableLogCallback { }
            FFmpegKitConfig.enableStatisticsCallback { }
        }
    }

    private const val MEASURE_CANCELLED = -1L

    /**
     * Encodes just this clip's audio at [kbps] into [probeOut] and returns its size in bytes, to
     * learn what the audio really costs (Vorbis often comes out under its nominal bitrate).
     * 0 = couldn't be measured; [MEASURE_CANCELLED] = the user cancelled. Deletes the file.
     */
    private fun measureAudioBytes(input: File, startSec: Double, duration: Double, kbps: Int, probeOut: File): Long {
        val r = FFmpegKit.executeWithArguments(
            (listOf("-y") + seekArgs(startSec) + listOf(
                "-i", input.absolutePath, "-t", "%.3f".format(java.util.Locale.US, duration),
                "-map", "0:a:0", "-vn", "-sn", "-dn", "-map_metadata", "-1",
                "-c:a", "libvorbis", "-b:a", "${kbps}k", "-ac", "2",
                "-f", "webm", probeOut.absolutePath,
            )).toTypedArray()
        )
        val bytes = when {
            ReturnCode.isCancel(r.returnCode) -> MEASURE_CANCELLED
            ReturnCode.isSuccess(r.returnCode) -> probeOut.length()
            else -> 0L
        }
        probeOut.delete()
        return bytes
    }

    /** Input seeking: fast (keyframe-based demux seek), and frame-accurate because we re-encode. */
    private fun seekArgs(startSec: Double): List<String> =
        if (startSec > 0.0) listOf("-ss", "%.3f".format(java.util.Locale.US, startSec)) else emptyList()

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
