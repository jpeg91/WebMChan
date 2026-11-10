// #history
package app.webmchan

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * The History page: a card for every conversion the app has finished, newest first. A card is added
 * when it lands in Finished and stays here when it is removed from the main screen, so Finished can
 * be cleared without losing track of what was made. It is a lazy list because History only grows.
 *
 * The cards are the Finished ones from the main screen ([QueueRow]), with Remove taking a card out
 * of History only. A video picked from the phone keeps a lasting link to its source, so "Add to pen
 * again" works while that source can still be read. A video shared in from another app has its copy
 * deleted when its last queue or Finished card goes, and its History card then says the source is lost.
 */
@Composable
fun HistoryScreen(onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    // Newest first, like every list in the app.
    val cards = BatchRunner.history.asReversed()
    // A finished card only shows what it was made for, never the settings a next conversion would
    // use, so these are placeholders.
    val placeholder = remember { Settings(limitBytes = 0L, maxDurationSec = 0, keepAudio = false) }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item { P5Button("← Back", onClick = onBack, kind = P5Kind.Ghost) }
        item { P5Title("History") }
        item {
            Text(
                "Every video the app has converted, newest first. A card stays here when you remove it " +
                    "from the main screen. Remove from history takes a card out of this list only; " +
                    "the saved video isn't touched.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (cards.isEmpty()) {
            item {
                Text(
                    "No converted videos yet.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        items(cards, key = { it.id }) { card ->
            QueueRow(
                item = card,
                busy = false,
                maxDuration = 0,
                isCustom = false,
                minW = 0,
                minH = 0,
                settings = placeholder,
                showControls = false,
                inHistory = true,
                sourceAvailable = BatchRunner.historySourceOk[card.id] ?: true,
            )
        }
        item { Spacer(Modifier.height(8.dp)) }
    }
}
