```kotlin
package app.webm4chan

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegKitConfig
import com.arthenica.ffmpegkit.FFprobeKit
import com.arthenica.ffmpegkit.ReturnCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.min

/** What we learned about the picked video. */
data class SourceInfo(
    val durationSec: Double,
    val width: Int,
    val height: Int,
    val fps: Double,
    val hasAudio: Boolean,
)

data class Settings(
    val limitBytes: Long,
    val maxDurationSec: Int,
    val keepAudio: Boolean,
)

sealed class ConvertResult {
    data class Ok(
        val file: File,
        val bytes: Long,
        val height: Int,
        val videoKbps: Int,
        val trimmed: Boolean,
        val audioDropped: Boolean,
    ) : ConvertResult()

    data class Fail(val message: String) : ConvertResult()
    object Cancelled : ConvertResult()
}

object Converter {

    /** 4chan: most boards 4096 KB, /wsg/ 6144 KB; max duration 300 s. */
    const val DEFAULT_MAX_DURATION_SEC = 300

    private const val AUDIO_KBPS = 64

    // Aim a little under the limit: container overhead + rate-control overshoot.
    private const val INITIAL_TARGET = 0.95
    private const val MAX_ATTEMPTS = 4

    fun cancel() {
        FFmpegKit.cancel()
    }

    fun displayName(ctx: Context, uri: Uri): String {
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) return c.getString(0) }
        return "video"
    }

    suspend fun probe(ctx: Context, uri: Uri): SourceInfo? = withContext(Dispatchers.IO) {
        val path = FFmpegKitConfig.getSafParameterForRead(ctx, uri)
        val info = FFprobeKit.getMediaInformation(path).mediaInformation ?: return@withContext null
        val dur = info.duration?.toDoubleOrNull() ?: return@withContext null

        var w = 0
        var h = 0
        var fps = 30.0
        var audio = false
        for (s in info.streams) {
            when (s.type) {
                "video" -> if (w == 0) {
                    w = s.width?.toInt() ?: 0
                    h = s.height?.toInt() ?: 0
                    fps = parseRate(s.averageFrameRate) ?: 30.0
                }
                "audio" -> audio = true
            }
        }
        if (w == 0 || h == 0) return@withContext null
        SourceInfo(dur, w, h, fps, audio)
    }

    private fun parseRate(r: String?): Double? {
        if (r.isNullOrBlank()) return null
        val parts = r.split("/")
        val v = if (parts.size == 2) {
            val n = parts[0].toDoubleOrNull() ?: return null
            val d = parts[1].toDoubleOrNull() ?: return null
            if (d == 0.0) return null else n / d
        } else r.toDoubleOrNull() ?: return null
        return if (v in 1.0..240.0) v else null
    }

    /** Biggest height (<=1080, <= source) that still gets a decent bits-per-pixel for VP8. */
    private fun chooseHeight(vKbps: Int, src: SourceInfo): Int {
        val fps = min(src.fps, 30.0)
        val aspect = src.width.toDouble() / src.height
        val ladder = listOf(1080, 720, 480, 360, 240)
        val candidates = if (src.height <= 1080) {
            listOf(src.height) + ladder.filter { it < src.height }
        } else ladder
        for (h in candidates) {
            val w = h * aspect
            val bpp = vKbps * 1000.0 / (w * h * fps)
            if (bpp >= 0.06) return h / 2 * 2
        }
        return candidates.last() / 2 * 2
    }

    suspend fun convert(
        ctx: Context,
        uri: Uri,
        src: SourceInfo,
        settings: Settings,
        onProgress: (fraction: Float, status: String) -> Unit,
    ): ConvertResult = withContext(Dispatchers.IO) {
        val inputFile = File.createTempFile(
            "webmchan-input-",
            ".video",
            ctx.cacheDir
        )

        try {
            val source = ctx.contentResolver.openInputStream(uri)
                ?: run {
                    inputFile.delete()
                    return@withContext ConvertResult.Fail(
                        "Could not open the selected video."
                    )
                }

            source.use { srcStream ->
                inputFile.outputStream().use { dst ->
                    srcStream.copyTo(dst)
                }
            }
        } catch (e: Exception) {
            inputFile.delete()
            return@withContext ConvertResult.Fail(
                "Could not copy selected video: ${e.message}"
            )
        }

        val input = inputFile.absolutePath

        try {
            val duration = min(src.durationSec, settings.maxDurationSec.toDouble())
            val trimmed = src.durationSec > settings.maxDurationSec
            val audioOn = settings.keepAudio && src.hasAudio
            val audioKbps = if (audioOn) AUDIO_KBPS else 0

            val out = File(ctx.cacheDir, "out.webm")
            val passLog = File(ctx.cacheDir, "ffpass").absolutePath
            var budgetBytes = settings.limitBytes * INITIAL_TARGET
            var lastHeight = 0
            var lastKbps = 0

            for (attempt in 1..MAX_ATTEMPTS) {
                val totalKbps = budgetBytes * 8.0 / duration / 1000.0
                val vKbps = (totalKbps - audioKbps).toInt()
                if (vKbps < 40) {
                    return@withContext ConvertResult.Fail(
                        "Video is too long for this size limit" +
                            if (audioOn) " with audio. Try removing audio." else "."
                    )
                }
                val height = chooseHeight(vKbps, src)
                lastHeight = height
                lastKbps = vKbps

                val filters = buildList {
                    if (src.fps > 30.5) add("fps=30")
                    if (height < src.height || src.height > 1080) add("scale=-2:$height")
                }.joinToString(",").ifEmpty { "null" }

                val common = listOf(
                    "-y", "-i", input, "-t", "%.3f".format(java.util.Locale.US, duration),
                    "-map", "0:v:0", "-sn", "-dn", "-map_metadata", "-1",
                    "-vf", filters,
                    "-c:v", "libvpx", "-b:v", "${vKbps}k",
                    "-deadline", "good", "-cpu-used", "2",
                    "-auto-alt-ref", "1", "-lag-in-frames", "25",
                    "-passlogfile", passLog,
                )

                fun stats(pass: Int) {
                    FFmpegKitConfig.enableStatisticsCallback { st ->
                        val frac = (st.time.toDouble() / 1000.0 / duration).coerceIn(0.0, 1.0)
                        val overall = ((pass - 1) + frac) / 2.0
                        onProgress(
                            overall.toFloat(),
                            "Attempt $attempt/$MAX_ATTEMPTS, pass $pass/2 (${height}p, ${vKbps} kbps)"
                        )
                    }
                }

                stats(1)
                val p1 = FFmpegKit.executeWithArguments(
                    (common + listOf("-pass", "1", "-an", "-f", "null", "/dev/null")).toTypedArray()
                )
                if (ReturnCode.isCancel(p1.returnCode)) return@withContext ConvertResult.Cancelled
                if (!ReturnCode.isSuccess(p1.returnCode)) {
                    return@withContext ConvertResult.Fail("Encoding failed (pass 1).\n${tail(p1.allLogsAsString)}")
                }

                stats(2)
                val audioArgs = if (audioOn) {
                    listOf("-map", "0:a:0", "-c:a", "libvorbis", "-b:a", "${audioKbps}k", "-ac", "2")
                } else listOf("-an")
                val p2 = FFmpegKit.executeWithArguments(
                    (common + listOf("-pass", "2") + audioArgs + listOf("-f", "webm", out.absolutePath)).toTypedArray()
                )
                if (ReturnCode.isCancel(p2.returnCode)) return@withContext ConvertResult.Cancelled
                if (!ReturnCode.isSuccess(p2.returnCode)) {
                    return@withContext ConvertResult.Fail("Encoding failed (pass 2).\n${tail(p2.allLogsAsString)}")
                }

                val size = out.length()
                if (size in 1..settings.limitBytes) {
                    onProgress(1f, "Done")
                    cleanupLogs(ctx)
                    return@withContext ConvertResult.Ok(
                        out, size, lastHeight, lastKbps, trimmed, settings.keepAudio && !src.hasAudio
                    )
                }
                // Too big: shrink the budget proportionally and retry.
                budgetBytes *= (settings.limitBytes * 0.97) / size.toDouble()
            }
            cleanupLogs(ctx)
            ConvertResult.Fail("Couldn't get under the size limit after $MAX_ATTEMPTS attempts.")
        } finally {
            inputFile.delete()
        }
    }

    private fun tail(s: String?): String = s?.takeLast(400)?.trim().orEmpty()

    private fun cleanupLogs(ctx: Context) {
        ctx.cacheDir.listFiles()?.filter { it.name.startsWith("ffpass") }?.forEach { it.delete() }
    }

    /** Copies the result into Downloads/WebM4chan and returns its content Uri. */
    fun saveToDownloads(ctx: Context, file: File, name: String): Uri? {
        val resolver = ctx.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, "video/webm")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/WebM4chan")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
        try {
            resolver.openOutputStream(uri)?.use { os -> file.inputStream().use { it.copyTo(os) } }
                ?: return null
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            return null
        }
        return uri
    }
}
```
