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
import androidx.compose.runtime.LaunchedEffect
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
import kotlinx.coroutines.delay
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
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    CardThumb(card.id, width = 72.dp, height = 46.dp)
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

/** How long each message stays up before the next one (a tap moves on sooner). */
private const val MESSAGE_MILLIS = 1200L

private enum class Menu { MOVES, TARGET, SWITCH, LEAVE }

@Composable
private fun RunScreen(session: BattleSession, onExit: () -> Unit) {
    // The engine finishes a whole turn at once. The screen keeps its own picture of the fight and replays
    // the turn's events one at a time, so bars, text and cards all move together, message by message.
    var view by remember { mutableStateOf(BattleView.of(session.battle)) }
    var queue by remember { mutableStateOf(session.lastEvents) }
    var shown by remember { mutableIntStateOf(0) }
    var message by remember { mutableStateOf("") }
    var menu by remember { mutableStateOf(Menu.MOVES) }
    var pendingSlot by remember { mutableIntStateOf(-1) }
    // Bumped whenever the session itself changes (phase, who must swap), so the screen redraws.
    var tick by remember { mutableIntStateOf(0) }
    @Suppress("UNUSED_VARIABLE") val redraw = tick

    val busy = shown < queue.size

    fun advance() {
        if (shown >= queue.size) return
        val e = queue[shown]
        view = view.apply(e)
        message = e.text()
        shown++
    }

    /** Starts replaying [events], from the picture [from]. */
    fun replay(from: BattleView, events: List<BattleEvent>) {
        view = from
        queue = events
        shown = 0
        menu = Menu.MOVES
        pendingSlot = -1
        tick++
        advance()
    }

    /** Runs a session change that plays out as events (a turn or a swap). */
    fun act(change: () -> Boolean) {
        val before = view
        if (change()) replay(before, session.lastEvents)
    }

    // Opening messages ("X was sent out!").
    LaunchedEffect(Unit) { advance() }
    // Each message stays up a moment, then the next one comes. A tap on the message box skips the wait.
    LaunchedEffect(queue, shown) {
        if (shown in 1 until queue.size) {
            delay(MESSAGE_MILLIS)
            advance()
        }
    }

    BackHandler {
        when {
            session.phase != RunPhase.FIGHTING -> onExit()
            menu == Menu.MOVES -> menu = Menu.LEAVE
            else -> menu = Menu.MOVES
        }
    }

    val foe = view.active(Side.OPPONENT)
    val me = view.active(Side.PLAYER)
    val moves = session.battle.active(Side.PLAYER).card.moves

    Column(Modifier.fillMaxSize().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // Top: the opponent.
        FighterPanel(foe, "Opponent · fight ${session.fightIndex + 1} of ${session.fightCount} · won ${session.wins}")

        // Middle: the messages, or whatever the player has to choose from.
        Box(Modifier.weight(1f).fillMaxWidth()) {
            val choosing = !busy && session.phase == RunPhase.FIGHTING
            when {
                busy -> MessageBox(message, hint = "tap to continue", onTap = { advance() })
                session.phase == RunPhase.FIGHT_WON -> ResultPanel(
                    "You won!",
                    "Next up: ${session.opponents[session.fightIndex + 1].name} " +
                        "(power ${session.opponents[session.fightIndex + 1].stats.power}). Your party is fully healed.",
                )
                session.phase == RunPhase.FIGHT_DRAWN -> ResultPanel("A draw.", "Nobody won. The same opponent comes again.")
                session.phase == RunPhase.RUN_LOST -> ResultPanel(
                    "Run over",
                    "${session.opponent.name} beat your whole party. You won ${session.wins} of ${session.fightCount} fights.",
                )
                session.phase == RunPhase.RUN_COMPLETE -> ResultPanel("Run complete!", "You beat all ${session.fightCount} opponents in your History.")
                choosing && session.mustSwitch -> Choices("Send out who?") {
                    PartyList(view, session.battle.legalSwitches(Side.PLAYER).toSet()) { index ->
                        act { session.replace(index) }
                    }
                }
                choosing && menu == Menu.SWITCH -> Choices("Switch to who? (it costs your turn)") {
                    PartyList(view, session.battle.legalSwitches(Side.PLAYER).toSet()) { index ->
                        act { session.play(BattleAction.Switch(index)) }
                    }
                    P5Button("← Back", onClick = { menu = Menu.MOVES }, kind = P5Kind.Ghost)
                }
                choosing && menu == Menu.TARGET -> {
                    val move = moves.getOrNull(pendingSlot)
                    val targets = if (move == null) emptySet() else session.battle.targetsFor(Side.PLAYER, move.kind).toSet()
                    Choices(if (move?.kind == MoveKind.REVIVE) "Bring back who?" else "Heal who?") {
                        PartyList(view, targets) { index -> act { session.play(BattleAction.UseMove(pendingSlot, index)) } }
                        P5Button("← Back", onClick = { menu = Menu.MOVES }, kind = P5Kind.Ghost)
                    }
                }
                choosing && menu == Menu.LEAVE -> Choices("Give up this run? Your progress is lost (saving runs comes in a later update).") {
                    P5Button("Give up", onClick = onExit)
                    P5Button("Keep fighting", onClick = { menu = Menu.MOVES }, kind = P5Kind.Secondary)
                }
                else -> MessageBox(message, hint = "", onTap = {})
            }
        }

        // Bottom: you, then the controls.
        FighterPanel(me, "Yours")
        val canMove = !busy && session.phase == RunPhase.FIGHTING && !session.mustSwitch && menu == Menu.MOVES
        when {
            session.phase == RunPhase.FIGHT_WON && !busy -> P5Button("Next fight", onClick = { session.next(); replay(BattleView.of(session.battle), session.lastEvents) }, modifier = Modifier.fillMaxWidth())
            session.phase == RunPhase.FIGHT_DRAWN && !busy -> P5Button("Fight again", onClick = { session.next(); replay(BattleView.of(session.battle), session.lastEvents) }, modifier = Modifier.fillMaxWidth())
            (session.phase == RunPhase.RUN_LOST || session.phase == RunPhase.RUN_COMPLETE) && !busy -> P5Button("Done", onClick = onExit, modifier = Modifier.fillMaxWidth())
            else -> MoveGrid(
                moves = moves,
                enabled = canMove,
                usable = { m -> !m.kind.needsTarget || session.battle.targetsFor(Side.PLAYER, m.kind).isNotEmpty() },
                onMove = { slot ->
                    val m = moves[slot]
                    if (m.kind.needsTarget) {
                        pendingSlot = slot
                        menu = Menu.TARGET
                    } else {
                        act { session.play(BattleAction.UseMove(slot)) }
                    }
                },
                canSwitch = session.battle.legalSwitches(Side.PLAYER).isNotEmpty(),
                onSwitch = { menu = Menu.SWITCH },
                onLeave = { menu = Menu.LEAVE },
            )
        }
    }
}

/** The four moves in a 2x2 grid, then Switch and Give up underneath. */
@Composable
private fun MoveGrid(
    moves: List<Move>,
    enabled: Boolean,
    usable: (Move) -> Boolean,
    onMove: (Int) -> Unit,
    canSwitch: Boolean,
    onSwitch: () -> Unit,
    onLeave: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (rowStart in moves.indices step 2) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (slot in rowStart until minOf(rowStart + 2, moves.size)) {
                    val move = moves[slot]
                    val ok = enabled && usable(move)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        P5Button(move.name, onClick = { onMove(slot) }, modifier = Modifier.fillMaxWidth(), enabled = ok, kind = P5Kind.Secondary, compact = true)
                        Text(
                            move.summary() + if (enabled && !usable(move)) " (nobody to use it on)" else "",
                            style = MaterialTheme.typography.bodySmall,
                            color = P5.GreyDim,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            P5Button("Switch card", onClick = onSwitch, modifier = Modifier.weight(1f), enabled = enabled && canSwitch, kind = P5Kind.Secondary, compact = true)
            P5Button("Give up", onClick = onLeave, modifier = Modifier.weight(1f), enabled = enabled, kind = P5Kind.Ghost, compact = true)
        }
    }
}

/** The current message, one at a time. Tapping it moves on to the next. */
@Composable
private fun MessageBox(text: String, hint: String, onTap: () -> Unit) {
    P5Card(accent = P5.Line, modifier = Modifier.fillMaxSize().clickable(onClick = onTap), verticalArrangement = Arrangement.Center) {
        Text(text, style = MaterialTheme.typography.titleMedium, color = P5.White)
        if (hint.isNotEmpty()) Text(hint, style = MaterialTheme.typography.labelSmall, color = P5.GreyDim)
    }
}

@Composable
private fun ResultPanel(title: String, body: String) {
    P5Card(accent = P5.Amber, modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
        Text(title, style = MaterialTheme.typography.titleLarge, color = P5.White)
        Text(body, style = MaterialTheme.typography.bodyMedium, color = P5.Grey)
    }
}

/** A heading and a scrolling list of choices, filling the middle of the screen. */
@Composable
private fun Choices(title: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = P5.Amber)
        content()
    }
}

/** A card in the fight: picture, name, type, HP bar and numbers, status and any raised or lowered stats. */
@Composable
private fun FighterPanel(f: FighterView, caption: String) {
    val color = typeColor(f.type)
    val frac = if (f.maxHp <= 0) 0f else (f.hp.toFloat() / f.maxHp).coerceIn(0f, 1f)
    val bar = when {
        frac > 0.5f -> P5.Amber
        frac > 0.2f -> P5.RedBright
        else -> P5.Red
    }
    P5Card(accent = color) {
        Text(caption, style = MaterialTheme.typography.labelSmall, color = P5.GreyDim)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            CardThumb(f.cardId, width = 64.dp, height = 40.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        f.name,
                        style = MaterialTheme.typography.titleMedium,
                        color = P5.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    P5Tag(f.type.label, color = color, filled = true)
                }
                Box(Modifier.fillMaxWidth().height(12.dp).background(P5.Line)) {
                    Box(Modifier.fillMaxWidth(frac).fillMaxHeight().background(bar))
                }
                Text("HP ${f.hp} / ${f.maxHp}", style = MaterialTheme.typography.bodySmall, color = P5.White)
            }
        }
        val shifted = StatId.values().filter { f.stages[it.ordinal] != 0 }
        val line = listOfNotNull(
            f.status?.label,
            shifted.takeIf { it.isNotEmpty() }?.joinToString("  ") { (if (f.stages[it.ordinal] > 0) "${it.label} +" else "${it.label} ") + f.stages[it.ordinal] },
        ).joinToString(" · ")
        if (line.isNotEmpty()) Text(line, style = MaterialTheme.typography.bodySmall, color = P5.Amber)
    }
}

/** The player's party as buttons; only the cards in [allowed] can be picked. */
@Composable
private fun PartyList(view: BattleView, allowed: Set<Int>, onPick: (Int) -> Unit) {
    for ((index, f) in view.player.withIndex()) {
        val state = when {
            f.fainted -> "fainted"
            index == view.playerOut -> "out now"
            else -> ""
        }
        P5Button(
            "${f.name} · ${f.hp}/${f.maxHp}" + if (state.isNotEmpty()) " · $state" else "",
            onClick = { onPick(index) },
            enabled = index in allowed,
            kind = P5Kind.Secondary,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
