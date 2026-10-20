package app.webmchan

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
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
        TextButton(onClick = onBack) { Text("← Back") }

        Text("About WebMChan", style = MaterialTheme.typography.headlineMedium)
        if (version != null) Text("Version $version", style = MaterialTheme.typography.bodyMedium)
        TextButton(onClick = onAdvanced) { Text("Advanced settings (for testing) →") }

        Text(
            "WebMChan makes it simple to turn your videos into WebM files that fit a board's rules. " +
                "Pick your videos, pick a board, and get files that are ready to post. " +
                "Everything runs on your phone, and your videos are never uploaded anywhere.",
            style = MaterialTheme.typography.bodyMedium
        )

        Heading("How it works")
        Text(
            "1. Choose one or more videos.\n" +
                "2. Pick the board you're posting to. Its rules (file size, length, minimum resolution and audio) are applied for you.\n" +
                "3. Tap Convert. The app works out the best quality that fits under the size limit and encodes each video in two passes, aiming for about 95% of the limit.\n" +
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
            "Created by jpeg91",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        // England flag (St George's Cross), written as escapes so it survives any file encoding:
        // black flag + tag letters "gbeng" + cancel tag.
        Text(
            "\uD83C\uDFF4\uDB40\uDC67\uDB40\uDC62\uDB40\uDC65\uDB40\uDC6E\uDB40\uDC67\uDB40\uDC7F",
            fontSize = 32.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun Heading(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium)
}

@Composable
private fun LinkItem(title: String, description: String, url: String) {
    val handler = LocalUriHandler.current
    Column(
        Modifier
            .fillMaxWidth()
            .clickable {
                // No browser installed: do nothing rather than crash.
                try { handler.openUri(url) } catch (_: Exception) { }
            }
            .padding(vertical = 6.dp),
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            textDecoration = TextDecoration.Underline,
        )
        Text(description, style = MaterialTheme.typography.bodySmall)
    }
}
