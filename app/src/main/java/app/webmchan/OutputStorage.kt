// #outputstorage
package app.webmchan

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.documentfile.provider.DocumentFile
import java.io.File

/**
 * Lets the user pick where converted videos go (internal storage, SD card, ...) using Android's
 * Storage Access Framework. Output always goes into a "WebMChan" subfolder of the chosen folder.
 * If nothing is chosen, or the chosen folder stops being usable, it falls back to the existing
 * Download/WebMChan/ behaviour.
 */
object OutputStorage {
    private const val PREFS = "webmchan"
    private const val KEY_OUTPUT_DIR = "output_dir_uri"
    const val SUBFOLDER = "WebMChan"
    private const val DEFAULT_LABEL = "Internal storage: Download/WebMChan"

    private const val RW = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION

    /** [uri] is where the file ended up (null if nothing could be saved); [message] is for the result text. */
    data class SaveResult(val uri: Uri?, val message: String)

    fun savedDirUri(ctx: Context): Uri? =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_OUTPUT_DIR, null)
            ?.let(Uri::parse)

    /** Call with the result of ActivityResultContracts.OpenDocumentTree(). Throws if the grant can't be kept. */
    fun rememberDir(ctx: Context, uri: Uri) {
        // Keep access after the picker closes and across restarts/reboots.
        ctx.contentResolver.takePersistableUriPermission(uri, RW)

        // Release the previous folder's grant so old ones don't pile up.
        savedDirUri(ctx)?.takeIf { it != uri }?.let {
            try { ctx.contentResolver.releasePersistableUriPermission(it, RW) } catch (_: Exception) { }
        }

        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_OUTPUT_DIR, uri.toString())
            .apply()
    }

    /** Back to the default Download/WebMChan/ location. */
    fun clearDir(ctx: Context) {
        savedDirUri(ctx)?.let {
            try { ctx.contentResolver.releasePersistableUriPermission(it, RW) } catch (_: Exception) { }
        }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove(KEY_OUTPUT_DIR).apply()
    }

    /** Readable save location for the UI, e.g. "SD card / USB: Videos/WebMChan". */
    fun displayName(ctx: Context): String {
        val uri = savedDirUri(ctx) ?: return DEFAULT_LABEL
        val parts = folderParts(uri)
        if (parts == null) {
            // Couldn't read the volume/path from the Uri; fall back to the folder's own name.
            val name = DocumentFile.fromTreeUri(ctx, uri)?.name ?: return DEFAULT_LABEL
            return if (name == SUBFOLDER) name else "$name/$SUBFOLDER"
        }
        val (volume, path) = parts
        // If the chosen folder is (or ends in) WebMChan, files go straight into it: no extra subfolder.
        val fullPath = when {
            path.isEmpty() -> SUBFOLDER
            path == SUBFOLDER || path.endsWith("/$SUBFOLDER") -> path
            else -> "$path/$SUBFOLDER"
        }
        return "$volume: $fullPath"
    }

    // Splits a tree URI's document id ("primary:Movies/Clips", "1234-ABCD:Videos")
    // into a volume label and the folder path inside it.
    private fun folderParts(uri: Uri): Pair<String, String>? = try {
        val docId = DocumentsContract.getTreeDocumentId(uri)
        val volume = docId.substringBefore(':')
        val path = docId.substringAfter(':', "")
        val volumeLabel = if (volume == "primary") "Internal storage" else "External Storage"
        volumeLabel to path
    } catch (_: Exception) {
        null
    }

    /**
     * The name a saved file really ended up with. MediaStore renames a file that clashes with an
     * existing one in the same folder (adding " (1)" and so on), so the name we asked for isn't
     * always the name that exists. Falls back to [requested] if it can't be read.
     */
    private fun actualName(ctx: Context, uri: Uri, requested: String): String {
        val found: String? = try {
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        } catch (_: Exception) {
            null
        }
        return if (found.isNullOrBlank()) requested else found
    }

    /**
     * Saves into <chosen folder>/WebMChan/. If no folder is chosen, or it can't be written to,
     * silently falls back to [fallback] (the existing saveToDownloads) and says so in the message.
     */
    fun save(ctx: Context, file: File, name: String, fallback: () -> Uri?): SaveResult {
        val dirUri = savedDirUri(ctx)
        if (dirUri == null) {
            val uri = fallback()
            return SaveResult(
                uri,
                if (uri != null) "Saved to internal storage: Download/WebMChan/${actualName(ctx, uri, name)}"
                else "Couldn't save to Downloads."
            )
        }

        val saved = saveToChosenFolder(ctx, dirUri, file, name)
        if (saved != null) {
            return SaveResult(saved.first, "Saved to ${displayName(ctx)}/${saved.second}")
        }

        val uri = fallback()
        return SaveResult(
            uri,
            if (uri != null) "Chosen folder unavailable. Saved to internal storage instead: Download/WebMChan/${actualName(ctx, uri, name)}"
            else "Chosen folder unavailable, and saving to Downloads failed too."
        )
    }

    /** Returns the new file's Uri and its final name, or null if anything went wrong. */
    private fun saveToChosenFolder(ctx: Context, dirUri: Uri, file: File, name: String): Pair<Uri, String>? {
        return try {
            // The grant can be revoked in Settings, or the SD card can be removed.
            val held = ctx.contentResolver.persistedUriPermissions
                .any { it.uri == dirUri && it.isWritePermission }
            if (!held) return null

            val root = DocumentFile.fromTreeUri(ctx, dirUri) ?: return null
            if (!root.exists() || !root.canWrite()) return null

            // Use the WebMChan subfolder; if the user picked a folder already named that, use it directly.
            val target = if (root.name == SUBFOLDER) root
            else root.findFile(SUBFOLDER)?.takeIf { it.isDirectory }
                ?: root.createDirectory(SUBFOLDER)
                ?: return null

            // createFile doesn't rename on a clash, so add (1), (2)... ourselves.
            var finalName = name
            var n = 1
            while (target.findFile(finalName) != null) {
                val dot = name.lastIndexOf('.')
                finalName = if (dot >= 0) "${name.substring(0, dot)} ($n)${name.substring(dot)}"
                else "$name ($n)"
                n++
            }

            val doc = target.createFile("video/webm", finalName) ?: return null
            try {
                ctx.contentResolver.openOutputStream(doc.uri)?.use { os ->
                    file.inputStream().use { it.copyTo(os) }
                } ?: run { doc.delete(); return null }
                doc.uri to (doc.name ?: finalName)
            } catch (e: Exception) {
                doc.delete()
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    // ---- Working with a file that was already saved (Rename / Delete on a Finished card) ----

    /** The file's current name, or null if it can't be read (e.g. the file is gone). */
    fun fileName(ctx: Context, uri: Uri): String? = try {
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
            ?.takeIf { it.isNotBlank() }
    } catch (_: Exception) {
        null
    }

    /**
     * Turns what the user typed into the part of a file name before ".webm", or null if it can't be
     * used (empty, or holding characters Android file systems don't allow). A typed ".webm" ending
     * is dropped, since the extension is always added back.
     */
    fun cleanBaseName(input: String): String? {
        var base = input.trim()
        if (base.endsWith(".webm", ignoreCase = true)) base = base.dropLast(5).trim()
        if (base.isEmpty() || base.length > 180) return null
        if (base.any { it in "/\\:*?\"<>|" || it.isISOControl() }) return null
        if (base.all { it == '.' }) return null
        return base
    }

    /** [uri] and [name] are where the file is now (null on failure, with [error] saying why). */
    data class RenameResult(val uri: Uri?, val name: String?, val error: String?)

    /** Renames a saved file to [newName] (which includes ".webm"). Works for both save locations. */
    fun rename(ctx: Context, uri: Uri, newName: String): RenameResult {
        val failed = RenameResult(null, null, "Couldn't rename the file. Is there already one with that name?")
        return try {
            if (DocumentsContract.isDocumentUri(ctx, uri)) {
                // A file in the folder the user chose. The Uri can change when it is renamed.
                val newUri = DocumentsContract.renameDocument(ctx.contentResolver, uri, newName) ?: return failed
                RenameResult(newUri, fileName(ctx, newUri) ?: newName, null)
            } else {
                // A file in the default Download/WebMChan/ (MediaStore). The Uri stays the same.
                val values = ContentValues().apply { put(MediaStore.MediaColumns.DISPLAY_NAME, newName) }
                if (ctx.contentResolver.update(uri, values, null, null) < 1) return failed
                RenameResult(uri, fileName(ctx, uri) ?: newName, null)
            }
        } catch (_: Exception) {
            failed
        }
    }

    /** Deletes a saved file. false if it couldn't be deleted. */
    fun delete(ctx: Context, uri: Uri): Boolean = try {
        if (DocumentsContract.isDocumentUri(ctx, uri)) {
            DocumentsContract.deleteDocument(ctx.contentResolver, uri)
        } else {
            ctx.contentResolver.delete(uri, null, null) > 0
        }
    } catch (_: Exception) {
        false
    }
}
