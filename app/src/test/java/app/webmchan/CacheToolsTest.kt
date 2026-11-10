package app.webmchan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Checks of the Cache page's file helpers. Plain JVM tests: no phone needed (./gradlew test). */
class CacheToolsTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun file(dir: File, name: String, bytes: Int): File =
        File(dir, name).apply { parentFile?.mkdirs(); writeBytes(ByteArray(bytes)) }

    @Test fun statOfCountsFilesAndBytesRecursively() {
        val root = tmp.newFolder("root")
        file(root, "a.bin", 100)
        file(root, "sub/b.bin", 50)
        file(root, "sub/deeper/c.bin", 25)
        assertEquals(CacheStat(3, 175), CacheTools.statOf(root))
        assertEquals(CacheStat(1, 100), CacheTools.statOf(File(root, "a.bin")))
        assertEquals(CacheStat(), CacheTools.statOf(File(root, "missing")))
    }

    @Test fun clearFolderEmptiesItButKeepsTheFolder() {
        val root = tmp.newFolder("cache")
        file(root, "out.webm", 10)
        file(root, "pipes/x", 5)
        CacheTools.clearFolder(root)
        assertTrue(root.exists())
        assertEquals(0, root.listFiles()!!.size)
        // A folder that isn't there is fine too.
        CacheTools.clearFolder(File(root, "nope"))
    }

    @Test fun formatSizeUsesSensibleUnits() {
        assertEquals("0 B", CacheTools.formatSize(0))
        assertEquals("850 B", CacheTools.formatSize(850))
        assertEquals("12 KB", CacheTools.formatSize(12 * 1024L))
        assertEquals("3.5 MB", CacheTools.formatSize((3.5 * 1048576).toLong()))
        assertEquals("1.25 GB", CacheTools.formatSize((1.25 * 1073741824).toLong()))
        assertEquals("0 B", CacheTools.formatSize(-5))
    }

    @Test fun describeSaysNothingForEmptyAndPluralisesFiles() {
        assertEquals("nothing", CacheTools.describe(CacheStat()))
        assertEquals("1 KB in 1 file", CacheTools.describe(CacheStat(1, 1024)))
        assertEquals("2 KB in 3 files", CacheTools.describe(CacheStat(3, 2048)))
    }

    @Test fun cacheFolderRowsSortFilesIntoKinds() {
        val dir = tmp.newFolder("cacheDir")
        file(dir, "webmchan-input-123.video", 1000)
        file(dir, "out.webm", 200)
        file(dir, "out-best.webm", 300)
        file(dir, "audioprobe.webm", 40)
        file(dir, "ffpass-0.log", 7)
        file(dir, "something-else.tmp", 3)
        val rows = CacheTools.cacheFolderRows(dir).toMap()
        assertEquals(CacheStat(1, 1000), rows["Staged copies of source videos"])
        assertEquals(CacheStat(3, 540), rows["Encoder output and audio probes"])
        assertEquals(CacheStat(1, 7), rows["Pass-1 logs"])
        assertEquals(CacheStat(1, 3), rows["Other cache files"])
    }

    @Test fun thumbnailsSplitByWhetherTheCardExists() {
        val dir = tmp.newFolder("thumbs")
        file(dir, "1.jpg", 10)
        file(dir, "2.jpg", 20)
        file(dir, "junk.jpg", 5)
        val (inUse, unused) = CacheTools.thumbsUsage(dir, setOf(1L))
        assertEquals(CacheStat(1, 10), inUse)
        assertEquals(CacheStat(2, 25), unused)
    }

    @Test fun sharedCopiesSplitByWhetherACardUsesTheFolder() {
        val dir = tmp.newFolder("shared")
        file(dir, "aaa/clip.mp4", 100)
        file(dir, "bbb/other.mp4", 60)
        val keep = setOf(File(dir, "aaa").canonicalPath)
        val (inUse, unused) = CacheTools.sharedUsage(dir, keep)
        assertEquals(CacheStat(1, 100), inUse)
        assertEquals(CacheStat(1, 60), unused)
    }

    @Test fun reportTotalsFollowWhatEachWipeRemoves() {
        val r = CacheReport(
            cacheFolder = listOf("a" to CacheStat(2, 100), "b" to CacheStat(1, 50)),
            analysisInUse = CacheStat(1, 1000), analysisUnused = CacheStat(1, 500),
            thumbsInUse = CacheStat(2, 40), thumbsUnused = CacheStat(1, 10),
            sharedInUse = CacheStat(1, 10_000), sharedUnused = CacheStat(1, 2_000),
            savedData = CacheStat(3, 300),
        )
        assertEquals(CacheStat(3, 150), r.cacheFolderTotal)
        // Cache folder + unused analysis + unused thumbnails + unused shared copies.
        assertEquals(CacheStat(3 + 1 + 1 + 1, 150 + 500 + 10 + 2_000), r.safeWipe)
        // Plus the analysis and thumbnails cards still use, but not the shared copies they use.
        assertEquals(CacheStat(6 + 1 + 2, 2_660 + 1_000 + 40), r.fullWipe)
        // Plus the shared copies in use and the saved lists.
        assertEquals(CacheStat(9 + 1 + 3, 3_700 + 10_000 + 300), r.resetApp)
        assertEquals(r.resetApp, r.total)
        assertTrue(r.safeWipe.bytes < r.fullWipe.bytes && r.fullWipe.bytes < r.resetApp.bytes)
    }

    @Test fun subtractingNeverGoesNegative() {
        assertEquals(CacheStat(0, 0), CacheStat(1, 5) - CacheStat(2, 9))
        assertEquals(CacheStat(1, 4), CacheStat(3, 10) - CacheStat(2, 6))
        assertFalse((CacheStat(0, 0) - CacheStat(1, 1)).bytes < 0)
    }
}
