package app.webmchan

import org.junit.Assert.assertEquals
import org.junit.Test

/** Checks of how converted files are named. Plain JVM tests: no phone needed (./gradlew test). */
class OutputNameTest {
    @Test fun singleVideoKeepsSourceNameWithMarkersAndTag() {
        val base = OutputName.baseFor("holiday.mp4", null)
        assertEquals("holiday", base)
        assertEquals(
            "holiday-AQ-WebMChan_for_Android.webm",
            OutputName.build(base, 1, 1, audioIncluded = true, effort = "Q"),
        )
    }

    @Test fun typedNameReplacesSourceNameButMarkersAndTagStillFollow() {
        val base = OutputName.baseFor("IMG_0042.mov", "my cool clip")
        assertEquals("my cool clip", base)
        assertEquals(
            "my cool clip-MF-WebMChan_for_Android.webm",
            OutputName.build(base, 1, 1, audioIncluded = false, effort = "F"),
        )
    }

    @Test fun splitPartsGetPartNumbersBeforeTheMarkers() {
        assertEquals(
            "clip-Part2-AS-WebMChan_for_Android.webm",
            OutputName.build("clip", 2, 3, audioIncluded = true, effort = "S"),
        )
        // Zero-padded from ten parts up so the files sort in order.
        assertEquals(
            "clip-Part02-MQ-WebMChan_for_Android.webm",
            OutputName.build("clip", 2, 12, audioIncluded = false, effort = "Q"),
        )
        assertEquals(
            "clip-Part12-MQ-WebMChan_for_Android.webm",
            OutputName.build("clip", 12, 12, audioIncluded = false, effort = "Q"),
        )
    }

    @Test fun sourceNameWithoutExtensionOrWithDotsIsHandled() {
        assertEquals("clip", OutputName.baseFor("clip", null))
        assertEquals("my.holiday.clip", OutputName.baseFor("my.holiday.clip.mp4", null))
    }

    @Test fun effortMarkers() {
        assertEquals("B", OutputName.effortMarker(bestQuality = true, fastest = false, fastEncode = false))
        assertEquals("F", OutputName.effortMarker(bestQuality = false, fastest = true, fastEncode = false))
        assertEquals("S", OutputName.effortMarker(bestQuality = false, fastest = false, fastEncode = true))
        assertEquals("Q", OutputName.effortMarker(bestQuality = false, fastest = false, fastEncode = false))
    }

    @Test fun everyNameEndsInWebm() {
        assertEquals(true, OutputName.build("x", 1, 1, true, "Q").endsWith(".webm"))
    }
}
