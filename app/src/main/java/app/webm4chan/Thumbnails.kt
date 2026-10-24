package app.webmchan

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegKitConfig
import com.arthenica.ffmpegkit.ReturnCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Small JPEG preview frames for the queue and Finished cards, stored in the app's private files
 * folder as thumbs/<item id>.jpg. A card knows it has one from [QueueItem.hasThumb]; the file
 * name comes from the item's id, so nothing else needs saving.
 *
 * Android's own frame grabber is tried first (fast, no copying). If it can't read the video,
 * ffmpeg is used instead, which reads anything the rest of the app can.
 */
object Thumbnails {
    private const val DIR = "thumbs"

    /** The longest edge of a stored thumbnail, in pixels. */
    private const val MAX_EDGE = 360

    fun file(ctx: Context, id: Long): File = File(File(ctx.filesDir, DIR), "$id.jpg")

    fun exists(ctx: Context, id: Long): Boolean = file(ctx, id).length() > 0

    fun delete(ctx: Context, id: Long) {
        file(ctx, id).delete()
    }

    /** Deletes thumbnails whose card no longer exists (left over from an app kill, say). */
    fun pruneExcept(ctx: Context, keep: Set<Long>) {
        File(ctx.filesDir, DIR).listFiles()?.forEach {
            val id = it.nameWithoutExtension.toLongOrNull()
            if (id == null || id !in keep) it.delete()
        }
    }

    /**
     * Makes the thumbnail for [id] from either [file] (a plain file, e.g. a conversion that has
     * just finished) or [uri] (a picked video, or a saved output). Returns whether it worked.
     * The frame comes from about 10% of the way in (at most 5 s), as the very first frame is
     * often black.
     */
    suspend fun create(ctx: Context, id: Long, uri: Uri?, file: File?, durationSec: Double): Boolean =
        withContext(Dispatchers.IO) {
            if (uri == null && file == null) return@withContext false
            val dest = file(ctx, id)
            dest.parentFile?.mkdirs()
            val timeSec = (durationSec * 0.1).coerceIn(0.0, 5.0)
            try {
                fromRetriever(ctx, uri, file, timeSec, dest) || fromFfmpeg(ctx, uri, file, timeSec, dest)
            } catch (_: Throwable) {
                // A missing thumbnail is fine; a serious error (e.g. the FFmpeg library not loading) must not close the app.
                dest.delete()
                false
            }
        }

    private fun fromRetriever(ctx: Context, uri: Uri?, file: File?, timeSec: Double, dest: File): Boolean {
        val r = MediaMetadataRetriever()
        return try {
            if (file != null) r.setDataSource(file.absolutePath) else r.setDataSource(ctx, uri!!)
            val us = (timeSec * 1_000_000).toLong()
            // getFrameAtTime applies the video's rotation, so portrait clips come out upright.
            val frame = r.getFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: r.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: return false
            val scale = min(1.0, MAX_EDGE.toDouble() / max(frame.width, frame.height))
            val small = if (scale < 1.0) {
                Bitmap.createScaledBitmap(
                    frame,
                    max(1, (frame.width * scale).roundToInt()),
                    max(1, (frame.height * scale).roundToInt()),
                    true,
                )
            } else frame
            dest.outputStream().use { small.compress(Bitmap.CompressFormat.JPEG, 80, it) }
            if (small !== frame) small.recycle()
            frame.recycle()
            if (dest.length() > 0) true else { dest.delete(); false }
        } catch (_: Exception) {
            dest.delete()
            false
        } finally {
            try { r.release() } catch (_: Exception) { }
        }
    }

    private fun fromFfmpeg(ctx: Context, uri: Uri?, file: File?, timeSec: Double, dest: File): Boolean {
        return try {
            val input = file?.absolutePath ?: FFmpegKitConfig.getSafParameterForRead(ctx, uri!!)
            val session = FFmpegKit.executeWithArguments(
                arrayOf(
                    "-y", "-ss", "%.3f".format(Locale.US, timeSec), "-i", input,
                    "-frames:v", "1", "-an", "-sn", "-dn",
                    "-vf", "scale=w='min($MAX_EDGE,iw)':h=-2",
                    "-q:v", "4", "-update", "1", dest.absolutePath,
                )
            )
            if (ReturnCode.isSuccess(session.returnCode) && dest.length() > 0) true
            else { dest.delete(); false }
        } catch (_: Throwable) {
            dest.delete()
            false
        }
    }
}
