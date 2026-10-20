package app.webmchan

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * The numbers that steer how a video is fitted into a size limit. Normally the defaults are
 * right; they're adjustable from About > Advanced so they can be tuned without rebuilding the
 * app. A conversion takes a copy when it starts, so changing them mid-run affects the next run.
 *
 * Every value is kept within a sensible range by [sanitized], so a typo can't make the app
 * misbehave.
 */
data class Tuning(
    /** First attempt aims for this % of the size limit. The rest is safety margin. */
    val firstTargetPct: Double = 97.5,
    /**
     * A retry aims for this % of the limit. Deliberately close to the limit: a big cushion can
     * overcorrect a near-miss into a much bigger cut than needed.
     */
    val retryMarginPct: Double = 99.0,
    /** How many encodes to try before giving up on a clip. */
    val maxAttempts: Int = 4,
    /** Normal audio bitrate, in kbps. */
    val audioKbps: Int = 64,
    /** Audio may drop this low (rather than failing the clip) when the budget is tight. */
    val minAudioKbps: Int = 32,
    /** Below this video bitrate the clip is reported as too long for the limit. */
    val minVideoKbps: Int = 40,
    /** Smallest bits-per-pixel a frame size must reach to be chosen (Quality and Speed modes). */
    val normalBpp: Double = 0.06,
    /** The same for Fastest mode; higher, so it picks smaller frames. */
    val fastestBpp: Double = 0.12,
    /** Seconds shaved off every board's length limit (300 s is treated as 299 s). */
    val lengthGraceSec: Double = 1.0,
    /** An overshoot of this many seconds or less is trimmed off instead of becoming another part. */
    val trimToleranceSec: Double = 0.5,
    /**
     * From this attempt on, the audio is measured on its own and the video gets exactly what's
     * left. Set above [maxAttempts] to turn it off.
     */
    val audioAnalysisAttempt: Int = 3,
) {
    /** The same values, forced into their allowed ranges. */
    fun sanitized(): Tuning {
        val audio = audioKbps.coerceIn(16, 192)
        return Tuning(
            firstTargetPct = firstTargetPct.coerceIn(80.0, 99.5),
            retryMarginPct = retryMarginPct.coerceIn(80.0, 99.9),
            maxAttempts = maxAttempts.coerceIn(1, 8),
            audioKbps = audio,
            minAudioKbps = minAudioKbps.coerceIn(8, audio),
            minVideoKbps = minVideoKbps.coerceIn(10, 500),
            normalBpp = normalBpp.coerceIn(0.01, 0.5),
            fastestBpp = fastestBpp.coerceIn(0.01, 1.0),
            lengthGraceSec = lengthGraceSec.coerceIn(0.0, 10.0),
            trimToleranceSec = trimToleranceSec.coerceIn(0.0, 5.0),
            audioAnalysisAttempt = audioAnalysisAttempt.coerceIn(2, 9),
        )
    }

    /** One short "name = value" entry for each setting that isn't at its default (for the log). */
    fun changesFromDefault(): List<String> {
        val d = Tuning()
        return buildList {
            if (firstTargetPct != d.firstTargetPct) add("first attempt target = $firstTargetPct%")
            if (retryMarginPct != d.retryMarginPct) add("retry target = $retryMarginPct%")
            if (maxAttempts != d.maxAttempts) add("max attempts = $maxAttempts")
            if (audioKbps != d.audioKbps) add("audio = $audioKbps kbps")
            if (minAudioKbps != d.minAudioKbps) add("min audio = $minAudioKbps kbps")
            if (minVideoKbps != d.minVideoKbps) add("min video = $minVideoKbps kbps")
            if (normalBpp != d.normalBpp) add("detail threshold = $normalBpp")
            if (fastestBpp != d.fastestBpp) add("Fastest detail threshold = $fastestBpp")
            if (lengthGraceSec != d.lengthGraceSec) add("length grace = $lengthGraceSec s")
            if (trimToleranceSec != d.trimToleranceSec) add("trim tolerance = $trimToleranceSec s")
            if (audioAnalysisAttempt != d.audioAnalysisAttempt) add("audio analysis from attempt $audioAnalysisAttempt")
        }
    }
}

/** Saves and loads [Tuning]. [current] is what the app uses; the screen reads it as live state. */
object TuningStore {
    private const val PREFS = "webmchan_tuning"

    var current by mutableStateOf(Tuning())
        private set

    /** Call once when the app starts. */
    fun load(ctx: Context) {
        val p = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val d = Tuning()
        fun dbl(key: String, def: Double) = p.getString(key, null)?.toDoubleOrNull() ?: def
        fun int(key: String, def: Int) = p.getString(key, null)?.toIntOrNull() ?: def
        current = Tuning(
            firstTargetPct = dbl("firstTargetPct", d.firstTargetPct),
            retryMarginPct = dbl("retryMarginPct", d.retryMarginPct),
            maxAttempts = int("maxAttempts", d.maxAttempts),
            audioKbps = int("audioKbps", d.audioKbps),
            minAudioKbps = int("minAudioKbps", d.minAudioKbps),
            minVideoKbps = int("minVideoKbps", d.minVideoKbps),
            normalBpp = dbl("normalBpp", d.normalBpp),
            fastestBpp = dbl("fastestBpp", d.fastestBpp),
            lengthGraceSec = dbl("lengthGraceSec", d.lengthGraceSec),
            trimToleranceSec = dbl("trimToleranceSec", d.trimToleranceSec),
            audioAnalysisAttempt = int("audioAnalysisAttempt", d.audioAnalysisAttempt),
        ).sanitized()
    }

    /** Saves [t] (forced into range first) and makes it current. Returns what was actually saved. */
    fun save(ctx: Context, t: Tuning): Tuning {
        val s = t.sanitized()
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("firstTargetPct", s.firstTargetPct.toString())
            .putString("retryMarginPct", s.retryMarginPct.toString())
            .putString("maxAttempts", s.maxAttempts.toString())
            .putString("audioKbps", s.audioKbps.toString())
            .putString("minAudioKbps", s.minAudioKbps.toString())
            .putString("minVideoKbps", s.minVideoKbps.toString())
            .putString("normalBpp", s.normalBpp.toString())
            .putString("fastestBpp", s.fastestBpp.toString())
            .putString("lengthGraceSec", s.lengthGraceSec.toString())
            .putString("trimToleranceSec", s.trimToleranceSec.toString())
            .putString("audioAnalysisAttempt", s.audioAnalysisAttempt.toString())
            .apply()
        current = s
        return s
    }

    fun reset(ctx: Context) {
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
        current = Tuning()
    }
}
