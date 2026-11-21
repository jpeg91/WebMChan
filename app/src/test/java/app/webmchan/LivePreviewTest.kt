package app.webmchan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Checks of the live preview's ffmpeg side (LivePreview): the arguments of its second output, the change it
 * makes to a filter graph, the interval's range, and how the main encoder's quantiser is still read from a
 * log that now has two video outputs. Plain JVM tests: no phone needed (./gradlew test).
 */
class LivePreviewTest {
    private val file = File("/cache/live-preview.jpg")
    private val shrink = "scale=160:160:force_original_aspect_ratio=decrease:force_divisible_by=2:flags=fast_bilinear,format=yuvj420p"

    @Test fun rateIsOneFrameEveryInterval() {
        assertEquals("1/3.00", LivePreview.rate(3.0))
        assertEquals("1/0.30", LivePreview.rate(0.3))
        assertEquals("1/12.50", LivePreview.rate(12.5))
    }

    /** Out-of-range video-time gaps are pulled back (one frame to 30 s), so a bad value can't make ffmpeg misbehave. */
    @Test fun rateStaysInRange() {
        assertEquals("1/0.03", LivePreview.rate(0.0))
        assertEquals("1/0.03", LivePreview.rate(-5.0))
        assertEquals("1/30.00", LivePreview.rate(500.0))
    }

    /** The setting is real time, ffmpeg counts video time: 3 s at the 0.08x seen on a phone is 0.245 s of video. */
    @Test fun mediaIntervalTurnsRealTimeIntoVideoTime() {
        assertEquals(0.2451, LivePreview.mediaInterval(3.0, 0.0817), 0.0001)
        // A fast encode (4x): the gap in video time is longer.
        assertEquals(12.0, LivePreview.mediaInterval(3.0, 4.0), 1e-9)
        // Never shorter than about a frame, never longer than 30 s, and a silly speed is pulled into range.
        assertEquals(LivePreview.MIN_MEDIA_SEC, LivePreview.mediaInterval(0.3, 0.01), 0.0)
        assertEquals(30.0, LivePreview.mediaInterval(30.0, 500.0), 0.0)
        assertEquals(0.03, LivePreview.mediaInterval(3.0, 0.0), 0.0)
    }

    @Test fun shrinkFitsABoxAndEndsReadyForJpeg() {
        assertEquals(shrink, LivePreview.shrinkFilters())
    }

    /** The frames are thinned first, so the real output's filters (crop, size, tone-mapping) run on the few that are kept. */
    @Test fun plainChainThinsBeforeTheRest() {
        assertEquals("fps=1/3.00,crop=640:360:0:0,scale=-2:240,$shrink", LivePreview.plainChain(3.0, "crop=640:360:0:0,scale=-2:240"))
        // Nothing to do to the picture: the "null" filter ffmpeg accepts.
        assertEquals("fps=1/3.00,null,$shrink", LivePreview.plainChain(3.0, "null"))
    }

    @Test fun plainOutputIsASecondOutputOfItsOwn() {
        val a = LivePreview.plainOutput(file, 83.3333, "null", 3.0)
        assertEquals(listOf("-map", "0:v:0", "-vf", "fps=1/3.00,null,$shrink"), a.take(4))
        // Bounded like the main output, or ffmpeg would read the source to its end.
        assertEquals("83.333", a[a.indexOf("-t") + 1])
        // One JPEG written over and over, via a temp name, ending with the file.
        assertEquals("mjpeg", a[a.indexOf("-c:v") + 1])
        assertEquals("image2", a[a.indexOf("-f") + 1])
        assertEquals("1", a[a.indexOf("-update") + 1])
        assertEquals("1", a[a.indexOf("-atomic_writing") + 1])
        assertEquals(file.absolutePath, a.last())
        // Nothing that belongs to the main output (two-pass, bitrate, sound).
        assertFalse(a.contains("-pass"))
        assertFalse(a.contains("-b:v"))
        assertTrue(a.contains("-an"))
    }

    @Test fun splitGraphKeepsTheMainOutputAndAddsABranch() {
        assertEquals(
            "[0:v:0]setpts=PTS-STARTPTS[v0];[v0]concat=n=1:v=1:a=0[cv];[cv]fps=30[vfull];" +
                "[vfull]split[vout][vsrc];[vsrc]fps=1/3.00,$shrink[vpv]",
            LivePreview.splitGraph("[0:v:0]setpts=PTS-STARTPTS[v0];[v0]concat=n=1:v=1:a=0[cv];[cv]fps=30[vout]", 3.0),
        )
    }

    /** A graph that goes on after the picture (the thumbnail's delayed sound) keeps that part, after the new branch. */
    @Test fun splitGraphKeepsLaterParts() {
        val g = LivePreview.splitGraph("[pv][tv]overlay=x=0:y=0[vout];[ca]adelay=delays=33.3:all=1[aout]", 0.3)!!
        assertTrue(g.startsWith("[pv][tv]overlay=x=0:y=0[vfull];[ca]adelay=delays=33.3:all=1[aout];[vfull]split[vout][vsrc];"))
        assertTrue(g.contains("fps=1/0.30"))
        assertEquals(1, Regex("\\[vout]").findAll(g).count())
        assertEquals(1, Regex("\\[aout]").findAll(g).count())
    }

    @Test fun splitGraphRefusesWhatItCannotSplit() {
        assertNull(LivePreview.splitGraph("[0:v:0]null[x]", 3.0))
        // Never split twice.
        val once = LivePreview.splitGraph("[0:v:0]null[vout]", 3.0)!!
        assertNull(LivePreview.splitGraph(once, 3.0))
    }

    @Test fun graphOutputMapsTheBranch() {
        val a = LivePreview.graphOutput(file, 10.0)
        assertEquals(listOf("-map", "[vpv]"), a.take(2))
        assertEquals("10.000", a[a.indexOf("-t") + 1])
        assertEquals(file.absolutePath, a.last())
    }

    // ---- Reading the main encoder's quantiser ----

    @Test fun quantiserOfASingleVideoOutputIsAsBefore() {
        val log = "Input #0...\nframe=  100 fps= 30 q=28.0 size=     256kB time=00:00:03.33 bitrate= 629.1kbits/s speed=1.0x\r" +
            "frame=  250 fps= 31 q=4.0 Lsize=     600kB time=00:00:08.33 bitrate= 590.0kbits/s speed=1.0x\n"
        assertEquals("4.0", LivePreview.mainEncoderQ(log))
    }

    /** With the preview ffmpeg prints a second q= on every progress line: it must be the first (the real video's) that counts. */
    @Test fun quantiserSkipsThePreviewsOwn() {
        val log = "frame=  100 fps= 30 q=28.0 q=8.0 size=     256kB time=00:00:03.33 bitrate= 629.1kbits/s speed=1.0x\r" +
            "frame=  250 fps= 31 q=-0.0 q=8.0 Lsize=     600kB time=00:00:08.33 bitrate= 590.0kbits/s speed=1.0x\n"
        assertEquals("-0.0", LivePreview.mainEncoderQ(log))
    }

    /** The real format: the preview's q is printed as "Lq=" on the final line. */
    @Test fun quantiserReadsTheRealFinalLine() {
        val log = "frame=  900 fps= 31 q=36.0 Lq=8.0 size=    1024kB time=00:00:30.00 bitrate= 279.6kbits/s speed=1.0x\n"
        assertEquals("36.0", LivePreview.mainEncoderQ(log))
    }

    @Test fun quantiserOfALogWithoutProgressIsNull() {
        assertNull(LivePreview.mainEncoderQ("Stream mapping:\n  Stream #0:0 -> #0:0 (h264 (native) -> vp8 (libvpx))"))
        assertNull(LivePreview.mainEncoderQ(null))
    }

    // ---- The interval setting ----

    @Test fun intervalDefaultsToThreeSeconds() {
        assertEquals(3.0, Tuning().previewIntervalSec, 0.0)
        assertTrue(Tuning().changesFromDefault().none { it.contains("preview") })
    }

    @Test fun intervalIsKeptBetweenPointThreeAndThirty() {
        assertEquals(0.3, Tuning(previewIntervalSec = 0.01).sanitized().previewIntervalSec, 0.0)
        assertEquals(0.3, Tuning(previewIntervalSec = 0.3).sanitized().previewIntervalSec, 0.0)
        assertEquals(7.5, Tuning(previewIntervalSec = 7.5).sanitized().previewIntervalSec, 0.0)
        assertEquals(30.0, Tuning(previewIntervalSec = 30.0).sanitized().previewIntervalSec, 0.0)
        assertEquals(30.0, Tuning(previewIntervalSec = 400.0).sanitized().previewIntervalSec, 0.0)
    }

    @Test fun changedIntervalIsListedForTheLog() {
        assertTrue(Tuning(previewIntervalSec = 1.0).changesFromDefault().contains("live preview interval = 1.0 s"))
    }
}
