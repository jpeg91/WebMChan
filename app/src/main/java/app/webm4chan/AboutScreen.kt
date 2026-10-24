package app.webmchan

import android.content.Context
import android.os.Build
import android.os.Process
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private const val REPO_URL = "https://github.com/jpeg91/WebMChan"

/** A simple About page: what the app is, how it works, what it's built with, and where to find the source. */
@Composable
fun AboutScreen(onBack: () -> Unit, onAdvanced: () -> Unit = {}) {
    val ctx = LocalContext.current
    val version = remember {
        try {
            ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName
        } catch (e: Exception) {
            null
        }
    }

    // The system back gesture/button closes this page instead of leaving the app.
    BackHandler(onBack = onBack)

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        P5Button("← Back", onClick = onBack, kind = P5Kind.Ghost)

        P5Title("About WebMChan", size = 30.sp)
        if (version != null) {
            Text(
                "Version $version".uppercase(),
                style = MaterialTheme.typography.labelMedium,
                color = P5.RedBright,
            )
        }
        P5Button(
            "Advanced settings (for testing) →",
            onClick = onAdvanced,
            kind = P5Kind.Secondary,
            modifier = Modifier.fillMaxWidth(),
        )

        Text(
            "WebMChan, the no fuss 4chan WebM converter for Android.\n\n" +
                "Pick a video (or a whole batch), choose a board, get .webm files that fit.\n\n" +
                "Select from a list of boards and their requirement presets (or set your own).\n\n" +
                "Converted videos target the highest possible quality within the board's constraints.\n\n" +
                "Everything runs locally. The app has no network permission, nothing is uploaded.",
            style = MaterialTheme.typography.bodyMedium
        )

        Heading("How it works")
        Text(
            "1. Choose one or more videos.\n" +
                "2. Pick the board you're posting to. Its rules (file size, length, minimum resolution and audio) are applied for you.\n" +
                "3. Tap Convert. Long videos will be split. The app works out the best quality that fits under the size limit and encodes each video in two passes, aiming for about 99% of the limit.\n" +
                "4. If a result still comes out over the limit, it's retried automatically a little smaller.\n" +
                "5. Finished videos are saved to your chosen folder (Download/WebMChan by default) and can be shared straight from the app.",
            style = MaterialTheme.typography.bodyMedium
        )
        Text(
            "The README on GitHub explains each step in more detail.",
            style = MaterialTheme.typography.bodySmall
        )

        Heading("Built with")
        LinkItem(
            "FFmpeg",
            "The engine that does the video conversion.",
            "https://ffmpeg.org"
        )
        LinkItem(
            "FFmpegKit (community-maintained)",
            "Packages FFmpeg for Android apps. WebMChan uses the ffmpegkit-maintained build.",
            "https://github.com/ffmpegkit-maintained/ffmpeg"
        )
        LinkItem(
            "libvpx",
            "Creates the VP8 and VP9 video.",
            "https://www.webmproject.org/code/"
        )
        LinkItem(
            "libvorbis",
            "Creates the Vorbis audio.",
            "https://xiph.org/vorbis/"
        )
        LinkItem(
            "Jetpack Compose and Material 3",
            "Build the app's interface.",
            "https://developer.android.com/jetpack/compose"
        )
        Text(
            "Thank you to everyone behind these projects.",
            style = MaterialTheme.typography.bodySmall
        )

        Heading("Source code")
        LinkItem(
            "WebMChan on GitHub",
            "Source code, README and bug reports. WebMChan is free and open source.",
            REPO_URL
        )
        LinkItem(
            "Latest release",
            "Download the newest version.",
            "$REPO_URL/releases/latest"
        )

        Text(
            "Created by jpeg91\nWith great thanks to:\nEveryone who's tested or reported issues\nMy darling wife, Lauren",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        // England flag (St George's Cross), written as escapes so it survives any file encoding:
        // black flag + tag letters "gbeng" + cancel tag.
        Text(
            "🏴󠁧󠁢󠁥󠁮󠁧󠁿",
            fontSize = 32.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )

        DeviceReadout(ctx)
        Spacer(Modifier.height(16.dp))
    }
}

/** What this phone is, and whether WebMChan can run on it. Copyable, so testers can send it on. */
@Composable
private fun DeviceReadout(ctx: Context) {
    val report = remember { deviceReport(ctx) }
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }

    Heading("This device")
    P5Card(Modifier.fillMaxWidth()) {
        Text(
            if (report.supported) "Supported" else "Not supported",
            style = MaterialTheme.typography.titleSmall,
            color = if (report.supported) P5.White else MaterialTheme.colorScheme.error,
        )
        report.problems.forEach {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        Text(report.lines.joinToString("\n"), style = MaterialTheme.typography.bodySmall)
    }
    P5Button(
        if (copied) "Copied ✓" else "Copy device details",
        onClick = {
            clipboard.setText(AnnotatedString(report.asText()))
            copied = true
        },
        kind = P5Kind.Secondary,
        modifier = Modifier.fillMaxWidth(),
    )
}

private class DeviceReport(val supported: Boolean, val problems: List<String>, val lines: List<String>) {
    fun asText(): String =
        (listOf(if (supported) "Supported" else "Not supported") + problems + lines).joinToString("\n")
}

private fun deviceReport(ctx: Context): DeviceReport {
    val version = try {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName
    } catch (e: Exception) {
        null
    }
    val abis = Build.SUPPORTED_ABIS.toList()
    val is64 = Process.is64Bit()
    val minSdk = ctx.applicationInfo.minSdkVersion // what the app was built to need
    val sdk = Build.VERSION.SDK_INT

    val problems = mutableListOf<String>()
    if (sdk < minSdk) problems.add("This app needs Android API $minSdk or newer; this device has API $sdk.")
    if ("arm64-v8a" !in abis) problems.add("This app needs a 64-bit ARM (arm64-v8a) phone; this device doesn't list it.")
    if (!is64) problems.add("The app is running as a 32-bit process, but its video engine is 64-bit only.")

    val lines = listOf(
        "App: WebMChan ${version ?: "(version unknown)"}",
        "Device: ${Build.MANUFACTURER} ${Build.MODEL}",
        "Android: ${Build.VERSION.RELEASE} (API $sdk, app needs $minSdk or newer)",
        "Supported architectures: ${abis.joinToString(", ")}",
        "App process: ${if (is64) "64-bit" else "32-bit"}",
    )
    return DeviceReport(problems.isEmpty(), problems, lines)
}

@Composable
private fun Heading(text: String) {
    Row(Modifier.fillMaxWidth()) { P5SectionTitle(text) }
}

@Composable
private fun LinkItem(title: String, description: String, url: String) {
    val handler = LocalUriHandler.current
    P5Card(
        Modifier
            .fillMaxWidth()
            .clickable {
                // No browser installed: do nothing rather than crash.
                try { handler.openUri(url) } catch (_: Exception) { }
            },
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            color = P5.RedBright,
            textDecoration = TextDecoration.Underline,
        )
        Text(description, style = MaterialTheme.typography.bodySmall)
    }
}
