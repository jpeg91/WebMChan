// #editspec
package app.webmchan

import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Everything the little video editor can do to a card, kept apart from the source file: the source
 * video is never changed. The conversion reads this and encodes only what it describes.
 *
 * An edit is a list of [EditClip]s (the "cuts": which stretches of the source are kept, in the order
 * they are joined) and an optional [CropRect] that applies to the whole video. No pure logic here
 * touches Android, so it can be tested on its own (see EditSpecTest).
 *
 * The rest of the app doesn't need to know about edits: [effectiveInfo] turns the source's
 * [SourceInfo] into the one the edited video would have (its length, its cropped size), and
 * everything that plans a conversion (parts, bitrate, frame height, quality bracket) just uses that.
 */

/** One kept stretch of the source, in seconds of the source video. */
data class EditClip(val startSec: Double, val endSec: Double) {
    val lengthSec: Double get() = endSec - startSec
}

/**
 * A crop, in whole pixels of the picture as it looks when played (after any rotation the file asks
 * for). [x] and [y] are the top left corner. Always even-numbered, because the video format
 * stores colour at half size.
 */
data class CropRect(val x: Int, val y: Int, val w: Int, val h: Int)

/** A stretch of the source to encode: where it starts in the source, and how long it is. */
data class EditSlice(val startSec: Double, val durationSec: Double)

data class EditSpec(
    val clips: List<EditClip>,
    val crop: CropRect? = null,
) {
    /** The length of the edited video. */
    val totalSec: Double get() = clips.sumOf { it.lengthSec }

    /**
     * The pieces of the source that make up the stretch from [startSec] for [durationSec] of the
     * EDITED video, in order. A conversion that is split into parts asks for one part at a time,
     * and a part can run across several clips. Empty if the stretch lies outside the edit.
     */
    fun slicesFor(startSec: Double, durationSec: Double): List<EditSlice> {
        val from = max(0.0, startSec)
        val to = from + durationSec
        val out = ArrayList<EditSlice>()
        var at = 0.0
        for (c in clips) {
            val clipFrom = at
            val clipTo = at + c.lengthSec
            val a = max(from, clipFrom)
            val b = min(to, clipTo)
            if (b - a > SLICE_EPS) out.add(EditSlice(c.startSec + (a - clipFrom), b - a))
            at = clipTo
        }
        return out
    }

    /**
     * What the source's [SourceInfo] becomes once edited: the edited length and the cropped size.
     * The source's video bitrate is scaled down for a crop (it belongs to the whole frame), the same
     * way the size predictions scale it for a smaller frame, so those stay consistent.
     */
    fun effectiveInfo(src: SourceInfo): SourceInfo {
        val w = crop?.w ?: src.width
        val h = crop?.h ?: src.height
        val area = (w.toDouble() * h) / (src.width.toDouble() * src.height).coerceAtLeast(1.0)
        return src.copy(
            durationSec = totalSec,
            width = w,
            height = h,
            videoKbps = if (src.videoKbps > 0) max(1, (src.videoKbps * area.pow(AREA_EXPONENT)).roundToInt()) else 0,
        )
    }

    /** True if this edit changes nothing: the whole video, uncropped. */
    fun isNoOp(src: SourceInfo): Boolean {
        val c = clips.singleOrNull() ?: return false
        return crop == null && c.startSec <= SLICE_EPS && c.endSec >= src.durationSec - SLICE_EPS
    }

    /** Identifies what the edit does to the picture and sound, for the kept analysis files (see Converter). */
    fun signature(): String =
        clips.joinToString(",") { "%.3f-%.3f".format(Locale.US, it.startSec, it.endSec) } +
            (crop?.let { "|crop=${it.x},${it.y},${it.w},${it.h}" } ?: "")

    /** A short description for cards and logs, e.g. "3 clips, cropped to 640×480". */
    fun describe(): String = buildList {
        add(if (clips.size == 1) "1 clip" else "${clips.size} clips")
        crop?.let { add("cropped to ${it.w}×${it.h}") }
    }.joinToString(", ")

    // ---- Editing ----

    /**
     * Where the clip at [index] may be trimmed to: back as far as the end of the nearest kept clip
     * before it in the SOURCE (or the start of the video), forward as far as the start of the nearest
     * one after it (or the end). Clips can be put in any order, but never overlap in the source.
     */
    fun bounds(index: Int, sourceSec: Double): Pair<Double, Double> {
        val c = clips[index]
        var lo = 0.0
        var hi = sourceSec
        for ((i, o) in clips.withIndex()) {
            if (i == index) continue
            if (o.endSec <= c.startSec + SLICE_EPS) lo = max(lo, o.endSec)
            if (o.startSec >= c.endSec - SLICE_EPS) hi = min(hi, o.startSec)
        }
        return lo to hi
    }

    /** Cuts the clip at [index] in two at [sourceSec] (a time in the source). null if not allowed. */
    fun split(index: Int, sourceSec: Double): EditSpec? {
        if (clips.size >= MAX_CLIPS) return null
        val c = clips.getOrNull(index) ?: return null
        if (sourceSec < c.startSec + MIN_CLIP_SEC || sourceSec > c.endSec - MIN_CLIP_SEC) return null
        val next = clips.toMutableList()
        next[index] = EditClip(c.startSec, sourceSec)
        next.add(index + 1, EditClip(sourceSec, c.endSec))
        return copy(clips = next)
    }

    /** Takes the clip at [index] out. null if it is the last one left. */
    fun delete(index: Int): EditSpec? {
        if (clips.size <= 1 || index !in clips.indices) return null
        return copy(clips = clips.filterIndexed { i, _ -> i != index })
    }

    /** Moves the clip at [index] one place earlier (-1) or later (+1) in the finished video. */
    fun move(index: Int, delta: Int): EditSpec? {
        val to = index + delta
        if (index !in clips.indices || to !in clips.indices || delta == 0) return null
        val next = clips.toMutableList()
        val c = next.removeAt(index)
        next.add(to, c)
        return copy(clips = next)
    }

    /**
     * Sets where the clip at [index] starts and ends in the source, kept inside [bounds] and at least
     * [MIN_CLIP_SEC] long. Whichever end the caller didn't mean to move should be passed unchanged.
     */
    fun trim(index: Int, startSec: Double, endSec: Double, sourceSec: Double): EditSpec? {
        val c = clips.getOrNull(index) ?: return null
        val (lo, hi) = bounds(index, sourceSec)
        var s = startSec.coerceIn(lo, hi)
        var e = endSec.coerceIn(lo, hi)
        // Moving one end into the other stops at the minimum length instead.
        if (e - s < MIN_CLIP_SEC) {
            if (startSec != c.startSec) s = max(lo, e - MIN_CLIP_SEC) else e = min(hi, s + MIN_CLIP_SEC)
        }
        if (e - s < MIN_CLIP_SEC - SLICE_EPS) return null
        val next = clips.toMutableList()
        next[index] = EditClip(s, e)
        return copy(clips = next)
    }

    fun withCrop(c: CropRect?): EditSpec = copy(crop = c)

    /**
     * Which clip a time in the EDITED video falls in and how far into it: (index, seconds into the
     * clip). Past the end it is the end of the last clip.
     */
    fun locate(editedSec: Double): Pair<Int, Double> {
        var rem = editedSec.coerceIn(0.0, totalSec)
        for ((i, c) in clips.withIndex()) {
            if (rem < c.lengthSec || i == clips.lastIndex) return i to min(rem, c.lengthSec)
            rem -= c.lengthSec
        }
        return 0 to 0.0
    }

    /** The time in the EDITED video at which the clip at [index] starts. */
    fun editedStartOf(index: Int): Double = clips.take(index).sumOf { it.lengthSec }

    companion object {
        /** Cuts are kept few and simple: this is a small editor, and every clip is a decoder open while converting. */
        const val MAX_CLIPS = 8

        /** The shortest a clip may be. */
        const val MIN_CLIP_SEC = 0.5

        /** The smallest a crop may be, in pixels on each side. */
        const val MIN_CROP_PX = 32

        private const val SLICE_EPS = 0.001

        // The same rule of thumb Converter uses to scale a bitrate with frame size.
        private const val AREA_EXPONENT = 0.75

        /** The uncut, uncropped video. */
        fun whole(src: SourceInfo): EditSpec = EditSpec(listOf(EditClip(0.0, src.durationSec)))

        /**
         * A crop made safe: whole even pixels, at least [MIN_CROP_PX] each way, fully inside a
         * [frameW]×[frameH] picture. null if it is (or rounds to) the whole picture.
         */
        fun cleanCrop(x: Int, y: Int, w: Int, h: Int, frameW: Int, frameH: Int): CropRect? {
            val minW = min(MIN_CROP_PX, frameW / 2 * 2)
            val minH = min(MIN_CROP_PX, frameH / 2 * 2)
            val cw = (w / 2 * 2).coerceIn(minW, frameW / 2 * 2)
            val ch = (h / 2 * 2).coerceIn(minH, frameH / 2 * 2)
            val cx = (x / 2 * 2).coerceIn(0, max(0, frameW - cw) / 2 * 2)
            val cy = (y / 2 * 2).coerceIn(0, max(0, frameH - ch) / 2 * 2)
            if (cx == 0 && cy == 0 && cw >= frameW - 1 && ch >= frameH - 1) return null
            return CropRect(cx, cy, cw, ch)
        }
    }
}
