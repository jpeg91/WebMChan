// #battle
package app.webmchan.battle

import android.content.Context
import java.io.File
import java.util.concurrent.Executors

/**
 * Keeps Battle-Cards' [BattleSave] (the party with records, and the run in progress) in a file of its
 * own in the app's private storage, apart from History. Written to a temporary file and swapped in, so
 * a crash mid-write can't corrupt it.
 */
object BattleStore {
    private const val FILE = "battlecards.json"
    private val writer = Executors.newSingleThreadExecutor()

    /** An empty save if nothing was saved, or the file can't be read. */
    fun load(ctx: Context): BattleSave = try {
        val f = File(ctx.filesDir, FILE)
        if (f.exists()) BattleSaveCodec.decode(f.readText()) else BattleSave()
    } catch (_: Exception) {
        BattleSave()
    }

    /** Saves in the background, in the order asked. Best effort: a lost save is not worth interrupting anything for. */
    fun save(ctx: Context, save: BattleSave) {
        val dir = ctx.filesDir
        val text = BattleSaveCodec.encode(save)
        writer.execute {
            try {
                val tmp = File(dir, "$FILE.tmp")
                tmp.writeText(text)
                val dest = File(dir, FILE)
                if (!tmp.renameTo(dest)) {
                    dest.delete()
                    tmp.renameTo(dest)
                }
            } catch (_: Exception) {
            }
        }
    }

    /** Forgets everything (Reset app). */
    fun clear(ctx: Context) {
        try {
            File(ctx.filesDir, FILE).delete()
        } catch (_: Exception) {
        }
    }
}
