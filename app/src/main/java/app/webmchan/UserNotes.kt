// #lognotes
package app.webmchan

import java.util.Locale
import kotlin.math.roundToInt

/**
 * One message from the app to the person, in plain words: what the speech bubble above the log shows.
 * [attention] is for the ones that want a second look (a retry, a lowered sound, a kept earlier result).
 */
data class UserNote(val text: String, val attention: Boolean = false)

/** Small helpers for writing the numbers in a note. */
object NoteText {
    /** "5.9 MB" (the size limits are in binary megabytes, so 6 MB is 6291456 bytes). */
    fun mb(bytes: Long): String = "%.1f MB".format(Locale.US, bytes / 1048576.0)

    /** "99.5%". */
    fun pct(p: Double): String = "%.1f%%".format(Locale.US, p)
}

/**
 * A rough guess at how long a conversion will take, for one vague line at the start ("This will probably
 * take about 6 min"). Never a countdown.
 *
 * The figures are seconds per output frame, measured on one phone with one 360p clip, for Quality and
 * Best quality in VP8 and VP9 (Fastest is not estimated). Pass 1 costs about the same whatever the
 * effort; pass 2 is what differs. How the cost grows with picture size has not been measured, only
 * hinted at by one other clip, so [sizeFactor] is a guess: treat the estimate for a big picture as rougher.
 */
object TimeEstimate {
    private const val PASS1_VP8 = 0.024
    private const val PASS1_VP9 = 0.016
    private const val PASS2_VP8_QUALITY = 0.064
    private const val PASS2_VP8_BEST = 0.082
    private const val PASS2_VP9_QUALITY = 0.341
    private const val PASS2_VP9_BEST = 0.486

    /** The picture size the figures above were measured at. */
    private const val REFERENCE_PIXELS = 640.0 * 360.0

    /** Per-frame cost against the reference size: part fixed, part in step with the pixels. A guess. */
    private fun sizeFactor(pixels: Double): Double =
        (0.55 + 0.45 * pixels / REFERENCE_PIXELS).coerceIn(0.5, 4.0)

    class Estimate(val pass1Sec: Double, val pass2Sec: Double)

    /** [frames] is the number of frames in the output, [pixels] the width times the height of one. */
    fun estimate(vp9: Boolean, best: Boolean, frames: Double, pixels: Double): Estimate {
        val f = sizeFactor(pixels)
        val pass1 = frames * (if (vp9) PASS1_VP9 else PASS1_VP8) * f
        val perFrame2 = if (vp9) {
            if (best) PASS2_VP9_BEST else PASS2_VP9_QUALITY
        } else {
            if (best) PASS2_VP8_BEST else PASS2_VP8_QUALITY
        }
        return Estimate(pass1, frames * perFrame2 * f)
    }

    /** "under a minute", "about 6 min", "about 25 min", "about 1 h 10 min": rounded so it reads as a guess. */
    fun phrase(sec: Double): String {
        val min = sec / 60.0
        return when {
            min < 1.0 -> "under a minute"
            min < 10.0 -> "about ${min.roundToInt()} min"
            min < 60.0 -> "about ${(min / 5.0).roundToInt() * 5} min"
            else -> {
                val total = (min / 5.0).roundToInt() * 5
                val h = total / 60
                val m = total % 60
                if (m == 0) "about $h h" else "about $h h $m min"
            }
        }
    }

    /** The line shown at the start. Best quality gives the time for one try, and says it may repeat. */
    fun message(e: Estimate, best: Boolean, pass1Reused: Boolean): String {
        val total = (if (pass1Reused) 0.0 else e.pass1Sec) + e.pass2Sec
        val time = phrase(total)
        val lead = if (time == "under a minute") "This should take under a minute" else "This will probably take $time"
        return lead + (if (best) " per try. Best quality may need to try again." else ".") +
            (if (pass1Reused) " The first pass is already done from an earlier run." else "")
    }

    /** The one correction, if the real speed turns out well off the guess. */
    fun revisedMessage(pass2Sec: Double, slower: Boolean, best: Boolean): String =
        "Running ${if (slower) "slower" else "faster"} than I guessed: " +
            (if (best) "each try" else "the encoding step") + " will probably take ${phrase(pass2Sec)}."
}
