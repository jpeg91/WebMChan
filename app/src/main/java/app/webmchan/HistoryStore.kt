// #history
package app.webmchan

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Saves the History list (every card a finished conversion has made, see [BatchRunner.history]) to a
 * file of its own in the app's private storage. It is kept apart from the queue's file because it
 * only grows, and so isn't rewritten every time the queue changes. A card is written the same way
 * as in [BatchStore].
 */
object HistoryStore {
    private const val FILE = "history.json"
    private const val VERSION = 1

    fun save(ctx: Context, items: List<QueueItem>) {
        try {
            val root = JSONObject()
            root.put("version", VERSION)
            root.put("items", JSONArray().apply { items.forEach { put(BatchStore.toJson(it)) } })

            // Written to a temporary file and swapped in, so a crash mid-write can't corrupt the saved copy.
            val tmp = File(ctx.filesDir, "$FILE.tmp")
            tmp.writeText(root.toString())
            val dest = File(ctx.filesDir, FILE)
            if (!tmp.renameTo(dest)) {
                dest.delete()
                tmp.renameTo(dest)
            }
        } catch (_: Exception) {
            // Best effort: losing a save is not worth interrupting anything for.
        }
    }

    /** Empty if nothing was saved, or the file can't be read. */
    fun load(ctx: Context): List<QueueItem> {
        return try {
            val file = File(ctx.filesDir, FILE)
            if (!file.exists()) return emptyList()
            val arr = JSONObject(file.readText()).optJSONArray("items") ?: return emptyList()
            val out = ArrayList<QueueItem>()
            for (i in 0 until arr.length()) {
                BatchStore.fromJson(arr.optJSONObject(i) ?: continue)?.let { out.add(it) }
            }
            out
        } catch (_: Exception) {
            emptyList()
        }
    }
}
