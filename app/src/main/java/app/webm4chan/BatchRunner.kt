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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import kotlin.math.min
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

enum class ItemStatus { WAITING, CONVERTING, DONE, FAILED, CANCELLED }

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
)

/**
 * Thread-safe, size-capped log. ffmpeg's repeating "frame=..." status line updates in place
 * instead of flooding the readout.
 */
private class LogBuffer(private val maxLines: Int = 400) {
    private val lock = Any()
    private var lines: List<String> = emptyList()

    fun clear(publish: (List<String>) -> Unit) = synchronized(lock) {
        lines = emptyList()
        publish(lines)
    }

    fun add(raw: String, publish: (List<String>) -> Unit) = synchronized(lock) {
        var next = lines
        for (piece in raw.split('\n', '\r')) {
            val line = piece.trim()
            if (line.isEmpty()) continue
            next = if (line.startsWith("frame=") && next.lastOrNull()?.startsWith("frame=") == true) {
                next.dropLast(1) + line
            } else {
                next + line
            }
        }
        lines = if (next.size > maxLines) next.takeLast(maxLines) else next
        publish(lines)
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
    var logLines by mutableStateOf<List<String>>(emptyList())
        private set

    /** Set by the activity: whether the app screen is visible (no "finished" notification if so). */
    @Volatile
    var uiVisible = false

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val ids = AtomicLong(0)
    private val skipFlag = AtomicBoolean(false)
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

        val state = BatchStore.load(appCtx) ?: return
        ids.set(maxOf(ids.get(), state.lastId))
        queue.addAll(
            state.queue.map {
                // Whatever was being converted when the app stopped has to start again from the top.
                if (it.status == ItemStatus.CONVERTING) {
                    it.copy(
                        status = ItemStatus.CANCELLED,
                        detail = if (it.partsDone > 0 && it.partsDone < it.partsTotal) {
                            "Interrupted when the app closed (${it.partsDone} of ${it.partsTotal} parts saved). " +
                                "Press Convert to continue from part ${it.partsDone + 1}."
                        } else "Interrupted when the app closed. Press Convert to try again.",
                    )
                } else it
            }
        )
        finished.addAll(state.finished)
        scope.launch { verifyRestored(appCtx) }
    }

    /** Writes the current lists to storage (in the background). */
    private fun persist() {
        val ctx = appContext ?: return
        val q = queue.toList()
        val f = finished.toList()
        val lastId = ids.get()
        writer.execute { BatchStore.save(ctx, q, f, lastId) }
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
                val at = queue.indexOfFirst { it.id == id }
                if (at >= 0) queue[at] = queue[at].copy(info = i, probeFailed = i == null)
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

    private fun releaseAccess(ctx: Context, uri: Uri) {
        try {
            ctx.contentResolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: Exception) {
        }
    }

    // ---- Editing the queue (the UI disables these while a batch is running) ----

    fun addVideos(ctx: Context, picked: List<Uri>) {
        val appCtx = ctx.applicationContext
        appContext = appCtx
        // Skip anything already in the queue.
        val fresh = picked.filter { u -> queue.none { it.uri == u } }
        if (fresh.isEmpty()) return
        fresh.forEach { keepAccess(appCtx, it) }
        summary = null
        val added = fresh.map { u -> QueueItem(ids.incrementAndGet(), u, Converter.displayName(appCtx, u)) }
        queue.addAll(added)
        persist()
        scope.launch {
            probing++
            try {
                for (item in added) {
                    val i = Converter.probe(appCtx, item.uri)
                    // The item may have been removed while probing.
                    val at = queue.indexOfFirst { it.id == item.id }
                    if (at >= 0) queue[at] = queue[at].copy(info = i, probeFailed = i == null)
                    if (i != null) setThumb(appCtx, item.id, item.uri, null, i.durationSec, queue)
                }
            } finally {
                probing--
                persist()
            }
        }
    }

    fun move(index: Int, delta: Int) {
        val to = index + delta
        if (index !in queue.indices || to !in queue.indices) return
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
        if (busy) return
        val at = queue.indexOfFirst { it.id == id }
        if (at < 0) return
        val item = queue[at]
        if (item.partsDone > 0) return
        val value = extra.coerceAtLeast(0)
        if (value == item.extraParts) return
        queue[at] = item.copy(extraParts = value)
        persist()
    }

    fun removeAt(index: Int) {
        if (index !in queue.indices) return
        appContext?.let {
            releaseAccess(it, queue[index].uri)
            Thumbnails.delete(it, queue[index].id)
        }
        queue.removeAt(index)
        persist()
    }

    fun clearQueue() {
        if (busy) return
        appContext?.let { ctx ->
            queue.forEach {
                releaseAccess(ctx, it.uri)
                Thumbnails.delete(ctx, it.id)
            }
        }
        queue.clear()
        summary = null
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
        val todo = queue.filter { it.info != null }.map { it.id }
        if (todo.isEmpty()) return

        summary = null
        runTotal = todo.size
        skipFlag.set(false)
        cancelAllFlag.set(false)
        busy = true
        progress = -1f // indeterminate until pass 2 reports real progress
        status = "Starting…"

        // Every video about to be tried again goes back to "Waiting", so a leftover "Cancelled",
        // "Skipped" or "Failed" message from an earlier run doesn't sit on cards the batch hasn't
        // reached yet. (Parts already saved are remembered separately in partsDone / partsTotal.)
        for (id in todo) {
            val at = queue.indexOfFirst { it.id == id }
            if (at >= 0 && (queue[at].status != ItemStatus.WAITING || queue[at].detail != null)) {
                queue[at] = queue[at].copy(status = ItemStatus.WAITING, detail = null)
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

        scope.launch { runBatch(appCtx, todo, settings) }
    }

    fun skipCurrent() {
        if (!busy) return
        skipFlag.set(true)
        Converter.cancel()
    }

    fun cancelAll() {
        if (!busy) return
        cancelAllFlag.set(true)
        Converter.cancel()
    }

    private suspend fun runBatch(ctx: Context, todo: List<Long>, settings: Settings) {
        var okCount = 0
        var failCount = 0
        var skipCount = 0
        try {
            for ((n, id) in todo.withIndex()) {
                if (cancelAllFlag.get()) break
                val idx = queue.indexOfFirst { it.id == id }
                if (idx < 0) continue
                val item = queue[idx]
                val src = item.info ?: continue

                skipFlag.set(false)
                val pos = "Video ${n + 1} of ${todo.size}"

                // Videos longer than the target's limit are split into consecutive parts rather
                // than cut off, each converted and saved on its own. The video is divided evenly
                // (e.g. 250 s with a 120 s limit = 3 parts of ~83.3 s) so there's never a tiny
                // leftover part, and no part is longer than the effective limit (the board's limit
                // minus the length grace; see Converter.effectiveMaxSec). A tiny overshoot is trimmed
                // off the end instead of becoming a part (see Converter.partCount). Splits chosen on
                // the card (item.extraParts) are added on top of that minimum.
                val numParts = Converter.partsFor(src.durationSec, settings.maxDurationSec, settings.tuning, item.extraParts)
                val partDur = min(src.durationSec / numParts, Converter.effectiveMaxSec(settings.maxDurationSec, settings.tuning))
                val base = item.name.substringBeforeLast('.', item.name)

                // Carry on after the parts that are already saved, if this video was part-way
                // through. That only holds if it would be split exactly the same way now (the same
                // number of parts means identical boundaries); if the target changed since, the
                // earlier parts don't line up with the new ones, so start again from part 1.
                val resumeFrom = if (numParts > 1 && item.partsDone in 1 until numParts && item.partsTotal == numParts) {
                    item.partsDone + 1
                } else 1
                if (resumeFrom > 1) {
                    logBuffer.add("=== $pos: ${item.name}: parts 1-${resumeFrom - 1} already saved, continuing from part $resumeFrom/$numParts ===") { logLines = it }
                } else if (item.partsDone > 0) {
                    logBuffer.add("=== $pos: ${item.name}: split has changed since the earlier attempt, starting again from part 1 ===") { logLines = it }
                }

                queue[idx] = item.copy(
                    status = ItemStatus.CONVERTING, detail = null, savedUri = null,
                    partsDone = resumeFrom - 1, partsTotal = if (numParts > 1) numParts else 0,
                )
                persist()

                var partsSaved = resumeFrom - 1
                var itemFailMsg: String? = null
                var itemCancelledAll = false
                var itemSkipped = false

                // One private copy of the source serves every part (and every attempt) of this video,
                // and is deleted as soon as the video is finished with, however that happens.
                var staged: File? = null
                try {
                    for (part in resumeFrom..numParts) {
                        if (cancelAllFlag.get()) { itemCancelledAll = true; break }

                        val startSec = (part - 1) * partDur
                        val clipDur = if (numParts > 1) min(partDur, src.durationSec - startSec) else null
                        val partLabel = if (numParts > 1) ", part $part/$numParts" else ""
                        val partTag = if (numParts > 1) " (part $part/$numParts)" else ""

                        skipFlag.set(false)
                        progress = -1f
                        status = "$pos$partLabel: Starting…"
                        logBuffer.add("=== $pos$partTag: ${item.name} ===") { logLines = it }
                        notifyProgress(ctx, "$pos$partLabel", item.name, "Starting…", force = true)

                        if (staged == null) {
                            logBuffer.add("   copying video to app cache…") { logLines = it }
                            val st = Converter.stageSource(
                                ctx, item.uri,
                                shouldStop = { skipFlag.get() || cancelAllFlag.get() },
                            ) { f ->
                                progress = f
                                status = "$pos: Preparing video…"
                                notifyProgress(ctx, "$pos$partLabel", item.name, "Preparing video…", force = false)
                            }
                            when (st) {
                                is StageResult.Ok -> staged = st.file
                                is StageResult.Fail -> { itemFailMsg = st.message; break }
                                StageResult.Cancelled -> {
                                    if (cancelAllFlag.get()) itemCancelledAll = true else itemSkipped = true
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
                            status = "$pos$partLabel: $s"
                            // A Skip/Cancel pressed before ffmpeg was running (e.g. while the
                            // video was being copied) would otherwise be missed.
                            if (skipFlag.get() || cancelAllFlag.get()) Converter.cancel()
                            notifyProgress(ctx, "$pos$partLabel", item.name, s, force = false)
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
                                val outInfo = Converter.probeFile(res.file) ?: run {
                                    val encodedSec = clipDur
                                        ?: min(src.durationSec, Converter.effectiveMaxSec(settings.maxDurationSec, settings.tuning))
                                    val h = res.height
                                    SourceInfo(
                                        durationSec = encodedSec,
                                        width = (h * src.width.toDouble() / src.height / 2).toInt() * 2,
                                        height = h,
                                        fps = min(src.fps, 30.0),
                                        hasAudio = res.audioIncluded,
                                    )
                                }
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
                                    if (res.attempts > 1) {
                                        append("\nTook ${res.attempts} attempts to fit the size limit")
                                        append(if (res.audioAnalysed) "; the audio was analysed on its own for a closer fit." else ".")
                                    }
                                }
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
                                    )
                                )
                                partsSaved++
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
                                if (cancelAllFlag.get()) itemCancelledAll = true else itemSkipped = true
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
                    val resumeHint = if (numParts > 1 && partsSaved in 1 until numParts) {
                        " Press Convert to continue from part ${partsSaved + 1}."
                    } else ""
                    when {
                        itemCancelledAll -> {
                            val note = if (resumeHint.isNotEmpty()) {
                                "Cancelled ($partsSaved of $numParts parts saved).$resumeHint"
                            } else "Cancelled."
                            queue[now] = queue[now].copy(status = ItemStatus.CANCELLED, detail = note)
                            skipCount++
                        }
                        itemSkipped -> {
                            val note = if (partsSaved > 0) {
                                "Skipped ($partsSaved of $numParts parts saved).$resumeHint"
                            } else "Skipped."
                            queue[now] = queue[now].copy(status = ItemStatus.CANCELLED, detail = note)
                            skipCount++
                        }
                        itemFailMsg != null -> {
                            val note = (if (partsSaved > 0) "$partsSaved of $numParts parts saved before this failed.$resumeHint\n" else "") +
                                itemFailMsg
                            queue[now] = queue[now].copy(status = ItemStatus.FAILED, detail = note)
                            failCount++
                        }
                        else -> {
                            // All parts done: the card moves from the queue (the per-part cards
                            // above are what shows up in Finished).
                            queue.removeAt(now)
                            // The source video isn't needed any more, nor its thumbnail.
                            releaseAccess(ctx, item.uri)
                            Thumbnails.delete(ctx, item.id)
                            okCount++
                        }
                    }
                }
                persist()
                if (itemCancelledAll) break
            }
        } finally {
            persist()
            val text = buildString {
                append("Finished: $okCount done")
                if (failCount > 0) append(", $failCount failed")
                if (skipCount > 0) append(", $skipCount skipped or cancelled")
                if (cancelAllFlag.get()) append(" (cancelled)")
            }
            summary = text
            busy = false
            // Remove the ongoing notification; tell the user the result if the app isn't on screen.
            try {
                ctx.stopService(Intent(ctx, ConvertService::class.java))
            } catch (_: Exception) {
            }
            ConvertService.clearProgress(ctx)
            if (!uiVisible) ConvertService.postFinished(ctx, text)
        }
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
