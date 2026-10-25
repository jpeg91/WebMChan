package app.webmchan

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Saves the queue and the Finished list to a small JSON file in the app's private storage, so they
 * come back if the app is closed or Android stops it. Loaded once, when the app starts.
 */
object BatchStore {
    private const val FILE = "batch_state.json"
    private const val VERSION = 1

    /** [runActive]: a batch was running (or paused) when this was saved, so it didn't finish cleanly. */
    data class State(val queue: List<QueueItem>, val finished: List<QueueItem>, val lastId: Long, val runActive: Boolean)

    fun save(ctx: Context, queue: List<QueueItem>, finished: List<QueueItem>, lastId: Long, runActive: Boolean) {
        try {
            val root = JSONObject()
            root.put("version", VERSION)
            root.put("lastId", lastId)
            root.put("runActive", runActive)
            root.put("queue", JSONArray().apply { queue.forEach { put(toJson(it)) } })
            root.put("finished", JSONArray().apply { finished.forEach { put(toJson(it)) } })

            // Write to a temporary file, then swap it in, so a crash mid-write can't corrupt the saved copy.
            val tmp = File(ctx.filesDir, "$FILE.tmp")
            tmp.writeText(root.toString())
            val dest = File(ctx.filesDir, FILE)
            if (!tmp.renameTo(dest)) {
                dest.delete()
                tmp.renameTo(dest)
            }
        } catch (_: Exception) {
            // Best effort: losing a save is not worth interrupting a conversion for.
        }
    }

    /** null if nothing was saved, or the file can't be read. */
    fun load(ctx: Context): State? {
        return try {
            val file = File(ctx.filesDir, FILE)
            if (!file.exists()) return null
            val root = JSONObject(file.readText())
            State(
                queue = readItems(root.optJSONArray("queue")),
                finished = readItems(root.optJSONArray("finished")),
                lastId = root.optLong("lastId", 0L),
                runActive = root.optBoolean("runActive", false),
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun readItems(arr: JSONArray?): List<QueueItem> {
        if (arr == null) return emptyList()
        val out = ArrayList<QueueItem>()
        for (i in 0 until arr.length()) {
            fromJson(arr.optJSONObject(i) ?: continue)?.let { out.add(it) }
        }
        return out
    }

    private fun toJson(i: QueueItem): JSONObject = JSONObject().apply {
        put("id", i.id)
        put("uri", i.uri.toString())
        put("name", i.name)
        put("status", i.status.name)
        put("probeFailed", i.probeFailed)
        put("partsDone", i.partsDone)
        put("partsTotal", i.partsTotal)
        put("extraParts", i.extraParts)
        put("hasThumb", i.hasThumb)
        put("inPen", i.inPen)
        i.detail?.let { put("detail", it) }
        i.savedUri?.let { put("savedUri", it.toString()) }
        // The Advanced tuning is global, so it isn't saved here.
        i.settings?.let {
            put(
                "settings",
                JSONObject().apply {
                    put("lb", it.limitBytes)
                    put("dur", it.maxDurationSec)
                    put("audio", it.keepAudio)
                    put("minW", it.minWidth)
                    put("minH", it.minHeight)
                    put("fast", it.fastEncode)
                    put("vp9", it.useVp9)
                    put("fastest", it.fastest)
                    put("fps", it.targetFps)
                    put("custom", it.isCustom)
                    put("ak", it.audioKbps)
                    put("cw", it.customWidth)
                    put("ch", it.customHeight)
                    put("label", it.targetLabel)
                }
            )
        }
        i.info?.let {
            put(
                "info",
                JSONObject().apply {
                    put("d", it.durationSec)
                    put("w", it.width)
                    put("h", it.height)
                    put("fps", it.fps)
                    put("audio", it.hasAudio)
                    put("bytes", it.fileBytes)
                    put("fmt", it.container)
                    put("vc", it.videoCodec)
                    put("vk", it.videoKbps)
                    put("ac", it.audioCodec)
                }
            )
        }
    }

    private fun fromJson(o: JSONObject): QueueItem? = try {
        QueueItem(
            id = o.getLong("id"),
            uri = Uri.parse(o.getString("uri")),
            name = o.getString("name"),
            info = o.optJSONObject("info")?.let {
                SourceInfo(
                    it.getDouble("d"), it.getInt("w"), it.getInt("h"), it.getDouble("fps"), it.getBoolean("audio"),
                    // Absent in files saved by older versions.
                    fileBytes = it.optLong("bytes", 0L),
                    container = it.optString("fmt", ""),
                    videoCodec = it.optString("vc", ""),
                    videoKbps = it.optInt("vk", 0),
                    audioCodec = it.optString("ac", ""),
                )
            },
            probeFailed = o.optBoolean("probeFailed", false),
            status = try {
                ItemStatus.valueOf(o.getString("status"))
            } catch (_: Exception) {
                ItemStatus.WAITING
            },
            detail = if (o.has("detail")) o.getString("detail") else null,
            savedUri = if (o.has("savedUri")) Uri.parse(o.getString("savedUri")) else null,
            // Absent in files saved by older versions: those read back as "not split".
            partsDone = o.optInt("partsDone", 0),
            partsTotal = o.optInt("partsTotal", 0),
            // Absent in files saved by older versions: no extra splits.
            extraParts = o.optInt("extraParts", 0).coerceAtLeast(0),
            hasThumb = o.optBoolean("hasThumb", false),
            // Absent in files saved by older versions: not in the Holding Pen.
            inPen = o.optBoolean("inPen", false),
            // Absent when no run was active (and in files saved by older versions).
            settings = o.optJSONObject("settings")?.let {
                runCatching {
                    Settings(
                        limitBytes = it.getLong("lb"),
                        maxDurationSec = it.getInt("dur"),
                        keepAudio = it.getBoolean("audio"),
                        minWidth = it.optInt("minW", 0),
                        minHeight = it.optInt("minH", 0),
                        fastEncode = it.optBoolean("fast", false),
                        useVp9 = it.optBoolean("vp9", false),
                        fastest = it.optBoolean("fastest", false),
                        targetFps = it.optInt("fps", Converter.DEFAULT_FPS),
                        isCustom = it.optBoolean("custom", false),
                        // Absent in files saved by older versions: no custom resolution.
                        // Absent in files saved by older versions: the old fixed 64 kbps.
                        audioKbps = it.optInt("ak", Converter.DEFAULT_AUDIO_KBPS),
                        customWidth = it.optInt("cw", 0),
                        customHeight = it.optInt("ch", 0),
                        targetLabel = it.optString("label", ""),
                    )
                }.getOrNull()
            },
        )
    } catch (_: Exception) {
        null
    }
}
