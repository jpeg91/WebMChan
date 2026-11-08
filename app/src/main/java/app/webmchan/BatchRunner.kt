// #batchrunner
package app.webmchan

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
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

/** A one-off effect a card plays when it appears: a flash for a finished video, a shake for a failed one. */
enum class CardEventKind { DONE, FAILED }

/** [at] is SystemClock.elapsedRealtime() when it happened, so a stale one (app in the background) is ignored. */
data class CardEvent(val kind: CardEventKind, val at: Long)

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
    /**
     * The edit made in the editor (cuts and crop), or null for none. It belongs to this card: the
     * source video is never changed, and the conversion reads the edit. Only a card in the Holding
     * Pen can be edited; it stays through the queue and comes back with the card if it is returned to
     * the pen. A fresh card made from the same video starts without one.
     */
    val edit: EditSpec? = null,
    /**
     * A name for the converted file chosen on the card (the part before the part number, markers and
     * app tag, which are still added when it is saved), or null for the source video's own name. Only
     * kept until the conversion is done: the card leaves the queue then and the saved file's name is
     * what the Finished card shows.
     */
    val customName: String? = null,
    /**
     * The frame picked as this video's thumbnail, as a time in the SOURCE video (null = none: the site
     * uses the converted video's own first frame). The conversion puts that frame in front of the video
     * as its new first frame, and the card's thumbnail shows it. Like [edit] it belongs to the card and
     * can only be set in the Holding Pen. On a Finished card it only records that the saved file starts
     * with the picked frame (so a lost thumbnail is remade from its first frame).
     */
    val thumbSec: Double? = null,
    /**
     * Set on an Audio Mode card: [uri] is then an audio file (or a video to take the sound from) and
     * this holds its picture(s) and tags (see [AudioConverter]). The card goes through the same pen,
     * queue and Finished lists as a video; the two screens each show only their own kind. A Finished
     * card keeps it, so "Add to pen again" brings the picture(s) back too.
     */
    val audio: AudioSpec? = null,
) {
    val isAudio: Boolean get() = audio != null
}

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

    /**
     * Every card a finished conversion has made, oldest first, for the History page. A card is added
     * here when it lands in [finished] and stays when it is removed from there; only History's own
     * "Remove from history" takes it out. Saved on its own (see [HistoryStore]).
     */
    val history = mutableStateListOf<QueueItem>()

    /**
     * Whether a History card's source video can still be read, so "Add to pen again" has something
     * to add. Filled in in the background, so a card not in here yet counts as readable. Not saved.
     */
    val historySourceOk = mutableStateMapOf<Long, Boolean>()

    var busy by mutableStateOf(false)

    /**
     * True when the batch running (or paused) is an Audio Mode one. Only one kind runs at a time; the
     * other screen then says so instead of showing its own Convert button. Notifications use it too.
     */
    var runAudio by mutableStateOf(false)
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

    /**
     * Effects waiting for a card to play, by card id. A card removes its own entry when it plays it
     * (see [noteCardEvent]). Not saved: they only matter for the next few seconds.
     */
    val cardEvents = mutableStateMapOf<Long, CardEvent>()

    private fun noteCardEvent(id: Long, kind: CardEventKind) {
        val now = SystemClock.elapsedRealtime()
        cardEvents.entries.removeAll { now - it.value.at > 30_000L } // never played (app in the background)
        cardEvents[id] = CardEvent(kind, now)
    }

    /**
     * The video that Pause Now interrupted: its card stays in the Converting section, with the
     * light round its border held still, until the batch carries on (or ends). -1 = none.
     */
    var pausedItemId by mutableLongStateOf(-1L)
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

    /** The plain-words messages for the bubble above the log, for the video being converted (see [UserNote]). */
    var notes by mutableStateOf<List<UserNote>>(emptyList())
        private set
    private val notesLock = Any()

    private fun addNote(text: String, attention: Boolean) {
        synchronized(notesLock) { notes = (notes + UserNote(text, attention)).takeLast(200) }
    }

    private fun clearNotes() {
        synchronized(notesLock) { notes = emptyList() }
    }

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

    /** The last progress notification's text, so the hourglass can be redrawn while nothing new is reported (see runBatch). */
    private var lastNote: Triple<String, String, String>? = null

    /** The settings the batch in progress was started (or last topped up) with: what a shared video joining it gets. */
    private var runSettings: Settings? = null

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
        AppSettings.init(appCtx)
        AudioArt.loadPlaceholder(appCtx)
        if (restored) return
        restored = true

        // A fresh process means nothing is converting: clear working files (staged source copies,
        // encoder output) that a run the system killed part-way left in the cache.
        Converter.clearWorkFiles(appCtx)

        // The Log page's saved conversions (read off the main thread: they can be big).
        scope.launch(Dispatchers.IO) { conversionLogs.addAll(ConversionLogStore.load(appCtx)) }

        // The History page's saved cards.
        history.addAll(HistoryStore.load(appCtx))

        val loaded = BatchStore.load(appCtx) ?: run {
            cleanOrphanCopies(appCtx)
            pruneAnalysis()
            scope.launch { verifyHistory(appCtx) }
            return
        }
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
                    if (it.status == ItemStatus.CONVERTING) {
                        it.copy(
                            status = ItemStatus.CANCELLED,
                            detail = if (it.partsDone > 0 && it.partsDone < it.partsTotal) {
                                "Interrupted when the app closed (${it.partsDone} of ${it.partsTotal} parts saved). " +
                                    "Press Convert to continue from part ${it.partsDone + 1}."
                            } else "Interrupted when the app closed. Press Convert to try again.",
                        )
                    } else it
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
        // A Finished card History doesn't have yet (made before History existed, or a save that didn't
        // finish) gets its entry now.
        val inHistory = history.map { it.id }.toSet()
        val missing = finished.filter { it.id !in inHistory }
        if (missing.isNotEmpty()) {
            val all = (history + missing).sortedBy { it.id }
            history.clear()
            history.addAll(all)
            persistHistory()
        }
        if (interrupted) {
            busy = true
            paused = true
            runAudio = queue.firstOrNull { !it.inPen && it.info != null && it.status == ItemStatus.WAITING }?.isAudio == true
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
        pruneAnalysis()
        scope.launch { verifyRestored(appCtx) }
        scope.launch { verifyHistory(appCtx) }
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

    /** Writes the History list to storage (in the background). */
    private fun persistHistory() {
        val ctx = appContext ?: return
        val h = history.toList()
        writer.execute { HistoryStore.save(ctx, h) }
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
                } else if (item.info == null || item.info.probeVersion < SourceInfo.PROBE_VERSION) {
                    // Never probed, or probed by an older version that read a sideways video's size the wrong way round.
                    toProbe.add(id)
                }
            }
            for (id in toProbe) {
                val item = queue.firstOrNull { it.id == id } ?: continue
                val i = if (item.isAudio) AudioConverter.probe(ctx, item.uri)?.info else Converter.probe(ctx, item.uri)
                val err = if (i == null) (if (item.isAudio) AudioConverter.lastProbeError else Converter.lastProbeError) else null
                val at = queue.indexOfFirst { it.id == id }
                if (at >= 0) {
                    // A video that was read fine before and can't be now keeps what it had.
                    val keepOld = i == null && item.info != null
                    queue[at] = queue[at].copy(info = i ?: item.info, probeFailed = i == null && !keepOld)
                    if (err != null && !keepOld) queue[at] = queue[at].copy(status = ItemStatus.FAILED, detail = err)
                }
            }

            // Thumbnails for queued videos that don't have one (or lost the file).
            for (id in queue.map { it.id }) {
                val item = queue.firstOrNull { it.id == id } ?: continue
                val info = item.info ?: continue
                if (item.status == ItemStatus.FAILED && item.probeFailed) continue
                if (item.isAudio) continue // an Audio Mode card shows its own picture, not a video frame
                if (item.hasThumb && Thumbnails.exists(ctx, id)) continue
                setThumb(ctx, id, item.uri, null, info.durationSec, queue, item.thumbSec)
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
                setThumb(ctx, id, saved, null, info.durationSec, finished, if (item.thumbSec != null) 0.0 else null)
            }

            Thumbnails.pruneExcept(ctx, (queue + finished + history).map { it.id }.toSet())
            AudioArt.prune(ctx, audioArtInUse())
        } finally {
            probing--
            persist()
        }
    }

    /** A shared video's copy in app storage is a plain file; anything else is opened through the system. */
    private fun sourceReadable(ctx: Context, uri: Uri): Boolean =
        if (uri.scheme == "file") uri.path?.let { File(it).exists() } ?: false else canOpen(ctx, uri)

    /**
     * After a restore, in the background: checks that each History card's saved file and source video
     * can still be read (a file may have been deleted or moved outside the app, an SD card removed,
     * or Android may have dropped an old lasting read grant), and makes a thumbnail for a card that
     * has none. Kept apart from [verifyRestored] so a long History never holds Convert up.
     */
    private suspend fun verifyHistory(ctx: Context) {
        for (id in history.map { it.id }) {
            val item = history.firstOrNull { it.id == id } ?: continue
            val saved = item.savedUri
            if (saved != null) {
                val ok = withContext(Dispatchers.IO) { canOpen(ctx, saved) }
                val at = history.indexOfFirst { it.id == id }
                if (!ok && at >= 0) {
                    history[at] = history[at].copy(
                        savedUri = null,
                        detail = (item.detail?.let { "$it\n" } ?: "") + "The saved file isn't available any more.",
                    )
                    persistHistory()
                }
            }
            historySourceOk[id] = withContext(Dispatchers.IO) { sourceReadable(ctx, item.uri) }
        }
        for (id in history.map { it.id }) {
            val item = history.firstOrNull { it.id == id } ?: continue
            val saved = item.savedUri ?: continue
            val info = item.info ?: continue
            if (item.hasThumb && Thumbnails.exists(ctx, id)) continue
            setThumb(ctx, id, saved, null, info.durationSec, history, if (item.thumbSec != null) 0.0 else null)
        }
        persistHistory()
    }

    /** Re-checks the source of every History card that uses [uri] (after a shared video's copy was deleted, say). */
    private fun refreshHistorySource(ctx: Context, uri: Uri) {
        val ids = history.filter { it.uri == uri }.map { it.id }
        if (ids.isEmpty()) return
        scope.launch {
            val ok = withContext(Dispatchers.IO) { sourceReadable(ctx, uri) }
            ids.forEach { historySourceOk[it] = ok }
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
        atSec: Double? = null,
    ) {
        val ok = Thumbnails.create(ctx, id, uri, file, durationSec, atSec)
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
    fun addShared(ctx: Context, shared: List<Uri>, audio: Boolean = false) {
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
                if (copies.isNotEmpty()) { if (audio) addAudio(appCtx, copies, fromShare = true) else addVideos(appCtx, copies, fromShare = true) }
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
        val keep = (queue + finished).mapNotNull { q -> q.uri.takeIf { it.scheme == "file" }?.path?.let { File(it).parentFile?.canonicalPath } }.toSet()
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
        // A Finished card keeps its source too, so "Add to pen again" still has a video to add.
        if (queue.any { it.uri == uri } || finished.any { it.uri == uri }) return
        // A History card keeps the lasting read grant on a picked video, so it can still be added again.
        // It never keeps a shared video's copy in app storage: that goes with the last queue or
        // Finished card that uses it, and the History card then says the source is lost.
        if (uri.scheme != "file" && history.any { it.uri == uri }) return
        releaseAccess(ctx, uri)
        deleteOwnCopy(ctx, uri)
        refreshHistorySource(ctx, uri)
    }

    /**
     * Deletes the kept pass-1 analysis (see [Converter.pruneAnalysis]) of every video that no card in
     * the Holding Pen, the queue or Finished uses any more.
     */
    private fun pruneAnalysis() {
        val ctx = appContext ?: return
        val keep = (queue.map { it.uri.toString() } + finished.map { it.uri.toString() }).toSet()
        scope.launch(Dispatchers.IO) { Converter.pruneAnalysis(ctx, keep) }
    }

    // ---- The Cache page ----

    /**
     * True while something is working with the cache's files (a run that is going or paused, videos being
     * read, or a shared video being copied in), when no wipe is allowed.
     */
    val cacheBusy: Boolean get() = busy || probing > 0 || copyingTotal > 0

    /** The folders (as canonical paths) of the shared-video copies that a queue, Holding Pen or Finished card still uses. */
    private fun sharedFoldersInUse(): Set<String> =
        (queue + finished).mapNotNull { q ->
            q.uri.takeIf { it.scheme == "file" }?.path?.let { File(it).parentFile?.canonicalPath }
        }.toSet()

    /** Reads what the app keeps in its storage, for the Cache page. */
    suspend fun cacheReport(ctx: Context): CacheReport {
        val appCtx = ctx.applicationContext
        // What cards still use is worked out here, where the lists are read; the folders are walked off the main thread.
        val keepSources = (queue + finished).map { it.uri.toString() }.toSet()
        val keepThumbs = (queue + finished + history).map { it.id }.toSet()
        val keepShared = sharedFoldersInUse()
        return withContext(Dispatchers.IO) {
            val (analysisIn, analysisOut) = Converter.analysisUsage(appCtx, keepSources)
            val (thumbsIn, thumbsOut) = CacheTools.thumbsUsage(Thumbnails.dir(appCtx), keepThumbs)
            val (sharedIn, sharedOut) = CacheTools.sharedUsage(sharedDir(appCtx), keepShared)
            // The few files at the top of the app's storage are the saved lists and logs.
            val saved = appCtx.filesDir.listFiles()?.filter { it.isFile }
                ?.fold(CacheStat()) { sum, f -> sum + CacheTools.statOf(f) } ?: CacheStat()
            CacheReport(
                cacheFolder = CacheTools.cacheFolderRows(appCtx.cacheDir),
                analysisInUse = analysisIn, analysisUnused = analysisOut,
                thumbsInUse = thumbsIn, thumbsUnused = thumbsOut,
                sharedInUse = sharedIn, sharedUnused = sharedOut,
                savedData = saved,
            )
        }
    }

    /**
     * Safe wipe: empties the cache folder and deletes the analysis, thumbnails and shared-video copies
     * that no card uses. Nothing on screen changes. Returns what was freed, or null if it wasn't
     * allowed ([cacheBusy]).
     */
    suspend fun wipeSafe(ctx: Context): CacheStat? {
        if (cacheBusy) return null
        val appCtx = ctx.applicationContext
        val before = cacheReport(appCtx)
        val keepSources = (queue + finished).map { it.uri.toString() }.toSet()
        val keepThumbs = (queue + finished + history).map { it.id }.toSet()
        val keepShared = sharedFoldersInUse()
        withContext(Dispatchers.IO) {
            CacheTools.clearFolder(appCtx.cacheDir)
            Converter.pruneAnalysis(appCtx, keepSources)
            Thumbnails.pruneExcept(appCtx, keepThumbs)
            sharedDir(appCtx).listFiles()?.forEach { if (it.canonicalPath !in keepShared) it.deleteRecursively() }
        }
        return before.total - cacheReport(appCtx).total
    }

    /**
     * Full cache wipe: the safe wipe, plus all the kept analysis and every thumbnail. The cards stay
     * (without thumbnails, which are made again the next time the app starts, where the video is still
     * there), as do shared copies a card uses and the saved lists. A video converted again redoes its analysis.
     */
    suspend fun wipeFull(ctx: Context): CacheStat? {
        if (cacheBusy) return null
        val appCtx = ctx.applicationContext
        val before = cacheReport(appCtx)
        val keepShared = sharedFoldersInUse()
        // The cards forget their thumbnails first, so none points at a file that is about to go.
        for (list in listOf(queue, finished, history)) {
            for (n in list.indices) if (list[n].hasThumb) list[n] = list[n].copy(hasThumb = false)
        }
        persist()
        persistHistory()
        withContext(Dispatchers.IO) {
            CacheTools.clearFolder(appCtx.cacheDir)
            Converter.pruneAnalysis(appCtx, emptySet())
            Thumbnails.pruneExcept(appCtx, emptySet())
            sharedDir(appCtx).listFiles()?.forEach { if (it.canonicalPath !in keepShared) it.deleteRecursively() }
        }
        return before.total - cacheReport(appCtx).total
    }

    /**
     * Reset app: back to a fresh install. The full cache wipe, and the Holding Pen, queue, Finished list,
     * History, the Log page's conversions and every shared-video copy as well, plus every setting: the
     * Settings switches, the Advanced numbers, the save location (its folder grant is let go) and the
     * choices made on the main screen. Only the saved .webm videos stay, as they are in the person's own
     * folders. The caller should restart the screen afterwards so it reads the defaults again.
     */
    suspend fun resetApp(ctx: Context): CacheStat? {
        if (cacheBusy) return null
        val appCtx = ctx.applicationContext
        val before = cacheReport(appCtx)
        closeEditor()
        // Let go of the lasting read grants on picked videos; the cards that held them are going.
        (queue + finished + history).map { it.uri }.distinct().forEach { if (it.scheme != "file") releaseAccess(appCtx, it) }
        queue.clear()
        finished.clear()
        history.clear()
        historySourceOk.clear()
        cardEvents.clear()
        conversionLogs.clear()
        summary = null
        notice = null
        logBuffer.clear { logLines = it }
        clearNotes()
        persist()
        persistHistory()
        writer.execute { ConversionLogStore.save(appCtx, emptyList()) }
        // Settings: the save location first (that lets go of the folder's lasting grant), then every
        // saved preference, the Settings switches and the Advanced numbers.
        OutputStorage.clearDir(appCtx)
        appCtx.getSharedPreferences("webmchan", Context.MODE_PRIVATE).edit().clear().apply()
        AppSettings.resetAll()
        TuningStore.reset(appCtx)
        withContext(Dispatchers.IO) {
            CacheTools.clearFolder(appCtx.cacheDir)
            Converter.pruneAnalysis(appCtx, emptySet())
            Thumbnails.pruneExcept(appCtx, emptySet())
            CacheTools.clearFolder(sharedDir(appCtx))
        }
        return before.total - cacheReport(appCtx).total
    }

    // ---- The video editor ----

    /** The card open in the editor (see EditorScreen), or -1 when it isn't open. Not saved. */
    var editingId by mutableLongStateOf(-1L)
        private set

    /**
     * The settings the Holding Pen is currently following, kept up to date by the main screen so the
     * editor can show what an edit would do to the conversion. null before the main screen has drawn.
     */
    var liveSettings by mutableStateOf<Settings?>(null)

    /** Opens the editor for a card in the Holding Pen. Nothing else can be edited. */
    fun openEditor(id: Long) {
        val item = queue.firstOrNull { it.id == id } ?: return
        if (!item.inPen || item.info == null || item.probeFailed) return
        editingId = id
    }

    fun closeEditor() {
        editingId = -1L
    }

    // ---- Audio Mode: the trim page and the video-frame picker ----

    /** The Audio Mode card open in the trim page (see AudioTools.kt), or -1. Not saved. */
    var audioTrimId by mutableLongStateOf(-1L)
        private set

    /** The Audio Mode card open in the frame picker, or -1. Not saved. */
    var framePickId by mutableLongStateOf(-1L)
        private set

    fun openAudioTrim(id: Long) {
        val item = queue.firstOrNull { it.id == id } ?: return
        if (!item.isAudio || !item.inPen || item.info == null || item.probeFailed) return
        audioTrimId = id
    }

    fun closeAudioTrim() { audioTrimId = -1L }

    fun openFramePicker(id: Long) {
        val item = queue.firstOrNull { it.id == id } ?: return
        if (!item.isAudio || !item.inPen || (item.info?.videoCodec ?: "").isEmpty() || item.probeFailed) return
        framePickId = id
    }

    fun closeFramePicker() { framePickId = -1L }

    /**
     * Keeps only the stretch from [startSec] to [endSec] of an audio file (the card then plans, splits and
     * converts as a track that long). The whole track is no edit at all.
     */
    fun setAudioTrim(id: Long, startSec: Double, endSec: Double) {
        val at = queue.indexOfFirst { it.id == id }
        if (at < 0) return
        val total = queue[at].info?.durationSec ?: return
        val whole = startSec <= 0.0005 && endSec >= total - 0.0005
        setEdit(id, if (whole) null else EditSpec(listOf(EditClip(startSec, endSec))))
    }

    /** Audio Mode: the frame of a video source at [sec] becomes the card's one picture. */
    fun setAudioFrame(ctx: Context, id: Long, sec: Double) {
        val appCtx = ctx.applicationContext
        val uri = queue.firstOrNull { it.id == id }?.uri ?: return
        scope.launch {
            val img = withContext(Dispatchers.IO) { AudioArt.frameAt(appCtx, uri, sec)?.let { AudioArt.fromBytes(appCtx, it) } }
            if (img == null) {
                notice = "Couldn't get that frame."
                return@launch
            }
            val at = queue.indexOfFirst { it.id == id }
            if (at < 0) {
                AudioArt.prune(appCtx, audioArtInUse())
                return@launch
            }
            val spec = queue[at].audio ?: return@launch
            queue[at] = queue[at].copy(audio = spec.copy(images = listOf(img), coverFromTags = false))
            AudioArt.prune(appCtx, audioArtInUse())
            persist()
        }
    }

    // ---- The thumbnail picker ----

    /** The card open in the thumbnail picker (see ThumbnailPickerScreen), or -1 when it isn't open. Not saved. */
    var thumbPickId by mutableLongStateOf(-1L)
        private set

    /** Opens the thumbnail picker for a card in the Holding Pen. Nothing else can be given a picked thumbnail. */
    fun openThumbPicker(id: Long) {
        val item = queue.firstOrNull { it.id == id } ?: return
        if (!item.inPen || item.info == null || item.probeFailed) return
        thumbPickId = id
    }

    fun closeThumbPicker() {
        thumbPickId = -1L
    }

    /**
     * Saves (or, with null, clears) the picked thumbnail frame of a Holding Pen card: a time in the
     * source video. The card's preview picture is remade from that frame first and the pick is
     * recorded once it is there, so the card never shows the old picture under the new pick.
     */
    fun setThumbPick(ctx: Context, id: Long, sec: Double?) {
        val appCtx = ctx.applicationContext
        val item = queue.firstOrNull { it.id == id } ?: return
        if (!item.inPen || item.thumbSec == sec) return
        val info = item.info ?: return
        scope.launch {
            val ok = Thumbnails.create(appCtx, id, item.uri, null, info.durationSec, atSec = sec)
            val at = queue.indexOfFirst { it.id == id }
            if (at < 0) {
                // The card was removed in the meantime.
                Thumbnails.delete(appCtx, id)
                return@launch
            }
            queue[at] = queue[at].copy(thumbSec = sec, hasThumb = ok)
            persist()
        }
    }

    /** Saves (or, with null, removes) the edit on a Holding Pen card. */
    fun setEdit(id: Long, edit: EditSpec?) {
        val at = queue.indexOfFirst { it.id == id }
        if (at < 0 || !queue[at].inPen) return
        if (queue[at].edit == edit) return
        queue[at] = queue[at].copy(edit = edit)
        persist()
    }

    // ---- Editing the queue (the UI disables these while a batch is running) ----

    fun addVideos(ctx: Context, picked: List<Uri>, fromShare: Boolean = false) {
        val appCtx = ctx.applicationContext
        appContext = appCtx
        // The same video may be added any number of times (e.g. to convert it again with different
        // settings): every pick becomes its own card, and cards are told apart by id, not by uri.
        if (picked.isEmpty()) return
        picked.forEach { keepAccess(appCtx, it) }
        summary = null
        // New videos always wait in the Holding Pen until they are sent to the queue.
        val added = picked.map { u -> QueueItem(ids.incrementAndGet(), u, Converter.displayName(appCtx, u), inPen = true) }
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
                    if (i != null && fromShare) joinRunningBatch(item.id)
                }
            } finally {
                probing--
                persist()
            }
        }
    }

    /** The picture files that some card (queue, Finished or History) still uses. */
    private fun audioArtInUse(): Set<String> =
        (queue + finished + history).flatMap { c -> c.audio?.images?.map { it.path } ?: emptyList() }.toSet()

    /**
     * Audio Mode: adds audio files (or videos, to take their sound) to the Holding Pen as cards. Each is
     * read for its length, title and artist, and if it has cover art that becomes its picture (the card's
     * "Image" menu changes it). [again] is for "Add to pen again": the picture(s) the finished card
     * had come back with it, and the tags and cover aren't looked up again.
     */
    fun addAudio(ctx: Context, picked: List<Uri>, fromShare: Boolean = false, again: AudioSpec? = null) {
        val appCtx = ctx.applicationContext
        appContext = appCtx
        if (picked.isEmpty()) return
        picked.forEach { keepAccess(appCtx, it) }
        summary = null
        val added = picked.map { u ->
            QueueItem(ids.incrementAndGet(), u, Converter.displayName(appCtx, u), inPen = true, audio = again ?: AudioSpec())
        }
        queue.addAll(added)
        persist()
        scope.launch {
            probing++
            try {
                for (item in added) {
                    val p = AudioConverter.probe(appCtx, item.uri)
                    val err = if (p == null) AudioConverter.lastProbeError else null
                    // A fresh card gets the file's own cover art as its picture, and its tags.
                    var spec = item.audio ?: AudioSpec()
                    if (p != null && again == null) {
                        val cover = withContext(Dispatchers.IO) { AudioArt.embeddedCover(appCtx, item.uri)?.let { AudioArt.fromBytes(appCtx, it) } }
                        spec = AudioSpec(
                            images = if (cover != null) listOf(cover) else emptyList(),
                            coverFromTags = cover != null,
                            title = p.title,
                            artist = p.artist,
                        )
                    }
                    // The output is named "artist - title" when the file has both tags, else by the title alone, else by the file name.
                    val named = when {
                        again != null -> null
                        p != null && p.artist.isNotBlank() && p.title.isNotBlank() -> "${p.artist} - ${p.title}"
                        p != null && p.title.isNotBlank() -> p.title
                        else -> null
                    }?.let { OutputStorage.cleanBaseName(it) }
                    val at = queue.indexOfFirst { it.id == item.id }
                    if (at >= 0) {
                        queue[at] = queue[at].copy(info = p?.info, probeFailed = p == null, audio = spec, customName = named ?: queue[at].customName)
                        if (err != null) queue[at] = queue[at].copy(status = ItemStatus.FAILED, detail = err)
                    } else {
                        // Removed while reading: its new picture isn't wanted.
                        AudioArt.prune(appCtx, audioArtInUse())
                    }
                    if (p != null && fromShare) joinRunningBatch(item.id)
                }
            } finally {
                probing--
                persist()
            }
        }
    }

    /** Audio Mode: picks pictures for a Holding Pen card. [append] adds to its pictures; otherwise they replace them. */
    fun setAudioImages(ctx: Context, id: Long, picked: List<Uri>, append: Boolean) {
        if (picked.isEmpty()) return
        val appCtx = ctx.applicationContext
        scope.launch {
            AudioArt.clearImportError()
            val made = withContext(Dispatchers.IO) { picked.mapNotNull { AudioArt.fromUri(appCtx, it) } }
            if (made.isEmpty()) {
                notice = AudioArt.lastImportError ?: "Couldn't read that as a picture."
                return@launch
            }
            // Some were used and some not (a GIF this build can't read, say): say so.
            if (made.size < picked.size) notice = AudioArt.lastImportError ?: "Some of those couldn't be read as pictures."
            val at = queue.indexOfFirst { it.id == id }
            if (at < 0) {
                AudioArt.prune(appCtx, audioArtInUse())
                return@launch
            }
            val spec = queue[at].audio ?: return@launch
            queue[at] = queue[at].copy(
                audio = spec.copy(images = if (append) spec.images + made else made, coverFromTags = spec.coverFromTags && append),
            )
            AudioArt.prune(appCtx, audioArtInUse())
            persist()
        }
    }

    /** Audio Mode: takes one picture off a Holding Pen card (by its place in the card's pictures). */
    fun removeAudioImage(ctx: Context, id: Long, index: Int) {
        val at = queue.indexOfFirst { it.id == id }
        if (at < 0) return
        val spec = queue[at].audio ?: return
        if (index !in spec.images.indices) return
        queue[at] = queue[at].copy(audio = spec.copy(images = spec.images.filterIndexed { n, _ -> n != index }, coverFromTags = false))
        AudioArt.prune(ctx.applicationContext, audioArtInUse())
        persist()
    }

    /** Audio Mode: the words on a card's title card. Blank goes back to the default. */
    fun setAudioCardText(id: Long, text: String) {
        val at = queue.indexOfFirst { it.id == id }
        if (at < 0) return
        val spec = queue[at].audio ?: return
        queue[at] = queue[at].copy(audio = spec.copy(cardText = text.trim()))
        persist()
    }

    /** Audio Mode: takes every picture off a Holding Pen card (it is then made with a plain black one, or the one in Settings). */
    fun clearAudioImages(ctx: Context, id: Long) {
        val at = queue.indexOfFirst { it.id == id }
        if (at < 0) return
        val spec = queue[at].audio ?: return
        queue[at] = queue[at].copy(audio = spec.copy(images = emptyList(), coverFromTags = false))
        AudioArt.prune(ctx.applicationContext, audioArtInUse())
        persist()
    }

    /**
     * A shared video, once it has been read, goes straight into the batch in progress if Settings says so
     * (with that batch's settings). With nothing running it stays in the Holding Pen, as every new video does.
     */
    private fun joinRunningBatch(id: Long) {
        if (!AppSettings.get(AppToggle.SHARE_TO_RUNNING_BATCH) || !busy || cancelling) return
        val s = runSettings ?: queue.firstNotNullOfOrNull { if (!it.inPen) it.settings else null } ?: return
        val at = queue.indexOfFirst { it.id == id }
        if (at < 0) return
        val item = queue[at]
        if (!item.inPen || item.info == null || item.probeFailed || item.isAudio != runAudio) return
        queue.removeAt(at)
        queue.add(item.copy(inPen = false, settings = s, status = ItemStatus.WAITING, detail = null))
        pickUpNewVideos()
        persist()
    }

    /**
     * Whether a queue (not Holding Pen) video can be moved: it is ready and still waiting its turn.
     * The one being converted, or held by Pause Now, stays where it is.
     */
    private fun queueMovable(q: QueueItem): Boolean =
        q.status == ItemStatus.WAITING && q.info != null && !q.probeFailed && q.id != pausedItemId

    /**
     * The nearest video in [delta]'s direction that is in the same list (Holding Pen or batch), or
     * null. In the queue only waiting videos count, so the one being converted is stepped over.
     */
    private fun neighbour(index: Int, delta: Int): Int? {
        if (index !in queue.indices) return null
        val pen = queue[index].inPen
        if (!pen && !queueMovable(queue[index])) return null
        var i = index + delta
        while (i in queue.indices) {
            if (queue[i].inPen == pen && queue[i].isAudio == queue[index].isAudio && (pen || queueMovable(queue[i]))) return i
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
        // A running batch converts in the order of runList, so swap the two there as well.
        if (!t.inPen) {
            val a = runList.indexOf(t.id)
            val b = runList.indexOf(queue[index].id)
            if (a >= 0 && b >= 0) {
                runList[a] = queue[index].id
                runList[b] = t.id
            }
        }
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

    /**
     * Sets (or, with a blank [name], clears) the name the converted file of queue card [id] will be
     * saved under. Allowed in the Holding Pen, the queue and on the card being converted (the name is
     * read when each part is saved). Returns false if [name] can't be used in a file name.
     */
    fun setCustomName(id: Long, name: String): Boolean {
        val at = queue.indexOfFirst { it.id == id }
        if (at < 0) return false
        val cleaned = if (name.isBlank()) null else (OutputStorage.cleanBaseName(name) ?: return false)
        val item = queue[at]
        if (cleaned != item.customName) {
            queue[at] = item.copy(customName = cleaned)
            persist()
        }
        return true
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
        pruneAnalysis()
        persist()
    }

    /** Empties the queue (not the Holding Pen) of the video cards, or of the Audio Mode ones when [audio]. */
    fun clearQueue(audio: Boolean = false) {
        if (busy) return
        val removed = queue.filter { !it.inPen && it.isAudio == audio }
        queue.removeAll { !it.inPen && it.isAudio == audio }
        appContext?.let { ctx ->
            removed.forEach {
                releaseSource(ctx, it.uri)
                Thumbnails.delete(ctx, it.id)
            }
        }
        pruneAnalysis()
        summary = null
        persist()
    }

    /**
     * Renames the saved file of the Finished card [id] to "[newBase].webm". Returns null when it
     * worked, otherwise a message to show the user. The card keeps pointing at the renamed file.
     */
    suspend fun renameFinished(ctx: Context, id: Long, newBase: String): String? {
        val base = OutputStorage.cleanBaseName(newBase) ?: return "That name can't be used."
        val saved = cardSavedUri(id) ?: return "This video's file isn't available."
        val oldName = withContext(Dispatchers.IO) { OutputStorage.fileName(ctx, saved) }
        val res = withContext(Dispatchers.IO) { OutputStorage.rename(ctx, saved, "$base.webm") }
        if (res.uri == null) return res.error
        updateCards(id) { cur ->
            // The card's text names the saved file ("Saved to ..."), so bring that up to date too.
            val detail = if (oldName != null && res.name != null) cur.detail?.replace(oldName, res.name) else cur.detail
            // The card's title follows the file's new name too.
            cur.copy(savedUri = res.uri, detail = detail, name = res.name ?: "$base.webm")
        }
        return null
    }

    /**
     * Deletes the converted .webm of the Finished card [id]. The card stays (without its file
     * buttons) until it is removed. Returns null when it worked, otherwise a message to show.
     */
    suspend fun deleteFinishedFile(ctx: Context, id: Long): String? {
        val saved = cardSavedUri(id) ?: return "This video's file isn't available."
        // A file that is already gone counts as deleted.
        val gone = withContext(Dispatchers.IO) { OutputStorage.delete(ctx, saved) || !canOpen(ctx, saved) }
        if (!gone) return "Couldn't delete the file."
        updateCards(id) { cur ->
            cur.copy(
                savedUri = null,
                detail = (cur.detail?.let { "$it\n" } ?: "") + "The saved file was deleted.",
            )
        }
        return null
    }

    /** The saved file of card [id], from the Finished list or, if it was removed from there, from History. */
    private fun cardSavedUri(id: Long): Uri? =
        (finished.firstOrNull { it.id == id } ?: history.firstOrNull { it.id == id })?.savedUri

    /** Applies [change] to card [id] wherever it is (the Finished list, History, or both) and saves. */
    private fun updateCards(id: Long, change: (QueueItem) -> QueueItem) {
        val f = finished.indexOfFirst { it.id == id }
        if (f >= 0) finished[f] = change(finished[f])
        val h = history.indexOfFirst { it.id == id }
        if (h >= 0) history[h] = change(history[h])
        persist()
        persistHistory()
    }

    /** Takes one card out of the Finished list. Never touches the saved file. */
    fun removeFinished(id: Long) {
        val at = finished.indexOfFirst { it.id == id }
        if (at < 0) return
        val gone = finished.removeAt(at)
        appContext?.let {
            // The card stays in History, which keeps its thumbnail.
            if (history.none { h -> h.id == id }) Thumbnails.delete(it, id)
            releaseSource(it, gone.uri) // the Finished card was holding its source
        }
        pruneAnalysis()
        persist()
    }

    /**
     * "Add to pen again" on a Finished card: adds the card's source video to the Holding Pen as a new
     * card (to convert it again, say with other settings). If the video's analysis was kept, the new
     * card's conversion reuses it. If the source can't be read any more, the new card says so as usual.
     */
    fun addAgain(ctx: Context, finishedId: Long) {
        val item = finished.firstOrNull { it.id == finishedId } ?: history.firstOrNull { it.id == finishedId } ?: return
        if (item.audio != null) addAudio(ctx, listOf(item.uri), again = item.audio) else addVideos(ctx, listOf(item.uri))
    }

    /** Empties the Finished list of the video cards, or of the Audio Mode ones when [audio]. */
    fun clearFinished(audio: Boolean = false) {
        val removed = finished.filter { it.isAudio == audio }
        appContext?.let { ctx ->
            removed.forEach { r -> if (history.none { h -> h.id == r.id }) Thumbnails.delete(ctx, r.id) }
        }
        finished.removeAll { it.isAudio == audio }
        appContext?.let { ctx -> removed.map { it.uri }.distinct().forEach { releaseSource(ctx, it) } }
        pruneAnalysis()
        persist()
    }

    /** Takes one card out of History, for good. Never touches the saved file. */
    fun removeFromHistory(id: Long) {
        val at = history.indexOfFirst { it.id == id }
        if (at < 0) return
        val gone = history.removeAt(at)
        historySourceOk.remove(id)
        appContext?.let {
            // The thumbnail goes too, unless the card is still on the main screen.
            if (finished.none { f -> f.id == id }) Thumbnails.delete(it, id)
            releaseSource(it, gone.uri) // History may have been holding its source's read grant
        }
        persistHistory()
    }

    // ---- Running ----

    /** Converts every readable video in the queue, in order, with [settings]. */
    fun start(ctx: Context, settings: Settings, audio: Boolean = false) {
        if (busy) return
        val appCtx = ctx.applicationContext
        appContext = appCtx
        // Everything ready in the Holding Pen goes to the queue (after anything already left there).
        movePenToQueue(settings, audio)
        val todo = queue.filter { !it.inPen && it.info != null && it.isAudio == audio }.map { it.id }
        if (todo.isEmpty()) return
        runAudio = audio

        // A new run starts with an empty Finished list, if Settings says so (History keeps the cards).
        if (AppSettings.get(AppToggle.AUTO_CLEAR_FINISHED)) clearFinished(audio)
        // The last run's finished and failed notifications have done their job.
        ConvertService.clearResults(appCtx)
        runSettings = settings

        summary = null
        runTotal = todo.size
        runList.clear()
        runList.addAll(todo)
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
        clearNotes()
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
    private fun movePenToQueue(settings: Settings, audio: Boolean): List<Long> {
        val moving = queue.filter { it.inPen && it.info != null && it.isAudio == audio }
        if (moving.isEmpty()) return emptyList()
        val movingIds = moving.map { it.id }.toHashSet()
        queue.removeAll { it.id in movingIds }
        queue.addAll(moving.map { it.copy(inPen = false, settings = settings, status = ItemStatus.WAITING, detail = null) })
        return moving.map { it.id }
    }

    /** While a batch runs (or is paused): sends the Holding Pen videos to the queue, using [settings] (the ones on screen). */
    fun addPenToRun(settings: Settings) {
        if (!busy) return
        if (movePenToQueue(settings, runAudio).isEmpty()) return
        runSettings = settings
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

    /**
     * A video that failed, was skipped or was cancelled goes back to the top of the Holding Pen,
     * keeping its message, so the queue only holds videos still waiting for their turn. Not when
     * some of its parts are already saved: it keeps its place in the queue so Convert can carry on
     * from the next part (see [QueueItem.partsDone]).
     */
    private fun backToPen(at: Int) {
        val item = queue[at]
        if (item.partsDone > 0) return
        queue.removeAt(at)
        // The end of the list is the top of the pen. It follows the settings on screen again.
        queue.add(item.copy(inPen = true, settings = null))
    }

    /** Removes every video from the Holding Pen (or every Audio Mode card, when [audio]). */
    fun clearPen(audio: Boolean = false) {
        val removed = queue.filter { it.inPen && it.isAudio == audio }
        queue.removeAll { it.inPen && it.isAudio == audio }
        appContext?.let { ctx ->
            removed.forEach {
                releaseSource(ctx, it.uri)
                Thumbnails.delete(ctx, it.id)
            }
        }
        pruneAnalysis()
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
        val todo = queue.filter { !it.inPen && it.info != null && it.status == ItemStatus.WAITING && it.isAudio == runAudio }.map { it.id }
        if (todo.isEmpty()) {
            finishWithoutLoop(appCtx, "Nothing left to convert.")
            return
        }
        runList.clear()
        runList.addAll(todo)
        runTotal = todo.size
        val base = fallback.copy(tuning = TuningStore.current)
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
            .filter { !it.inPen && it.id !in known && it.info != null && it.status == ItemStatus.WAITING && it.isAudio == runAudio }
            .map { it.id }
        runList.addAll(joining)
        runTotal = runList.size
        // The progress text is only rewritten when the encoder reports back, which can take a while
        // in pass 1, so bring its "Video n of N" up to date now.
        status = Regex("^(Video|Track) (\\d+) of \\d+").replace(status) { "${it.groupValues[1]} ${it.groupValues[2]} of $runTotal" }
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
        // Pass 1 reports nothing for a long while, so the notification would never update and its
        // hourglass would stand still. While there is no percentage, redraw it every half second.
        val hourglassTicker = scope.launch {
            while (true) {
                delay(500)
                val note = lastNote ?: continue
                if (busy && !paused && pauseRequest != PauseRequest.NOW && progress < 0f &&
                    (AppSettings.get(AppToggle.NOTIFICATION_PROGRESS) || AppSettings.get(AppToggle.STATUS_BAR_ICON)) &&
                    !AppSettings.get(AppToggle.ANIMATIONS_OFF)
                ) {
                    notifyProgress(ctx, note.first, note.second, note.third, force = true)
                }
            }
        }
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
                val sourceInfo = item.info ?: continue
                // An edited video is planned, split and converted as the video the edit makes: its
                // length and (cropped) size stand in for the source's everywhere below.
                val src = item.edit?.effectiveInfo(sourceInfo) ?: sourceInfo
                // This video's own settings (the Advanced tuning always comes from the run).
                val settings = item.settings?.copy(tuning = startSettings.tuning) ?: startSettings

                skipFlag.set(false)
                skipPartFlag.set(false)
                processed++
                val current = processed
                // A function, not a fixed string: the total can grow while this video converts
                // (a video added mid-run), and the label should follow it.
                fun pos() = "${if (item.isAudio) "Track" else "Video"} $current of $runTotal"
                // From here to the end of this video, everything logged is kept for the Log page.
                logBuffer.beginCapture()
                clearNotes()

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

                pausedItemId = -1L
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
                            logBuffer.add(if (item.isAudio) "   copying audio to app cache…" else "   copying video to app cache…") { logLines = it }
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

                        val onLogLine: (String) -> Unit = { line -> logBuffer.add(line) { logLines = it } }
                        val onNoteLine: (String, Boolean) -> Unit = { text, attention -> addNote(text, attention) }
                        val onProgressUpdate: (Float, String) -> Unit = { p, s ->
                            progress = p
                            status = "${pos()}$partLabel: $s"
                            // A Skip/Cancel pressed before ffmpeg was running (e.g. while the
                            // video was being copied) would otherwise be missed.
                            if (skipFlag.get() || cancelAllFlag.get()) Converter.cancel()
                            notifyProgress(ctx, "${pos()}$partLabel", item.name, s, force = false)
                        }
                        val audioSpec = item.audio
                        val res = if (audioSpec != null) {
                            AudioConverter.convert(
                                ctx, staged!!, src, settings, audioSpec,
                                // A trimmed track: the edit's start is where the track begins in the file.
                                startSec = startSec + (item.edit?.clips?.firstOrNull()?.startSec ?: 0.0),
                                clipDurationSec = clipDur ?: src.durationSec, part = part, numParts = numParts,
                                title = audioSpec.title.ifBlank { OutputName.baseFor(item.name, null) },
                                artist = audioSpec.artist,
                                onLog = onLogLine, onNote = onNoteLine, onProgress = onProgressUpdate,
                            )
                        } else {
                            Converter.convert(
                                ctx, staged!!, src, settings,
                                startSec = startSec, clipDurationSec = clipDur,
                                edit = item.edit,
                                thumbSec = item.thumbSec,
                                sourceId = item.uri.toString(),
                                onLog = onLogLine,
                                onNote = onNoteLine,
                                onProgress = onProgressUpdate,
                            )
                        }

                        when (res) {
                            is ConvertResult.Ok -> {
                                // Marks the audio and encoding-effort choices used, e.g. "-AQ" for
                                // audio kept at Quality, "-MF" for muted at Fastest. Split parts also
                                // get a "-Part1", "-Part2"... marker ahead of that (zero-padded when
                                // there are 10 or more, so they sort), and every name ends with the
                                // app tag: "clip-Part02-MQ-WebMChan_for_Android.webm".
                                // A name typed on the card (even while converting) replaces the source's name.
                                val base = OutputName.baseFor(item.name, queue.firstOrNull { it.id == id }?.customName)
                                // An Audio Mode file has no effort setting to mark: "clip-A-WebMChan_for_Android.webm".
                                val effortMarker = if (item.isAudio) "" else OutputName.effortMarker(settings.bestQuality, settings.fastest, settings.fastEncode)
                                val outName = OutputName.build(base, part, numParts, res.audioIncluded, effortMarker)

                                // What the Finished card shows is the converted file itself (its own
                                // length, size and so on), not the source video it came from. Read
                                // it from the file; if that fails, work it out from what we know.
                                val probedOut = Converter.probeFile(res.file) ?: run {
                                    val encodedSec = clipDur
                                        ?: min(src.durationSec, Converter.effectiveMaxSec(settings.maxDurationSec, settings.tuning))
                                    val h = res.height
                                    SourceInfo(
                                        durationSec = encodedSec,
                                        width = if (src.height > 0) (h * src.width.toDouble() / src.height / 2).toInt() * 2 else h,
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
                                // With a picked thumbnail frame, the file starts with it: that first frame is the thumbnail.
                                val hasThumb = Thumbnails.create(
                                    ctx, cardId, null, res.file, outInfo.durationSec,
                                    atSec = if (item.thumbSec != null) 0.0 else null,
                                )

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
                                // The name the file really got (a clash adds "(1)"), for the Finished card's title.
                                val savedName = withContext(Dispatchers.IO) { OutputStorage.fileName(ctx, saved.uri) } ?: outName
                                val mb = String.format(Locale.US, "%.2f", res.bytes / 1048576.0)
                                val detail = buildString {
                                    if (item.isAudio) append("Done: $mb MB, sound at ${res.audioKbps} kbps, picture ${res.height}p.\n")
                                    else append("Done: $mb MB, ${res.height}p, ${res.videoKbps} kbps video.\n")
                                    append(saved.message)
                                    if (res.audioDropped) append("\nSource had no audio.")
                                    if (res.attempts > 1) append("\nTook ${res.attempts} attempts to fit the size limit.")
                                }
                                lastDoneDetail = detail
                                // Each part gets its own card in Finished (a single-part conversion
                                // is unchanged: one card, same as before).
                                noteCardEvent(cardId, CardEventKind.DONE) // before the card exists, so it finds it
                                val doneCard =
                                    QueueItem(
                                        id = cardId,
                                        uri = item.uri,
                                        // The saved file's own name, which always ends in .webm.
                                        name = savedName,
                                        info = outInfo,
                                        hasThumb = hasThumb,
                                        status = ItemStatus.DONE,
                                        detail = detail,
                                        savedUri = saved.uri,
                                        // What this file was made for, shown on its Finished card.
                                        settings = settings,
                                        // Marks that the file starts with a picked thumbnail frame.
                                        thumbSec = item.thumbSec,
                                        // An Audio Mode card stays one (and keeps its pictures, for "Add to pen again").
                                        audio = item.audio,
                                    )
                                finished.add(doneCard)
                                // History keeps it even after it is removed from Finished.
                                history.add(doneCard)
                                persistHistory()
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
                            backToPen(now)
                            skipCount++
                        }
                        itemPaused -> {
                            // Pause Now: back to waiting; starts again after Resume.
                            queue[now] = queue[now].copy(status = ItemStatus.WAITING, detail = null)
                            pausedItemId = id
                        }
                        itemSkipped -> {
                            val note = if (lastPartTried > 0) {
                                "Skipped ($partsSaved of $numParts parts saved).$resumeHint"
                            } else "Skipped."
                            queue[now] = queue[now].copy(status = ItemStatus.CANCELLED, detail = note)
                            backToPen(now)
                            skipCount++
                        }
                        itemFailMsg != null -> {
                            val note = (if (lastPartTried > 0) "$partsSaved of $numParts parts saved before this failed.$resumeHint\n" else "") +
                                itemFailMsg
                            noteCardEvent(id, CardEventKind.FAILED) // before the card moves, so it finds it
                            queue[now] = queue[now].copy(status = ItemStatus.FAILED, detail = note)
                            backToPen(now)
                            failCount++
                            if (AppSettings.get(AppToggle.NOTIFICATION_FAILURE)) ConvertService.postFailed(ctx, id, item.name, itemFailMsg.orEmpty())
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
                            pruneAnalysis()
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
            hourglassTicker.cancel()
            lastNote = null
            // The run is over. Whatever is left in the queue keeps the settings it was sent with.
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
            pausedItemId = -1L
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
            runSettings = null
            // The finished tick always shows when the status bar indicator is on: as the usual Finished
            // notification if that is on and the app is off screen, otherwise as a quiet one.
            if (!uiVisible && AppSettings.get(AppToggle.NOTIFICATION_FINISHED)) ConvertService.postFinished(ctx, text)
            else if (AppSettings.get(AppToggle.STATUS_BAR_ICON)) ConvertService.postFinished(ctx, text, quiet = true)
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
        val sourceLine = if (item.isAudio) {
            "audio, ${String.format(Locale.US, "%.1f", src.durationSec)}s" +
                (if (src.audioCodec.isNotBlank()) ", ${src.audioCodec}" else "") +
                ", ${item.audio?.images?.size ?: 0} picture(s)"
        } else {
            "${src.width}×${src.height}, ${String.format(Locale.US, "%.1f", src.durationSec)}s, " +
                "$fpsText, ${if (src.hasAudio) "has audio" else "no audio"}" +
                (item.edit?.let { " (edited: ${it.describe()})" } ?: "")
        }
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
        // The same single writer thread as the queue saves, so log saves also happen in order.
        writer.execute { ConversionLogStore.save(appCtx, snapshot) }
    }

    /** Updates the ongoing notification, at most about once a second. */
    private fun notifyProgress(ctx: Context, pos: String, name: String, step: String, force: Boolean) {
        if (!busy) return
        lastNote = Triple(pos, name, step)
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastNotifyMs < 1000) return
        lastNotifyMs = now
        val p = progress
        val percent = if (p < 0f) null else (p * 100).toInt().coerceIn(0, 100)
        ConvertService.postProgress(ctx, (if (runAudio) "Converting audio: " else "Converting: ") + pos, "$name\n$step", percent)
    }
}