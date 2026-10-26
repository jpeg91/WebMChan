// #conversionlogs
package app.webmchan

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * One video's conversion as the Log page shows it: what it was, how it ended, and the complete
 * log of that conversion (every attempt, every part), ready to copy.
 */
data class ConversionLog(
    /** The queue item's id. The same video tried again later gets another entry with the same id. */
    val id: Long,
    val name: String,
    /** DONE, FAILED or CANCELLED (cancelled or skipped: nothing was saved). */
    val outcome: ItemStatus,
    val whenMillis: Long,
    /** e.g. "1920×1080, 52.1s, 30 fps, has audio". */
    val sourceLine: String,
    /** What it was converted with, as the cards show it. */
    val settingsLine: String,
    /** The result or error text the card showed. */
    val detail: String,
    /** The whole log of this conversion, one line per line. */
    val log: String,
)

/**
 * Saves the recent conversion logs to a file in the app's private storage, so they are still
 * there after the app is closed (the live log on the main screen is cleared when a new batch starts).
 */
object ConversionLogStore {
    /** How long a conversion's log is kept: 12 hours from when it finished. */
    const val KEEP_MILLIS = 12L * 60 * 60 * 1000

    /** A safety limit on how many are kept, so the saved file can't grow without bound. The oldest go first. */
    const val MAX_ENTRIES = 100

    private const val FILE = "conversion_logs.json"

    fun save(ctx: Context, logs: List<ConversionLog>) {
        try {
            val arr = JSONArray()
            logs.forEach {
                arr.put(
                    JSONObject().apply {
                        put("id", it.id)
                        put("name", it.name)
                        put("outcome", it.outcome.name)
                        put("when", it.whenMillis)
                        put("source", it.sourceLine)
                        put("settings", it.settingsLine)
                        put("detail", it.detail)
                        put("log", it.log)
                    }
                )
            }
            // Write to a temporary file, then swap it in, so a crash mid-write can't corrupt the saved copy.
            val tmp = File(ctx.filesDir, "$FILE.tmp")
            tmp.writeText(JSONObject().put("logs", arr).toString())
            val dest = File(ctx.filesDir, FILE)
            if (!tmp.renameTo(dest)) {
                dest.delete()
                tmp.renameTo(dest)
            }
        } catch (_: Exception) {
            // Best effort: losing a log is not worth interrupting anything for.
        }
    }

    fun load(ctx: Context): List<ConversionLog> {
        return try {
            val f = File(ctx.filesDir, FILE)
            if (!f.exists()) return emptyList()
            val arr = JSONObject(f.readText()).getJSONArray("logs")
            (0 until arr.length()).mapNotNull { n ->
                try {
                    val o = arr.getJSONObject(n)
                    ConversionLog(
                        id = o.getLong("id"),
                        name = o.getString("name"),
                        outcome = ItemStatus.valueOf(o.getString("outcome")),
                        whenMillis = o.getLong("when"),
                        sourceLine = o.optString("source", ""),
                        settingsLine = o.optString("settings", ""),
                        detail = o.optString("detail", ""),
                        log = o.optString("log", ""),
                    )
                } catch (_: Exception) {
                    null
                }
            }.filter { it.whenMillis >= System.currentTimeMillis() - KEEP_MILLIS }.takeLast(MAX_ENTRIES)
        } catch (_: Exception) {
            emptyList()
        }
    }
}
