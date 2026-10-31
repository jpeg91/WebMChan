// #tuning
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
    /**
     * The most CPU threads the decoder and the encoder may each use. 0 (the default) puts no limit
     * on them, so ffmpeg uses every core. Fewer threads means less heat and less memory in use at
     * once, at the cost of a slower conversion. For phones that Android stops under a heavy load
     * (Best quality on long or large videos) even with unrestricted battery use allowed.
     */
    val maxThreads: Int = 0,
    /**
     * First attempt aims for this % of the size limit. The rest is safety margin. Can be taken
     * up to 110% for testing (a file over the limit then counts as too big and is retried).
     */
    val firstTargetPct: Double = 99.0,
    /**
     * A retry aims for this % of the limit. Lower than the first attempt: a file that has already
     * come out over has shown the encoder overshooting, so the retry leaves a bigger cushion. Can
     * be taken up to 110% for testing.
     */
    val retryMarginPct: Double = 97.5,
    /** How many encodes to try before giving up on a clip. */
    val maxAttempts: Int = 4,
    /**
     * Audio may drop this low (rather than failing the clip) when the budget is tight. Never
     * higher than the audio bitrate chosen in the Sound settings (which then is the lowest).
     */
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
     * Easy clips (logos, test patterns) can't use the whole bitrate budget: the encoder hits its
     * lowest quantiser and the file comes out far under the limit. So the frame size is also
     * chosen by predicting that point from the source (see [floorFactor]): the biggest frame size
     * whose predicted size is at most this % of the video bitrate budget is used, if that is
     * bigger than the detail threshold would pick. 0 turns the prediction off. Not used in Fastest
     * mode or with a Custom resolution.
     */
    val floorBudgetPct: Double = 70.0,
    /**
     * How the prediction is scaled: predicted kbps = source video kbps, scaled for frame size and
     * frame rate, times this. Fitted from a few test clips (2.75 to 3.97), so it's a rough guide.
     * Higher is more cautious (picks smaller frames).
     */
    val floorFactor: Double = 3.5,
    /**
     * Where each quality bracket on a queue card starts, in bits per pixel (video bitrate divided
     * by pixels per second, so frame size and frame rate are both accounted for, then adjusted for
     * frame size by [bracketSizeExponent]; the starts are set for a 240p frame). Below
     * [bracketWebAnimation] is "still image". At 240p and 30 fps, 0.026 is about 80 kbps.
     */
    val bracketWebAnimation: Double = 0.026,
    /** Start of "pro animation" (about 126 kbps at 240p and 30 fps). */
    val bracketProAnimation: Double = 0.041,
    /** Start of "old web video" (about 200 kbps at 240p and 30 fps). */
    val bracketOldWebVideo: Double = 0.065,
    /** Start of "quality" (about 400 kbps at 240p and 30 fps). */
    val bracketQuality: Double = 0.13,
    /** Start of "high quality" (about 860 kbps at 240p and 30 fps). */
    val bracketHighQuality: Double = 0.28,
    /** Start of "ultra high quality" (about 1900 kbps at 240p and 30 fps). */
    val bracketUltraQuality: Double = 0.62,
    /**
     * Larger frames get more out of each bit, so before it is compared with the bracket starts the
     * bits per pixel is multiplied by (frame pixels / a 240p frame's pixels) raised to this. 240p
     * is unchanged, which is where the bracket starts were set. At 0.5 that is about the frame
     * height / 240 (720p counts three times over). 0 turns the correction off.
     */
    val bracketSizeExponent: Double = 0.5,
    /**
     * Best quality mode only: how many encodes may be tried to get the file as close under the
     * size limit as it will go. Each one is a full two-pass encode, so this is mostly a cap on time.
     */
    val bestMaxAttempts: Int = 7,
    /**
     * Best quality mode only: a result at or above this % of the size limit (and not over it) is
     * accepted. Below it the clip is encoded again with a higher bitrate, unless the encoder
     * has shown it can't use more bits.
     */
    val bestMinFillPct: Double = 99.0,
    /**
     * Best quality mode only: every attempt aims for this % of the limit (the first one too). It sits
     * between [bestMinFillPct] and 100, so a result that lands on target is accepted at once.
     */
    val bestAimPct: Double = 99.5,
    /**
     * Best quality mode only: libvpx's effort setting (-cpu-used). 0 is the slowest and best, and
     * each step up is quicker with a little less detail per bit. Quality mode uses 2.
     */
    val bestCpuUsed: Int = 1,
    /**
     * Best quality mode only: how many frames the encoder blends into each hidden alt-ref frame
     * (-arnr-maxframes). More frames clean up noise in that reference better, at some cost in time.
     */
    val bestArnrMaxFrames: Int = 7,
    /**
     * Best quality mode only: how strongly those frames are blended (-arnr-strength, 0 to 6). It only
     * affects the hidden reference frame, never a frame that is shown. 0 turns the blending off.
     */
    val bestArnrStrength: Int = 5,
) {
    /** The same values, forced into their allowed ranges. */
    fun sanitized(): Tuning {
        // The bracket starts have to rise from one to the next.
        val web = bracketWebAnimation.coerceIn(0.001, 5.0)
        val pro = bracketProAnimation.coerceIn(web, 5.0)
        val old = bracketOldWebVideo.coerceIn(pro, 5.0)
        val quality = bracketQuality.coerceIn(old, 5.0)
        val high = bracketHighQuality.coerceIn(quality, 5.0)
        val ultra = bracketUltraQuality.coerceIn(high, 5.0)
        val sizeExp = bracketSizeExponent.coerceIn(0.0, 1.0)
        // The aim has to sit inside the accepted window, so a result that hits it is never retried.
        val minFill = bestMinFillPct.coerceIn(80.0, 100.0)
        return Tuning(
            maxThreads = maxThreads.coerceIn(0, 64),
            firstTargetPct = firstTargetPct.coerceIn(80.0, 110.0),
            retryMarginPct = retryMarginPct.coerceIn(80.0, 110.0),
            maxAttempts = maxAttempts.coerceIn(1, 8),
            minAudioKbps = minAudioKbps.coerceIn(8, 256),
            minVideoKbps = minVideoKbps.coerceIn(10, 500),
            normalBpp = normalBpp.coerceIn(0.01, 0.5),
            fastestBpp = fastestBpp.coerceIn(0.01, 1.0),
            lengthGraceSec = lengthGraceSec.coerceIn(0.0, 10.0),
            trimToleranceSec = trimToleranceSec.coerceIn(0.0, 5.0),
            floorBudgetPct = floorBudgetPct.coerceIn(0.0, 100.0),
            floorFactor = floorFactor.coerceIn(1.0, 10.0),
            bracketWebAnimation = web,
            bracketProAnimation = pro,
            bracketOldWebVideo = old,
            bracketQuality = quality,
            bracketHighQuality = high,
            bracketUltraQuality = ultra,
            bracketSizeExponent = sizeExp,
            bestMaxAttempts = bestMaxAttempts.coerceIn(1, 12),
            bestMinFillPct = minFill,
            bestAimPct = bestAimPct.coerceIn(minFill, 100.0),
            bestCpuUsed = bestCpuUsed.coerceIn(0, 3),
            bestArnrMaxFrames = bestArnrMaxFrames.coerceIn(0, 15),
            bestArnrStrength = bestArnrStrength.coerceIn(0, 6),
        )
    }

    /** One short "name = value" entry for each setting that isn't at its default (for the log). */
    fun changesFromDefault(): List<String> {
        val d = Tuning()
        return buildList {
            if (maxThreads != d.maxThreads) add("thread cap = $maxThreads")
            if (firstTargetPct != d.firstTargetPct) add("first attempt target = $firstTargetPct%")
            if (retryMarginPct != d.retryMarginPct) add("retry target = $retryMarginPct%")
            if (maxAttempts != d.maxAttempts) add("max attempts = $maxAttempts")
            if (minAudioKbps != d.minAudioKbps) add("min audio = $minAudioKbps kbps")
            if (minVideoKbps != d.minVideoKbps) add("min video = $minVideoKbps kbps")
            if (normalBpp != d.normalBpp) add("detail threshold = $normalBpp")
            if (fastestBpp != d.fastestBpp) add("Fastest detail threshold = $fastestBpp")
            if (lengthGraceSec != d.lengthGraceSec) add("length grace = $lengthGraceSec s")
            if (trimToleranceSec != d.trimToleranceSec) add("trim tolerance = $trimToleranceSec s")
            if (floorBudgetPct != d.floorBudgetPct) add("easy-clip frame size limit = $floorBudgetPct% of budget")
            if (floorFactor != d.floorFactor) add("easy-clip prediction factor = $floorFactor")
            if (bracketWebAnimation != d.bracketWebAnimation) add("web animation from $bracketWebAnimation bpp")
            if (bracketProAnimation != d.bracketProAnimation) add("pro animation from $bracketProAnimation bpp")
            if (bracketOldWebVideo != d.bracketOldWebVideo) add("old web video from $bracketOldWebVideo bpp")
            if (bracketQuality != d.bracketQuality) add("quality from $bracketQuality bpp")
            if (bracketHighQuality != d.bracketHighQuality) add("high quality from $bracketHighQuality bpp")
            if (bracketUltraQuality != d.bracketUltraQuality) add("ultra high quality from $bracketUltraQuality bpp")
            if (bracketSizeExponent != d.bracketSizeExponent) add("bracket frame size correction = $bracketSizeExponent")
            if (bestMaxAttempts != d.bestMaxAttempts) add("Best quality max attempts = $bestMaxAttempts")
            if (bestMinFillPct != d.bestMinFillPct) add("Best quality accepted from = $bestMinFillPct% of the limit")
            if (bestAimPct != d.bestAimPct) add("Best quality target = $bestAimPct% of the limit")
            if (bestCpuUsed != d.bestCpuUsed) add("Best quality effort = cpu-used $bestCpuUsed")
            if (bestArnrMaxFrames != d.bestArnrMaxFrames) add("Best quality alt-ref blend frames = $bestArnrMaxFrames")
            if (bestArnrStrength != d.bestArnrStrength) add("Best quality alt-ref blend strength = $bestArnrStrength")
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
            maxThreads = int("maxThreads", d.maxThreads),
            firstTargetPct = dbl("firstTargetPct", d.firstTargetPct),
            retryMarginPct = dbl("retryMarginPct", d.retryMarginPct),
            maxAttempts = int("maxAttempts", d.maxAttempts),
            minAudioKbps = int("minAudioKbps", d.minAudioKbps),
            minVideoKbps = int("minVideoKbps", d.minVideoKbps),
            normalBpp = dbl("normalBpp", d.normalBpp),
            fastestBpp = dbl("fastestBpp", d.fastestBpp),
            lengthGraceSec = dbl("lengthGraceSec", d.lengthGraceSec),
            trimToleranceSec = dbl("trimToleranceSec", d.trimToleranceSec),
            floorBudgetPct = dbl("floorBudgetPct", d.floorBudgetPct),
            floorFactor = dbl("floorFactor", d.floorFactor),
            bracketWebAnimation = dbl("bracketWebAnimation", d.bracketWebAnimation),
            bracketProAnimation = dbl("bracketProAnimation", d.bracketProAnimation),
            bracketOldWebVideo = dbl("bracketOldWebVideo", d.bracketOldWebVideo),
            bracketQuality = dbl("bracketQualityFrom", d.bracketQuality),
            bracketHighQuality = dbl("bracketHighQualityFrom", d.bracketHighQuality),
            bracketUltraQuality = dbl("bracketUltraQuality", d.bracketUltraQuality),
            bracketSizeExponent = dbl("bracketSizeExponent", d.bracketSizeExponent),
            bestMaxAttempts = int("bestMaxAttempts", d.bestMaxAttempts),
            bestMinFillPct = dbl("bestMinFillPct", d.bestMinFillPct),
            bestAimPct = dbl("bestAimPct", d.bestAimPct),
            bestCpuUsed = int("bestCpuUsed", d.bestCpuUsed),
            bestArnrMaxFrames = int("bestArnrMaxFrames", d.bestArnrMaxFrames),
            bestArnrStrength = int("bestArnrStrength", d.bestArnrStrength),
        ).sanitized()
    }

    /** Saves [t] (forced into range first) and makes it current. Returns what was actually saved. */
    fun save(ctx: Context, t: Tuning): Tuning {
        val s = t.sanitized()
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("maxThreads", s.maxThreads.toString())
            .putString("firstTargetPct", s.firstTargetPct.toString())
            .putString("retryMarginPct", s.retryMarginPct.toString())
            .putString("maxAttempts", s.maxAttempts.toString())
            .putString("minAudioKbps", s.minAudioKbps.toString())
            .putString("minVideoKbps", s.minVideoKbps.toString())
            .putString("normalBpp", s.normalBpp.toString())
            .putString("fastestBpp", s.fastestBpp.toString())
            .putString("lengthGraceSec", s.lengthGraceSec.toString())
            .putString("trimToleranceSec", s.trimToleranceSec.toString())
            .putString("floorBudgetPct", s.floorBudgetPct.toString())
            .putString("floorFactor", s.floorFactor.toString())
            .putString("bracketWebAnimation", s.bracketWebAnimation.toString())
            .putString("bracketProAnimation", s.bracketProAnimation.toString())
            .putString("bracketOldWebVideo", s.bracketOldWebVideo.toString())
            .putString("bracketQualityFrom", s.bracketQuality.toString())
            .putString("bracketHighQualityFrom", s.bracketHighQuality.toString())
            .putString("bracketUltraQuality", s.bracketUltraQuality.toString())
            .putString("bracketSizeExponent", s.bracketSizeExponent.toString())
            .putString("bestMaxAttempts", s.bestMaxAttempts.toString())
            .putString("bestMinFillPct", s.bestMinFillPct.toString())
            .putString("bestAimPct", s.bestAimPct.toString())
            .putString("bestCpuUsed", s.bestCpuUsed.toString())
            .putString("bestArnrMaxFrames", s.bestArnrMaxFrames.toString())
            .putString("bestArnrStrength", s.bestArnrStrength.toString())
            .apply()
        current = s
        return s
    }

    fun reset(ctx: Context) {
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
        current = Tuning()
    }
}