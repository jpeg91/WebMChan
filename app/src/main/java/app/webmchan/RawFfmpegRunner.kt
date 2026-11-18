// #rawffmpegrunner
package app.webmchan

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegSession
import com.arthenica.ffmpegkit.ReturnCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Backs the Raw FFmpeg page (see RawFfmpegScreen): what's typed in and the run itself both live
 * here, not in the composable, so closing the page (or the activity being recreated) doesn't
 * abandon a run in progress or lose what was typed. Reopening the page just shows where it's up to,
 * the same way BatchRunner lets a batch survive the screen closing.
 *
 * Only one of this and a BatchRunner conversion runs at a time: see [running] and the matching
 * check in [BatchRunner.start]. Nothing stops the app process itself being killed while this runs
 * in the background (unlike a batch conversion, this isn't wrapped in a foreground service), so a
 * long raw command is safest run with the app in the foreground.
 */
object RawFfmpegRunner {
    // What's typed in. Kept here (not in the screen's own state) so it survives leaving the page.
    var inputUri by mutableStateOf<Uri?>(null)
        private set
    var inputName by mutableStateOf("")
        private set
    var argsText by mutableStateOf("-i %IN% -c:v libvpx-vp9 -crf 30 -b:v 0 -c:a libopus -b:a 96k %OUT%")
    var outName by mutableStateOf("output.webm")

    fun setInput(uri: Uri?, name: String) {
        inputUri = uri
        inputName = name
    }

    var running by mutableStateOf(false)
        private set
    var log by mutableStateOf("")
        private set
    var resultMsg by mutableStateOf<String?>(null)
        private set
    var resultFailed by mutableStateOf(false)
        private set
    var savedUri by mutableStateOf<Uri?>(null)
        private set
    var savedMime by mutableStateOf("*/*")
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    // The ffmpeg session actually running right now, so Cancel can stop just this one (not
    // FFmpegKit.cancel(), which would also hit an unrelated session if one were somehow running).
    @Volatile
    private var activeSession: FFmpegSession? = null

    fun run(ctx: Context) {
        if (running || BatchRunner.busy) return
        val uri = inputUri ?: return
        val args = argsText.trim()
        if (args.isEmpty()) {
            resultMsg = "Enter the ffmpeg arguments first."
            resultFailed = true
            return
        }
        val outBaseName = outName.trim().ifBlank { "output" }.replace('/', '_').replace("..", "_")
        val appCtx = ctx.applicationContext
        running = true
        log = ""
        resultMsg = null
        resultFailed = false
        savedUri = null
        scope.launch {
            var staged: File? = null
            var outFile: File? = null
            try {
                val stage = Converter.stageSource(appCtx, uri, shouldStop = { false }) { }
                val stagedFile = (stage as? StageResult.Ok)?.file
                if (stagedFile == null) {
                    resultMsg = (stage as? StageResult.Fail)?.message ?: "Couldn't read the input file."
                    resultFailed = true
                    return@launch
                }
                staged = stagedFile
                val finalOutFile = File.createTempFile("webmchan-raw-", "-$outBaseName", appCtx.cacheDir)
                outFile = finalOutFile

                val tokens = tokenizeArgs(args)
                    .map { it.replace("%IN%", stagedFile.absolutePath).replace("%OUT%", finalOutFile.absolutePath) }
                if (tokens.isEmpty()) {
                    resultMsg = "Couldn't parse that command line."
                    resultFailed = true
                    return@launch
                }

                val lines = StringBuilder()
                val session = withContext(Dispatchers.IO) {
                    runSession(tokens) { msg ->
                        lines.append(msg).append('\n')
                        log = lines.toString()
                    }
                }

                if (ReturnCode.isCancel(session.returnCode)) {
                    resultMsg = "Cancelled."
                    resultFailed = true
                    return@launch
                }
                if (!ReturnCode.isSuccess(session.returnCode) || !finalOutFile.exists() || finalOutFile.length() == 0L) {
                    resultMsg = "ffmpeg didn't produce an output file (return code ${session.returnCode})."
                    resultFailed = true
                    return@launch
                }

                val mime = mimeFor(outBaseName)
                val saved = withContext(Dispatchers.IO) {
                    OutputStorage.save(appCtx, finalOutFile, outBaseName, mime) {
                        Converter.saveToDownloads(appCtx, finalOutFile, outBaseName, mime)
                    }
                }
                savedUri = saved.uri
                savedMime = mime
                resultMsg = saved.message
                resultFailed = saved.uri == null
            } catch (e: Exception) {
                resultMsg = "Failed: ${e.message}"
                resultFailed = true
            } finally {
                staged?.delete()
                outFile?.delete()
                running = false
            }
        }
    }

    /**
     * Runs [tokens] asynchronously and suspends until ffmpeg finishes (or is cancelled), without
     * touching FFmpegKitConfig's single global log callback — so this never races a batch
     * conversion's own log callback, even if one somehow started at the same moment.
     */
    private suspend fun runSession(tokens: List<String>, onLog: (String) -> Unit): FFmpegSession =
        suspendCancellableCoroutine { cont ->
            val session = FFmpegKit.executeWithArgumentsAsync(
                tokens.toTypedArray(),
                { completed -> if (cont.isActive) cont.resumeWith(Result.success(completed)) },
                { log -> onLog(log.message) },
                { },
            )
            activeSession = session
            cont.invokeOnCancellation { session.cancel() }
        }

    /** Stops the session that's running right now, if any. Does nothing to any other ffmpeg work. */
    fun cancel() {
        activeSession?.let { FFmpegKit.cancel(it.sessionId) }
    }
}
