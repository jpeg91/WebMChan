// #cache
package app.webmchan

import java.io.File
import java.util.Locale

/** How many files, and how many bytes, something takes. */
data class CacheStat(val files: Int = 0, val bytes: Long = 0L) {
    operator fun plus(o: CacheStat) = CacheStat(files + o.files, bytes + o.bytes)

    /** Never below zero, so a measurement that changed underneath us can't show a negative size. */
    operator fun minus(o: CacheStat) = CacheStat((files - o.files).coerceAtLeast(0), (bytes - o.bytes).coerceAtLeast(0L))
}

/**
 * What the Cache page lists. Each kept thing is split into what a card still uses ("in use") and what
 * nothing needs any more ("unused"), because the two wipes treat them differently.
 */
data class CacheReport(
    /** The app's cache folder (the one Android's own "Clear cache" empties), by kind of file. */
    val cacheFolder: List<Pair<String, CacheStat>>,
    /** The kept pass-1 analysis of videos (reused if the same video is converted again). */
    val analysisInUse: CacheStat,
    val analysisUnused: CacheStat,
    val thumbsInUse: CacheStat,
    val thumbsUnused: CacheStat,
    /** Copies of videos shared into the app. */
    val sharedInUse: CacheStat,
    val sharedUnused: CacheStat,
    /** The saved queue, Finished list, History and conversion logs. Not a cache: only Reset app removes them. */
    val savedData: CacheStat,
) {
    val cacheFolderTotal: CacheStat get() = cacheFolder.fold(CacheStat()) { sum, row -> sum + row.second }

    /** What the safe wipe removes: the cache folder and anything no card uses. */
    val safeWipe: CacheStat get() = cacheFolderTotal + analysisUnused + thumbsUnused + sharedUnused

    /** What the full cache wipe removes: the safe wipe, plus the analysis and thumbnails cards still use. */
    val fullWipe: CacheStat get() = safeWipe + analysisInUse + thumbsInUse

    /** What Reset app removes: everything above, the shared copies cards use, and the saved lists and logs. */
    val resetApp: CacheStat get() = fullWipe + sharedInUse + savedData

    /** Everything the app keeps in its own storage (never counts the saved .webm videos). */
    val total: CacheStat get() = resetApp
}

/** Plain file helpers for the Cache page: no Android in here, so the unit tests can check them. */
object CacheTools {
    /** A file's own size, or everything under a folder. A missing path is empty. */
    fun statOf(f: File): CacheStat {
        if (!f.exists()) return CacheStat()
        if (f.isFile) return CacheStat(1, f.length())
        var files = 0
        var bytes = 0L
        f.walkTopDown().forEach { if (it.isFile) { files++; bytes += it.length() } }
        return CacheStat(files, bytes)
    }

    /** Deletes everything inside [dir], keeping the folder itself. */
    fun clearFolder(dir: File) {
        dir.listFiles()?.forEach { if (it.isDirectory) it.deleteRecursively() else it.delete() }
    }

    /** "0 B", "850 B", "12 KB", "3.4 MB", "1.25 GB". */
    fun formatSize(bytes: Long): String {
        val b = bytes.coerceAtLeast(0L)
        return when {
            b < 1024L -> "$b B"
            b < 1024L * 1024L -> String.format(Locale.US, "%.0f KB", b / 1024.0)
            b < 1024L * 1024L * 1024L -> String.format(Locale.US, "%.1f MB", b / 1048576.0)
            else -> String.format(Locale.US, "%.2f GB", b / 1073741824.0)
        }
    }

    /** "12 KB in 3 files" (or "nothing"). */
    fun describe(s: CacheStat): String =
        if (s.files == 0 && s.bytes == 0L) "nothing" else "${formatSize(s.bytes)} in ${s.files} file${if (s.files == 1) "" else "s"}"

    /** The cache folder's contents, sorted into the kinds of file the converter leaves there. */
    fun cacheFolderRows(cacheDir: File): List<Pair<String, CacheStat>> {
        var staged = CacheStat()
        var output = CacheStat()
        var logs = CacheStat()
        var other = CacheStat()
        cacheDir.listFiles()?.forEach {
            val s = statOf(it)
            when {
                it.name.startsWith("webmchan-input-") -> staged += s
                it.name == "out.webm" || it.name == "out-best.webm" || it.name == "audioprobe.webm" -> output += s
                it.name.startsWith("ffpass") -> logs += s
                else -> other += s
            }
        }
        return listOf(
            "Staged copies of source videos" to staged,
            "Encoder output and audio probes" to output,
            "Pass-1 logs" to logs,
            "Other cache files" to other,
        )
    }

    /** Thumbnails split by whether the card (its id is the file's name) still exists. */
    fun thumbsUsage(thumbsDir: File, keepIds: Set<Long>): Pair<CacheStat, CacheStat> {
        var inUse = CacheStat()
        var unused = CacheStat()
        thumbsDir.listFiles()?.forEach {
            val id = it.nameWithoutExtension.toLongOrNull()
            if (id != null && id in keepIds) inUse += statOf(it) else unused += statOf(it)
        }
        return inUse to unused
    }

    /** Shared-video copies (one folder each) split by whether a card still points at the folder. */
    fun sharedUsage(sharedDir: File, keepFolders: Set<String>): Pair<CacheStat, CacheStat> {
        var inUse = CacheStat()
        var unused = CacheStat()
        sharedDir.listFiles()?.forEach {
            if (it.canonicalPath in keepFolders) inUse += statOf(it) else unused += statOf(it)
        }
        return inUse to unused
    }
}
