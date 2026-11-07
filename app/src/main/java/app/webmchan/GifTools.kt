// #gifaudio
package app.webmchan

import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * What the app needs to know about an animated GIF: its size, how many pictures it holds and how long
 * one run through them takes. Read straight from the file's own bytes (see [GifReader]), so it works
 * whether or not the FFmpeg build can decode GIFs.
 */
data class GifInfo(val width: Int, val height: Int, val frames: Int, val durationSec: Double) {
    /** Pictures per second over the whole loop. */
    val fps: Double get() = if (durationSec > 0.0) frames / durationSec else 0.0

    /** A GIF with a single picture is just a still image. */
    val animated: Boolean get() = frames > 1
}

/**
 * A small reader for the GIF file format: walks the blocks, counting pictures and adding up their
 * delays. No pixels are decoded. Plain Kotlin with nothing from Android in it, so it can be tested
 * without a phone (see GifAudioTest).
 */
object GifReader {
    /** A delay shorter than this many hundredths of a second is replaced by the default, the way ffmpeg and browsers do. */
    private const val MIN_DELAY_CS = 2
    private const val DEFAULT_DELAY_CS = 10

    fun looksLikeGif(b: ByteArray): Boolean =
        b.size >= 13 && b[0] == 'G'.code.toByte() && b[1] == 'I'.code.toByte() && b[2] == 'F'.code.toByte() && b[3] == '8'.code.toByte()

    /** null if [b] isn't a readable GIF (or holds no pictures). */
    fun parse(b: ByteArray): GifInfo? {
        if (!looksLikeGif(b)) return null
        fun u8(i: Int): Int = if (i in b.indices) b[i].toInt() and 0xFF else -1
        fun u16(i: Int): Int {
            val lo = u8(i)
            val hi = u8(i + 1)
            return if (lo < 0 || hi < 0) -1 else lo or (hi shl 8)
        }
        // Skips a run of data sub-blocks (each starts with its length; a zero length ends the run). -1 if the file ends first.
        fun skipSubBlocks(from: Int): Int {
            var p = from
            while (true) {
                val n = u8(p)
                if (n < 0) return -1
                if (n == 0) return p + 1
                p += 1 + n
            }
        }

        val w = u16(6)
        val h = u16(8)
        if (w <= 0 || h <= 0) return null
        val packed = u8(10)
        if (packed < 0) return null
        var pos = 13
        if (packed and 0x80 != 0) pos += 3 * (1 shl ((packed and 7) + 1))

        var frames = 0
        var totalCs = 0L
        // The delay from the last graphic control extension, which applies to the next picture.
        var delayCs = -1
        loop@ while (true) {
            when (u8(pos)) {
                -1, 0x3B -> break@loop
                0x21 -> {
                    if (u8(pos + 1) == 0xF9 && u8(pos + 2) >= 4) delayCs = u16(pos + 4)
                    pos = skipSubBlocks(pos + 2)
                    if (pos < 0) break@loop
                }
                0x2C -> {
                    val local = u8(pos + 9)
                    if (local < 0) break@loop
                    pos += 10
                    if (local and 0x80 != 0) pos += 3 * (1 shl ((local and 7) + 1))
                    pos += 1 // the LZW code size
                    pos = skipSubBlocks(pos)
                    if (pos < 0) break@loop
                    frames++
                    var d = if (delayCs < 0) DEFAULT_DELAY_CS else delayCs
                    if (d < MIN_DELAY_CS) d = DEFAULT_DELAY_CS
                    totalCs += d
                    delayCs = -1
                }
                else -> break@loop
            }
        }
        if (frames <= 0) return null
        return GifInfo(w, h, frames, totalCs / 100.0)
    }
}

/** An audio and a video bitrate, in kbps. */
data class GifPlan(val videoKbps: Int, val audioKbps: Int)

/**
 * The size sums for an Audio Mode card with an animated GIF among its pictures. Without a GIF the picture
 * costs almost nothing and [AudioFit] gives the sound all the rest; a GIF is real video, so here the sound
 * gets a good quality first ([AUDIO_KBPS]) and the GIF gets what is left. Retries then lower the GIF's
 * bitrate before the sound's. Plain Kotlin, tested in GifAudioTest.
 */
object GifFit {
    /** The sound quality that is kept first; the GIF video gets the rest of the size limit. */
    const val AUDIO_KBPS = 128

    /** The GIF video is never given less than this; the sound is lowered instead (down to [AudioFit.MIN_KBPS]). */
    const val MIN_VIDEO_KBPS = 24

    /** The most bits per pixel a GIF is given: above this, more bitrate buys nothing visible. */
    const val MAX_BPP = 0.35

    /** Below this many bits per pixel the card warns that the GIF will look rough. */
    const val ROUGH_BPP = 0.03

    /** How many encodes a part may take (the sound's bitrate takes AudioFit's four; the GIF video needs one more). */
    const val MAX_TRIES = 5

    /** How often the video has a keyframe, in seconds. */
    const val KEYFRAME_SEC = 10

    private const val SAFETY = 0.97
    private const val OVERHEAD_BYTES = 3000.0
    private const val LOWER_TARGET = 0.965
    private const val RAISE_TARGET = 0.975

    /** The frame rate of a card with GIFs: the highest of its GIFs' rates, rounded. 0 when it has none. */
    fun frameRate(images: List<AudioImage>): Int {
        val top = images.filter { it.animated }.maxOfOrNull { it.fps } ?: return 0
        return top.roundToInt().coerceIn(Converter.MIN_FPS, Converter.MAX_FPS)
    }

    /** The most video bitrate worth giving a picture [w]×[h] at [fps]. */
    fun videoCapKbps(w: Int, h: Int, fps: Int): Int =
        max(MIN_VIDEO_KBPS, (w.toDouble() * h * fps * MAX_BPP / 1000.0).toInt())

    /** What the video part takes of the file, in bytes, at [videoKbps] for [partSec]. */
    fun videoBytes(videoKbps: Int, partSec: Double): Double = videoKbps * 125.0 * max(0.1, partSec)

    /** The share of the size limit the GIF video is planned to take (0 to 1). */
    fun videoShare(videoKbps: Int, partSec: Double, limitBytes: Long): Double =
        if (limitBytes <= 0L) 0.0 else min(1.0, videoBytes(videoKbps, partSec) / limitBytes)

    /** Video bits per pixel at [videoKbps]. */
    fun bitsPerPixel(videoKbps: Int, w: Int, h: Int, fps: Int): Double =
        videoKbps * 1000.0 / max(1.0, w.toDouble() * h * max(1, fps))

    /**
     * The first try for a part [partSec] long: sound at [AUDIO_KBPS] (or [audioCapKbps] if that is lower),
     * the rest of the limit for the GIF video, at most [videoCapKbps]; any surplus goes back to the sound.
     * If the video would get less than [MIN_VIDEO_KBPS] it gets that, and the sound gives way. The sound's
     * result can be below [AudioFit.MIN_KBPS] (even negative): then nothing fits and the caller says so.
     */
    fun plan(limitBytes: Long, partSec: Double, w: Int, h: Int, fps: Int, audioCapKbps: Int): GifPlan {
        val sec = max(0.1, partSec)
        val totalKbps = (limitBytes * SAFETY - OVERHEAD_BYTES) * 8.0 / sec / 1000.0
        var audio = min(audioCapKbps, AUDIO_KBPS).toDouble()
        var video = totalKbps - audio
        val cap = videoCapKbps(w, h, fps)
        if (video > cap) {
            audio = min(audioCapKbps.toDouble(), audio + (video - cap))
            video = cap.toDouble()
        }
        if (video < MIN_VIDEO_KBPS) {
            video = MIN_VIDEO_KBPS.toDouble()
            audio = totalKbps - video
        }
        return GifPlan(floor(video).toInt(), floor(audio).toInt())
    }

    /**
     * After an encode that came out [sizeBytes], over [limitBytes]: the next try. The GIF video is lowered
     * by the overshoot first; only when it reaches [MIN_VIDEO_KBPS] does the sound give up the rest.
     * null when the sound would fall below [AudioFit.MIN_KBPS]: it can't be made to fit.
     */
    fun lower(videoKbps: Int, audioKbps: Int, sizeBytes: Long, limitBytes: Long, partSec: Double): GifPlan? {
        val overKbps = (sizeBytes - limitBytes * LOWER_TARGET) * 8.0 / max(0.1, partSec) / 1000.0
        if (overKbps <= 0.0) return GifPlan(videoKbps, audioKbps)
        val newVideo = videoKbps - overKbps
        if (newVideo >= MIN_VIDEO_KBPS) return GifPlan(floor(newVideo).toInt(), audioKbps)
        val stillOver = overKbps - max(0, videoKbps - MIN_VIDEO_KBPS)
        val newAudio = audioKbps - stillOver
        if (newAudio < AudioFit.MIN_KBPS) return null
        return GifPlan(MIN_VIDEO_KBPS, floor(newAudio).toInt())
    }

    /**
     * After an encode that fitted with room to spare: the next try. Sound that was squeezed goes back to
     * [AUDIO_KBPS] first, then the GIF video goes up to [videoCapKbps], then the sound up to [audioCapKbps].
     * null when there is too little room (or nothing left to raise).
     */
    fun raise(
        videoKbps: Int, audioKbps: Int, sizeBytes: Long, limitBytes: Long, partSec: Double,
        videoCapKbps: Int, audioCapKbps: Int,
    ): GifPlan? {
        var spare = (limitBytes * RAISE_TARGET - sizeBytes) * 8.0 / max(0.1, partSec) / 1000.0
        if (spare < 2.0) return null
        var v = videoKbps.toDouble()
        var a = audioKbps.toDouble()
        fun raiseAudio(upTo: Int) {
            val add = min(spare, max(0.0, upTo - a))
            a += add
            spare -= add
        }
        raiseAudio(min(AUDIO_KBPS, audioCapKbps))
        val addV = min(spare, max(0.0, videoCapKbps - v))
        v += addV
        spare -= addV
        raiseAudio(audioCapKbps)
        val nv = floor(v).toInt()
        val na = floor(a).toInt()
        return if (nv <= videoKbps && na <= audioKbps) null else GifPlan(nv, na)
    }
}
