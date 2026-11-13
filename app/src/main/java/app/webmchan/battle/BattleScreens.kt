// #battle
package app.webmchan.battle

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.webmchan.P5
import app.webmchan.P5Button
import app.webmchan.P5Card
import app.webmchan.P5Kind
import app.webmchan.P5Tag
import app.webmchan.P5Title

/*
 * The information screens of Battle-Cards: the type chart and how it all works, the records board, and
 * the inspect page for a card in a fight. They only show things (see BattleInfo for the wording).
 */

// -- Type chart and how it works ---------------------------------------------------------------

private fun shortLabel(t: CardType): String = when (t) {
    CardType.MAINSTREAM -> "Main"
    CardType.MATROSKA -> "Matr"
    CardType.LEGACY -> "Old"
    CardType.AUDIO -> "Aud"
    CardType.WILD -> "Wild"
    CardType.NONE -> "-"
}

/** A page with the type chart and the rules, reached from the Battle-Cards home screen. */
@Composable
fun BattleHelpPage(onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { P5Button("← Back", onClick = onBack, kind = P5Kind.Ghost) }
        item { P5Title("Type chart & rules") }
        item { HelpBody() }
        item { Spacer(Modifier.height(8.dp)) }
    }
}

/** The type chart, the rules and the statuses, one after another. Used on the help page and inside a fight. */
@Composable
internal fun HelpBody() {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        TypeChartCard()
        for ((title, body) in BattleInfo.howItWorks) HelpBlock(title, body)
        P5Card(accent = P5.Line) {
            Text("Statuses", style = MaterialTheme.typography.titleSmall, color = P5.RedBright)
            for (s in StatusEffect.values()) {
                Text(s.label, style = MaterialTheme.typography.labelLarge, color = P5.White)
                Text(BattleInfo.statusDetail(s) + ".", style = MaterialTheme.typography.bodySmall, color = P5.Grey)
            }
        }
    }
}

@Composable
private fun HelpBlock(title: String, body: String) {
    P5Card(accent = P5.Line) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = P5.RedBright)
        Text(body, style = MaterialTheme.typography.bodySmall, color = P5.Grey)
    }
}

/** Who beats whom: a grid with the attacker down the side and the defender along the top, then a line for each type. */
@Composable
internal fun TypeChartCard() {
    P5Card(accent = P5.Red) {
        Text("Type chart", style = MaterialTheme.typography.titleSmall, color = P5.RedBright)
        Text(
            "A move's type against the type it hits. ▲ is super effective (x${TypeChart.SUPER_EFFECTIVE}), ▼ is resisted (x${TypeChart.RESISTED}), a dot is normal.",
            style = MaterialTheme.typography.bodySmall,
            color = P5.Grey,
        )
        val ring = CardType.fighting
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("attacker ↓  defender →", style = MaterialTheme.typography.labelSmall, color = P5.GreyDim, modifier = Modifier.weight(2f), maxLines = 2)
            for (d in ring) Text(shortLabel(d), style = MaterialTheme.typography.labelMedium, color = typeColor(d), modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
        }
        for (a in ring) {
            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(2f)) { P5Tag(a.label, color = typeColor(a), filled = true) }
                for (d in ring) {
                    val m = BattleInfo.chartCell(a, d)
                    Text(
                        if (m > 1.0) "▲" else if (m < 1.0) "▼" else "·",
                        style = MaterialTheme.typography.titleSmall,
                        color = if (m > 1.0) P5.RedBright else if (m < 1.0) P5.DangerBright else P5.GreyDim,
                        modifier = Modifier.weight(1f),
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
        for (t in ring) {
            Column(Modifier.padding(top = 6.dp)) {
                Text(t.label + " · " + BattleInfo.sourceOf(t), style = MaterialTheme.typography.labelLarge, color = typeColor(t))
                Text(
                    "Strong against " + BattleInfo.strongAgainst(t).joinToString(", ") { it.label } + ". Weak to " + BattleInfo.weakTo(t).joinToString(", ") { it.label } + ".",
                    style = MaterialTheme.typography.bodySmall,
                    color = P5.Grey,
                )
            }
        }
        Text("Neutral (the secret boss, and some moves) is never helped or hurt by the chart.", style = MaterialTheme.typography.bodySmall, color = P5.GreyDim)
    }
}

// -- Records ------------------------------------------------------------------------------------

/** Totals, bests and a board of the cards that have won the most. */
@Composable
fun BattleRecordsPage(save: BattleSave, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val r = save.records
    val ranked = remember(save.party) {
        save.party.filter { it.record.fightsWon + it.record.fightsLost > 0 }
            .sortedWith(compareByDescending<SavedPartyCard> { it.record.fightsWon }.thenBy { it.record.fightsLost })
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { P5Button("← Back", onClick = onBack, kind = P5Kind.Ghost) }
        item { P5Title("Records") }
        item {
            P5Card(accent = P5.Red) {
                Text("Best run", style = MaterialTheme.typography.titleSmall, color = P5.RedBright)
                if (r.bestRunWins > 0) {
                    Text("${r.bestRunWins} fight${if (r.bestRunWins == 1) "" else "s"} won in one run", style = MaterialTheme.typography.titleMedium, color = P5.White)
                    if (r.bestRunParty.isNotEmpty()) Text(r.bestRunParty.joinToString(", "), style = MaterialTheme.typography.bodySmall, color = P5.Grey)
                } else {
                    Text("No finished run yet.", style = MaterialTheme.typography.bodySmall, color = P5.Grey)
                }
                if (r.highestLevel > 0) Text("Highest level: Lv ${r.highestLevel} (${r.highestLevelName})", style = MaterialTheme.typography.bodySmall, color = P5.White)
            }
        }
        item {
            P5Card(accent = P5.Line) {
                Text("Totals", style = MaterialTheme.typography.titleSmall, color = P5.RedBright)
                val fights = r.fightsWon + r.fightsLost
                RecordLine("Runs started", r.runsStarted.toString())
                RecordLine("Runs finished", "${r.runsEnded} (${r.runsCompleted} won all the way through)")
                RecordLine("Secret boss beaten", r.bossesBeaten.toString())
                RecordLine("Fights won", r.fightsWon.toString())
                RecordLine("Fights lost", r.fightsLost.toString())
                if (fights > 0) RecordLine("Win rate", "${r.fightsWon * 100 / fights}%")
            }
        }
        item { Text("Cards", style = MaterialTheme.typography.titleSmall, color = P5.RedBright) }
        if (ranked.isEmpty()) {
            item { Text("No card has fought yet. Start a run and they will appear here.", style = MaterialTheme.typography.bodySmall, color = P5.Grey) }
        } else {
            items(ranked, key = { it.card.id }) { p ->
                P5Card(accent = typeColor(p.card.type)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        CardThumb(p.card.id, width = 64.dp, height = 40.dp)
                        Column(Modifier.weight(1f)) {
                            Text(p.card.name, style = MaterialTheme.typography.titleSmall, color = P5.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                "Won ${p.record.fightsWon} · Lost ${p.record.fightsLost}" + if (p.record.runsCompleted > 0) " · Runs completed ${p.record.runsCompleted}" else "",
                                style = MaterialTheme.typography.bodySmall,
                                color = P5.Grey,
                            )
                        }
                    }
                }
            }
        }
        item { Spacer(Modifier.height(8.dp)) }
    }
}

@Composable
private fun RecordLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = P5.Grey, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodySmall, color = P5.White)
    }
}

// -- Inspecting a card in a fight ---------------------------------------------------------------

/**
 * Everything about one fighter in a fight: its picture, type matchups, stats (as bands and as real
 * numbers), status and stat changes, and its moves with what each one does and how it fares against
 * [foeType]. [record] is the card's win/loss record when it has one.
 */
@Composable
internal fun InspectPage(card: BattleCard, f: FighterView, foeType: CardType?, record: CardRecord?, onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    Column(
        Modifier.fillMaxSize().background(P5.Black).verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            CardThumb(card.id, width = 150.dp, height = 84.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(f.name, style = MaterialTheme.typography.titleMedium, color = P5.White, maxLines = 3, overflow = TextOverflow.Ellipsis)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    P5Tag(f.type.label, color = typeColor(f.type), filled = true)
                    Text("Lv ${f.level}", style = MaterialTheme.typography.labelLarge, color = P5.Grey)
                }
                Text("HP ${f.hp} / ${f.maxHp}", style = MaterialTheme.typography.bodySmall, color = P5.White)
                Text("Power ${card.stats.power}", style = MaterialTheme.typography.bodySmall, color = P5.GreyDim)
            }
        }
        if (f.type != CardType.NONE) {
            Text(
                "Strong against " + BattleInfo.strongAgainst(f.type).joinToString(", ") { it.label } +
                    ". Weak to " + BattleInfo.weakTo(f.type).joinToString(", ") { it.label } + ". Made from: " + BattleInfo.sourceOf(f.type) + ".",
                style = MaterialTheme.typography.bodySmall,
                color = P5.Grey,
            )
        } else {
            Text(BattleInfo.sourceOf(CardType.NONE) + ".", style = MaterialTheme.typography.bodySmall, color = P5.Grey)
        }
        if (card.isDud) Text("A failed conversion: the weakest card there is, with one useless move.", style = MaterialTheme.typography.bodySmall, color = P5.DangerBright)
        if (card.parts > 1) Text("A split video: ${card.parts} parts folded into one card.", style = MaterialTheme.typography.bodySmall, color = P5.Grey)
        if (record != null && record.fightsWon + record.fightsLost > 0) {
            Text("Record: won ${record.fightsWon}, lost ${record.fightsLost}" + if (record.runsCompleted > 0) ", runs completed ${record.runsCompleted}" else "", style = MaterialTheme.typography.bodySmall, color = P5.RedBright)
        }

        // Right now: status and stat changes.
        val shifted = StatId.values().filter { f.stages[it.ordinal] != 0 }
        if (f.status != null || shifted.isNotEmpty()) {
            P5Card(accent = P5.DangerBright) {
                Text("Right now", style = MaterialTheme.typography.titleSmall, color = P5.White)
                f.status?.let {
                    Text(it.label, style = MaterialTheme.typography.labelLarge, color = P5.DangerBright)
                    Text(BattleInfo.statusDetail(it) + ".", style = MaterialTheme.typography.bodySmall, color = P5.Grey)
                }
                for (stat in shifted) {
                    val n = f.stages[stat.ordinal]
                    Text("${stat.label} ${BattleInfo.stageText(n)}", style = MaterialTheme.typography.bodySmall, color = if (n > 0) P5.RedBright else P5.DangerBright)
                }
            }
        }

        // Stats.
        P5Card(accent = P5.Line) {
            Text("Stats at Lv ${f.level}", style = MaterialTheme.typography.titleSmall, color = P5.RedBright)
            for (row in BattleInfo.statRows(card, f.level)) {
                Column(Modifier.padding(vertical = 2.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(row.label, style = MaterialTheme.typography.labelLarge, color = P5.White, modifier = Modifier.width(92.dp))
                        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                            for (i in 1..BAND_COUNT) Box(Modifier.weight(1f).height(8.dp).background(if (i <= row.band) P5.Red else P5.Line))
                        }
                        Text(row.value.toString(), style = MaterialTheme.typography.labelLarge, color = P5.White, modifier = Modifier.width(36.dp), textAlign = TextAlign.End)
                    }
                    Text(row.source, style = MaterialTheme.typography.labelSmall, color = P5.GreyDim)
                }
            }
        }

        // Moves.
        Text("Moves", style = MaterialTheme.typography.titleSmall, color = P5.RedBright)
        for (m in card.moves) {
            P5Card(accent = if (m.type != CardType.NONE) typeColor(m.type) else P5.Line) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(m.name, style = MaterialTheme.typography.titleSmall, color = P5.White, modifier = Modifier.weight(1f))
                    if (m.type != CardType.NONE) P5Tag(m.type.label, color = typeColor(m.type), filled = false)
                }
                Text(BattleInfo.describe(m), style = MaterialTheme.typography.bodySmall, color = P5.Grey)
                if (foeType != null && m.kind.isDamage && m.type != CardType.NONE) {
                    val mult = TypeChart.multiplier(m.type, foeType)
                    Text(
                        if (mult > 1.0) "Super effective against ${foeType.label} ▲" else if (mult < 1.0) "Resisted by ${foeType.label} ▼" else "Normal damage against ${foeType.label}",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (mult > 1.0) P5.RedBright else if (mult < 1.0) P5.DangerBright else P5.GreyDim,
                    )
                }
            }
        }
        P5Button("Close", onClick = onClose, modifier = Modifier.fillMaxWidth())
    }
}
