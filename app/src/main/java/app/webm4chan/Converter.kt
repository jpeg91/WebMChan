package app.webm4chan

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
    ) : ConvertResult()

    data class Fail(val message: String) : ConvertResult()
    object Cancelled : ConvertResult()
}

object Converter {

    /** Max duration used for the Custom option only; board targets carry their own. */
    const val DEFAULT_MAX_DURATION_SEC = 300

    private const val AUDIO_KBPS = 64

    // Aim a little under the limit: container overhead + rate-control overshoot.
    private const val INITIAL_TARGET = 0.95
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

    suspend fun probe(ctx: Context, uri: Uri): SourceInfo? = withContext(Dispatchers.IO) {
        val path = FFmpegKitConfig.getSafParameterForRead(ctx, uri)
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

    suspend fun convert(
        ctx: Context,
        uri: Uri,
        src: SourceInfo,
        settings: Settings,
        onLog: (line: String) -> Unit,
        onProgress: (fraction: Float, status: String) -> Unit, // fraction < 0 = indeterminate
    ): ConvertResult = withContext(Dispatchers.IO) {
        onLog("Copying video to app cache…")
        val inputFile = File.createTempFile(
            "webmchan-input-",
            ".video",
            ctx.cacheDir
        )

        try {
            val source = ctx.contentResolver.openInputStream(uri)
                ?: run {
                    inputFile.delete()
                    return@withContext ConvertResult.Fail(
                        "Could not open the selected video."
                    )
                }

            source.use { srcStream ->
                inputFile.outputStream().use { dst ->
                    srcStream.copyTo(dst)
                }
            }
        } catch (e: Exception) {
            inputFile.delete()
            return@withContext ConvertResult.Fail(
                "Could not copy selected video: ${e.message}"
            )
        }

        val input = inputFile.absolutePath

        FFmpegKitConfig.enableLogCallback { log -> onLog(log.message) }
        try {
            val duration = min(src.durationSec, settings.maxDurationSec.toDouble())
            val trimmed = src.durationSec > settings.maxDurationSec
            val audioOn = settings.keepAudio && src.hasAudio
            val audioKbps = if (audioOn) AUDIO_KBPS else 0

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
                val vKbps = (totalKbps - audioKbps).toInt()
                if (vKbps < 40) {
                    return@withContext ConvertResult.Fail(
                        "Video is too long for this size limit" +
                            if (audioOn) " with audio. Try removing audio." else "."
                    )
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

                val common = listOf(
                    "-y", "-i", input, "-t", "%.3f".format(java.util.Locale.US, duration),
                    "-map", "0:v:0", "-sn", "-dn", "-map_metadata", "-1",
                    "-vf", filters,
                    "-c:v", if (settings.useVp9) "libvpx-vp9" else "libvpx", "-b:v", "${vKbps}k",
                    "-deadline", "good", "-cpu-used", cpuUsed,
                    "-lag-in-frames", lagFrames,
                ) + (if (settings.useVp9) {
                    
                    listOf("-row-mt", "1", "-pix_fmt", "yuv420p", "-colorspace", "unknown") +
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
                        if (audioOn) " + $audioKbps kbps audio" else ", no audio"
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
                        out, size, lastHeight, lastKbps, trimmed, settings.keepAudio && !src.hasAudio
                    )
                }
                // Too big: shrink the budget proportionally and retry.
                onLog("   over the limit, retrying with a smaller bitrate")
                budgetBytes *= (settings.limitBytes * 0.97) / size.toDouble()
            }
            cleanupLogs(ctx)
            ConvertResult.Fail("Couldn't get under the size limit after $MAX_ATTEMPTS attempts.")
        } finally {
            inputFile.delete()
            // Detach so later ffprobe/ffmpeg calls don't write into a finished run's log.
            FFmpegKitConfig.enableLogCallback { }
            FFmpegKitConfig.enableStatisticsCallback { }
        }
    }

    private fun tail(s: String?): String = s?.takeLast(400)?.trim().orEmpty()

    private fun cleanupLogs(ctx: Context) {
        ctx.cacheDir.listFiles()?.filter { it.name.startsWith("ffpass") }?.forEach { it.delete() }
    }

    /** Copies the result into Downloads/WebM4chan and returns its content Uri. */
    fun saveToDownloads(ctx: Context, file: File, name: String): Uri? {
        val resolver = ctx.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, "video/webm")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/WebM4chan")
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
