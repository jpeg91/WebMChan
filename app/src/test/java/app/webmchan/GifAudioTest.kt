// #gifaudiotest
package app.webmchan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GifAudioTest {
    /** A tiny GIF: one 2x2 picture per delay (in hundredths of a second), each with its own graphic control extension. */
    private fun gif(vararg delaysCs: Int, withControl: Boolean = true): ByteArray {
        val out = ArrayList<Byte>()
        fun add(vararg v: Int) = v.forEach { out.add(it.toByte()) }
        "GIF89a".forEach { out.add(it.code.toByte()) }
        add(2, 0, 2, 0, 0, 0, 0) // 2x2, no global colour table
        for (d in delaysCs) {
            if (withControl) add(0x21, 0xF9, 4, 0, d and 0xFF, d shr 8, 0, 0)
            add(0x2C, 0, 0, 0, 0, 2, 0, 2, 0, 0) // picture, no local colour table
            add(2, 2, 0x4C, 0x01, 0) // LZW size, one data sub-block, end of data
        }
        add(0x3B)
        return out.toByteArray()
    }

    @Test
    fun readsSizeFramesAndLength() {
        val info = GifReader.parse(gif(10, 10, 20))!!
        assertEquals(2, info.width)
        assertEquals(2, info.height)
        assertEquals(3, info.frames)
        assertEquals(0.4, info.durationSec, 1e-9)
        assertEquals(7.5, info.fps, 1e-9)
        assertTrue(info.animated)
    }

    @Test
    fun tooShortDelaysUseTheDefault() {
        // 0 and 1 hundredths are treated as 10, as ffmpeg and browsers do.
        assertEquals(0.2, GifReader.parse(gif(0, 1))!!.durationSec, 1e-9)
        assertEquals(0.04, GifReader.parse(gif(2, 2))!!.durationSec, 1e-9)
    }

    @Test
    fun aPictureWithNoControlBlockGetsTheDefaultDelay() {
        assertEquals(0.2, GifReader.parse(gif(5, 5, withControl = false))!!.durationSec, 1e-9)
    }

    @Test
    fun onePictureIsNotAnimated() {
        val info = GifReader.parse(gif(10))!!
        assertEquals(1, info.frames)
        assertFalse(info.animated)
    }

    @Test
    fun notAGifOrCutShort() {
        assertNull(GifReader.parse(ByteArray(40)))
        assertNull(GifReader.parse("GIF89a".toByteArray()))
        val whole = gif(10, 10)
        // Cut in the middle of the second picture: the complete one is still counted.
        assertEquals(1, GifReader.parse(whole.copyOf(whole.size - 8))!!.frames)
        assertTrue(GifReader.looksLikeGif(whole))
        assertFalse(GifReader.looksLikeGif(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0, 0)))
    }

    @Test
    fun frameRateIsTheHighestGifRoundedAndZeroWithNone() {
        val still = AudioImage("a.jpg", 100, 100)
        val slow = AudioImage("b.gif", 100, 100, animated = true, fps = 9.8, loopSec = 1.0)
        val fast = AudioImage("c.gif", 100, 100, animated = true, fps = 12.4, loopSec = 1.0)
        assertEquals(0, GifFit.frameRate(listOf(still)))
        assertEquals(0, GifFit.frameRate(emptyList()))
        assertEquals(10, GifFit.frameRate(listOf(still, slow)))
        assertEquals(12, GifFit.frameRate(listOf(slow, still, fast)))
        assertTrue(AudioSpec(listOf(still, slow)).hasGif)
        assertFalse(AudioSpec(listOf(still)).hasGif)
    }

    @Test
    fun planKeepsTheSoundFirstAndGivesTheGifTheRest() {
        // 4 MB over 60 s is about 540 kbps in all.
        val plan = GifFit.plan(4L * 1024 * 1024, 60.0, 480, 270, 10, 320)
        assertEquals(128, plan.audioKbps)
        assertTrue(plan.videoKbps in 405..420)
    }

    @Test
    fun planGivesPlentyToTheSoundOnceTheGifHasItsCap() {
        // A long limit for a short part: the GIF video stops at its cap and the sound gets the surplus (up to its own cap).
        val plan = GifFit.plan(40L * 1024 * 1024, 30.0, 320, 240, 10, 320)
        assertEquals(GifFit.videoCapKbps(320, 240, 10), plan.videoKbps)
        assertEquals(320, plan.audioKbps)
    }

    @Test
    fun planSqueezesTheSoundOnlyWhenTheVideoIsAtItsFloor() {
        val plan = GifFit.plan(1024L * 1024, 60.0, 320, 240, 10, 320)
        assertEquals(GifFit.MIN_VIDEO_KBPS, plan.videoKbps)
        assertTrue(plan.audioKbps in 105..112)
    }

    @Test
    fun lowerCutsTheVideoBeforeTheSound() {
        // 400 kbps of video, 128 of sound; 4,400,000 bytes against 4,000,000 is about 72 kbps too much.
        val next = GifFit.lower(400, 128, 4_400_000L, 4_000_000L, 60.0)!!
        assertEquals(128, next.audioKbps)
        assertTrue(next.videoKbps in 325..330)
    }

    @Test
    fun lowerTakesTheRestFromTheSoundAtTheFloor() {
        val next = GifFit.lower(30, 128, 4_400_000L, 4_000_000L, 60.0)!!
        assertEquals(GifFit.MIN_VIDEO_KBPS, next.videoKbps)
        assertTrue(next.audioKbps in 58..62)
    }

    @Test
    fun lowerGivesUpWhenTheSoundWouldBeUnlistenable() {
        assertNull(GifFit.lower(GifFit.MIN_VIDEO_KBPS, 30, 9_000_000L, 4_000_000L, 60.0))
    }

    @Test
    fun raiseSpendsRoomOnTheVideoFirstThenTheSound() {
        // About 120 kbps of room: all of it goes to the video, which is far below its cap.
        val a = GifFit.raise(300, 128, 3_000_000L, 4_000_000L, 60.0, 453, 320)!!
        assertEquals(128, a.audioKbps)
        assertTrue(a.videoKbps in 415..422)
        // Squeezed sound is restored to its good quality before the video gets anything.
        val b = GifFit.raise(24, 60, 3_000_000L, 4_000_000L, 60.0, 453, 320)!!
        assertEquals(128, b.audioKbps)
        assertTrue(b.videoKbps >= 24)
        // Hardly any room: no new try.
        assertNull(GifFit.raise(300, 128, 3_950_000L, 4_000_000L, 60.0, 453, 320))
    }

    @Test
    fun shareAndBitsPerPixel() {
        // 100 kbps for 80 s is 1,000,000 bytes: a quarter of a 4,000,000-byte limit.
        assertEquals(0.25, GifFit.videoShare(100, 80.0, 4_000_000L), 1e-9)
        assertEquals(100_000.0 / (320 * 240 * 10), GifFit.bitsPerPixel(100, 320, 240, 10), 1e-9)
    }
}
