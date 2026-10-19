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

    data class State(val queue: List<QueueItem>, val finished: List<QueueItem>, val lastId: Long)

    fun save(ctx: Context, queue: List<QueueItem>, finished: List<QueueItem>, lastId: Long) {
        try {
            val root = JSONObject()
            root.put("version", VERSION)
            root.put("lastId", lastId)
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
        i.detail?.let { put("detail", it) }
        i.savedUri?.let { put("savedUri", it.toString()) }
        i.info?.let {
            put(
                "info",
                JSONObject().apply {
                    put("d", it.durationSec)
                    put("w", it.width)
                    put("h", it.height)
                    put("fps", it.fps)
                    put("audio", it.hasAudio)
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
                SourceInfo(it.getDouble("d"), it.getInt("w"), it.getInt("h"), it.getDouble("fps"), it.getBoolean("audio"))
            },
            probeFailed = o.optBoolean("probeFailed", false),
            status = try {
                ItemStatus.valueOf(o.getString("status"))
            } catch (_: Exception) {
                ItemStatus.WAITING
            },
            detail = if (o.has("detail")) o.getString("detail") else null,
            savedUri = if (o.has("savedUri")) Uri.parse(o.getString("savedUri")) else null,
        )
    } catch (_: Exception) {
        null
    }
}
