// #batchrunner
package app.webmchan

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import kotlin.math.min
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

enum class ItemStatus { WAITING, CONVERTING, DONE, FAILED, CANCELLED }

/** A pause that has been asked for but hasn't happened yet. */
enum class PauseRequest { NONE, AFTER, NOW }

/** One video in the batch. Immutable: update by replacing the list entry with a copy. */
data class QueueItem(
    val id: Long,
    val uri: Uri,
    val name: String,
    /** null until probed (see [probeFailed]). */
    val info: SourceInfo? = null,
    val probeFailed: Boolean = false,
    val status: ItemStatus = ItemStatus.WAITING,
    /** Result or error text shown under the item. */
    val detail: String? = null,
    /** Where the converted file was saved, once done. */
    val savedUri: Uri? = null,
    /**
     * Split videos only: how many parts have already been converted and saved, and how many there
     * are in total. Lets Convert carry on from the next part after a failure, skip or app restart
     * instead of redoing (and duplicating) the parts that are already saved. 0 / 0 = not split.
     */
    val partsDone: Int = 0,
    val partsTotal: Int = 0,
    /**
     * Splits added on the card on top of the minimum the board's length limit needs (see
     * [Converter.partsFor]). Stored as an offset, so it stays meaningful if the board is changed.
     */
    val extraParts: Int = 0,
    /** Whether a thumbnail file exists for this card (see [Thumbnails]). */
    val hasThumb: Boolean = false,
    /**
     * The settings this video is converted with, fixed when it is sent from the Holding Pen to the
     * queue and kept (through failures, cancels and restarts of the app) until it goes back to the
     * pen, where it follows the settings on screen again. null in the pen. The Advanced tuning is
     * not part of it (that stays global; the run supplies it).
     */
    val settings: Settings? = null,
    /**
     * In the Holding Pen: added to the app but not yet sent to the queue. The button on the main
     * screen moves everything ready from the pen to the queue (starting the batch if none is running).
     */
    val inPen: Boolean = false,
)

/**
 * Thread-safe, size-capped log. ffmpeg's repeating "frame=..." status line updates in place
 * instead of flooding the readout.
 */
private class LogBuffer(private val maxLines: Int = 400) {
    private val lock = Any()
    private var lines: List<String> = emptyList()

    // The complete log of the video being converted, kept for the Log page. The readout above is
    // capped at the last few hundred lines; this holds far more. Over [CAPTURE_MAX] lines the
    // oldest of the middle are dropped (the first [CAPTURE_HEAD] lines, which say what the video
    // and settings were, are always kept).
    private var capture: ArrayList<String>? = null
    private var captureDropped = 0

    fun clear(publish: (List<String>) -> Unit) = synchronized(lock) {
        lines = emptyList()
        publish(lines)
    }

    /** Starts keeping everything logged from now on (see [endCapture]). */
    fun beginCapture() = synchronized(lock) {
        capture = ArrayList()
        captureDropped = 0
    }

    /** Stops keeping and returns what was logged since [beginCapture]. */
    fun endCapture(): List<String> = synchronized(lock) {
        val kept = capture
        capture = null
        if (kept == null) {
            emptyList()
        } else {
            if (captureDropped > 0) {
                kept.add(
                    CAPTURE_HEAD.coerceAtMost(kept.size),
                    "… $captureDropped lines left out here to keep this log to a size that can be saved …"
                )
            }
            kept
        }
    }

    fun add(raw: String, publish: (List<String>) -> Unit) = synchronized(lock) {
        var next = lines
        for (piece in raw.split('\n', '\r')) {
            val line = piece.trim()
            if (line.isEmpty()) continue
            val inPlace = line.startsWith("frame=")
            next = if (inPlace && next.lastOrNull()?.startsWith("frame=") == true) {
                next.dropLast(1) + line
            } else {
                next + line
            }
            capture?.let { kept ->
                if (inPlace && kept.lastOrNull()?.startsWith("frame=") == true) {
                    kept[kept.size - 1] = line
                } else {
                    kept.add(line)
                }
                if (kept.size > CAPTURE_MAX) {
                    kept.subList(CAPTURE_HEAD, CAPTURE_HEAD + CAPTURE_CHUNK).clear()
                    captureDropped += CAPTURE_CHUNK
                }
            }
        }
        lines = if (next.size > maxLines) next.takeLast(maxLines) else next
        publish(lines)
    }

    private companion object {
        const val CAPTURE_MAX = 4000
        const val CAPTURE_HEAD = 300
        const val CAPTURE_CHUNK = 500
    }
}

/**
 * Owns the batch: the queue, the finished list, progress, the log and the conversion loop.
 *
 * It lives for as long as the app's process does, not as long as the screen does, so a batch
 * keeps running if the activity is closed, and reopening the app shows the current state.
 * [ConvertService] keeps the process alive (foreground service + wake lock) while it runs.
 *
 * All state is Compose snapshot state, so the UI just reads it.
 */
object BatchRunner {
    /** Videos waiting to be converted (also failed / skipped ones, so Convert can retry them). */
    val queue = mutableStateListOf<QueueItem>()

    /** Videos converted and saved. Stays until the user clears it. */
    val finished = mutableStateListOf<QueueItem>()

    var busy by mutableStateOf(false)
        private set

    /** Number of probe passes currently running (new videos are probed one after another). */
    var probing by mutableIntStateOf(0)
        private set

    /**
     * Shared videos still being copied into app storage (see [addShared]): how many in this share,
     * and how many are done. 0 means nothing is being copied, and the UI shows nothing.
     */
    var copyingTotal by mutableIntStateOf(0)
        private set
    var copyingDone by mutableIntStateOf(0)
        private set

    /** < 0 means indeterminate (pass 1). */
    var progress by mutableFloatStateOf(0f)
        private set
    var status by mutableStateOf("")
        private set
    var summary by mutableStateOf<String?>(null)
        private set

    /** How many videos the current (or last) run is working through. */
    var runTotal by mutableIntStateOf(0)
        private set

    /**
     * How many parts the video being converted is split into (1 = not split), or 0 when nothing is
     * converting. The screen uses it to offer "Skip part" for a split video.
     */
    var currentParts by mutableIntStateOf(0)
        private set
    var logLines by mutableStateOf<List<String>>(emptyList())
        private set

    /**
     * The last few conversions with their complete logs, oldest first, for the Log page
     * (see [ConversionLogStore]). Unlike [logLines] these survive a new batch starting.
     */
    val conversionLogs = mutableStateListOf<ConversionLog>()

    /** True from Cancel all until the run has stopped (the UI stops accepting new videos then). */
    var cancelling by mutableStateOf(false)
        private set

    /** The batch exists (busy is true) but nothing is converting: paused by the user, or restored after the app stopped. */
    var paused by mutableStateOf(false)
        private set

    /** A pause that has been asked for: Pause Now, or Pause After This Conversion. */
    var pauseRequest by mutableStateOf(PauseRequest.NONE)
        private set

    /** A message for the user about the paused batch (for example after the app was stopped). */
    var notice by mutableStateOf<String?>(null)
        private set

    /** True while [runBatch] is running in this process (false for a batch restored paused after a restart). */
    private var loopAlive = false

    /**
     * The ids the current run works through, in order. Videos added while it runs are appended
     * (see [pickUpNewVideos]); one removed before its turn is dropped. Main thread only.
     */
    private val runList = ArrayList<Long>()

    /** The settings the current run started with. Videos that join it mid-run get these. */
    private var runSettings: Settings? = null

    /** Set by the activity: whether the app screen is visible (no "finished" notification if so). */
    @Volatile
    var uiVisible = false

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val ids = AtomicLong(0)
    private val skipFlag = AtomicBoolean(false)

    /** With [skipFlag]: the skip is for the part being encoded only, not for the whole video. */
    private val skipPartFlag = AtomicBoolean(false)
    private val cancelAllFlag = AtomicBoolean(false)
    private val logBuffer = LogBuffer()
    private var lastNotifyMs = 0L

    // ---- Saving and restoring (so the lists survive the app being closed or killed) ----

    private var appContext: Context? = null
    private var restored = false

    // One thread, so saves are written in the order they were requested.
    private val writer = Executors.newSingleThreadExecutor()

    /**
     * Brings back the queue and Finished list saved by a previous run. Call once when the app
     * starts; later calls (for example the screen being recreated) do nothing.
     */
    fun restore(ctx: Context) {
        val appCtx = ctx.applicationContext
        appContext = appCtx
        if (restored) return
        restored = true

        // A fresh process means nothing is converting: clear working files (staged source copies,
        // encoder output) that a run the system killed part-way left in the cache.
        Converter.clearWorkFiles(appCtx)

        // The Log page's saved conversions (read off the main thread: they can be big).
        scope.launch(Dispatchers.IO) { conversionLogs.addAll(ConversionLogStore.load(appCtx)) }

        val loaded = BatchStore.load(appCtx) ?: run { cleanOrphanCopies(appCtx); return }
        ids.set(maxOf(ids.get(), loaded.lastId))
        // Every queued video has to carry its own settings. One saved by an older version has none,
        // so it goes back to the Holding Pen, where it follows the settings on screen until it is
        // sent to the queue again.
        var sentBackToPen = 0
        val state = loaded.copy(
            queue = loaded.queue.map {
                if (!it.inPen && it.settings == null) {
                    sentBackToPen++
                    it.copy(inPen = true, status = ItemStatus.WAITING, detail = null)
                } else it
            }
        )
        // A batch that was running when the app stopped comes back paused (see resume), as long as
        // some of its videos still need converting.
        val interrupted = state.runActive && state.queue.any {
            !it.inPen && it.info != null && (it.status == ItemStatus.WAITING || it.status == ItemStatus.CONVERTING)
        }
        queue.addAll(
            state.queue.map {
                if (!interrupted) {
                    // No batch to come back to. Each queued video keeps its own settings. Whatever was
                    // being converted when the app stopped has to start again from the top.
                    val plain = it
                    if (plain.status == ItemStatus.CONVERTING) {
                        plain.copy(
                            status = ItemStatus.CANCELLED,
                            detail = if (plain.partsDone > 0 && plain.partsDone < plain.partsTotal) {
                                "Interrupted when the app closed (${plain.partsDone} of ${plain.partsTotal} parts saved). " +
                                    "Press Convert to continue from part ${plain.partsDone + 1}."
                            } else "Interrupted when the app closed. Press Convert to try again.",
                        )
                    } else plain
                } else if (it.status == ItemStatus.CONVERTING) {
                    // Starts again when the batch is resumed.
                    it.copy(
                        status = ItemStatus.WAITING,
                        detail = if (it.partsDone > 0 && it.partsDone < it.partsTotal) {
                            "Interrupted when the app closed (${it.partsDone} of ${it.partsTotal} parts saved). " +
                                "Press Resume to continue from part ${it.partsDone + 1}."
                        } else "Interrupted when the app closed. Press Resume to start it again from the beginning.",
                    )
                } else it
            }
        )
        finished.addAll(state.finished)
        if (interrupted) {
            busy = true
            paused = true
            runTotal = queue.count { !it.inPen && it.info != null && it.status == ItemStatus.WAITING }
            notice = "WebMChan was closed while converting, so your batch has been paused. Press Resume to carry on. " +
                "The video that was converting starts again (from its first unsaved part, if it was split)."
        }
        if (sentBackToPen > 0) {
            val note = "$sentBackToPen queued video(s) saved by an older version had no settings of their own, " +
                "so they went back to the Holding Pen. Send them to the queue again with the settings you want."
            notice = notice?.let { "$it\n\n$note" } ?: note
        }
        cleanOrphanCopies(appCtx)
        scope.launch { verifyRestored(appCtx) }
    }

    /** Writes the current lists to storage (in the background). */
    private fun persist() {
        val ctx = appContext ?: return
        val q = queue.toList()
        val f = finished.toList()
        val lastId = ids.get()
        val running = busy
        writer.execute { BatchStore.save(ctx, q, f, lastId, running) }
    }

    private fun canOpen(ctx: Context, uri: Uri): Boolean = try {
        ctx.contentResolver.openFileDescriptor(uri, "r")?.use { true } ?: false
    } catch (_: Exception) {
        false
    }

    /**
     * After a restore: check each queued source can still be read (re-probing any that never got
     * probed), and that each finished file is still there.
     */
    private suspend fun verifyRestored(ctx: Context) {
        probing++ // keeps Convert disabled until the checks are done
        try {
            val toProbe = mutableListOf<Long>()
            for (id in queue.map { it.id }) {
                val item = queue.firstOrNull { it.id == id } ?: continue
                if (item.probeFailed) continue
                val ok = withContext(Dispatchers.IO) { canOpen(ctx, item.uri) }
                val at = queue.indexOfFirst { it.id == id }
                if (at < 0) continue
                if (!ok) {
                    queue[at] = queue[at].copy(
                        info = null,
                        probeFailed = true,
                        status = ItemStatus.FAILED,
                        detail = "This video isn't accessible any more. Remove it and add it again.",
                    )
                } else if (item.info == null) {
                    toProbe.add(id)
                }
            }
            for (id in toProbe) {
                val item = queue.firstOrNull { it.id == id } ?: continue
                val i = Converter.probe(ctx, item.uri)
                val err = if (i == null) Converter.lastProbeError else null
                val at = queue.indexOfFirst { it.id == id }
                if (at >= 0) {
                    queue[at] = queue[at].copy(info = i, probeFailed = i == null)
                    if (err != null) queue[at] = queue[at].copy(status = ItemStatus.FAILED, detail = err)
                }
            }

            // Thumbnails for queued videos that don't have one (or lost the file).
            for (id in queue.map { it.id }) {
                val item = queue.firstOrNull { it.id == id } ?: continue
                val info = item.info ?: continue
                if (item.status == ItemStatus.FAILED && item.probeFailed) continue
                if (item.hasThumb && Thumbnails.exists(ctx, id)) continue
                setThumb(ctx, id, item.uri, null, info.durationSec, queue)
            }

            // Finished files might have been deleted or moved outside the app (or the SD card
            // removed). Keep the card, but drop its Share button and say why.
            for (id in finished.map { it.id }) {
                val item = finished.firstOrNull { it.id == id } ?: continue
                val saved = item.savedUri ?: continue
                val ok = withContext(Dispatchers.IO) { canOpen(ctx, saved) }
                if (ok) continue
                val at = finished.indexOfFirst { it.id == id }
                if (at >= 0) {
                    finished[at] = finished[at].copy(
                        savedUri = null,
                        detail = (item.detail?.let { "$it\n" } ?: "") + "The saved file isn't available any more.",
                    )
                }
            }

            // Thumbnails for finished videos that don't have one (made before thumbnails existed,
            // or lost): taken from the saved file itself.
            for (id in finished.map { it.id }) {
                val item = finished.firstOrNull { it.id == id } ?: continue
                val saved = item.savedUri ?: continue
                val info = item.info ?: continue
                if (item.hasThumb && Thumbnails.exists(ctx, id)) continue
                setThumb(ctx, id, saved, null, info.durationSec, finished)
            }

            Thumbnails.pruneExcept(ctx, (queue + finished).map { it.id }.toSet())
        } finally {
            probing--
            persist()
        }
    }

    /**
     * Makes the thumbnail for card [id] (from [uri] or [file]) and marks it on the card in [list].
     * If the card was removed in the meantime, the new thumbnail is thrown away.
     */
    private suspend fun setThumb(
        ctx: Context,
        id: Long,
        uri: Uri?,
        file: File?,
        durationSec: Double,
        list: MutableList<QueueItem>,
    ) {
        val ok = Thumbnails.create(ctx, id, uri, file, durationSec)
        val at = list.indexOfFirst { it.id == id }
        if (at < 0) {
            Thumbnails.delete(ctx, id)
        } else if (list[at].hasThumb != ok) {
            list[at] = list[at].copy(hasThumb = ok)
        }
    }

    // Android only lets us read a picked video for a short while. Asking for a lasting grant is what
    // lets a restored queue still open its videos after the app has been stopped and restarted.
    private fun keepAccess(ctx: Context, uri: Uri) {
        try {
            ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: Exception) {
            // Some sources don't allow it; the video still works until the app stops.
        }
    }

    // ---- Videos shared to us from other apps ----
    // A share only grants temporary read access, which is lost when the app stops. So a shared
    // video is copied into the app's own storage first; the queue then points at that copy.

    private fun sharedDir(ctx: Context) = File(ctx.filesDir, "shared")

    /** Copies shared videos into app storage (in the background), then adds them to the queue. */
    fun addShared(ctx: Context, shared: List<Uri>) {
        val appCtx = ctx.applicationContext
        appContext = appCtx
        // A second share while one is still copying adds to the same count.
        if (copyingTotal == 0) copyingDone = 0
        copyingTotal += shared.size
        scope.launch {
            val copies = mutableListOf<Uri>()
            try {
                for (u in shared) {
                    // If a copy fails (e.g. no space), fall back to the original; it works until the app stops.
                    copies.add(withContext(Dispatchers.IO) { copyShared(appCtx, u) } ?: u)
                    copyingDone++
                }
            } finally {
                if (copies.isNotEmpty()) addVideos(appCtx, copies)
                copyingTotal -= shared.size
                if (copyingTotal <= 0) {
                    copyingTotal = 0
                    copyingDone = 0
                }
            }
        }
    }

    private fun copyShared(ctx: Context, uri: Uri): Uri? {
        val dir = File(sharedDir(ctx), java.util.UUID.randomUUID().toString())
        return try {
            val name = Converter.displayName(ctx, uri).replace(Regex("[\\\\/:*?\"<>|]"), "_").ifBlank { "video" }
            if (!dir.mkdirs()) return null
            val out = File(dir, name)
            val input = ctx.contentResolver.openInputStream(uri) ?: run { dir.deleteRecursively(); return null }
            input.use { src -> out.outputStream().use { dst -> src.copyTo(dst, 256 * 1024) } }
            Uri.fromFile(out)
        } catch (_: Exception) {
            dir.deleteRecursively()
            null
        }
    }

    /** Deletes the app-storage copy behind [uri], if it is one (does nothing for any other video). */
    private fun deleteOwnCopy(ctx: Context, uri: Uri) {
        if (uri.scheme != "file") return
        val f = File(uri.path ?: return)
        val root = sharedDir(ctx)
        val dir = f.parentFile ?: return
        if (dir.parentFile?.canonicalPath == root.canonicalPath) dir.deleteRecursively()
    }

    /** Removes copies no queue item points at (left by a crash, or a removal that didn't finish). */
    private fun cleanOrphanCopies(ctx: Context) {
        val keep = queue.mapNotNull { q -> q.uri.takeIf { it.scheme == "file" }?.path?.let { File(it).parentFile?.canonicalPath } }.toSet()
        sharedDir(ctx).listFiles()?.forEach { if (it.canonicalPath !in keep) it.deleteRecursively() }
    }

    private fun releaseAccess(ctx: Context, uri: Uri) {
        try {
            ctx.contentResolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: Exception) {
        }
    }

    /**
     * Lets go of a source video (its lasting read grant and, for a shared video, its app-storage
     * copy) once no card in the queue uses it any more. The same video can be queued several
     * times, so call this after the card has been taken out of [queue].
     */
    private fun releaseSource(ctx: Context, uri: Uri) {
        if (queue.any { it.uri == uri }) return
        releaseAccess(ctx, uri)
        deleteOwnCopy(ctx, uri)
    }

    // ---- Editing the queue (the UI disables these while a batch is running) ----

    fun addVideos(ctx: Context, picked: List<Uri>) {
        val appCtx = ctx.applicationContext
        appContext = appCtx
        // The same video may be added any number of times (e.g. to convert it again with different
        // settings): every pick becomes its own card, and cards are told apart by id, not by uri.
        val fresh = picked
        if (fresh.isEmpty()) return
        fresh.forEach { keepAccess(appCtx, it) }
        summary = null
        // New videos always wait in the Holding Pen until they are sent to the queue.
        val added = fresh.map { u -> QueueItem(ids.incrementAndGet(), u, Converter.displayName(appCtx, u), inPen = true) }
        queue.addAll(added)
        persist()
        scope.launch {
            probing++
            try {
                for (item in added) {
                    val i = Converter.probe(appCtx, item.uri)
                    val err = if (i == null) Converter.lastProbeError else null
                    // The item may have been removed while probing.
                    val at = queue.indexOfFirst { it.id == item.id }
                    if (at >= 0) {
                        queue[at] = queue[at].copy(info = i, probeFailed = i == null)
                        if (err != null) queue[at] = queue[at].copy(status = ItemStatus.FAILED, detail = err)
                    }
                    if (i != null) setThumb(appCtx, item.id, item.uri, null, i.durationSec, queue)
                }
            } finally {
                probing--
                persist()
            }
        }
    }

    /** The nearest video in [delta]'s direction that is in the same list (Holding Pen or batch), or null. */
    private fun neighbour(index: Int, delta: Int): Int? {
        if (index !in queue.indices) return null
        val pen = queue[index].inPen
        var i = index + delta
        while (i in queue.indices) {
            if (queue[i].inPen == pen) return i
            i += delta
        }
        return null
    }

    fun canMove(index: Int, delta: Int): Boolean = neighbour(index, delta) != null

    fun move(index: Int, delta: Int) {
        val to = neighbour(index, delta) ?: return
        val t = queue[index]
        queue[index] = queue[to]
        queue[to] = t
        persist()
    }

    /**
     * Sets how many splits a queued video gets on top of the minimum its board needs. Ignored
     * while a batch is running, and for a video that's already part-way through its parts (changing
     * the split then would no longer line up with the parts that are saved). The card keeps
     * [extra] within what the video allows; [Converter.partsFor] clamps it again when converting.
     */
    fun setExtraParts(id: Long, extra: Int) {
        val at = queue.indexOfFirst { it.id == id }
        if (at < 0) return
        val item = queue[at]
        if (busy && !item.inPen) return
        if (item.partsDone > 0) return
        val value = extra.coerceAtLeast(0)
        if (value == item.extraParts) return
        queue[at] = item.copy(extraParts = value)
        persist()
    }

    fun removeAt(index: Int) {
        if (index !in queue.indices) return
        val item = queue[index]
        // The video being converted can't be removed (the UI greys its button out too).
        if (busy && item.status == ItemStatus.CONVERTING) return
        // A video still waiting for its turn in the running batch: take it out of the run, so the
        // "Video n of N" count stays right. Videos already tried this run are left where they are.
        if (busy && item.status == ItemStatus.WAITING && runList.remove(item.id)) {
            runTotal = (runTotal - 1).coerceAtLeast(0)
        }
        queue.removeAt(index)
        appContext?.let {
            releaseSource(it, item.uri)
            Thumbnails.delete(it, item.id)
        }
        persist()
    }

    /** Empties the queue (not the Holding Pen). */
    fun clearQueue() {
        if (busy) return
        val removed = queue.filter { !it.inPen }
        queue.removeAll { !it.inPen }
        appContext?.let { ctx ->
            removed.forEach {
                releaseSource(ctx, it.uri)
                Thumbnails.delete(ctx, it.id)
            }
        }
        summary = null
        persist()
    }

    /**
     * Renames the saved file of the Finished card [id] to "[newBase].webm". Returns null when it
     * worked, otherwise a message to show the user. The card keeps pointing at the renamed file.
     */
    suspend fun renameFinished(ctx: Context, id: Long, newBase: String): String? {
        val base = OutputStorage.cleanBaseName(newBase) ?: return "That name can't be used."
        val saved = finished.firstOrNull { it.id == id }?.savedUri ?: return "This video's file isn't available."
        val oldName = withContext(Dispatchers.IO) { OutputStorage.fileName(ctx, saved) }
        val res = withContext(Dispatchers.IO) { OutputStorage.rename(ctx, saved, "$base.webm") }
        if (res.uri == null) return res.error
        val at = finished.indexOfFirst { it.id == id }
        if (at >= 0) {
            val cur = finished[at]
            // The card's text names the saved file ("Saved to ..."), so bring that up to date too.
            val detail = if (oldName != null && res.name != null) cur.detail?.replace(oldName, res.name) else cur.detail
            finished[at] = cur.copy(savedUri = res.uri, detail = detail)
            persist()
        }
        return null
    }

    /**
     * Deletes the converted .webm of the Finished card [id]. The card stays (without its file
     * buttons) until it is removed. Returns null when it worked, otherwise a message to show.
     */
    suspend fun deleteFinishedFile(ctx: Context, id: Long): String? {
        val saved = finished.firstOrNull { it.id == id }?.savedUri ?: return "This video's file isn't available."
        // A file that is already gone counts as deleted.
        val gone = withContext(Dispatchers.IO) { OutputStorage.delete(ctx, saved) || !canOpen(ctx, saved) }
        if (!gone) return "Couldn't delete the file."
        val at = finished.indexOfFirst { it.id == id }
        if (at >= 0) {
            val cur = finished[at]
            finished[at] = cur.copy(
                savedUri = null,
                detail = (cur.detail?.let { "$it\n" } ?: "") + "The saved file was deleted.",
            )
            persist()
        }
        return null
    }

    /** Takes one card out of the Finished list. Never touches the saved file. */
    fun removeFinished(id: Long) {
        val at = finished.indexOfFirst { it.id == id }
        if (at < 0) return
        finished.removeAt(at)
        appContext?.let { Thumbnails.delete(it, id) }
        persist()
    }

    fun clearFinished() {
        appContext?.let { ctx -> finished.forEach { Thumbnails.delete(ctx, it.id) } }
        finished.clear()
        persist()
    }

    // ---- Running ----

    /** Converts every readable video in the queue, in order, with [settings]. */
    fun start(ctx: Context, settings: Settings) {
        if (busy) return
        val appCtx = ctx.applicationContext
        appContext = appCtx
        // Everything ready in the Holding Pen goes to the queue (after anything already left there).
        movePenToQueue(settings)
        val todo = queue.filter { !it.inPen && it.info != null }.map { it.id }
        if (todo.isEmpty()) return

        summary = null
        runTotal = todo.size
        runList.clear()
        runList.addAll(todo)
        runSettings = settings
        cancelling = false
        paused = false
        pauseRequest = PauseRequest.NONE
        notice = null
        skipFlag.set(false)
        cancelAllFlag.set(false)
        busy = true
        progress = -1f // indeterminate until pass 2 reports real progress
        status = "Starting…"

        // Every video about to be tried again goes back to "Waiting", so a leftover "Cancelled",
        // "Skipped" or "Failed" message from an earlier run doesn't sit on cards the batch hasn't
        // reached yet. (Parts already saved are remembered separately in partsDone / partsTotal.)
        // Each keeps the settings it was sent to the queue with (the ones on screen only if it somehow has none).
        for (id in todo) {
            val at = queue.indexOfFirst { it.id == id }
            if (at >= 0) {
                queue[at] = queue[at].copy(status = ItemStatus.WAITING, detail = null, settings = queue[at].settings ?: settings)
            }
        }
        persist()

        logBuffer.clear { logLines = it }
        // Anything changed in About > Advanced goes at the top of the log, so a copied log says
        // which settings produced it.
        settings.tuning.changesFromDefault().takeIf { it.isNotEmpty() }?.let { changes ->
            logBuffer.add("Advanced settings in use: ${changes.joinToString("; ")}") { logLines = it }
        }

        // The foreground service keeps the process alive and the CPU awake while we work.
        // If it can't start for some reason, the batch still runs while the app is open.
        try {
            ContextCompat.startForegroundService(appCtx, Intent(appCtx, ConvertService::class.java))
        } catch (e: Exception) {
            logBuffer.add("Couldn't start the background service: ${e.message}") { logLines = it }
        }

        scope.launch { runBatch(appCtx, settings) }
    }

    /** Skips the whole video being converted (all of its remaining parts) and moves to the next one. */
    fun skipCurrent() {
        if (!busy) return
        skipPartFlag.set(false)
        skipFlag.set(true)
        Converter.cancel()
    }

    /**
     * Skips only the part being encoded and carries on with the next part of the same video. The
     * skipped part isn't saved. For a video that isn't split this is the same as [skipCurrent].
     */
    fun skipPart() {
        if (!busy) return
        skipPartFlag.set(currentParts > 1)
        skipFlag.set(true)
        Converter.cancel()
    }

    fun cancelAll() {
        if (!busy) return
        // A batch restored paused after a restart has no running loop to stop: end it here.
        if (paused && !loopAlive) {
            appContext?.let { finishWithoutLoop(it, "Cancelled") }
            return
        }
        cancelling = true
        cancelAllFlag.set(true)
        Converter.cancel()
    }


    /** Ends a batch that has no running loop (one restored paused after the app stopped). */
    private fun finishWithoutLoop(ctx: Context, text: String) {
        runSettings = null
        runList.clear()
        summary = text
        paused = false
        pauseRequest = PauseRequest.NONE
        notice = null
        cancelling = false
        busy = false
        ConvertService.clearPaused(ctx)
        persist()
    }

    /**
     * Moves the Holding Pen videos that are ready to the end of the queue (after what is already
     * there, so the queue's order is the order they will convert in), each fixed to [settings].
     * Returns their ids.
     */
    private fun movePenToQueue(settings: Settings): List<Long> {
        val moving = queue.filter { it.inPen && it.info != null }
        if (moving.isEmpty()) return emptyList()
        val movingIds = moving.map { it.id }.toHashSet()
        queue.removeAll { it.id in movingIds }
        queue.addAll(moving.map { it.copy(inPen = false, settings = settings, status = ItemStatus.WAITING, detail = null) })
        return moving.map { it.id }
    }

    /** While a batch runs (or is paused): sends the Holding Pen videos to the queue, using [settings] (the ones on screen). */
    fun addPenToRun(settings: Settings) {
        if (!busy) return
        if (movePenToQueue(settings).isEmpty()) return
        pickUpNewVideos()
        persist()
    }

    /**
     * Sends a queued video back to the Holding Pen (where its settings can be changed). It goes to
     * the top of the pen. Not for the video being converted, a split video that already has parts
     * saved, or (while a batch runs) a video the batch has already tried.
     */
    fun returnToPen(id: Long) {
        val at = queue.indexOfFirst { it.id == id }
        if (at < 0) return
        val item = queue[at]
        if (item.inPen || item.status == ItemStatus.CONVERTING || item.partsDone > 0) return
        if (busy) {
            if (item.status != ItemStatus.WAITING) return
            // Still waiting for its turn: take it out of the running batch, as removing it would.
            if (runList.remove(id)) runTotal = (runTotal - 1).coerceAtLeast(0)
        }
        // The end of the list is the top of the pen. It follows the settings on screen again.
        queue.removeAt(at)
        queue.add(item.copy(inPen = true, settings = null, status = ItemStatus.WAITING, detail = null))
        persist()
    }

    /** Removes every video from the Holding Pen. */
    fun clearPen() {
        val removed = queue.filter { it.inPen }
        queue.removeAll { it.inPen }
        appContext?.let { ctx ->
            removed.forEach {
                releaseSource(ctx, it.uri)
                Thumbnails.delete(ctx, it.id)
            }
        }
        persist()
    }

    /**
     * Pause Now: stops what is converting. That video goes back to waiting and starts again after
     * Resume (from its first unsaved part, if it is split).
     */
    fun pauseNow() {
        if (!busy || paused) return
        pauseRequest = PauseRequest.NOW
        skipFlag.set(true)
        Converter.cancel()
    }

    /** Pause After This Conversion: the video being converted finishes, then the batch pauses. Press again to undo. */
    fun pauseAfter() {
        if (!busy || paused || pauseRequest == PauseRequest.NOW) return
        pauseRequest = if (pauseRequest == PauseRequest.AFTER) PauseRequest.NONE else PauseRequest.AFTER
    }

    /**
     * Carries on a paused batch. [fallback] only matters for a batch restored after the app
     * stopped, for any video without settings of its own.
     */
    fun resume(ctx: Context, fallback: Settings) {
        if (!busy || !paused) return
        val appCtx = ctx.applicationContext
        appContext = appCtx
        notice = null
        ConvertService.clearPaused(appCtx)
        // The foreground service was stopped while paused; bring it back.
        try {
            ContextCompat.startForegroundService(appCtx, Intent(appCtx, ConvertService::class.java))
        } catch (e: Exception) {
            logBuffer.add("Couldn't start the background service: ${e.message}") { logLines = it }
        }
        if (loopAlive) {
            // The loop is waiting in holdWhilePaused and carries on by itself.
            paused = false
            return
        }
        // Restored after the app stopped: nothing is running, so start the batch again.
        val todo = queue.filter { !it.inPen && it.info != null && it.status == ItemStatus.WAITING }.map { it.id }
        if (todo.isEmpty()) {
            finishWithoutLoop(appCtx, "Nothing left to convert.")
            return
        }
        runList.clear()
        runList.addAll(todo)
        runTotal = todo.size
        val base = fallback.copy(tuning = TuningStore.current)
        runSettings = base
        pauseRequest = PauseRequest.NONE
        skipFlag.set(false)
        cancelAllFlag.set(false)
        cancelling = false
        paused = false
        progress = -1f
        status = "Starting…"
        scope.launch { runBatch(appCtx, base) }
    }

    /** Waits (without converting) until resumed or cancelled. Called by [runBatch] between videos. */
    private suspend fun holdWhilePaused(ctx: Context) {
        pauseRequest = PauseRequest.NONE
        skipFlag.set(false)
        paused = true
        logBuffer.add("=== Paused ===") { logLines = it }
        // Nothing is converting, so the foreground service (and its wake lock) isn't needed. The
        // state is saved, so if Android stops the app meanwhile the batch comes back paused.
        try {
            ctx.stopService(Intent(ctx, ConvertService::class.java))
        } catch (_: Exception) {
        }
        ConvertService.clearProgress(ctx)
        ConvertService.postPaused(ctx, "Open WebMChan and press Resume to carry on.")
        persist()
        while (paused && !cancelAllFlag.get()) delay(200)
        if (!cancelAllFlag.get()) logBuffer.add("=== Resumed ===") { logLines = it }
    }

    /**
     * Adds videos that joined the queue while the run was going (a share, say) to the end of the
     * run. Only brand-new, readable, waiting videos qualify: anything already tried this run
     * (done, failed, skipped) is in [runList] already and isn't picked up again.
     */
    private fun pickUpNewVideos() {
        val known = runList.toHashSet()
        val joining = queue
            .filter { !it.inPen && it.id !in known && it.info != null && it.status == ItemStatus.WAITING }
            .map { it.id }
        runList.addAll(joining)
        runTotal = runList.size
        // The progress text is only rewritten when the encoder reports back, which can take a while
        // in pass 1, so bring its "Video n of N" up to date now.
        status = Regex("^Video (\\d+) of \\d+").replace(status) { "Video ${it.groupValues[1]} of $runTotal" }
        if (joining.isNotEmpty()) persist()
    }

    private suspend fun runBatch(ctx: Context, startSettings: Settings) {
        var okCount = 0
        var failCount = 0
        var skipCount = 0
        var partsSkippedTotal = 0 // parts skipped with "Skip part", across the whole run
        var processed = 0 // videos actually started so far, for the "Video n of N" label
        var n = -1
        loopAlive = true
        try {
            while (true) {
                n++
                if (n >= runList.size) break
                // A pause holds here: Pause Now right away, Pause After This Conversion once the
                // video that was converting has finished.
                if (pauseRequest != PauseRequest.NONE && !cancelAllFlag.get()) holdWhilePaused(ctx)
                if (cancelAllFlag.get()) break
                val id = runList[n]
                val idx = queue.indexOfFirst { it.id == id }
                if (idx < 0) continue
                val item = queue[idx]
                val src = item.info ?: continue
                // This video's own settings (the Advanced tuning always comes from the run).
                val settings = item.settings?.copy(tuning = startSettings.tuning) ?: startSettings

                skipFlag.set(false)
                skipPartFlag.set(false)
                processed++
                val current = processed
                // A function, not a fixed string: the total can grow while this video converts
                // (a video added mid-run), and the label should follow it.
                fun pos() = "Video $current of $runTotal"
                // From here to the end of this video, everything logged is kept for the Log page.
                logBuffer.beginCapture()

                // Videos longer than the target's limit are split into consecutive parts rather
                // than cut off, each converted and saved on its own. The video is divided evenly
                // (e.g. 250 s with a 120 s limit = 3 parts of ~83.3 s) so there's never a tiny
                // leftover part, and no part is longer than the effective limit (the board's limit
                // minus the length grace; see Converter.effectiveMaxSec). A tiny overshoot is trimmed
                // off the end instead of becoming a part (see Converter.partCount). Splits chosen on
                // the card (item.extraParts) are added on top of that minimum.
                val numParts = Converter.partsFor(src.durationSec, settings.maxDurationSec, settings.tuning, item.extraParts)
                val partDur = min(src.durationSec / numParts, Converter.effectiveMaxSec(settings.maxDurationSec, settings.tuning))
                currentParts = numParts
                val base = item.name.substringBeforeLast('.', item.name)

                // Carry on after the parts that are already saved, if this video was part-way
                // through. That only holds if it would be split exactly the same way now (the same
                // number of parts means identical boundaries); if the target changed since, the
                // earlier parts don't line up with the new ones, so start again from part 1.
                val resumeFrom = if (numParts > 1 && item.partsDone in 1 until numParts && item.partsTotal == numParts) {
                    item.partsDone + 1
                } else 1
                if (resumeFrom > 1) {
                    logBuffer.add("=== ${pos()}: ${item.name}: parts 1-${resumeFrom - 1} already saved, continuing from part $resumeFrom/$numParts ===") { logLines = it }
                } else if (item.partsDone > 0) {
                    logBuffer.add("=== ${pos()}: ${item.name}: split has changed since the earlier attempt, starting again from part 1 ===") { logLines = it }
                }

                queue[idx] = item.copy(
                    status = ItemStatus.CONVERTING, detail = null, savedUri = null,
                    partsDone = resumeFrom - 1, partsTotal = if (numParts > 1) numParts else 0,
                )
                persist()

                var partsSaved = resumeFrom - 1
                // The last part dealt with, saved or skipped. Convert carries on after it, so with
                // skipped parts this can be ahead of partsSaved.
                var lastPartTried = resumeFrom - 1
                var partsSkipped = 0
                var itemFailMsg: String? = null
                var itemCancelledAll = false
                var itemSkipped = false
                var itemPaused = false
                // The card text of the part saved last, for this video's entry on the Log page.
                var lastDoneDetail: String? = null

                // "Skip part": this part is dropped (nothing saved for it) and the video carries on
                // with the next one.
                fun markPartSkipped(part: Int) {
                    partsSkipped++
                    partsSkippedTotal++
                    lastPartTried = part
                    logBuffer.add("   part $part/$numParts skipped") { logLines = it }
                    val at = queue.indexOfFirst { it.id == id }
                    if (at >= 0) queue[at] = queue[at].copy(partsDone = part, partsTotal = numParts)
                    persist()
                }

                // One private copy of the source serves every part (and every attempt) of this video,
                // and is deleted as soon as the video is finished with, however that happens.
                var staged: File? = null
                try {
                    for (part in resumeFrom..numParts) {
                        if (cancelAllFlag.get()) { itemCancelledAll = true; break }
                        if (pauseRequest == PauseRequest.NOW) { itemPaused = true; break }

                        val startSec = (part - 1) * partDur
                        val clipDur = if (numParts > 1) min(partDur, src.durationSec - startSec) else null
                        val partLabel = if (numParts > 1) ", part $part/$numParts" else ""
                        val partTag = if (numParts > 1) " (part $part/$numParts)" else ""

                        skipFlag.set(false)
                        skipPartFlag.set(false)
                        progress = -1f
                        status = "${pos()}$partLabel: Starting…"
                        logBuffer.add("=== ${pos()}$partTag: ${item.name} ===") { logLines = it }
                        notifyProgress(ctx, "${pos()}$partLabel", item.name, "Starting…", force = true)

                        if (staged == null) {
                            logBuffer.add("   copying video to app cache…") { logLines = it }
                            val st = Converter.stageSource(
                                ctx, item.uri,
                                shouldStop = { skipFlag.get() || cancelAllFlag.get() },
                            ) { f ->
                                progress = f
                                status = "${pos()}: Preparing video…"
                                notifyProgress(ctx, "${pos()}$partLabel", item.name, "Preparing video…", force = false)
                            }
                            when (st) {
                                is StageResult.Ok -> staged = st.file
                                is StageResult.Fail -> { itemFailMsg = st.message; break }
                                StageResult.Cancelled -> {
                                    if (cancelAllFlag.get()) { itemCancelledAll = true; break }
                                    if (pauseRequest == PauseRequest.NOW) { itemPaused = true; break }
                                    if (skipPartFlag.get() && numParts > 1) {
                                        markPartSkipped(part)
                                        continue
                                    }
                                    itemSkipped = true
                                    break
                                }
                            }
                        }

                        val res = Converter.convert(
                            ctx, staged!!, src, settings,
                            startSec = startSec, clipDurationSec = clipDur,
                            onLog = { line -> logBuffer.add(line) { logLines = it } },
                        ) { p, s ->
                            progress = p
                            status = "${pos()}$partLabel: $s"
                            // A Skip/Cancel pressed before ffmpeg was running (e.g. while the
                            // video was being copied) would otherwise be missed.
                            if (skipFlag.get() || cancelAllFlag.get()) Converter.cancel()
                            notifyProgress(ctx, "${pos()}$partLabel", item.name, s, force = false)
                        }

                        when (res) {
                            is ConvertResult.Ok -> {
                                // Marks the audio and encoding-effort choices used, e.g. "-AQ" for
                                // audio kept at Quality, "-MF" for muted at Fastest. Split parts also
                                // get a "-Part1", "-Part2"... marker ahead of that (zero-padded when
                                // there are 10 or more, so they sort), and every name ends with the
                                // app tag: "clip-Part02-MQ-WebMChan_for_Android.webm".
                                val audioMarker = if (res.audioIncluded) "A" else "M"
                                val effortMarker = if (settings.fastest) "F" else if (settings.fastEncode) "S" else "Q"
                                val partSuffix = if (numParts > 1) "-${Converter.partName(part, numParts)}" else ""
                                val outName = "$base$partSuffix-$audioMarker$effortMarker-${Converter.OUTPUT_NAME_TAG}.webm"

                                // What the Finished card shows is the converted file itself (its own
                                // length, size and so on), not the source video it came from. Read
                                // it from the file; if that fails, work it out from what we know.
                                val probedOut = Converter.probeFile(res.file) ?: run {
                                    val encodedSec = clipDur
                                        ?: min(src.durationSec, Converter.effectiveMaxSec(settings.maxDurationSec, settings.tuning))
                                    val h = res.height
                                    SourceInfo(
                                        durationSec = encodedSec,
                                        width = (h * src.width.toDouble() / src.height / 2).toInt() * 2,
                                        height = h,
                                        fps = 0.0,
                                        fpsReadable = false,
                                        hasAudio = res.audioIncluded,
                                        fileBytes = res.bytes,
                                    )
                                }
                                // If the converted file's frame rate couldn't be read, don't guess it: the card
                                // says so and shows the rate the encoder aimed for instead.
                                val outInfo = if (probedOut.fpsReadable) probedOut else
                                    probedOut.copy(targetedFps = Converter.targetedFps(src, settings.targetFps))
                                // Each part gets its own thumbnail, from its own converted file.
                                val cardId = ids.incrementAndGet()
                                val hasThumb = Thumbnails.create(ctx, cardId, null, res.file, outInfo.durationSec)

                                val saved = withContext(Dispatchers.IO) {
                                    OutputStorage.save(ctx, res.file, outName) {
                                        Converter.saveToDownloads(ctx, res.file, outName)
                                    }
                                }
                                res.file.delete()
                                if (saved.uri == null) {
                                    Thumbnails.delete(ctx, cardId)
                                    itemFailMsg = saved.message
                                    break
                                }
                                val mb = String.format(Locale.US, "%.2f", res.bytes / 1048576.0)
                                val detail = buildString {
                                    append("Done: $mb MB, ${res.height}p, ${res.videoKbps} kbps video.\n")
                                    append(saved.message)
                                    if (res.audioDropped) append("\nSource had no audio.")
                                    if (res.attempts > 1) append("\nTook ${res.attempts} attempts to fit the size limit.")
                                }
                                lastDoneDetail = detail
                                // Each part gets its own card in Finished (a single-part conversion
                                // is unchanged: one card, same as before).
                                finished.add(
                                    QueueItem(
                                        id = cardId,
                                        uri = item.uri,
                                        name = if (numParts > 1) "${item.name}$partTag" else item.name,
                                        info = outInfo,
                                        hasThumb = hasThumb,
                                        status = ItemStatus.DONE,
                                        detail = detail,
                                        savedUri = saved.uri,
                                        // What this file was made for, shown on its Finished card.
                                        settings = settings,
                                    )
                                )
                                partsSaved++
                                lastPartTried = part
                                // Remember how far this video got (see QueueItem.partsDone).
                                if (numParts > 1) {
                                    val at = queue.indexOfFirst { it.id == id }
                                    if (at >= 0) queue[at] = queue[at].copy(partsDone = part, partsTotal = numParts)
                                }
                                persist()
                            }
                            is ConvertResult.Fail -> {
                                itemFailMsg = res.message
                                break
                            }
                            ConvertResult.Cancelled -> {
                                if (cancelAllFlag.get()) { itemCancelledAll = true; break }
                                if (pauseRequest == PauseRequest.NOW) { itemPaused = true; break }
                                if (skipPartFlag.get() && numParts > 1) {
                                    markPartSkipped(part)
                                    continue
                                }
                                itemSkipped = true
                                break
                            }
                        }
                    }
                } finally {
                    staged?.delete()
                    Converter.clearWorkFiles(ctx) // out.webm / pass logs a failed or cancelled part left behind
                }

                val now = queue.indexOfFirst { it.id == id }
                if (now >= 0) {
                    // For a split video that stopped part-way: Convert carries on from here.
                    val resumeHint = if (numParts > 1 && lastPartTried in 1 until numParts) {
                        " Press Convert to continue from part ${lastPartTried + 1}."
                    } else ""
                    when {
                        itemCancelledAll -> {
                            val note = if (resumeHint.isNotEmpty()) {
                                "Cancelled ($partsSaved of $numParts parts saved).$resumeHint"
                            } else "Cancelled."
                            queue[now] = queue[now].copy(status = ItemStatus.CANCELLED, detail = note)
                            skipCount++
                        }
                        itemPaused -> {
                            // Pause Now: back to waiting; starts again after Resume.
                            queue[now] = queue[now].copy(status = ItemStatus.WAITING, detail = null)
                        }
                        itemSkipped -> {
                            val note = if (lastPartTried > 0) {
                                "Skipped ($partsSaved of $numParts parts saved).$resumeHint"
                            } else "Skipped."
                            queue[now] = queue[now].copy(status = ItemStatus.CANCELLED, detail = note)
                            skipCount++
                        }
                        itemFailMsg != null -> {
                            val note = (if (lastPartTried > 0) "$partsSaved of $numParts parts saved before this failed.$resumeHint\n" else "") +
                                itemFailMsg
                            queue[now] = queue[now].copy(status = ItemStatus.FAILED, detail = note)
                            failCount++
                        }
                        partsSkipped > 0 && partsSaved == 0 -> {
                            // Every part was skipped, so there is nothing to show in Finished. Keep the
                            // card (like a skipped video) so it can be converted again.
                            queue[now] = queue[now].copy(
                                status = ItemStatus.CANCELLED,
                                detail = "Every part was skipped, so nothing was saved.",
                            )
                            skipCount++
                        }
                        else -> {
                            // All parts done (some may have been skipped): the card moves from the
                            // queue (the per-part cards above are what shows up in Finished).
                            if (partsSkipped > 0) {
                                logBuffer.add("=== ${item.name}: $partsSkipped of $numParts parts skipped, the rest saved ===") { logLines = it }
                            }
                            queue.removeAt(now)
                            // The source video isn't needed any more, nor its thumbnail.
                            releaseSource(ctx, item.uri)
                            Thumbnails.delete(ctx, item.id)
                            okCount++
                        }
                    }
                }
                // Keep this video's complete log for the Log page. A pause isn't an ending (the video
                // goes round again and starts a fresh log), so nothing is kept for it.
                val capturedLog = logBuffer.endCapture()
                if (!itemPaused) {
                    val outcome = when {
                        itemCancelledAll || itemSkipped -> ItemStatus.CANCELLED
                        itemFailMsg != null -> ItemStatus.FAILED
                        partsSkipped > 0 && partsSaved == 0 -> ItemStatus.CANCELLED
                        else -> ItemStatus.DONE
                    }
                    val detailText = if (outcome == ItemStatus.DONE) {
                        if (numParts > 1) {
                            "Done: $partsSaved of $numParts parts saved." + (lastDoneDetail?.let { "\nLast part: $it" } ?: "")
                        } else lastDoneDetail ?: "Done."
                    } else {
                        queue.firstOrNull { it.id == id }?.detail ?: itemFailMsg.orEmpty()
                    }
                    recordConversionLog(ctx, item, src, settings, outcome, detailText, capturedLog)
                }
                persist()
                if (itemCancelledAll) break
                if (itemPaused) {
                    // Go round again for this same video; the pause check at the top holds first.
                    n--
                    processed--
                    continue
                }
            }
        } finally {
            // The run is over. Whatever is left in the queue keeps the settings it was sent with.
            runSettings = null
            val text = buildString {
                append("Finished: $okCount done")
                if (failCount > 0) append(", $failCount failed")
                if (skipCount > 0) append(", $skipCount skipped or cancelled")
                if (partsSkippedTotal > 0) append(", $partsSkippedTotal part${if (partsSkippedTotal == 1) "" else "s"} skipped")
                if (cancelAllFlag.get()) append(" (cancelled)")
            }
            summary = text
            busy = false
            currentParts = 0
            cancelling = false
            paused = false
            pauseRequest = PauseRequest.NONE
            notice = null
            loopAlive = false
            runList.clear()
            persist() // after busy is false, so the saved copy says the batch finished cleanly
            // Remove the ongoing notification; tell the user the result if the app isn't on screen.
            try {
                ctx.stopService(Intent(ctx, ConvertService::class.java))
            } catch (_: Exception) {
            }
            ConvertService.clearProgress(ctx)
            ConvertService.clearPaused(ctx)
            if (!uiVisible) ConvertService.postFinished(ctx, text)
        }
    }

    /** Adds a finished conversion to the Logs page's list (those from the last [ConversionLogStore.KEEP_MILLIS] are kept) and saves it. */
    private fun recordConversionLog(
        ctx: Context, item: QueueItem, src: SourceInfo, settings: Settings,
        outcome: ItemStatus, detail: String, lines: List<String>,
    ) {
        val fpsText = if (src.fpsReadable) {
            String.format(Locale.US, "%.2f", src.fps).trimEnd('0').trimEnd('.') + " fps"
        } else "fps unreadable"
        val sourceLine = "${src.width}×${src.height}, ${String.format(Locale.US, "%.1f", src.durationSec)}s, " +
            "$fpsText, ${if (src.hasAudio) "has audio" else "no audio"}"
        conversionLogs.add(
            ConversionLog(
                id = item.id,
                name = item.name,
                outcome = outcome,
                whenMillis = System.currentTimeMillis(),
                sourceLine = sourceLine,
                settingsLine = settingsWithTarget(settings),
                detail = detail,
                log = lines.joinToString("\n"),
            )
        )
        val oldest = System.currentTimeMillis() - ConversionLogStore.KEEP_MILLIS
        conversionLogs.removeAll { it.whenMillis < oldest }
        while (conversionLogs.size > ConversionLogStore.MAX_ENTRIES) conversionLogs.removeAt(0)
        val snapshot = conversionLogs.toList()
        val appCtx = ctx.applicationContext
        scope.launch(Dispatchers.IO) { ConversionLogStore.save(appCtx, snapshot) }
    }

    /** Updates the ongoing notification, at most about once a second. */
    private fun notifyProgress(ctx: Context, pos: String, name: String, step: String, force: Boolean) {
        if (!busy) return
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastNotifyMs < 1000) return
        lastNotifyMs = now
        val p = progress
        val percent = if (p < 0f) null else (p * 100).toInt().coerceIn(0, 100)
        ConvertService.postProgress(ctx, "Converting: $pos", "$name\n$step", percent)
    }
}
