// #converter
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** What we learned about the picked video. */
data class SourceInfo(
    val durationSec: Double,
    val width: Int,
    val height: Int,
    val fps: Double,
    val hasAudio: Boolean,
    /**
     * false when the frame rate couldn't be read. [fps] is then only a placeholder (30) used for
     * planning, and must never be shown as if it were the real rate.
     */
    val fpsReadable: Boolean = true,
    /**
     * Finished clips whose frame rate couldn't be read only: the rate the encoder aimed for,
     * or 0 when that isn't known either (the source's rate was kept and it wasn't readable).
     */
    val targetedFps: Double = 0.0,
    /** Size of the file in bytes; 0 when not known. */
    val fileBytes: Long = 0L,
    /** e.g. "MP4"; empty when not known. */
    val container: String = "",
    /** The ffprobe codec name of the video stream, e.g. "h264"; empty when not known. */
    val videoCodec: String = "",
    /** Video bitrate in kbps (the whole file's if the stream's isn't given); 0 when not known. */
    val videoKbps: Int = 0,
    /** The ffprobe codec name of the first audio stream, e.g. "aac"; empty when none or not known. */
    val audioCodec: String = "",
)

data class Settings(
    val limitBytes: Long,
    /** The target's length limit in seconds. 0 = no length limit (the Custom target). */
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
    /**
     * Best quality: the Quality encode plus hidden alt-ref frames, a slower effort setting
     * ([Tuning.bestCpuUsed]) and extra attempts that keep going until the file is within about 1% of
     * the size limit ([Tuning.bestMinFillPct]), keeping the fullest result that fits. Time is no
     * object. Overrides [fastEncode]; never set together with [fastest] (the screen makes the three
     * choices exclusive).
     */
    val bestQuality: Boolean = false,
    /**
     * The highest frame rate the output may have. A source at or below this keeps its own rate;
     * a faster source is reduced to it. Never raises a source's rate. See [Converter.effectiveFps].
     */
    val targetFps: Int = Converter.DEFAULT_FPS,
    /** The fitting numbers (targets, attempts, audio bitrate...). See [Tuning]. */
    val tuning: Tuning = Tuning(),
    /** True when the Custom target was selected (its length cap isn't a board's rule). Only affects card wording. */
    val isCustom: Boolean = false,
    /** The boards this was set up for, for cards to show (e.g. "/wsg/"), or "Custom". Only a label. */
    val targetLabel: String = "",
    /**
     * Custom target only: the output size in pixels. 0 = not set. With both set, the picture is
     * scaled to fit inside that box; with one set, the other side follows. The aspect ratio is
     * always kept, so the picture is never stretched. With neither set the size is picked
     * automatically to suit the size limit. See [Converter.customScaleFilter].
     */
    val customWidth: Int = 0,
    val customHeight: Int = 0,
    /**
     * The audio bitrate in kbps (one of [Converter.AUDIO_KBPS_OPTIONS]). On a tight budget the
     * audio may be lowered below it, down to [Tuning.minAudioKbps] (or this value if that is
     * lower). At [Converter.MONO_AUDIO_MAX_KBPS] and below the audio is mono.
     */
    val audioKbps: Int = Converter.DEFAULT_AUDIO_KBPS,
) {
    /** True when the Custom target has a resolution of its own (so the automatic pick is bypassed). */
    val hasCustomSize: Boolean get() = customWidth > 0 || customHeight > 0
}

/**
 * What a queue card shows about how a video would come out with a given number of parts:
 * how many parts, how long each is, the video bitrate each would get and the frame height that
 * bitrate would be encoded at. It's an estimate of the first attempt, worked out the same way
 * [Converter.convert] does it.
 */
data class PartEstimate(
    val parts: Int,
    val partSec: Double,
    val videoKbps: Int,
    val height: Int,
    /** The audio bitrate it would get on the first attempt; 0 when there is no audio. */
    val audioKbps: Int,
    /** Video bits per pixel: the bitrate over the pixels per second the output has. */
    val bitsPerPixel: Double,
    /** [bitsPerPixel] adjusted for frame size (see [Tuning.bracketSizeExponent]): what [QualityBracket] is judged on. */
    val qualityBpp: Double,
)

/**
 * How good a video's picture is expected to be at its estimated bitrate, going by bits per pixel
 * (which already includes the frame size and the frame rate). Where each bracket starts is set in
 * [Tuning]. [poor] brackets get improvement tips on the card.
 */
enum class QualityBracket(val label: String, val suits: String, val poor: Boolean) {
    STILL_IMAGE("Still Image", "Suits still images and slideshows. Anything with real motion will look rough.", true),
    WEB_ANIMATION("Web Animation", "Suits simple, flat-colour animation and memes. Live action will look poor.", true),
    PRO_ANIMATION("Pro Animation", "Suits clean animation with smooth motion. Live action will look soft and blocky.", true),
    OLD_WEB_VIDEO("Old Web Video", "Suits live action that looks like an old web clip. Fine for talking heads and moderate motion.", false),
    QUALITY("Quality", "Suits clean-looking live action. Fast motion may show some blockiness.", false),
    HIGH_QUALITY("High Quality", "Suits live action with clean detail, including fast motion.", false),
    ULTRA_HIGH_QUALITY("Ultra High Quality", "Suits detailed, grainy or fast-moving footage with room to spare.", false),
}

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

/** One finished attempt of a Best quality conversion (see [Converter.convert]). */
private data class BestTry(
    /** The video bitrate that was asked for. */
    val vKbps: Int,
    /** What came out: the file's size less its audio's expected share. */
    val videoBytes: Double,
    /** The whole file's size. */
    val size: Long,
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

    /** A [Settings.maxDurationSec] of this means no length limit. The Custom option uses it; board targets carry their own limit. */
    const val NO_LENGTH_LIMIT = 0

    /** Stands in for "no limit" in sums: far longer than any video (about 31 years). */
    private const val UNLIMITED_SEC = 1.0e9

    // ---- Audio ----

    /** The audio bitrates offered in the Sound settings, lowest first (the order they are listed in). */
    val AUDIO_KBPS_OPTIONS: List<Int> = listOf(16, 32, 64, 128, 256)

    const val DEFAULT_AUDIO_KBPS = 64

    /** Audio at or below this bitrate is encoded in mono: stereo at such a low rate isn't workable. */
    const val MONO_AUDIO_MAX_KBPS = 16

    /** The share of the budget audio must take before "decrease sound quality" is suggested. */
    const val AUDIO_TIP_SHARE = 0.10

    /** 1 for mono, else 2. */
    fun audioChannels(kbps: Int): Int = if (kbps <= MONO_AUDIO_MAX_KBPS) 1 else 2

    /** e.g. "64 kbps", or "16 kbps (mono)". */
    fun audioLabel(kbps: Int): String = if (kbps <= MONO_AUDIO_MAX_KBPS) "$kbps kbps (mono)" else "$kbps kbps"

    /**
     * The audio bitrate for a budget of [totalKbps]: the chosen [wanted] when the video still gets
     * at least [Tuning.minVideoKbps], otherwise as low as the budget needs, but never below
     * [Tuning.minAudioKbps] (or [wanted], if that is lower). 0 when there is no audio.
     */
    private fun audioKbpsFor(totalKbps: Double, wanted: Int, audioOn: Boolean, t: Tuning): Int {
        if (!audioOn) return 0
        val floor = min(wanted, t.minAudioKbps)
        return when {
            totalKbps - wanted >= t.minVideoKbps -> wanted
            totalKbps - floor >= t.minVideoKbps -> (totalKbps - t.minVideoKbps).toInt()
            else -> floor
        }
    }

    /** The pixels in a 16:9 240p frame: what the quality bracket starts are set for. */
    private const val REFERENCE_FRAME_PIXELS = 102_400.0

    /** Which [QualityBracket] a video of [bitsPerPixel] (frame size adjusted: [PartEstimate.qualityBpp]) falls in. */
    fun qualityBracket(bitsPerPixel: Double, t: Tuning): QualityBracket = when {
        bitsPerPixel < t.bracketWebAnimation -> QualityBracket.STILL_IMAGE
        bitsPerPixel < t.bracketProAnimation -> QualityBracket.WEB_ANIMATION
        bitsPerPixel < t.bracketOldWebVideo -> QualityBracket.PRO_ANIMATION
        bitsPerPixel < t.bracketQuality -> QualityBracket.OLD_WEB_VIDEO
        bitsPerPixel < t.bracketHighQuality -> QualityBracket.QUALITY
        bitsPerPixel < t.bracketUltraQuality -> QualityBracket.HIGH_QUALITY
        else -> QualityBracket.ULTRA_HIGH_QUALITY
    }

    /** The range a Custom resolution side is kept within, in pixels. */
    const val MIN_CUSTOM_PX = 16
    const val MAX_CUSTOM_PX = 7680

    /** Extra splits are never allowed to make a part shorter than this. */
    const val MIN_PART_SEC = 10.0

    // ---- Frame rate ----

    /** The frame rate cap used unless another is chosen. */
    const val DEFAULT_FPS = 30

    /** The range a Custom frame rate cap is kept within. */
    const val MIN_FPS = 1
    const val MAX_FPS = 120

    /** [fps] forced into the allowed range. */
    fun clampFps(fps: Int): Int = fps.coerceIn(MIN_FPS, MAX_FPS)

    /**
     * The frame rate the output is planned around: the source's own rate, or [targetFps] if the
     * source is faster. Never higher than the source.
     */
    fun effectiveFps(src: SourceInfo, targetFps: Int): Double =
        min(src.fps, clampFps(targetFps).toDouble())

    /**
     * The frame rate the encoder aimed for, for a clip whose own frame rate couldn't be read
     * afterwards. Known when the source's rate was readable (see [effectiveFps]) or when the
     * rate cap was applied by the fps filter. 0 = the source's own, unreadable, rate was kept.
     */
    fun targetedFps(src: SourceInfo, targetFps: Int): Double {
        val cap = clampFps(targetFps)
        return when {
            src.fpsReadable -> effectiveFps(src, targetFps)
            src.fps > cap + 0.5 -> cap.toDouble()
            else -> 0.0
        }
    }

    // ---- Length limits (back end only: the UI always shows the board's real limit) ----

    /** What the app actually aims for: the target's limit minus the length grace (e.g. 300 -> 299). */
    fun effectiveMaxSec(maxDurationSec: Int, tuning: Tuning): Double =
        if (maxDurationSec <= NO_LENGTH_LIMIT) UNLIMITED_SEC
        else (maxDurationSec - tuning.lengthGraceSec).coerceAtLeast(1.0)

    // ---- Custom resolution ----

    /** [v] rounded down to an even number (video frames want even sides); 0 stays 0 (= not set). */
    private fun evenDown(v: Int): Int = if (v <= 0) 0 else max(2, v / 2 * 2)

    /**
     * The ffmpeg scale filter for a Custom resolution. Both sides set: the picture is scaled to fit
     * inside that box (up or down) and keeps its aspect ratio, so there is no stretching and no
     * cropping or bars; the sides come out even and never beyond the box. One side set: the other
     * follows from the aspect ratio. ffmpeg works from the frame as it will be shown (rotation
     * applied), so portrait phone videos come out right.
     */
    fun customScaleFilter(width: Int, height: Int): String {
        val w = evenDown(width)
        val h = evenDown(height)
        return when {
            w > 0 && h > 0 -> "scale=w=$w:h=$h:force_original_aspect_ratio=decrease:force_divisible_by=2"
            w > 0 -> "scale=w=$w:h=-2"
            else -> "scale=w=-2:h=$h"
        }
    }

    /** The height [customScaleFilter] gives for [src], for the cards' estimates and the log. */
    fun customOutputHeight(src: SourceInfo, width: Int, height: Int): Int {
        val w = evenDown(width)
        val h = evenDown(height)
        val sw = src.width.toDouble()
        val sh = src.height.toDouble()
        val out = when {
            w > 0 && h > 0 -> min(h.toDouble(), w * sh / sw)
            w > 0 -> w * sh / sw
            else -> h.toDouble()
        }
        return max(2, (out / 2 + 0.5).toInt() * 2)
    }

    /**
     * The fewest files a video of [durationSec] can become for a target with this [maxDurationSec]
     * (1 = no split). A video that only just overshoots (by the trim tolerance or less, per
     * part) is trimmed to fit instead of getting an extra, almost-empty part.
     */
    fun partCount(durationSec: Double, maxDurationSec: Int, tuning: Tuning): Int {
        val eff = effectiveMaxSec(maxDurationSec, tuning)
        return max(1, ceil((durationSec - tuning.trimToleranceSec) / eff).toInt())
    }

    /**
     * The most parts a video can be split into: no part shorter than [MIN_PART_SEC]. Never less
     * than [partCount], which is the original minimum.
     */
    fun maxPartCount(durationSec: Double, maxDurationSec: Int, tuning: Tuning): Int =
        max(partCount(durationSec, maxDurationSec, tuning), floor(durationSec / MIN_PART_SEC).toInt())

    /**
     * The number of parts actually used: the minimum from [partCount] plus [extraParts] chosen on
     * the card, kept between that minimum and [maxPartCount].
     */
    fun partsFor(durationSec: Double, maxDurationSec: Int, tuning: Tuning, extraParts: Int): Int {
        val base = partCount(durationSec, maxDurationSec, tuning)
        val most = maxPartCount(durationSec, maxDurationSec, tuning)
        return min(base + extraParts.coerceAtLeast(0), most)
    }

    /**
     * Estimates how [src] would come out as [partsFor] parts: each part's length, the video bitrate
     * it would get on the first attempt (after audio) and the frame height that would be chosen.
     * Uses the same budget, audio and frame size rules as [convert], so the card and the
     * conversion agree.
     */
    fun estimate(src: SourceInfo, settings: Settings, extraParts: Int): PartEstimate {
        val t = settings.tuning
        val parts = partsFor(src.durationSec, settings.maxDurationSec, t, extraParts)
        val partSec = min(src.durationSec / parts, effectiveMaxSec(settings.maxDurationSec, t)).coerceAtLeast(0.1)
        // Best quality aims a little higher than the other modes on every attempt, the first included.
        val aimPct = if (settings.bestQuality) t.bestAimPct else t.firstTargetPct
        val totalKbps = settings.limitBytes * (aimPct / 100.0) * 8.0 / partSec / 1000.0
        val audioOn = settings.keepAudio && src.hasAudio
        val audioKbps = audioKbpsFor(totalKbps, settings.audioKbps, audioOn, t)
        val vKbps = (totalKbps - audioKbps).toInt()
        val height = if (settings.hasCustomSize) {
            customOutputHeight(src, settings.customWidth, settings.customHeight)
        } else {
            chooseHeight(
                vKbps, src, minHeightFor(src, settings.minWidth, settings.minHeight),
                if (settings.fastest) t.fastestBpp else t.normalBpp,
                settings.targetFps, t, !settings.fastest,
            )
        }
        // Bits per pixel of the output: the width follows from the height at the source's shape.
        val aspect = src.width.toDouble() / src.height
        val framePixels = height * (height * aspect)
        val pixelsPerSec = framePixels * effectiveFps(src, settings.targetFps)
        val bpp = if (vKbps > 0 && pixelsPerSec > 0) vKbps * 1000.0 / pixelsPerSec else 0.0
        // Larger frames get more out of each bit, so what the brackets judge is scaled up with frame
        // size; a 240p frame is unchanged, which is where the bracket starts were set.
        val qualityBpp = bpp * (framePixels / REFERENCE_FRAME_PIXELS).pow(t.bracketSizeExponent)
        return PartEstimate(parts, partSec, vKbps, height, audioKbps, bpp, qualityBpp)
    }

    fun cancel() {
        FFmpegKit.cancel()
    }

    fun displayName(ctx: Context, uri: Uri): String {
        // A copy in app storage (see BatchRunner.addShared) has no content provider to ask.
        if (uri.scheme == "file") uri.path?.let { return File(it).name }
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) return c.getString(0) }
        return "video"
    }

    /**
     * Why the last [probe] failed, when it failed because something threw (including serious
     * errors such as the FFmpeg library not loading on this device). null when the last probe
     * worked, or simply found no usable video. Read it straight after [probe] returns null.
     */
    @Volatile
    var lastProbeError: String? = null
        private set

    suspend fun probe(ctx: Context, uri: Uri): SourceInfo? {
        lastProbeError = null
        return try {
            val info = probePath(FFmpegKitConfig.getSafParameterForRead(ctx, uri)) ?: return null
            val bytes = withContext(Dispatchers.IO) { sourceBytes(ctx, uri) }
            info.copy(fileBytes = bytes)
        } catch (e: CancellationException) {
            throw e // keep coroutine cancellation working
        } catch (t: Throwable) {
            // Throwable, not Exception: a library that can't load is an Error, and would otherwise close the app.
            lastProbeError = describeProbeFailure(t)
            null
        }
    }

    /**
     * The technical details of the first time the FFmpeg library failed to load. Once it has
     * failed, Android refuses every later attempt with a bare "NoClassDefFoundError" that no
     * longer says why, so later failures reuse these.
     */
    @Volatile
    private var firstEngineDetail: String? = null

    private fun isEngineFailure(t: Throwable): Boolean =
        t is Error && t !is OutOfMemoryError

    /** [t] and everything that caused it, one per line, e.g. "Error: ...\n  caused by UnsatisfiedLinkError: ...". */
    private fun describeThrowable(t: Throwable): String {
        val lines = ArrayList<String>()
        var current: Throwable? = t
        while (current != null && lines.size < 5) {
            lines.add("${current.javaClass.simpleName}: ${current.message ?: "no message"}")
            val next = current.cause
            current = if (next === current) null else next
        }
        return lines.joinToString("\n  caused by ").take(1200)
    }

    private fun describeProbeFailure(t: Throwable): String {
        if (!isEngineFailure(t)) return "Technical details: ${describeThrowable(t)}"
        val needs64 = "arm64-v8a" !in android.os.Build.SUPPORTED_ABIS
        return "The video engine (FFmpeg) couldn't start on this device." +
            (if (needs64) " WebMChan needs a 64-bit ARM phone." else "") +
            " The About page shows this device's details.\n" +
            "Technical details: ${engineDetail(t)}"
    }

    /** What went wrong with the FFmpeg library, remembering the first (most useful) answer. */
    private fun engineDetail(t: Throwable): String {
        // A bare NoClassDefFoundError (the library already failed once) carries no reason of its own.
        val earlier = firstEngineDetail
        if (t is NoClassDefFoundError && t.cause == null && earlier != null) return earlier
        val detail = describeThrowable(t)
        if (earlier == null) firstEngineDetail = detail
        return detail
    }

    /**
     * null when the FFmpeg library loads on this device (it is loaded now if it hasn't been yet);
     * otherwise the technical details of why it can't. For the About page.
     */
    fun engineProblem(): String? = try {
        FFmpegKitConfig.getVersion()
        null
    } catch (t: Throwable) {
        engineDetail(t)
    }

    /** The size of the video at [uri] in bytes, or 0 if it can't be told. */
    private fun sourceBytes(ctx: Context, uri: Uri): Long = try {
        if (uri.scheme == "file") {
            File(uri.path ?: "").length()
        } else {
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else 0L
            } ?: 0L
        }
    } catch (_: Exception) {
        0L
    }

    /** Same as [probe] but for a plain file, e.g. a finished conversion in the cache. */
    suspend fun probeFile(file: File): SourceInfo? = probePath(file.absolutePath)?.copy(fileBytes = file.length())

    private suspend fun probePath(path: String): SourceInfo? = withContext(Dispatchers.IO) {
        val info = FFprobeKit.getMediaInformation(path).mediaInformation ?: return@withContext null
        val dur = info.duration?.toDoubleOrNull() ?: return@withContext null

        var w = 0
        var h = 0
        var fps = 30.0
        var fpsReadable = false
        var audio = false
        var vCodec = ""
        var vKbps = 0
        var aCodec = ""
        for (s in info.streams) {
            when (s.type) {
                "video" -> if (w == 0) {
                    w = s.width?.toInt() ?: 0
                    h = s.height?.toInt() ?: 0
                    val parsed = parseRate(s.averageFrameRate)
                    fpsReadable = parsed != null
                    fps = parsed ?: 30.0
                    vCodec = s.codec ?: ""
                    vKbps = ((s.bitrate?.toLongOrNull() ?: 0L) / 1000).toInt()
                }
                "audio" -> {
                    audio = true
                    if (aCodec.isEmpty()) aCodec = s.codec ?: ""
                }
            }
        }
        if (w == 0 || h == 0) return@withContext null
        // Many files don't give the video stream's own bitrate; the whole file's is the next best thing.
        if (vKbps <= 0) vKbps = ((info.bitrate?.toLongOrNull() ?: 0L) / 1000).toInt()
        SourceInfo(
            dur, w, h, fps, audio, fpsReadable,
            container = containerName(info.format ?: ""),
            videoCodec = vCodec,
            videoKbps = vKbps,
            audioCodec = aCodec,
        )
    }

    /** ffprobe's format list (e.g. "mov,mp4,m4a,3gp,3g2,mj2") as one readable name. */
    private fun containerName(format: String): String {
        val f = format.lowercase()
        return when {
            f.isBlank() -> ""
            "mp4" in f -> "MP4"
            "matroska" in f || "webm" in f -> "Matroska/WebM"
            "avi" in f -> "AVI"
            "flv" in f -> "FLV"
            "mpegts" in f -> "MPEG-TS"
            "gif" in f -> "GIF"
            "mov" in f -> "MOV"
            else -> f.substringBefore(',').uppercase()
        }
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

    // How the predicted lowest-quantiser size scales with frame size and frame rate (rough rules of
    // thumb: bitrate grows more slowly than pixel count or frame rate).
    private const val FLOOR_PIXEL_EXPONENT = 0.75
    private const val FLOOR_FPS_EXPONENT = 0.7

    /**
     * A guess at the video bitrate the encoder would use at [height] when it can't spend any more
     * bits (its lowest quantiser), worked out from the source's own video bitrate, scaled for the
     * change in frame size and frame rate and multiplied by [Tuning.floorFactor]. null when the
     * source's bitrate or frame rate isn't known, so nothing can be predicted.
     */
    private fun predictedFloorKbps(src: SourceInfo, height: Int, targetFps: Int, t: Tuning): Double? {
        if (src.videoKbps <= 0 || !src.fpsReadable || src.width <= 0 || src.height <= 0 || src.fps <= 0.0) return null
        val aspect = src.width.toDouble() / src.height
        val pixelRatio = (height * height * aspect) / (src.width.toDouble() * src.height)
        val fpsRatio = effectiveFps(src, targetFps) / src.fps
        return src.videoKbps * pixelRatio.pow(FLOOR_PIXEL_EXPONENT) * fpsRatio.pow(FLOOR_FPS_EXPONENT) * t.floorFactor
    }

    /**
     * Biggest height (<=1080, <= source) that still gets a decent bits-per-pixel for VP8,
     * never below [minHeight]. A source smaller than the minimum is scaled up to it. The frame
     * rate used for the bits-per-pixel sum is the output's: the source's, capped at [targetFps].
     *
     * With [predictFloor] on (and [Tuning.floorBudgetPct] above 0), a bigger height wins if its
     * predicted lowest-quantiser size (see [predictedFloorKbps]) is within that % of [vKbps]: an
     * easy clip can't use the whole budget, so a fixed bits-per-pixel rule would shrink it for
     * nothing. It only ever raises the pick, never lowers it.
     */
    private fun chooseHeight(
        vKbps: Int, src: SourceInfo, minHeight: Int, minBpp: Double, targetFps: Int,
        t: Tuning, predictFloor: Boolean,
    ): Int {
        val fps = effectiveFps(src, targetFps)
        val aspect = src.width.toDouble() / src.height
        val ladder = listOf(1080, 720, 480, 360, 240)
        val base = if (src.height <= 1080) {
            listOf(src.height) + ladder.filter { it < src.height }
        } else ladder
        val candidates = base.filter { it >= minHeight }.toMutableList()
        if (minHeight > 0 && (candidates.isEmpty() || candidates.last() > minHeight)) {
            candidates.add(minHeight)
        }
        var pick = candidates.last() / 2 * 2
        for (h in candidates) {
            val w = h * aspect
            val bpp = vKbps * 1000.0 / (w * h * fps)
            if (bpp >= minBpp) {
                pick = h / 2 * 2
                break
            }
        }
        if (predictFloor && t.floorBudgetPct > 0.0) {
            val room = vKbps * t.floorBudgetPct / 100.0
            for (h in candidates) {
                if (h / 2 * 2 <= pick) break // nothing bigger left to offer
                val floor = predictedFloorKbps(src, h, targetFps, t) ?: break
                if (floor <= room) {
                    pick = h / 2 * 2
                    break
                }
            }
        }
        return pick
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
            StageResult.Fail("Could not copy the selected video: ${e.message}")
        }
    }

    /**
     * Deletes working files left in the cache: staged source copies, the encoder's output and
     * the pass-1 logs. Safe between videos; also run once when the app starts, to clear what a
     * killed run left behind.
     */
    fun clearWorkFiles(ctx: Context) {
        ctx.cacheDir.listFiles()?.filter {
            it.name.startsWith("webmchan-input-") || it.name == "out.webm" || it.name == BEST_FILE_NAME ||
                it.name == "audioprobe.webm" || it.name.startsWith("ffpass")
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

            // The frame rate cap. The fps filter is only added when the source is faster than it
            // (with half a frame of leeway, so 29.97 and 59.94 sources aren't touched by a 30 or
            // 60 cap). A slower source is never sped up.
            val fpsCap = clampFps(settings.targetFps)
            val reduceFps = src.fps > fpsCap + 0.5

            val out = File(ctx.cacheDir, "out.webm")
            val passLog = File(ctx.cacheDir, "ffpass").absolutePath

            // BEST QUALITY. Instead of stopping at the first file that fits, keep encoding with a
            // corrected bitrate until a file lands between [Tuning.bestMinFillPct] and 100% of the
            // limit, or the attempts run out, or the encoder shows it can't use more bits. Only two
            // encodes ever exist at once: the attempt being made (out.webm) and the fullest one so far
            // that fits (out-best.webm). An attempt that is over the limit, or that fits but is
            // emptier than the one kept, is deleted as soon as it is measured.
            val best = settings.bestQuality
            val maxAttempts = if (best) t.bestMaxAttempts else t.maxAttempts
            val bestFile = File(ctx.cacheDir, BEST_FILE_NAME)
            var bestSize = 0L
            var bestHeight = 0
            var bestVKbps = 0
            // The frame height is picked once, on the first attempt, and kept: a bitrate that moves
            // the pick across a size boundary would make the attempts incomparable.
            var lockedHeight = 0
            val tries = ArrayList<BestTry>()

            /** Puts the kept result back as out.webm (where the caller expects it) and reports it. */
            fun finishBest(attemptsUsed: Int): ConvertResult {
                out.delete()
                if (!bestFile.renameTo(out)) {
                    bestFile.copyTo(out, overwrite = true)
                    bestFile.delete()
                }
                cleanupLogs(ctx)
                onProgress(1f, "Done")
                return ConvertResult.Ok(
                    out, bestSize, bestHeight, bestVKbps, trimmed, settings.keepAudio && !src.hasAudio, audioOn,
                    attempts = attemptsUsed,
                )
            }

            var budgetBytes = settings.limitBytes * ((if (best) t.bestAimPct else t.firstTargetPct) / 100.0)
            var lastHeight = 0
            var lastKbps = 0
            val hasMin = settings.minWidth > 0 || settings.minHeight > 0
            val minHeight = minHeightFor(src, settings.minWidth, settings.minHeight)
            // Encoder effort, not CPU cores: higher = faster but slightly lower quality at the
            // same bitrate. The same value is used for both passes.
            val cpuUsed =
                if (best) t.bestCpuUsed.toString()
                else if (settings.fastest) "5" else if (settings.fastEncode) "4" else "2"
            val lagFrames = if (settings.fastest) "8" else "25"
            val effortName =
                if (best) "Best quality"
                else if (settings.fastest) "Fastest" else if (settings.fastEncode) "Speed" else "Quality"
            // Best quality only: hidden alt-ref frames and their noise-reduction settings, added to the
            // encoder's arguments below. Empty in every other mode, which are encoded exactly as before.
            val bestArgs = if (best) vpxBestArgs(settings.useVp9, t, onLog) else emptyList()

            // The audio bitrate chosen in the Sound settings. See [audioKbpsFor] for how far it may drop.
            val normalAudioKbps = settings.audioKbps

            // Carried from one attempt to the next, for the audio analysis (see below).
            var lastSize = 0L
            var lastVideoKbps = 0
            var lastAudioKbps = 0
            // Size of this clip's audio on its own at the normal audio bitrate: -1 = not measured
            // yet, 0 = tried and couldn't.
            var audioBytes = -1L
            // The sample rate each audio bitrate needs (see findAudioRate), looked up once per clip.
            // The default bitrate and above are known to work at the source's rate.
            val audioRates = HashMap<Int, Int>()
            fun audioRateFor(kbps: Int): Int =
                if (kbps >= DEFAULT_AUDIO_KBPS) 0
                else audioRates.getOrPut(kbps) { findAudioRate(input, startSec, kbps, onLog) }

            // What Vorbis really produces is off the nominal figure at every setting (about 22 kbps for
            // a 16 kbps setting, about 30 for 32, about 56 for 64), and at the first attempt that error
            // comes straight out of the video's share of the size limit. So measure the audio on its
            // own first (a quick audio-only encode) and plan the video around its real size. An attempt
            // where the audio had to be lowered to fit goes by the nominal figure for that lower rate.
            if (audioOn) {
                onProgress(-1f, "Checking how big the audio will be…")
                val measureHz = audioRateFor(normalAudioKbps)
                if (measureHz == AUDIO_RATE_CANCELLED) return@withContext ConvertResult.Cancelled
                if (measureHz >= 0) {
                    audioBytes = measureAudioBytes(input, startSec, duration, normalAudioKbps, measureHz, File(ctx.cacheDir, "audioprobe.webm"))
                    if (audioBytes == MEASURE_CANCELLED) return@withContext ConvertResult.Cancelled
                    if (audioBytes > 0) {
                        onLog(
                            "   audio at ${audioLabel(normalAudioKbps)} comes out at %.1f kbps for this clip; planning the video around that".format(
                                java.util.Locale.US, audioBytes * 8.0 / duration / 1000.0
                            )
                        )
                    } else {
                        audioBytes = 0L
                        onLog("   couldn't measure the audio; planning with its nominal bitrate")
                    }
                }
            }

            for (attempt in 1..maxAttempts) {
                val totalKbps = budgetBytes * 8.0 / duration / 1000.0
                // Audio normally gets its full bitrate, but on a tight budget that alone can push
                // video under the minimum and fail a clip that would otherwise fit fine. Let audio
                // flex down (as low as the minimum audio bitrate) before giving up on the attempt.
                var audioKbps = audioKbpsFor(totalKbps, normalAudioKbps, audioOn, t)
                // The audio's real cost where it has been measured (and isn't being lowered to fit),
                // else its nominal bitrate.
                val plannedAudioKbps =
                    if (audioOn && audioBytes > 0 && audioKbps == normalAudioKbps) audioBytes * 8.0 / duration / 1000.0
                    else audioKbps.toDouble()
                var vKbps = (totalKbps - plannedAudioKbps).toInt()
                // What the audio is expected to take of the file (0 with no audio).
                val plannedAudioBytes = if (audioOn) plannedAudioKbps * 125.0 * duration else 0.0

                // RETRY PLANNING WITH THE REAL AUDIO. After an attempt that went over, the usual retry
                // scales the whole file, which can't tell a video overshoot from an audio one. The
                // audio's real size is known (measured before the first attempt), so work out how the
                // video encoder really responded to its bitrate and give the video exactly what's left
                // of the limit. Only when the earlier attempt used the normal audio bitrate, so that
                // the measurement matches the audio inside its file. (Best quality plans its own
                // retries, below, from every attempt so far.)
                if (!best && audioOn && attempt >= 2 && audioBytes > 0 &&
                    lastSize > 0 && lastVideoKbps > 0 && lastAudioKbps == normalAudioKbps
                ) {
                    val videoObserved = lastSize - audioBytes
                    val videoAsked = lastVideoKbps * 1000.0 / 8.0 * duration
                    if (videoObserved > 0 && videoAsked > 0) {
                        val response = videoObserved / videoAsked // how much video it actually produced per bit asked for
                        val wanted = settings.limitBytes * (t.retryMarginPct / 100.0) - audioBytes
                        val analysedKbps = (wanted / response * 8.0 / duration / 1000.0).toInt()
                        if (analysedKbps >= t.minVideoKbps) {
                            onLog("   video came out at %.0f%% of its bitrate target; now %d kbps video".format(java.util.Locale.US, response * 100, analysedKbps))
                            audioKbps = normalAudioKbps
                            vKbps = analysedKbps
                        }
                    }
                }

                // BEST QUALITY RETRY PLANNING. Aim the video at what is left of the target once the audio
                // has its share, using the attempts so far: between two attempts either side of the
                // target it interpolates, otherwise it scales from the closest one. See
                // [planBestVideoKbps]. null = every bitrate worth trying has been tried.
                if (best && attempt >= 2) {
                    val wantedVideoBytes = settings.limitBytes * (t.bestAimPct / 100.0) - plannedAudioBytes
                    val next = planBestVideoKbps(tries, wantedVideoBytes, duration)
                    if (next == null) {
                        onLog("   no untried video bitrate is left between the attempts so far")
                        if (bestSize > 0L) return@withContext finishBest(attempt - 1)
                        return@withContext ConvertResult.Fail(
                            "Couldn't get under the size limit after ${attempt - 1} attempts. " +
                                "You've probably changed something in Advanced Settings. If not, please copy " +
                                "the log and report this edge case on Github. In the meantime, try splitting."
                        )
                    }
                    onLog("   next video bitrate: $next kbps (aiming for %.1f%% of the limit)".format(java.util.Locale.US, t.bestAimPct))
                    vKbps = next
                }

                if (vKbps < t.minVideoKbps) {
                    if (best && bestSize > 0L) {
                        onLog("   the next bitrate would be below the lowest video bitrate; keeping the best result so far")
                        return@withContext finishBest(attempt - 1)
                    }
                    return@withContext ConvertResult.Fail(
                        "Video is too long for this size limit. Try setting it to split."
                    )
                }
                if (audioOn && audioKbps < normalAudioKbps) {
                    onLog("   audio bitrate reduced to ${audioKbps} kbps to fit the limit")
                }
                // Checked before pass 1, so a bitrate the audio encoder won't take doesn't cost a pass.
                val audioHz = if (audioOn) audioRateFor(audioKbps) else 0
                if (audioHz == AUDIO_RATE_CANCELLED) return@withContext ConvertResult.Cancelled
                if (audioHz == AUDIO_RATE_NONE) {
                    return@withContext ConvertResult.Fail(
                        "The audio encoder won't accept ${audioLabel(audioKbps)} for this video. " +
                            "Choose a higher sound quality, or remove the audio."
                    )
                }
                // A Custom resolution is used exactly as asked (the automatic pick is bypassed); the
                // size limit then only steers the bitrate.
                val height = if (best && lockedHeight > 0) {
                    lockedHeight
                } else if (settings.hasCustomSize) {
                    customOutputHeight(src, settings.customWidth, settings.customHeight)
                } else {
                    chooseHeight(
                        vKbps, src, minHeight, if (settings.fastest) t.fastestBpp else t.normalBpp,
                        settings.targetFps, t, !settings.fastest,
                    )
                }
                if (best && lockedHeight == 0) lockedHeight = height
                lastHeight = height
                lastKbps = vKbps

                val filters = buildList {
                    if (reduceFps) add("fps=$fpsCap")
                    if (settings.hasCustomSize) {
                        add(customScaleFilter(settings.customWidth, settings.customHeight))
                    } else if (hasMin) {
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

                val common = listOf("-hide_banner", "-y") +
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
                } else emptyList()) + bestArgs + listOf(
                    "-passlogfile", passLog,
                )

                // Pass 2 only: libvpx's analysis pass (pass 1) reports no frames or time.
                fun pass2Stats() {
                    FFmpegKitConfig.enableStatisticsCallback { st ->
                        val frac = (st.time.toDouble() / 1000.0 / duration).coerceIn(0.0, 1.0)
                        onProgress(
                            frac.toFloat(),
                            "Attempt $attempt/$maxAttempts, pass 2/2: encoding (${height}p, ${vKbps} kbps)"
                        )
                    }
                }

                onLog(
                    "== Attempt $attempt/$maxAttempts: ${height}p, $vKbps kbps video" +
                        (if (audioOn) " + ${audioLabel(audioKbps)} audio" else ", no audio") +
                        (if (startSec > 0.0) " (from %.1fs)".format(java.util.Locale.US, startSec) else "")
                )
                onLog("   filters: $filters")
                onLog(
                    (if (src.fpsReadable) "   frame rate: source %.2f fps, cap $fpsCap fps, ".format(java.util.Locale.US, src.fps)
                    else "   frame rate: source fps unreadable (treated as ${src.fps.toInt()} fps for planning), cap $fpsCap fps, ") +
                        if (reduceFps) "reduced to $fpsCap fps" else "kept at the source rate"
                )
                onLog("   codec: ${if (settings.useVp9) "VP9 (libvpx-vp9)" else "VP8 (libvpx)"}")
                onLog("   encoder effort: $effortName (cpu-used $cpuUsed)")
                if (best) {
                    onLog("   best quality: ${bestArgs.joinToString(" ")}")
                    onLog(
                        "   best quality: up to $maxAttempts attempts, aiming for %.1f%% of the limit and accepting %.1f%% to 100%%; frame height kept at ${height}p for every attempt".format(
                            java.util.Locale.US, t.bestAimPct, t.bestMinFillPct
                        )
                    )
                }
                onLog("   rate control: 2-pass, average target $vKbps kbps for the video, no minimum or maximum rate and no quantiser limits set (libvpx defaults)")
                if (!settings.hasCustomSize) {
                    predictedFloorKbps(src, height, settings.targetFps, t)?.let { floor ->
                        onLog(
                            "   predicted size at the encoder's best quality for ${height}p: about %.0f kbps video (%.0f%% of the video target)".format(
                                java.util.Locale.US, floor, floor / vKbps * 100.0
                            )
                        )
                    }
                }
                onLog("-- pass 1/2: analysis only. ffmpeg usually reports no progress here.")
                FFmpegKitConfig.enableStatisticsCallback { }
                onProgress(-1f, "Attempt $attempt/$maxAttempts, pass 1/2: analysing (${height}p, ${vKbps} kbps)")
                val p1 = FFmpegKit.executeWithArguments(
                    (common + listOf("-pass", "1", "-an", "-f", "null", "/dev/null")).toTypedArray()
                )
                if (ReturnCode.isCancel(p1.returnCode)) return@withContext ConvertResult.Cancelled
                if (!ReturnCode.isSuccess(p1.returnCode)) {
                    // A failed attempt shouldn't throw away a good result from an earlier one.
                    if (best && bestSize > 0L) {
                        onLog("   pass 1 failed; keeping the best result so far")
                        return@withContext finishBest(attempt)
                    }
                    return@withContext ConvertResult.Fail("Encoding failed (pass 1).\n${tail(p1.allLogsAsString)}")
                }

                onLog("-- pass 2/2: encoding")
                onProgress(0f, "Attempt $attempt/$maxAttempts, pass 2/2: encoding (${height}p, ${vKbps} kbps)")
                pass2Stats()
                val audioArgs = if (audioOn) {
                    listOf("-map", "0:a:0", "-c:a", "libvorbis", "-b:a", "${audioKbps}k", "-ac", audioChannels(audioKbps).toString()) +
                        (if (audioHz > 0) listOf("-ar", audioHz.toString()) else emptyList())
                } else listOf("-an")
                val p2 = FFmpegKit.executeWithArguments(
                    (common + listOf("-pass", "2") + audioArgs + listOf("-f", "webm", out.absolutePath)).toTypedArray()
                )
                if (ReturnCode.isCancel(p2.returnCode)) return@withContext ConvertResult.Cancelled
                if (!ReturnCode.isSuccess(p2.returnCode)) {
                    if (best && bestSize > 0L) {
                        onLog("   pass 2 failed; keeping the best result so far")
                        out.delete()
                        return@withContext finishBest(attempt)
                    }
                    return@withContext ConvertResult.Fail("Encoding failed (pass 2).\n${tail(p2.allLogsAsString)}")
                }

                val size = out.length()
                lastSize = size
                lastVideoKbps = vKbps
                lastAudioKbps = audioKbps
                onLog("== Attempt $attempt result: $size bytes (limit ${settings.limitBytes})")
                // Asked for against delivered, to show whether the encoder is undershooting its target
                // (and, if so, whether it has run out of room to spend bits: see the q value).
                run {
                    val askedAudioKbps =
                        if (audioOn && audioBytes > 0 && audioKbps == normalAudioKbps) audioBytes * 8.0 / duration / 1000.0
                        else audioKbps.toDouble()
                    val askedKbps = vKbps + askedAudioKbps
                    val gotKbps = size * 8.0 / duration / 1000.0
                    val qEnd = Regex("""\bq=(-?\d+(?:\.\d+)?)""").findAll(p2.allLogsAsString.orEmpty())
                        .lastOrNull()?.groupValues?.get(1)
                    onLog(
                        "   asked for $vKbps kbps video + " +
                            "%.1f kbps audio (%.0f kbps in all); the file came out at %.0f kbps in all, %.0f%% of what was asked and %.0f%% of the size limit".format(
                                java.util.Locale.US, askedAudioKbps, askedKbps, gotKbps, gotKbps / askedKbps * 100.0,
                                size * 100.0 / settings.limitBytes
                            ) + (qEnd?.let { "; encoder q at the end: $it" } ?: "")
                    )
                    if (gotKbps < askedKbps * 0.85 && (qEnd?.toDoubleOrNull() ?: 99.0) <= 4.5) {
                        onLog("   note: well under the target with q at 4 (libvpx's default lowest), so the encoder likely couldn't spend more bits on this clip")
                        if (!settings.hasCustomSize) {
                            predictedFloorKbps(src, height, settings.targetFps, t)?.let { floor ->
                                onLog(
                                    "   the prediction for ${height}p was about %.0f kbps video; the video actually came out at about %.0f kbps".format(
                                        java.util.Locale.US, floor, (gotKbps - askedAudioKbps).coerceAtLeast(0.0)
                                    )
                                )
                            }
                        }
                    }
                }
                if (size <= 0L) {
                    if (best && bestSize > 0L) {
                        onLog("   the attempt produced an empty file; keeping the best result so far")
                        return@withContext finishBest(attempt)
                    }
                    return@withContext ConvertResult.Fail("Encoding produced an empty file.")
                }

                if (best) {
                    // What this attempt's video came to once the audio is taken off, for planning the next.
                    tries.add(BestTry(vKbps, (size - plannedAudioBytes).coerceAtLeast(1.0), size))
                    val fits = size <= settings.limitBytes
                    val pctOfLimit = size * 100.0 / settings.limitBytes
                    val pctText = "%.2f%%".format(java.util.Locale.US, pctOfLimit)

                    // Keep the fullest file that fits; delete the other one straight away.
                    if (fits && size > bestSize) {
                        bestFile.delete()
                        if (!out.renameTo(bestFile)) {
                            out.copyTo(bestFile, overwrite = true)
                            out.delete()
                        }
                        bestSize = size
                        bestHeight = height
                        bestVKbps = vKbps
                        onLog("   fits, at $pctText of the limit: kept as the best so far")
                    } else if (fits) {
                        out.delete()
                        onLog("   fits, at $pctText of the limit, but the one kept is fuller: this one is deleted")
                    } else {
                        out.delete()
                        onLog("   over the limit, at $pctText: deleted")
                    }

                    if (fits && pctOfLimit >= t.bestMinFillPct) {
                        onLog(
                            "== Done: %.2f%% of the limit, inside the accepted %.1f%% to 100%%".format(
                                java.util.Locale.US, pctOfLimit, t.bestMinFillPct
                            )
                        )
                        return@withContext finishBest(attempt)
                    }

                    // Under the accepted range. More bitrate only helps if the encoder can use it.
                    if (fits) {
                        val gotKbps = size * 8.0 / duration / 1000.0
                        val askedKbps = vKbps + plannedAudioKbps
                        val qEnd = Regex("""\bq=(-?\d+(?:\.\d+)?)""").findAll(p2.allLogsAsString.orEmpty())
                            .lastOrNull()?.groupValues?.get(1)?.toDoubleOrNull()
                        val atFloor = gotKbps < askedKbps * 0.85 && (qEnd ?: 99.0) <= 4.5
                        // Stalled: this attempt asked for clearly more bitrate than the one before it (3% or
                        // more) but the video grew by less than a quarter of that. The encoder is already
                        // spending all it can, so a further retry would only repeat the result.
                        val prev = tries.getOrNull(tries.size - 2)
                        val askedGrowth = if (prev != null) vKbps.toDouble() / prev.vKbps - 1.0 else 0.0
                        val gotGrowth = if (prev != null) tries.last().videoBytes / prev.videoBytes - 1.0 else 0.0
                        val stalled = prev != null && askedGrowth >= 0.03 && gotGrowth < 0.25 * askedGrowth
                        if (atFloor) {
                            onLog("== Done: the encoder is at its lowest quantiser, so this clip can't use more bits. The best result is $pctText of the limit.")
                            return@withContext finishBest(attempt)
                        }
                        if (stalled) {
                            onLog("== Done: asking for more bitrate no longer makes the file bigger. The best result is %.2f%% of the limit.".format(java.util.Locale.US, bestSize * 100.0 / settings.limitBytes))
                            return@withContext finishBest(attempt)
                        }
                    }

                    if (attempt >= maxAttempts) {
                        if (bestSize > 0L) {
                            onLog("== Done: out of attempts. The best result is %.2f%% of the limit.".format(java.util.Locale.US, bestSize * 100.0 / settings.limitBytes))
                            return@withContext finishBest(attempt)
                        }
                        // Nothing fitted: falls out of the loop to the usual failure message.
                    } else {
                        onLog(if (fits) "   under the accepted range, retrying with a higher bitrate" else "   over the limit, retrying with a smaller bitrate")
                    }
                    continue
                }

                if (size in 1..settings.limitBytes) {
                    onProgress(1f, "Done")
                    cleanupLogs(ctx)
                    return@withContext ConvertResult.Ok(
                        out, size, lastHeight, lastKbps, trimmed, settings.keepAudio && !src.hasAudio, audioOn,
                        attempts = attempt,
                    )
                }
                // Too big: shrink the budget proportionally and retry.
                onLog("   over the limit, retrying with a smaller bitrate")
                budgetBytes *= (settings.limitBytes * (t.retryMarginPct / 100.0)) / size.toDouble()
            }
            cleanupLogs(ctx)
            ConvertResult.Fail(
                "Couldn't get under the size limit after $maxAttempts attempts. " +
                    "You've probably changed something in Advanced Settings. If not, please copy " +
                    "the log and report this edge case on Github. In the meantime, try splitting."
            )
        } finally {
            // Detach so later ffprobe/ffmpeg calls don't write into a finished run's log.
            FFmpegKitConfig.enableLogCallback { }
            FFmpegKitConfig.enableStatisticsCallback { }
        }
    }

    // ---- Best quality ----

    /** The fullest result of a Best quality conversion so far is kept under this name (out.webm is the attempt in progress). */
    private const val BEST_FILE_NAME = "out-best.webm"

    /**
     * The video bitrate for the next Best quality attempt, in kbps, or null when no untried bitrate is
     * left that could be worth encoding. [tries] is every attempt so far; [wantedVideoBytes] is the
     * target size less the audio's share.
     *
     * Between an attempt that came out under the target and one that came out over it, the bitrate is
     * interpolated (file size isn't proportional to the bitrate, so two points beat one) and kept
     * strictly inside the two. With attempts on one side only, it scales from the closest one.
     */
    private fun planBestVideoKbps(tries: List<BestTry>, wantedVideoBytes: Double, duration: Double): Int? {
        if (tries.isEmpty() || wantedVideoBytes <= 0.0 || duration <= 0.0) return null
        val below = tries.filter { it.videoBytes <= wantedVideoBytes }.maxByOrNull { it.videoBytes }
        val above = tries.filter { it.videoBytes > wantedVideoBytes }.minByOrNull { it.videoBytes }
        val tried = tries.map { it.vKbps }.toSet()
        val bytesPerKbps = 125.0 * duration // one kbps, held for the clip's length

        if (below != null && above != null) {
            val lo = minOf(below.vKbps, above.vKbps)
            val hi = maxOf(below.vKbps, above.vKbps)
            if (hi - lo < 2) return null // no whole kbps left strictly between them
            val dBytes = above.videoBytes - below.videoBytes
            val dKbps = (above.vKbps - below.vKbps).toDouble()
            val raw = if (dBytes > 0.0 && dKbps > 0.0) {
                below.vKbps + (wantedVideoBytes - below.videoBytes) / dBytes * dKbps
            } else {
                (below.vKbps + above.vKbps) / 2.0
            }
            val k = raw.toInt().coerceIn(lo + 1, hi - 1)
            return if (k in tried) null else k
        }

        val base = below ?: above ?: return null
        val response = base.videoBytes / (base.vKbps * bytesPerKbps) // video bytes out per byte asked for
        if (response <= 0.0) return null
        var k = (wantedVideoBytes / (response * bytesPerKbps)).toInt()
        if (k in tried) k += if (below != null) 1 else -1 // an exact repeat would give the same file
        return if (k in tried || k < 1) null else k
    }

    // What this ffmpeg build says its libvpx encoders accept, from `-h encoder=...`, looked up once each.
    private val encoderOptionCache = java.util.concurrent.ConcurrentHashMap<String, Set<String>>()

    private fun encoderOptionNames(encoder: String, onLog: (String) -> Unit): Set<String> {
        encoderOptionCache[encoder]?.let { return it }
        // The help text is long and of no use in the conversion log, so the log is muted while it is read.
        FFmpegKitConfig.enableLogCallback { }
        val names = try {
            val session = FFmpegKit.executeWithArguments(arrayOf("-hide_banner", "-h", "encoder=$encoder"))
            Regex("""(?m)^\s+-([A-Za-z0-9_-]+)\s""").findAll(session.allLogsAsString.orEmpty())
                .map { it.groupValues[1] }.toSet()
        } catch (_: Exception) {
            emptySet()
        } finally {
            FFmpegKitConfig.enableLogCallback { log -> onLog(log.message) }
        }
        if (names.isNotEmpty()) encoderOptionCache[encoder] = names // an empty answer is a failed look-up: try again next time
        return names
    }

    /**
     * The encoder arguments only Best quality adds: hidden alt-ref frames (the encoder looks ahead, builds
     * a cleaned-up reference frame from several neighbours, codes it without showing it, and the frames
     * around it then need fewer bits), with the settings for how many frames are blended and how strongly.
     * Each option other than auto-alt-ref is only passed if this ffmpeg build lists it, so a build that
     * names one differently or lacks it can't make the whole encode fail.
     */
    private fun vpxBestArgs(vp9: Boolean, t: Tuning, onLog: (String) -> Unit): List<String> {
        val known = encoderOptionNames(if (vp9) "libvpx-vp9" else "libvpx", onLog)
        fun firstKnown(vararg names: String): String? = names.firstOrNull { it in known }
        val args = arrayListOf("-auto-alt-ref", "1")
        firstKnown("arnr-maxframes", "arnr-max-frames")?.let { args += listOf("-$it", t.bestArnrMaxFrames.toString()) }
        firstKnown("arnr-strength")?.let { args += listOf("-$it", t.bestArnrStrength.toString()) }
        // The blend type only exists for VP8: 3 = centred on the alt-ref frame (the usual choice).
        if (!vp9) firstKnown("arnr-type")?.let { args += listOf("-$it", "3") }
        // VP9's temporal dependency model, which decides where the bits are best spent.
        if (vp9) firstKnown("enable-tpl")?.let { args += listOf("-$it", "1") }
        if (known.isEmpty()) onLog("   couldn't read this encoder's option list; using auto-alt-ref only")
        return args
    }

    private const val MEASURE_CANCELLED = -1L

    /**
     * libvorbis refuses some combinations of bitrate, channels and sample rate ("encoder setup
     * failed"). Low bitrates hit that at a typical source's 44.1 or 48 kHz, where the encoder has
     * no setting low enough. Lowering the sample rate makes lower bitrates possible. This tries a
     * short audio-only encode at the source's own rate first, then at lower and lower rates, and
     * returns the first that works: 0 = the source's rate, otherwise the rate in Hz, or
     * [AUDIO_RATE_NONE] / [AUDIO_RATE_CANCELLED]. Only worth asking for bitrates under the default.
     */
    private fun findAudioRate(input: File, startSec: Double, kbps: Int, onLog: (String) -> Unit): Int {
        onLog("   checking that the audio encoder accepts ${audioLabel(kbps)}")
        for (hz in AUDIO_RATE_CANDIDATES) {
            val r = FFmpegKit.executeWithArguments(
                (listOf("-hide_banner", "-y") + seekArgs(startSec) + listOf(
                    "-i", input.absolutePath, "-t", "0.5",
                    "-map", "0:a:0", "-vn", "-sn", "-dn", "-map_metadata", "-1",
                    "-c:a", "libvorbis", "-b:a", "${kbps}k", "-ac", audioChannels(kbps).toString(),
                ) + (if (hz > 0) listOf("-ar", hz.toString()) else emptyList()) +
                    listOf("-f", "null", "/dev/null")).toTypedArray()
            )
            if (ReturnCode.isCancel(r.returnCode)) return AUDIO_RATE_CANCELLED
            if (ReturnCode.isSuccess(r.returnCode)) {
                onLog(
                    if (hz == 0) "   audio: ${audioLabel(kbps)} works at the source's sample rate"
                    else "   audio: ${audioLabel(kbps)} needs a lower sample rate; using $hz Hz"
                )
                return hz
            }
        }
        return AUDIO_RATE_NONE
    }

    private const val AUDIO_RATE_NONE = -1
    private const val AUDIO_RATE_CANCELLED = -2

    /** Sample rates [findAudioRate] tries, in order (0 = leave as the source has it). */
    private val AUDIO_RATE_CANDIDATES = listOf(0, 32000, 24000, 22050, 16000, 12000, 11025, 8000)

    /**
     * Encodes just this clip's audio at [kbps] into [probeOut] and returns its size in bytes, to
     * learn what the audio really costs (Vorbis often comes out under its nominal bitrate).
     * 0 = couldn't be measured; [MEASURE_CANCELLED] = the user cancelled. Deletes the file.
     */
    private fun measureAudioBytes(input: File, startSec: Double, duration: Double, kbps: Int, hz: Int, probeOut: File): Long {
        val r = FFmpegKit.executeWithArguments(
            (listOf("-hide_banner", "-y") + seekArgs(startSec) + listOf(
                "-i", input.absolutePath, "-t", "%.3f".format(java.util.Locale.US, duration),
                "-map", "0:a:0", "-vn", "-sn", "-dn", "-map_metadata", "-1",
                "-c:a", "libvorbis", "-b:a", "${kbps}k", "-ac", audioChannels(kbps).toString(),
            ) + (if (hz > 0) listOf("-ar", hz.toString()) else emptyList()) +
                listOf("-f", "webm", probeOut.absolutePath)).toTypedArray()
        )
        val bytes = when {
            ReturnCode.isCancel(r.returnCode) -> MEASURE_CANCELLED
            ReturnCode.isSuccess(r.returnCode) -> {
                // Count the audio's own bytes, not the test file: that has a container of its own,
                // roughly a tenth on top, whereas in the finished video the container is shared with
                // the picture and costs very little. ffmpeg's closing line gives the audio's size
                // ("audio:333kB", in units of 1024 bytes); the file size is only the fallback.
                val kib = Regex("""audio:(\d+)kB""").findAll(r.allLogsAsString.orEmpty())
                    .lastOrNull()?.groupValues?.get(1)?.toLongOrNull()
                if (kib != null && kib > 0) kib * 1024 else probeOut.length()
            }
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
            val os = resolver.openOutputStream(uri)
            if (os == null) {
                resolver.delete(uri, null, null)
                return null
            }
            os.use { file.inputStream().use { input -> input.copyTo(it) } }
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