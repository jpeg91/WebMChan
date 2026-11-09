package app.webmchan

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The full README, exactly as it is on GitHub, shown as plain text. The text is the file
 * app/src/main/assets/README.md: when the README changes, copy the new one over it.
 */
@Composable
fun ReadmeScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    BackHandler(onBack = onBack)

    val text by produceState<String?>(null) {
        value = withContext(Dispatchers.IO) {
            try {
                ctx.assets.open("README.md").bufferedReader(Charsets.UTF_8).use { it.readText() }
            } catch (_: Exception) {
                ""
            }
        }
    }
    // Shown a paragraph at a time, so a long README isn't one enormous piece of text.
    val paragraphs = remember(text) { text?.split("\n\n") ?: emptyList() }

    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        P5Button("← Back", onClick = onBack, kind = P5Kind.Ghost, modifier = Modifier.padding(top = 20.dp))
        P5Title("README", size = 30.sp)
        Text(
            "The README from GitHub, as it was when this version was made.",
            style = MaterialTheme.typography.bodySmall,
            color = P5.Grey,
        )
        when {
            text == null -> Text("Loading…", style = MaterialTheme.typography.bodyMedium)
            text!!.isEmpty() -> Text(
                "Couldn't load the README. It's on the GitHub page.",
                style = MaterialTheme.typography.bodyMedium,
            )
            else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(paragraphs) { p -> Text(p, style = MaterialTheme.typography.bodySmall) }
                item { Spacer(Modifier.height(20.dp)) }
            }
        }
    }
}
