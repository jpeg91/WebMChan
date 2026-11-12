// #history
package app.webmchan

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
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
fun HistoryScreen(onBack: () -> Unit, onBattleCards: () -> Unit) {
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
        item { P5Button("Battle-Cards", onClick = onBattleCards, kind = P5Kind.Secondary) }
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
            if (card.status == ItemStatus.FAILED) {
                DudRow(card)
            } else {
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
        }
        item { Spacer(Modifier.height(8.dp)) }
    }
}

/** Whether the Battle-Cards warning has been shown yet since the app started (it is shown on the first removal, whatever the settings say). */
internal object BattleRemovalWarning {
    var shown = false
}

/** A failed conversion: it has no video, but it is a (very weak) Battle-Card. */
@Composable
private fun DudRow(card: QueueItem) {
    var confirm by remember { mutableStateOf(false) }
    P5Card(accent = P5.GreyDim) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            app.webmchan.battle.CardThumb(card.id, width = 88.dp, height = 56.dp)
            Text(card.name, style = MaterialTheme.typography.titleSmall, color = P5.White, modifier = Modifier.weight(1f))
            P5Tag("Failed", color = P5.RedBright, filled = true)
        }
        Text(
            "This conversion failed, so there is no video. It is a dud Battle-Card.",
            style = MaterialTheme.typography.bodySmall,
            color = P5.Grey,
        )
        card.detail?.takeIf { it.isNotBlank() }?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = P5.GreyDim, maxLines = 3)
        }
        P5Button(
            "Remove from history",
            onClick = { if (AppSettings.get(AppToggle.CONFIRM_DESTRUCTIVE) || !BattleRemovalWarning.shown) confirm = true else BatchRunner.removeFromHistory(card.id) },
            kind = P5Kind.Secondary,
        )
    }
    if (confirm) HistoryRemoveDialog(hasSavedFile = false, onConfirm = { confirm = false; BatchRunner.removeFromHistory(card.id) }, onDismiss = { confirm = false })
}

/** Asks before a card leaves History, and says what that does to Battle-Cards. */
@Composable
internal fun HistoryRemoveDialog(hasSavedFile: Boolean, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = CutCornerShape(topStart = 20.dp, bottomEnd = 20.dp),
        containerColor = P5.Ink,
        titleContentColor = P5.White,
        textContentColor = P5.White,
        title = { Text("Remove from history".uppercase(), style = MaterialTheme.typography.titleMedium) },
        text = {
            Text(
                (if (hasSavedFile) "The saved video isn't touched.\n\n" else "") +
                    "Every card in History is also a Battle-Card. Removing this one takes it out of the Battle-Cards " +
                    "roster, so it can't be picked for a party or met as an opponent in a new run. A card already " +
                    "saved in your party, and a run already in progress, keep their own copy.",
            )
        },
        confirmButton = { P5Button("Remove", onClick = { BattleRemovalWarning.shown = true; onConfirm() }) },
        dismissButton = { P5Button("Cancel", onClick = onDismiss, kind = P5Kind.Ghost) },
    )
}
