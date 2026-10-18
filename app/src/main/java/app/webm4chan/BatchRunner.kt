package app.webmchan

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

internal enum class ItemStatus { WAITING, CONVERTING, DONE, FAILED, CANCELLED }

/** One video in the queue or the finished list. Immutable: update by replacing the list entry with a copy. */
internal data class QueueItem(
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
internal class LogBuffer(private val maxLines: Int = 400) {
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
 * Owns the queue, the finished list and the conversion loop. It lives as long as the app's
 * process, not as long as the screen, so a running (or just finished) batch is still there
 * when the app is reopened. The screen only displays this state and calls the functions below.
 *
 * While a batch runs, [ConvertService] keeps the process alive in the background.
 */
internal object BatchRunner {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val skipFlag = AtomicBoolean(false)
    private val cancelAllFlag = AtomicBoolean(false)
    private val idCounter = AtomicLong(0)
    private val logBuffer = LogBuffer()

    /** Videos waiting to be converted, plus any that failed or were skipped, in processing order. */
    val queue = mutableStateListOf<QueueItem>()

    /** Videos converted and saved this session, oldest first. */
    val finished = mutableStateListOf<QueueItem>()

    /** Number of probe passes currently running (new videos are probed one after another). */
    var probing by mutableIntStateOf(0)
        private set
    var busy by mutableStateOf(false)
        private set
    /** 0..1, or below 0 while the encode is in pass 1 and has no real progress to report. */
    var progress by mutableFloatStateOf(0f)
        private set
    var status by mutableStateOf("")
        private set
    var summary by mutableStateOf<String?>(null)
        private set
    /** How many videos the current (or last) run works through. */
    var runTotal by mutableIntStateOf(0)
        private set
    var logLines by mutableStateOf<List<String>>(emptyList())
        private set

    /** Set by the screen while it is visible, so the service can skip the "finished" notification. */
    @Volatile
    var appVisible: Boolean = false

    /** True if the last run was stopped with Cancel all. */
    val cancelRequested: Boolean
        get() = cancelAllFlag.get()

    fun addVideos(ctx: Context, uris: List<Uri>) {
        if (busy) return
        val app = ctx.applicationContext
        // Skip anything already in the queue. A video that was already converted can be added again.
        val fresh = uris.filter { u -> queue.none { it.uri == u } }
        if (fresh.isEmpty()) return
        summary = null
        val added = fresh.map { u -> QueueItem(idCounter.incrementAndGet(), u, Converter.displayName(app, u)) }
        queue.addAll(added)
        scope.launch {
            probing++
            try {
                for (item in added) {
                    val info = Converter.probe(app, item.uri)
                    // The item may have been removed while probing.
                    val at = queue.indexOfFirst { it.id == item.id }
                    if (at >= 0) queue[at] = queue[at].copy(info = info, probeFailed = info == null)
                }
            } finally {
                probing--
            }
        }
    }

    fun removeAt(index: Int) {
        if (!busy && index in queue.indices) queue.removeAt(index)
    }

    fun move(index: Int, delta: Int) {
        val to = index + delta
        if (busy || index !in queue.indices || to !in queue.indices) return
        val t = queue[index]
        queue[index] = queue[to]
        queue[to] = t
    }

    fun clearQueue() {
        if (!busy) queue.clear()
    }

    fun clearFinished() {
        finished.clear()
        if (!busy) summary = null
    }

    fun skipCurrent() {
        skipFlag.set(true)
        Converter.cancel()
    }

    fun cancelAll() {
        cancelAllFlag.set(true)
        Converter.cancel()
    }

    /** Starts converting everything in the queue that has been read successfully. */
    fun start(ctx: Context, settings: Settings) {
        if (busy) return
        val app = ctx.applicationContext
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

        // Foreground service: keeps the process alive and shows a notification while we work.
        ConvertService.start(app)
        scope.launch { run(app, settings, todo) }
    }

    private suspend fun run(app: Context, settings: Settings, todo: List<Long>) {
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
                val pos = "Video ${n + 1} of ${todo.size}"
                progress = -1f
                status = "$pos: Starting…"
                logBuffer.add("=== $pos: ${item.name} ===") { logLines = it }

                try {
                    val res = Converter.convert(
                        app, item.uri, src, settings,
                        onLog = { line -> logBuffer.add(line) { logLines = it } },
                    ) { p, s ->
                        progress = p
                        status = "$pos: $s"
                        // A Skip/Cancel pressed before ffmpeg was running (e.g. while the
                        // video was being copied) would otherwise be missed.
                        if (skipFlag.get() || cancelAllFlag.get()) Converter.cancel()
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
                                OutputStorage.save(app, res.file, outName) {
                                    Converter.saveToDownloads(app, res.file, outName)
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
                            if (saved.uri != null) {
                                // Done: the card moves from the queue to the finished list.
                                queue.removeAt(at)
                                finished.add(item.copy(status = ItemStatus.DONE, detail = detail, savedUri = saved.uri))
                                okCount++
                            } else {
                                queue[at] = queue[at].copy(status = ItemStatus.FAILED, detail = detail)
                                failCount++
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
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Don't let one video's unexpected error take the whole app down mid-batch.
                    val at = queue.indexOfFirst { it.id == id }
                    if (at >= 0) {
                        queue[at] = queue[at].copy(
                            status = ItemStatus.FAILED,
                            detail = "Unexpected error: ${e.message ?: e.javaClass.simpleName}",
                        )
                    }
                    failCount++
                }
            }
        } finally {
            summary = buildString {
                append("Finished: $okCount done")
                if (failCount > 0) append(", $failCount failed")
                if (skipCount > 0) append(", $skipCount skipped or cancelled")
                if (cancelAllFlag.get()) append(" (cancelled)")
            }
            busy = false
        }
    }
}
