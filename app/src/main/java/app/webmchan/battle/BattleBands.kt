// #battle
package app.webmchan.battle

import kotlin.math.roundToInt

/**
 * Turns the raw numbers of a conversion into stat bands (1..[BAND_COUNT]). Every threshold lives here
 * as a constant, in one list per stat, so balance is changed by editing numbers and nothing else.
 *
 * A list of seven cut points makes eight bands: a value at or below the first cut is band 1, above
 * the last is band 8. The values are a first guess, to be checked against real History in the roster
 * preview and tuned in Stage 2.
 */
object BattleBands {
    /** The band an unknown stat gets: the middle one. */
    const val NEUTRAL_BAND = 4

    /** Output length in seconds. */
    val DURATION_SEC = doubleArrayOf(3.0, 6.0, 10.0, 20.0, 40.0, 90.0, 180.0)

    /** Source size over output size: how hard the video was squeezed. Under 1 means it grew. */
    val COMPRESSION_RATIO = doubleArrayOf(0.8, 1.5, 3.0, 6.0, 12.0, 30.0, 80.0)

    /** Output picture size in pixels: 144p, 240p, 360p, 480p, ~540p, 720p, ~900p, then 1080p above. */
    val PIXELS = doubleArrayOf(55_000.0, 110_000.0, 240_000.0, 430_000.0, 700_000.0, 1_000_000.0, 1_600_000.0)

    /** Output bitrate in kbps. */
    val BITRATE_KBPS = doubleArrayOf(120.0, 250.0, 450.0, 800.0, 1300.0, 2200.0, 4000.0)

    /** How full the size limit is (output bytes over the limit): a tight squeeze is high. */
    val SIZE_FILL = doubleArrayOf(0.10, 0.25, 0.45, 0.65, 0.80, 0.90, 0.96)

    /** Output frame rate in fps: a still-picture sound file sits at the bottom, 60 fps at the top. */
    val FRAME_RATE = doubleArrayOf(5.0, 10.0, 15.0, 24.0, 30.0, 48.0, 59.0)

    /** Seconds of video made per second of waiting: below 1 is slower than real time. */
    val REAL_TIME_FACTOR = doubleArrayOf(0.04, 0.08, 0.15, 0.30, 0.60, 1.20, 2.40)

    /** The band for [value] given seven ascending cut points. */
    fun band(value: Double, cuts: DoubleArray): Int {
        var band = 1
        for (cut in cuts) if (value > cut) band++
        return band.coerceIn(1, BAND_COUNT)
    }

    /** HP: the length band and the squeeze band averaged (rounded up); length alone when the squeeze isn't known. */
    fun hp(totalDurationSec: Double, compressionRatio: Double?): Int {
        val length = band(totalDurationSec, DURATION_SEC)
        if (compressionRatio == null) return length
        val squeeze = band(compressionRatio, COMPRESSION_RATIO)
        return ((length + squeeze) / 2.0 + 0.25).roundToInt().coerceIn(1, BAND_COUNT)
    }

    fun attack(pixels: Double): Int = if (pixels <= 0.0) NEUTRAL_BAND else band(pixels, PIXELS)

    fun spAttack(kbps: Double): Int = if (kbps <= 0.0) NEUTRAL_BAND else band(kbps, BITRATE_KBPS)

    /** Defence: [fill] is how close the file came to the size limit (1.0 = exactly the limit). */
    fun defence(fill: Double?): Int = if (fill == null || fill <= 0.0) NEUTRAL_BAND else band(fill, SIZE_FILL)

    /** Special Defence: the output frame rate; smoother is harder to hit. 0 or less = not known. */
    fun spDefence(fps: Double): Int = if (fps <= 0.0) NEUTRAL_BAND else band(fps, FRAME_RATE)

    /** Speed: [realTimeFactor] is seconds of video made per second of conversion, null if unknown. */
    fun speed(realTimeFactor: Double?): Int =
        if (realTimeFactor == null || realTimeFactor <= 0.0) NEUTRAL_BAND else band(realTimeFactor, REAL_TIME_FACTOR)
}
