package app.webmchan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Checks of the editor's cut and crop logic. Plain JVM tests: no phone needed (./gradlew test). */
class EditSpecTest {
    private val src = SourceInfo(
        durationSec = 60.0, width = 1920, height = 1080, fps = 30.0, hasAudio = true, videoKbps = 8000,
    )

    private fun spec(vararg c: Pair<Double, Double>, crop: CropRect? = null) =
        EditSpec(c.map { EditClip(it.first, it.second) }, crop)

    @Test fun wholeVideoIsNoOp() {
        assertTrue(EditSpec.whole(src).isNoOp(src))
        assertFalse(EditSpec.whole(src).withCrop(CropRect(0, 0, 100, 100)).isNoOp(src))
        assertFalse(spec(0.0 to 30.0).isNoOp(src))
    }

    @Test fun splitMakesTwoClipsAndRespectsLimits() {
        val whole = EditSpec.whole(src)
        val cut = whole.split(0, 20.0)!!
        assertEquals(listOf(EditClip(0.0, 20.0), EditClip(20.0, 60.0)), cut.clips)
        // Too close to either end.
        assertNull(whole.split(0, 0.3))
        assertNull(whole.split(0, 59.8))
        // Too many clips.
        val eight = EditSpec((0 until EditSpec.MAX_CLIPS).map { EditClip(it * 5.0, it * 5.0 + 4.0) })
        assertNull(eight.split(0, 2.0))
    }

    @Test fun deleteKeepsAtLeastOneClip() {
        assertNull(EditSpec.whole(src).delete(0))
        assertEquals(listOf(EditClip(20.0, 60.0)), spec(0.0 to 20.0, 20.0 to 60.0).delete(0)!!.clips)
    }

    @Test fun moveReorders() {
        val m = spec(0.0 to 10.0, 20.0 to 30.0, 40.0 to 50.0).move(2, -1)!!
        assertEquals(listOf(EditClip(0.0, 10.0), EditClip(40.0, 50.0), EditClip(20.0, 30.0)), m.clips)
        assertNull(spec(0.0 to 10.0, 20.0 to 30.0).move(0, -1))
        assertNull(spec(0.0 to 10.0, 20.0 to 30.0).move(1, 1))
    }

    @Test fun slicesFollowTheEditedTimelineAcrossClips() {
        // Edited video: source 20-40, then 0-10, then 50-60.
        val e = spec(20.0 to 40.0, 0.0 to 10.0, 50.0 to 60.0)
        assertEquals(40.0, e.totalSec, 1e-9)
        assertEquals(listOf(EditSlice(20.0, 20.0), EditSlice(0.0, 10.0)), e.slicesFor(0.0, 30.0))
        // A part that runs across all three.
        assertEquals(listOf(EditSlice(35.0, 5.0), EditSlice(0.0, 10.0), EditSlice(50.0, 5.0)), e.slicesFor(15.0, 20.0))
        // A part inside one clip.
        assertEquals(listOf(EditSlice(25.0, 10.0)), e.slicesFor(5.0, 10.0))
        // Outside the edit.
        assertTrue(e.slicesFor(100.0, 10.0).isEmpty())
    }

    @Test fun locateFindsClipAndOffset() {
        val e = spec(20.0 to 40.0, 0.0 to 10.0, 50.0 to 60.0)
        assertEquals(0 to 0.0, e.locate(0.0))
        assertEquals(1 to 0.0, e.locate(20.0))
        assertEquals(1 to 5.0, e.locate(25.0))
        // Past the end: the end of the last clip.
        assertEquals(2 to 10.0, e.locate(99.0))
        assertEquals(20.0, e.editedStartOf(1), 1e-9)
    }

    @Test fun trimStaysInsideTheSpaceBesideOtherClips() {
        val e = spec(0.0 to 10.0, 20.0 to 60.0)
        // The first clip may grow into the gap up to the next clip's start...
        assertEquals(EditClip(0.0, 15.0), e.trim(0, 0.0, 15.0, 60.0)!!.clips[0])
        assertEquals(EditClip(0.0, 20.0), e.trim(0, 0.0, 45.0, 60.0)!!.clips[0])
        // ...and the second back into it, down to the end of the first.
        assertEquals(EditClip(10.0, 60.0), e.trim(1, 5.0, 60.0, 60.0)!!.clips[1])
        // Never shorter than the minimum, whichever end is moved.
        assertEquals(EditClip(9.5, 10.0), spec(0.0 to 10.0).trim(0, 9.9, 10.0, 60.0)!!.clips[0])
        assertEquals(EditClip(0.0, 0.5), spec(0.0 to 10.0).trim(0, 0.0, 0.1, 60.0)!!.clips[0])
    }

    @Test fun effectiveInfoUsesEditedLengthAndCropSize() {
        val e = spec(0.0 to 10.0, 30.0 to 50.0, crop = CropRect(0, 0, 960, 540))
        val eff = e.effectiveInfo(src)
        assertEquals(30.0, eff.durationSec, 1e-9)
        assertEquals(960, eff.width)
        assertEquals(540, eff.height)
        // A quarter of the area: the bitrate scales by 0.25^0.75.
        assertEquals(2828, eff.videoKbps)
        // Unknown stays unknown.
        assertEquals(0, e.effectiveInfo(src.copy(videoKbps = 0)).videoKbps)
        // No crop: the size is untouched.
        assertEquals(1920, spec(0.0 to 10.0).effectiveInfo(src).width)
    }

    @Test fun cleanCropMakesWholeEvenPixelsInsideThePicture() {
        assertEquals(CropRect(0, 2, 100, 50), EditSpec.cleanCrop(1, 3, 101, 51, 200, 100))
        assertEquals(CropRect(10, 10, 32, 32), EditSpec.cleanCrop(10, 10, 5, 5, 200, 100))
        assertEquals(CropRect(100, 40, 100, 60), EditSpec.cleanCrop(190, 90, 100, 60, 200, 100))
        // The whole picture is no crop at all.
        assertNull(EditSpec.cleanCrop(0, 0, 200, 100, 200, 100))
    }

    @Test fun signatureChangesWithOrderAndCrop() {
        val a = spec(0.0 to 10.0, 20.0 to 30.0)
        val b = spec(20.0 to 30.0, 0.0 to 10.0)
        assertTrue(a.signature() != b.signature())
        assertTrue(a.signature() != a.withCrop(CropRect(0, 0, 64, 64)).signature())
        assertNotNull(a.describe())
        assertEquals("2 clips, cropped to 64×64", a.withCrop(CropRect(0, 0, 64, 64)).describe())
    }
}
