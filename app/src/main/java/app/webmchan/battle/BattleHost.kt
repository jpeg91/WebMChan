// #battle
package app.webmchan.battle

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
 * Battle-Cards as the player sees it: the roster, then picking a party, then a run. Plain screens with
 * text and bars; the hand-drawn animation comes later. A run isn't saved yet (that is the next stage), so
 * leaving it ends it.
 */
@Composable
fun BattleHost(onBack: () -> Unit) {
    val roster by remember { derivedStateOf { BattleFromHistory.roster(BatchRunner.history.toList()) } }
    var picking by remember { mutableStateOf(false) }
    var session by remember { mutableStateOf<BattleSession?>(null) }

    val running = session
    when {
        running != null -> RunScreen(running, onExit = { session = null })
        picking -> PartyScreen(
            roster,
            onBack = { picking = false },
            onStart = { party ->
                val ids = party.map { it.id }.toSet()
                val seed = BattleHash.mix(System.currentTimeMillis(), party.fold(0L) { acc, c -> BattleHash.mix(acc, c.id) })
                session = BattleSession(party, BattleRun.opponentOrder(roster, ids), seed)
                picking = false
            },
        )
        else -> BattlePreviewScreen(onBack = onBack, onPlay = { picking = true })
    }
}

// -- Picking a party ----------------------------------------------------------------------------

@Composable
private fun PartyScreen(roster: List<BattleCard>, onBack: () -> Unit, onStart: (List<BattleCard>) -> Unit) {
    BackHandler(onBack = onBack)
    val chosen = remember { mutableStateListOf<Long>() }
    // The party is in the order the cards were picked: the first one leads.
    val party = chosen.mapNotNull { id -> roster.firstOrNull { it.id == id } }
    // Keep at least one card out of the party to fight.
    val canStart = party.isNotEmpty() && party.size < roster.size

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { P5Button("← Back", onClick = onBack, kind = P5Kind.Ghost) }
        item { P5Title("Pick your party") }
        item {
            Text(
                "Choose up to ${BattleRun.PARTY_MAX} cards. The first one you pick leads. Every card left in " +
                    "your History becomes an opponent, weakest first.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item {
            P5Button(
                "Start (${party.size}/${BattleRun.PARTY_MAX})",
                onClick = { onStart(party) },
                enabled = canStart,
            )
        }
        items(roster, key = { it.id }) { card ->
            val place = chosen.indexOf(card.id)
            val color = typeColor(card.type)
            P5Card(
                modifier = Modifier.clickable {
                    if (place >= 0) chosen.remove(card.id)
                    else if (chosen.size < BattleRun.PARTY_MAX) chosen.add(card.id)
                },
                accent = if (place >= 0) P5.Amber else color,
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(card.name, style = MaterialTheme.typography.titleSmall, color = P5.White, modifier = Modifier.weight(1f))
                    if (place >= 0) P5Tag("#${place + 1}", color = P5.Amber, filled = true)
                    P5Tag(card.type.label, color = color)
                }
                Text(
                    "Power ${card.stats.power} · HP ${card.stats.hp} · Atk ${card.stats.attack} · Sp.Atk ${card.stats.spAttack} · " +
                        "Def ${card.stats.defence} · Sp.Def ${card.stats.spDefence} · Spd ${card.stats.speed}",
                    style = MaterialTheme.typography.bodySmall,
                    color = P5.Grey,
                )
                Text(
                    card.moves.joinToString(" · ") { it.name },
                    style = MaterialTheme.typography.bodySmall,
                    color = P5.White,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        item { Spacer(Modifier.height(8.dp)) }
    }
}

// -- A run --------------------------------------------------------------------------------------

private enum class Menu { MAIN, MOVES, TARGET, SWITCH, LEAVE }

@Composable
private fun RunScreen(session: BattleSession, onExit: () -> Unit) {
    // The session isn't observable, so every change bumps this and the screen redraws from the session.
    var tick by remember { mutableIntStateOf(0) }
    var menu by remember { mutableStateOf(Menu.MAIN) }
    var pendingSlot by remember { mutableIntStateOf(-1) }
    val version = tick

    fun changed() { tick++ }
    fun backToMain() { menu = Menu.MAIN; pendingSlot = -1 }

    BackHandler {
        when {
            session.phase != RunPhase.FIGHTING -> onExit()
            menu == Menu.MAIN -> menu = Menu.LEAVE
            else -> backToMain()
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "Fight ${session.fightIndex + 1} of ${session.fightCount}",
                style = MaterialTheme.typography.titleMedium,
                color = P5.White,
                modifier = Modifier.weight(1f),
            )
            P5Tag("Won ${session.wins}", color = P5.Amber)
        }

        val foe = session.battle.active(Side.OPPONENT)
        val me = session.battle.active(Side.PLAYER)
        FighterPanel(foe, "Opponent", version)
        FighterPanel(me, "Yours", version)
        EventLog(session.lastEvents, version)

        when (session.phase) {
            RunPhase.FIGHTING -> if (session.mustSwitch) {
                Text("Send out who?", style = MaterialTheme.typography.titleSmall, color = P5.Amber)
                PartyList(session, version) { index ->
                    if (session.replace(index)) { backToMain(); changed() }
                }
            } else when (menu) {
                Menu.MAIN -> {
                    P5Button("Fight", onClick = { menu = Menu.MOVES })
                    P5Button(
                        "Switch card",
                        onClick = { menu = Menu.SWITCH },
                        enabled = session.battle.legalSwitches(Side.PLAYER).isNotEmpty(),
                        kind = P5Kind.Secondary,
                    )
                    P5Button("Give up", onClick = { menu = Menu.LEAVE }, kind = P5Kind.Ghost)
                }
                Menu.MOVES -> {
                    for ((slot, move) in me.card.moves.withIndex()) {
                        val targets = session.battle.targetsFor(Side.PLAYER, move.kind)
                        val usable = !move.kind.needsTarget || targets.isNotEmpty()
                        P5Button(
                            move.name,
                            onClick = {
                                if (move.kind.needsTarget) {
                                    pendingSlot = slot
                                    menu = Menu.TARGET
                                } else {
                                    session.play(BattleAction.UseMove(slot))
                                    backToMain()
                                    changed()
                                }
                            },
                            enabled = usable,
                            kind = P5Kind.Secondary,
                        )
                        Text(
                            move.summary() + if (!usable) " (nobody to use it on)" else "",
                            style = MaterialTheme.typography.bodySmall,
                            color = P5.Grey,
                        )
                    }
                    P5Button("← Back", onClick = { backToMain() }, kind = P5Kind.Ghost)
                }
                Menu.TARGET -> {
                    val move = me.card.moves.getOrNull(pendingSlot)
                    Text(
                        if (move?.kind == MoveKind.REVIVE) "Bring back who?" else "Heal who?",
                        style = MaterialTheme.typography.titleSmall,
                        color = P5.Amber,
                    )
                    val targets = if (move == null) emptyList() else session.battle.targetsFor(Side.PLAYER, move.kind)
                    PartyList(session, version, only = targets.toSet(), allowActive = true) { index ->
                        session.play(BattleAction.UseMove(pendingSlot, index))
                        backToMain()
                        changed()
                    }
                    P5Button("← Back", onClick = { menu = Menu.MOVES }, kind = P5Kind.Ghost)
                }
                Menu.SWITCH -> {
                    Text("Switch to who? (it costs your turn)", style = MaterialTheme.typography.titleSmall, color = P5.Amber)
                    PartyList(session, version) { index ->
                        session.play(BattleAction.Switch(index))
                        backToMain()
                        changed()
                    }
                    P5Button("← Back", onClick = { backToMain() }, kind = P5Kind.Ghost)
                }
                Menu.LEAVE -> {
                    Text(
                        "Give up this run? Your progress is lost (saving runs comes in a later update).",
                        style = MaterialTheme.typography.bodyMedium,
                        color = P5.White,
                    )
                    P5Button("Give up", onClick = onExit)
                    P5Button("Keep fighting", onClick = { backToMain() }, kind = P5Kind.Secondary)
                }
            }
            RunPhase.FIGHT_WON -> {
                ResultPanel(
                    "You won!",
                    "Next up: ${session.opponents[session.fightIndex + 1].name} " +
                        "(power ${session.opponents[session.fightIndex + 1].stats.power}). Your party is fully healed.",
                )
                P5Button("Next fight", onClick = { session.next(); backToMain(); changed() })
            }
            RunPhase.FIGHT_DRAWN -> {
                ResultPanel("A draw.", "Nobody won. The same opponent comes again.")
                P5Button("Fight again", onClick = { session.next(); backToMain(); changed() })
            }
            RunPhase.RUN_LOST -> {
                ResultPanel(
                    "Run over",
                    "${session.opponent.name} beat your whole party. You won ${session.wins} of ${session.fightCount} fights.",
                )
                P5Button("Done", onClick = onExit)
            }
            RunPhase.RUN_COMPLETE -> {
                ResultPanel("Run complete!", "You beat all ${session.fightCount} opponents in your History.")
                P5Button("Done", onClick = onExit)
            }
        }
    }
}

@Composable
private fun ResultPanel(title: String, body: String) {
    P5Card(accent = P5.Amber) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = P5.White)
        Text(body, style = MaterialTheme.typography.bodySmall, color = P5.Grey)
    }
}

/** The text of the latest turn, one line per thing that happened. */
@Composable
private fun EventLog(events: List<BattleEvent>, @Suppress("UNUSED_PARAMETER") version: Int) {
    P5Card(accent = P5.Line) {
        for (e in events) Text(e.text(), style = MaterialTheme.typography.bodySmall, color = P5.White)
    }
}

/** A card in the fight: name, type, HP bar, status and any raised or lowered stats. */
@Composable
private fun FighterPanel(f: Fighter, caption: String, @Suppress("UNUSED_PARAMETER") version: Int) {
    val color = typeColor(f.card.type)
    val frac = (f.hp.toFloat() / f.maxHp).coerceIn(0f, 1f)
    val bar = when {
        frac > 0.5f -> P5.Amber
        frac > 0.2f -> P5.RedBright
        else -> P5.Red
    }
    P5Card(accent = color) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f)) {
                Text(caption, style = MaterialTheme.typography.labelSmall, color = P5.GreyDim)
                Text(f.card.name, style = MaterialTheme.typography.titleMedium, color = P5.White)
            }
            P5Tag(f.card.type.label, color = color, filled = true)
        }
        Box(Modifier.fillMaxWidth().height(12.dp).background(P5.Line)) {
            Box(Modifier.fillMaxWidth(frac).fillMaxHeight().background(bar))
        }
        Text("HP ${f.hp} / ${f.maxHp}", style = MaterialTheme.typography.bodySmall, color = P5.White)
        f.status?.let { Text("${it.label}: ${it.blurb}", style = MaterialTheme.typography.bodySmall, color = P5.Amber) }
        val shifted = StatId.values().filter { f.stages[it.ordinal] != 0 }
        if (shifted.isNotEmpty()) {
            Text(
                shifted.joinToString("  ") { (if (f.stages[it.ordinal] > 0) "${it.label} +" else "${it.label} ") + f.stages[it.ordinal] },
                style = MaterialTheme.typography.bodySmall,
                color = P5.Grey,
            )
        }
    }
}

/**
 * The player's party as buttons. By default the cards that can be swapped in; with [only] and
 * [allowActive], the cards a support move can be aimed at (the one out included).
 */
@Composable
private fun PartyList(
    session: BattleSession,
    @Suppress("UNUSED_PARAMETER") version: Int,
    only: Set<Int>? = null,
    allowActive: Boolean = false,
    onPick: (Int) -> Unit,
) {
    val legal = session.battle.legalSwitches(Side.PLAYER).toSet()
    for ((index, f) in session.battle.party(Side.PLAYER).withIndex()) {
        val isOut = index == session.battle.activeIndex(Side.PLAYER)
        val ok = if (only != null) index in only && (allowActive || !isOut) else index in legal
        val state = when {
            f.fainted -> "fainted"
            isOut -> "out now"
            else -> ""
        }
        P5Button(
            "${f.card.name} · ${f.hp}/${f.maxHp}" + if (state.isNotEmpty()) " · $state" else "",
            onClick = { onPick(index) },
            enabled = ok,
            kind = P5Kind.Secondary,
        )
    }
}
