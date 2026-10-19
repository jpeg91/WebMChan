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
import java.util.Locale
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

        val state = BatchStore.load(appCtx) ?: return
        ids.set(maxOf(ids.get(), state.lastId))
        queue.addAll(
            state.queue.map {
                // Whatever was being converted when the app stopped has to start again from the top.
                if (it.status == ItemStatus.CONVERTING) {
                    it.copy(
                        status = ItemStatus.CANCELLED,
                        detail = "Interrupted when the app closed. Press Convert to try again.",
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
        } finally {
            probing--
            persist()
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

    fun removeAt(index: Int) {
        if (index !in queue.indices) return
        appContext?.let { releaseAccess(it, queue[index].uri) }
        queue.removeAt(index)
        persist()
    }

    fun clearQueue() {
        if (busy) return
        appContext?.let { ctx -> queue.forEach { releaseAccess(ctx, it.uri) } }
        queue.clear()
        summary = null
        persist()
    }

    fun clearFinished() {
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
        logBuffer.clear { logLines = it }

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
                queue[idx] = item.copy(status = ItemStatus.CONVERTING, detail = null, savedUri = null)
                persist()
                val pos = "Video ${n + 1} of ${todo.size}"
                progress = -1f
                status = "$pos: Starting…"
                logBuffer.add("=== $pos: ${item.name} ===") { logLines = it }
                notifyProgress(ctx, pos, item.name, "Starting…", force = true)

                val res = Converter.convert(
                    ctx, item.uri, src, settings,
                    onLog = { line -> logBuffer.add(line) { logLines = it } },
                ) { p, s ->
                    progress = p
                    status = "$pos: $s"
                    // A Skip/Cancel pressed before ffmpeg was running (e.g. while the
                    // video was being copied) would otherwise be missed.
                    if (skipFlag.get() || cancelAllFlag.get()) Converter.cancel()
                    notifyProgress(ctx, pos, item.name, s, force = false)
                }

                val at = queue.indexOfFirst { it.id == id }
                if (at < 0) continue
                when (res) {
                    is ConvertResult.Ok -> {
                        val base = item.name.substringBeforeLast('.', item.name)
                        // Marks the audio and encoding-effort choices used, e.g. "-AQ" for
                        // audio kept at Quality, "-MF" for muted at Fastest.
                        val audioMarker = if (res.audioIncluded) "A" else "M"
                        val effortMarker = if (settings.fastest) "F" else if (settings.fastEncode) "S" else "Q"
                        val outName = "$base-$audioMarker$effortMarker.webm"
                        val saved = withContext(Dispatchers.IO) {
                            OutputStorage.save(ctx, res.file, outName) {
                                Converter.saveToDownloads(ctx, res.file, outName)
                            }
                        }
                        res.file.delete()
                        val mb = String.format(Locale.US, "%.2f", res.bytes / 1048576.0)
                        val detail = buildString {
                            append("Done: $mb MB, ${res.height}p, ${res.videoKbps} kbps video.\n")
                            append(saved.message)
                            if (res.trimmed) append("\nCut to ${settings.maxDurationSec}s.")
                            if (res.audioDropped) append("\nSource had no audio.")
                        }
                        val now = queue.indexOfFirst { it.id == id }
                        if (now >= 0) {
                            if (saved.uri != null) {
                                // Done: the card moves from the queue to the Finished list.
                                val doneItem = queue[now].copy(
                                    status = ItemStatus.DONE,
                                    detail = detail,
                                    savedUri = saved.uri,
                                )
                                queue.removeAt(now)
                                finished.add(doneItem)
                                // The source video isn't needed any more.
                                releaseAccess(ctx, item.uri)
                                okCount++
                            } else {
                                queue[now] = queue[now].copy(status = ItemStatus.FAILED, detail = detail, savedUri = null)
                                failCount++
                            }
                        }
                    }
                    is ConvertResult.Fail -> {
                        queue[at] = queue[at].copy(status = ItemStatus.FAILED, detail = res.message)
                        failCount++
                    }
                    ConvertResult.Cancelled -> {
                        val all = cancelAllFlag.get()
                        queue[at] = queue[at].copy(
                            status = ItemStatus.CANCELLED,
                            detail = if (all) "Cancelled." else "Skipped.",
                        )
                        skipCount++
                        if (all) break
                    }
                }
                persist()
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
