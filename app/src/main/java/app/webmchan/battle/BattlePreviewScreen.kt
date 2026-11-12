// #battle
package app.webmchan.battle

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.webmchan.BatchRunner
import app.webmchan.P5
import app.webmchan.P5Button
import app.webmchan.P5Card
import app.webmchan.P5Kind
import app.webmchan.P5Tag
import app.webmchan.P5Title

/**
 * The Battle-Cards roster: every conversion in History as a card, newest first, with a spread chart
 * at the top and a button to start a run (see [BattleHost]).
 */
@Composable
fun BattlePreviewScreen(
    cards: List<BattleCard>,
    onBack: () -> Unit,
    onPlay: () -> Unit,
    /** A line saying where the saved run is up to, or null when there is none. */
    savedRun: String?,
    onResume: () -> Unit,
) {
    BackHandler(onBack = onBack)

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item { P5Button("← Back", onClick = onBack, kind = P5Kind.Ghost) }
        item { P5Title("Battle-Cards") }
        if (savedRun != null) {
            item { P5Button("Resume run", onClick = onResume) }
            item { Text(savedRun, style = MaterialTheme.typography.bodySmall, color = P5.Amber) }
        }
        item { P5Button("Start a run", onClick = onPlay, kind = if (savedRun != null) P5Kind.Secondary else P5Kind.Primary, enabled = cards.isNotEmpty()) }
        item {
            Text(
                "Every conversion in History as a card. The stats come from the converted file, and a " +
                    "split video is one card. Pick up to six for your party, then fight the rest of your " +
                    "History, weakest first.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (cards.isEmpty()) {
            item {
                Text(
                    "No converted videos yet. Convert something and its card will appear here.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            item { SpreadCard(cards) }
        }
        items(cards, key = { it.id }) { card -> CardView(card) }
        item { Spacer(Modifier.height(8.dp)) }
    }
}

/** One colour per type, so a card's type reads at a glance. */
internal fun typeColor(type: CardType): Color = when (type) {
    CardType.MAINSTREAM -> P5.RedBright
    CardType.MATROSKA -> P5.Amber
    CardType.LEGACY -> P5.Grey
    CardType.AUDIO -> Color(0xFF9C86E8)
    CardType.WILD -> P5.White
    CardType.NONE -> P5.GreyDim
}

@Composable
private fun SpreadCard(cards: List<BattleCard>) {
    val spread = remember(cards) { BattleSpread.stats(cards) }
    val types = remember(cards) { BattleSpread.types(cards) }
    P5Card {
        Text("${cards.size} cards", style = MaterialTheme.typography.titleSmall, color = P5.White)
        Text(
            "How the cards spread across the bands of each stat (left = weakest, right = strongest). " +
                "If a stat piles up in one band, its thresholds need moving.",
            style = MaterialTheme.typography.bodySmall,
            color = P5.Grey,
        )
        for (h in spread) {
            val most = (h.counts.maxOrNull() ?: 0).coerceAtLeast(1)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(h.label, style = MaterialTheme.typography.bodySmall, color = P5.Grey, modifier = Modifier.width(84.dp))
                Row(
                    Modifier.weight(1f).height(24.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalAlignment = Alignment.Bottom,
                ) {
                    for (count in h.counts) {
                        Box(Modifier.weight(1f).height((2f + 22f * count / most).dp).background(if (count == 0) P5.Line else P5.Red))
                    }
                }
            }
        }
        Text(
            "Types: " + types.joinToString("  ") { (t, n) -> "${t.label} $n" },
            style = MaterialTheme.typography.bodySmall,
            color = P5.Grey,
        )
    }
}

@Composable
private fun CardView(card: BattleCard) {
    val color = typeColor(card.type)
    P5Card(accent = color) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            CardThumb(card.id, width = 88.dp, height = 56.dp)
            Text(card.name, style = MaterialTheme.typography.titleMedium, color = P5.White, modifier = Modifier.weight(1f))
            P5Tag(card.type.label, color = color, filled = true)
        }
        Text(
            card.sourceName,
            style = MaterialTheme.typography.bodySmall,
            color = P5.Grey,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            "Power ${card.stats.power}" + (if (card.isAudio) " · sound" else "") + (if (card.isDud) " · dud (failed conversion)" else "") + (if (card.parts > 1) " · ${card.parts} parts" else ""),
            style = MaterialTheme.typography.bodySmall,
            color = P5.White,
        )
        StatRow("HP", card.stats.hp, color, unknown = Missing.SOURCE_SIZE in card.missing)
        StatRow("Attack", card.stats.attack, color)
        StatRow("Sp. Attack", card.stats.spAttack, color)
        StatRow("Defence", card.stats.defence, color, unknown = Missing.SIZE_LIMIT in card.missing)
        StatRow("Sp. Defence", card.stats.spDefence, color)
        StatRow("Speed", card.stats.speed, color, unknown = Missing.CONVERSION_TIME in card.missing)
        for (move in card.moves) {
            Text(
                move.name + "  ·  " + move.summary(),
                style = MaterialTheme.typography.bodySmall,
                color = P5.White,
            )
        }
        if (card.missing.isNotEmpty()) {
            Text(
                "Not recorded for this older card, so shown as middle: " + card.missing.joinToString { it.label } + ".",
                style = MaterialTheme.typography.bodySmall,
                color = P5.Amber,
            )
        }
    }
}

/** A stat as eight small blocks, filled up to its band. A grey one is a guess: the data wasn't recorded. */
@Composable
private fun StatRow(label: String, band: Int, color: Color, unknown: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = P5.Grey, modifier = Modifier.width(84.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            for (i in 0 until BAND_COUNT) {
                Box(
                    Modifier
                        .width(13.dp)
                        .height(8.dp)
                        .background(if (i < band) (if (unknown) P5.GreyDim else color) else P5.Line),
                )
            }
        }
        Text(
            if (unknown) "$band?" else "$band",
            style = MaterialTheme.typography.bodySmall,
            color = if (unknown) P5.GreyDim else P5.White,
        )
    }
}
