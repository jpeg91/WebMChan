// #audiomode
package app.webmchan

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.media.ExifInterface
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegKitConfig
import com.arthenica.ffmpegkit.FFprobeKit
import com.arthenica.ffmpegkit.ReturnCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/*
 * Audio Mode: an audio file plus a still picture (or several), made into a webm. The card is an
 * ordinary [QueueItem] with [QueueItem.audio] set; it goes through the same Holding Pen, queue,
 * Converting and Finished flow as a video, in [BatchRunner]. This file holds what is particular to it:
 * the card's extra data, the pictures kept in app storage, the sums for how much of the size limit
 * the sound can have, and the encoder.
 */

/** One picture on an Audio Mode card: a copy in app storage (see [AudioArt]), so it outlives the picker's access. */
data class AudioImage(val path: String, val w: Int, val h: Int)

/** What makes a card an Audio Mode card. The audio file itself is the card's [QueueItem.uri]. */
data class AudioSpec(
    /** Shown across the video, each for an equal share of its length. Empty = no picture chosen (a black one is used). */
    val images: List<AudioImage> = emptyList(),
    /** True while the picture is the audio file's own cover art (so the card can say so). */
    val coverFromTags: Boolean = false,
    /** From the file's tags; empty when it has none. */
    val title: String = "",
    val artist: String = "",
)

/** What reading an audio file found. */
data class AudioProbe(val info: SourceInfo, val title: String, val artist: String)

/**
 * The sums behind Audio Mode. Plain Kotlin with nothing from Android in it, so they can be checked by
 * unit tests without a phone.
 */
object AudioFit {
    /** The most Vorbis can usefully be given: more only makes the file bigger. */
    const val MAX_KBPS = 320

    /** Below this a part isn't worth making (the card asks for more parts instead). */
    const val MIN_KBPS = 24

    /** Below this the card warns that the sound will lose quality. */
    const val WARN_KBPS = 128

    /** The longest side of the picture, in pixels (a board's minimum resolution can push it higher). */
    const val MAX_EDGE = 720

    /** How much of the size limit the first attempt aims for; the rest is slack for the encoder's error. */
    private const val SAFETY = 0.97

    /** What a keyframe of the picture costs, per pixel (a still picture is one keyframe plus almost nothing). */
    private const val KEY_BYTES_PER_PIXEL = 0.08

    /** The picture's keyframe is repeated about this often, so a player can seek. */
    const val KEYFRAME_SEC = 60

    /** The video's frame size: the largest picture sets it (long side capped at [MAX_EDGE]), even numbers, a board's minimum honoured. */
    fun frameSize(images: List<AudioImage>, minW: Int, minH: Int): Pair<Int, Int> {
        val big = images.filter { it.w > 0 && it.h > 0 }.maxByOrNull { it.w.toLong() * it.h } ?: return MAX_EDGE to MAX_EDGE
        val w = big.w.toDouble()
        val h = big.h.toDouble()
        var scale = if (max(w, h) > MAX_EDGE) MAX_EDGE / max(w, h) else 1.0
        // A board's minimum resolution wins over the cap.
        if (minW > 0 && w * scale < minW) scale = max(scale, minW / w)
        if (minH > 0 && h * scale < minH) scale = max(scale, minH / h)
        return even(w * scale) to even(h * scale)
    }

    private fun even(x: Double): Int = max(2, (x / 2.0).roundToInt() * 2)

    /** What the picture(s) take of the file, in bytes, for a part [partSec] long. */
    fun videoBytes(w: Int, h: Int, imageCount: Int, partSec: Double): Double =
        w.toDouble() * h * KEY_BYTES_PER_PIXEL * (max(1, imageCount) + partSec / KEYFRAME_SEC) + 3000.0

    /** The audio bitrate (kbps, whole) the rest of the size limit allows for a part [partSec] long, never above [MAX_KBPS]. Can be zero or negative when nothing fits. */
    fun audioKbps(limitBytes: Long, partSec: Double, videoBytes: Double): Int {
        val audioBytes = limitBytes * SAFETY - videoBytes
        val kbps = audioBytes * 8.0 / max(0.1, partSec) / 1000.0
        return min(MAX_KBPS.toDouble(), floor(kbps)).toInt()
    }

    /** Mono below 48 kbps, else stereo. */
    fun channels(kbps: Int): Int = if (kbps < 48) 1 else 2

    /** The sample rate to give the encoder, or 0 to keep the source's: lower bitrates sound better at lower rates. */
    fun sampleRate(kbps: Int): Int = when {
        kbps >= 96 -> 0
        kbps >= 64 -> 44100
        kbps >= 32 -> 32000
        else -> 22050
    }

    /**
     * What a card shows before it is converted: the parts it would be split into, how long each is,
     * the picture's height and the audio bitrate each part would get ([PartEstimate.audioKbps]).
     */
    fun estimate(info: SourceInfo, settings: Settings, extraParts: Int, spec: AudioSpec?): PartEstimate {
        val t = settings.tuning
        val parts = Converter.partsFor(info.durationSec, settings.maxDurationSec, t, extraParts)
        val partSec = min(info.durationSec / parts, Converter.effectiveMaxSec(settings.maxDurationSec, t)).coerceAtLeast(0.1)
        val images = spec?.images.orEmpty()
        val (w, h) = frameSize(images, settings.minWidth, settings.minHeight)
        val kbps = audioKbps(settings.limitBytes, partSec, videoBytes(w, h, images.size, partSec))
        return PartEstimate(parts, partSec, 0, h, kbps, 0.0, 0.0)
    }
}

/** The pictures of Audio Mode cards, kept as JPEG files in app storage. */
object AudioArt {
    private const val DIR = "audioart"
    private const val BLANK = "blank.jpg"

    /** Pictures are stored no bigger than this on their longest side (twice the video's size, so there is room to scale). */
    private const val STORE_EDGE = 1440
    private const val MAX_BYTES = 40 * 1024 * 1024
    private val counter = AtomicLong(0)

    fun dir(ctx: Context): File = File(ctx.filesDir, DIR).also { it.mkdirs() }

    /** A picture from a picked file (any format the phone can show). null if it can't be read as a picture. */
    fun fromUri(ctx: Context, uri: Uri): AudioImage? = try {
        val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        if (bytes == null || bytes.isEmpty() || bytes.size > MAX_BYTES) null else fromBytes(ctx, bytes)
    } catch (_: Exception) {
        null
    }

    /** A picture from the bytes of an image file (a cover art, say). null if it can't be read as a picture. */
    fun fromBytes(ctx: Context, bytes: ByteArray): AudioImage? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            null
        } else {
            var sample = 1
            while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= STORE_EDGE) sample *= 2
            val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            if (decoded == null) {
                null
            } else {
                var bmp: Bitmap = decoded
                // Photos are often stored sideways with a flag; turn them upright.
                val degrees = try {
                    when (ExifInterface(ByteArrayInputStream(bytes)).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                        ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                        ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                        ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                        else -> 0f
                    }
                } catch (_: Exception) {
                    0f
                }
                if (degrees != 0f) {
                    val turned = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(degrees) }, true)
                    if (turned !== bmp) bmp.recycle()
                    bmp = turned
                }
                if (max(bmp.width, bmp.height) > STORE_EDGE) {
                    val k = STORE_EDGE.toDouble() / max(bmp.width, bmp.height)
                    val scaled = Bitmap.createScaledBitmap(
                        bmp, max(1, (bmp.width * k).roundToInt()), max(1, (bmp.height * k).roundToInt()), true,
                    )
                    if (scaled !== bmp) bmp.recycle()
                    bmp = scaled
                }
                val file = File(dir(ctx), "art-${System.currentTimeMillis()}-${counter.incrementAndGet()}.jpg")
                file.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 92, it) }
                val image = AudioImage(file.absolutePath, bmp.width, bmp.height)
                bmp.recycle()
                if (file.length() > 0) image else { file.delete(); null }
            }
        }
    } catch (_: Throwable) {
        null
    }

    /** The plain black picture used when a card has none: 720 x 720, made once. */
    fun blank(ctx: Context): AudioImage {
        val file = File(dir(ctx), BLANK)
        if (file.length() == 0L) {
            val bmp = Bitmap.createBitmap(AudioFit.MAX_EDGE, AudioFit.MAX_EDGE, Bitmap.Config.ARGB_8888)
            Canvas(bmp).drawColor(Color.BLACK)
            file.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            bmp.recycle()
        }
        return AudioImage(file.absolutePath, AudioFit.MAX_EDGE, AudioFit.MAX_EDGE)
    }

    /** The cover art inside an audio file, or null when it has none (or the file can't be read that way). */
    fun embeddedCover(ctx: Context, uri: Uri): ByteArray? {
        val r = MediaMetadataRetriever()
        return try {
            if (uri.scheme == "file") r.setDataSource(uri.path) else r.setDataSource(ctx, uri)
            r.embeddedPicture
        } catch (_: Throwable) {
            null
        } finally {
            try { r.release() } catch (_: Throwable) { }
        }
    }

    /** Deletes the pictures no card uses any more ([keep] holds the paths still in use). */
    fun prune(ctx: Context, keep: Set<String>) {
        File(ctx.filesDir, DIR).listFiles()?.forEach {
            if (it.name != BLANK && it.absolutePath !in keep) it.delete()
        }
    }
}

/** Reading an audio file, and turning it and its picture(s) into a webm. */
object AudioConverter {
    /** Why the last [probe] failed, for the card. */
    @Volatile
    var lastProbeError: String? = null
        private set

    /** How many encodes a part may take to find the highest audio bitrate that fits. */
    private const val MAX_TRIES = 4

    /** The length of the fade at each cut of a split track: just enough to hide the click. */
    private const val CUT_FADE_SEC = 0.02

    private const val BEST_FILE = "audio-best.webm"

    private fun fmt(x: Double) = String.format(Locale.US, "%.3f", x)

    private fun tagOf(tags: JSONObject?, key: String): String {
        if (tags == null) return ""
        val names = tags.keys()
        while (names.hasNext()) {
            val k = names.next()
            if (k.equals(key, ignoreCase = true)) return tags.optString(k, "").trim()
        }
        return ""
    }

    /** The length, sound and title/artist tags of a file (an audio file, or a video to take the sound from). null if there is no sound to read. */
    suspend fun probe(ctx: Context, uri: Uri): AudioProbe? = withContext(Dispatchers.IO) {
        lastProbeError = null
        try {
            val path = if (uri.scheme == "file") uri.path ?: "" else FFmpegKitConfig.getSafParameterForRead(ctx, uri)
            val info = FFprobeKit.getMediaInformation(path).mediaInformation
            val dur = info?.duration?.toDoubleOrNull()
            if (info == null || dur == null || dur <= 0.0) {
                lastProbeError = "Couldn't read this file."
                return@withContext null
            }
            var codec = ""
            var streamTags: JSONObject? = null
            for (s in info.streams) {
                if (s.type == "audio") {
                    codec = s.codec ?: ""
                    streamTags = s.allProperties?.optJSONObject("tags")
                    break
                }
            }
            if (codec.isEmpty()) {
                lastProbeError = "This file has no sound."
                return@withContext null
            }
            val formatTags = info.allProperties?.optJSONObject("format")?.optJSONObject("tags")
            fun tag(key: String) = tagOf(formatTags, key).ifBlank { tagOf(streamTags, key) }
            val bytes = info.size?.toLongOrNull() ?: 0L
            AudioProbe(
                info = SourceInfo(
                    durationSec = dur, width = 0, height = 0, fps = 0.0, hasAudio = true, fpsReadable = false,
                    fileBytes = bytes,
                    container = (info.format ?: "").substringBefore(',').uppercase(Locale.US),
                    audioCodec = codec,
                ),
                title = tag("title"),
                artist = tag("artist"),
            )
        } catch (t: Throwable) {
            lastProbeError = "Technical details: ${t.javaClass.simpleName}: ${t.message ?: "no message"}"
            null
        }
    }

    private fun tail(s: String?): String = s?.takeLast(400)?.trim().orEmpty()

    /**
     * Makes one webm of the part of [input] (the staged audio file) from [startSec], [clipDurationSec]
     * long, with the card's picture(s) across it. The audio gets as much of the size limit as the
     * picture leaves, up to [AudioFit.MAX_KBPS]: the first encode aims for what the sums allow, and an
     * encode that comes out over the limit is redone lower (one that comes out well under is redone higher).
     */
    suspend fun convert(
        ctx: Context,
        input: File,
        src: SourceInfo,
        settings: Settings,
        spec: AudioSpec,
        startSec: Double,
        clipDurationSec: Double?,
        part: Int,
        numParts: Int,
        title: String,
        artist: String,
        onLog: (String) -> Unit,
        onProgress: (fraction: Float, status: String) -> Unit,
    ): ConvertResult = withContext(Dispatchers.IO) {
        FFmpegKitConfig.enableLogCallback { log -> onLog(log.message) }
        val out = File(ctx.cacheDir, "out.webm")
        val best = File(ctx.cacheDir, BEST_FILE)
        try {
            val t = settings.tuning
            val effMax = Converter.effectiveMaxSec(settings.maxDurationSec, t)
            val duration = clipDurationSec ?: min((src.durationSec - startSec).coerceAtLeast(0.0), effMax)
            if (duration < 0.1) return@withContext ConvertResult.Fail("There is nothing to convert in this part.")

            // The pictures that are really there; with none, the plain black one.
            val images = spec.images.filter { File(it.path).length() > 0 }.ifEmpty { listOf(AudioArt.blank(ctx)) }
            val (w, h) = AudioFit.frameSize(images, settings.minWidth, settings.minHeight)
            val fps = Converter.clampFps(settings.targetFps)
            val videoBytes = AudioFit.videoBytes(w, h, images.size, duration)
            var kbps = AudioFit.audioKbps(settings.limitBytes, duration, videoBytes)
            if (kbps < AudioFit.MIN_KBPS) {
                return@withContext ConvertResult.Fail(
                    "This part is %.0f s long, too long for the size limit at a listenable quality. Split the track into more parts.".format(Locale.US, duration)
                )
            }
            val codec = if (settings.useVp9) "VP9 (libvpx-vp9)" else "VP8 (libvpx)"
            onLog("   picture: ${w}×${h}, ${images.size} image${if (images.size == 1) "" else "s"}, $fps fps, $codec")
            onLog(
                "   size limit ${settings.limitBytes} bytes; the picture is expected to take about ${videoBytes.toLong()}; " +
                    "first try: audio at $kbps kbps"
            )
            if (kbps < AudioFit.WARN_KBPS) onLog("   note: below ${AudioFit.WARN_KBPS} kbps the sound loses quality; more parts would give it more room")

            val segSec = duration / images.size
            val gop = max(2, fps * AudioFit.KEYFRAME_SEC)
            val cutFades = ArrayList<String>()
            if (numParts > 1) {
                if (part > 1) cutFades.add("afade=t=in:st=0:d=${fmt(CUT_FADE_SEC)}")
                if (part < numParts) cutFades.add("afade=t=out:st=${fmt(max(0.0, duration - CUT_FADE_SEC))}:d=${fmt(CUT_FADE_SEC)}")
            }

            // One filter graph: each picture fitted into the frame on black, held at the frame rate, then joined end to end.
            val graph = buildString {
                for (n in images.indices) {
                    append("[${n + 1}:v]scale=$w:$h:force_original_aspect_ratio=decrease,")
                    append("pad=$w:$h:(ow-iw)/2:(oh-ih)/2:color=black,setsar=1,fps=$fps,format=yuv420p[v$n];")
                }
                if (images.size == 1) {
                    append("[v0]null[vout]")
                } else {
                    for (n in images.indices) append("[v$n]")
                    append("concat=n=${images.size}:v=1:a=0[vout]")
                }
            }

            var bestSize = 0L
            var bestKbps = 0
            var attemptsUsed = 0
            var attempt = 0
            while (attempt < MAX_TRIES) {
                attempt++
                attemptsUsed = attempt
                out.delete()
                onProgress(0f, "Attempt $attempt/$MAX_TRIES: Encoding audio ($kbps kbps)")
                FFmpegKitConfig.enableStatisticsCallback { st ->
                    val frac = (st.time.toDouble() / 1000.0 / duration).coerceIn(0.0, 1.0)
                    onProgress(frac.toFloat(), "Attempt $attempt/$MAX_TRIES: Encoding audio ($kbps kbps)")
                }
                val args = ArrayList<String>()
                args.addAll(listOf("-hide_banner", "-y"))
                args.addAll(listOf("-ss", fmt(startSec), "-t", fmt(duration), "-i", input.absolutePath))
                for (img in images) args.addAll(listOf("-loop", "1", "-framerate", fps.toString(), "-t", fmt(segSec), "-i", img.path))
                args.addAll(listOf("-filter_complex", graph, "-map", "[vout]", "-map", "0:a:0", "-map_metadata", "-1", "-map_chapters", "-1"))
                if (t.maxThreads > 0) args.addAll(listOf("-threads", t.maxThreads.toString()))
                if (settings.useVp9) {
                    args.addAll(listOf("-c:v", "libvpx-vp9", "-b:v", "0", "-crf", "34", "-row-mt", "1", "-cpu-used", "4", "-deadline", "good"))
                } else {
                    args.addAll(listOf("-c:v", "libvpx", "-b:v", "400k", "-crf", "30", "-auto-alt-ref", "0", "-cpu-used", "4", "-deadline", "good"))
                }
                args.addAll(listOf("-g", gop.toString(), "-pix_fmt", "yuv420p"))
                args.addAll(listOf("-c:a", "libvorbis", "-b:a", "${kbps}k", "-ac", AudioFit.channels(kbps).toString()))
                val hz = AudioFit.sampleRate(kbps)
                if (hz > 0) args.addAll(listOf("-ar", hz.toString()))
                if (cutFades.isNotEmpty()) args.addAll(listOf("-af", cutFades.joinToString(",")))
                if (title.isNotBlank()) args.addAll(listOf("-metadata", "title=$title"))
                if (artist.isNotBlank()) args.addAll(listOf("-metadata", "artist=$artist"))
                args.addAll(listOf("-t", fmt(duration), "-f", "webm", out.absolutePath))

                val session = FFmpegKit.executeWithArguments(args.toTypedArray())
                if (ReturnCode.isCancel(session.returnCode)) return@withContext ConvertResult.Cancelled
                if (!ReturnCode.isSuccess(session.returnCode)) {
                    if (bestSize > 0L) break // an earlier try fit; keep it
                    return@withContext ConvertResult.Fail("Encoding failed.\n${tail(session.allLogsAsString)}")
                }
                val size = out.length()
                if (size <= 0L) {
                    if (bestSize > 0L) break
                    return@withContext ConvertResult.Fail("Encoding produced an empty file.")
                }
                onLog("== Attempt $attempt result: $size bytes at $kbps kbps audio (limit ${settings.limitBytes})")

                if (size <= settings.limitBytes) {
                    if (size > bestSize) {
                        best.delete()
                        if (!out.renameTo(best)) { out.copyTo(best, overwrite = true); out.delete() }
                        bestSize = size
                        bestKbps = kbps
                    } else out.delete()
                    // Done when it is within about 7% of the limit, at the top bitrate, or out of tries.
                    if (size >= settings.limitBytes * 0.93 || kbps >= AudioFit.MAX_KBPS || attempt >= MAX_TRIES) break
                    val next = min(AudioFit.MAX_KBPS.toDouble(), kbps * (settings.limitBytes * 0.975 / size)).toInt()
                    if (next <= kbps + 2) break
                    onLog("   room to spare: trying ${next} kbps")
                    kbps = next
                } else {
                    out.delete()
                    if (bestSize > 0L) break // we already hold a fit; a higher try that overshoots isn't needed
                    val next = (kbps * (settings.limitBytes * 0.965 / size)).toInt()
                    onLog("   over the limit: trying ${next} kbps")
                    if (next < AudioFit.MIN_KBPS) {
                        return@withContext ConvertResult.Fail(
                            "This part doesn't fit the size limit at a listenable quality. Split the track into more parts."
                        )
                    }
                    kbps = min(next, kbps - 1)
                }
            }
            if (bestSize <= 0L) {
                return@withContext ConvertResult.Fail("Couldn't make the file fit the size limit. Split the track into more parts.")
            }
            ConvertResult.Ok(
                file = best,
                bytes = bestSize,
                height = h,
                videoKbps = 0,
                trimmed = false,
                audioDropped = false,
                audioIncluded = true,
                attempts = attemptsUsed,
                audioKbps = bestKbps,
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (t: Throwable) {
            ConvertResult.Fail("Encoding failed: ${t.javaClass.simpleName}: ${t.message ?: "no message"}")
        } finally {
            FFmpegKitConfig.enableLogCallback { }
            FFmpegKitConfig.enableStatisticsCallback { }
            out.delete()
            // The best file is handed to the caller when it succeeded; on any other ending it is not wanted.
            // (The caller deletes the returned file itself once it has been saved.)
        }
    }

    /** Deletes the leftovers of an interrupted encode. */
    fun clearWorkFiles(ctx: Context) {
        File(ctx.cacheDir, BEST_FILE).delete()
    }
}
