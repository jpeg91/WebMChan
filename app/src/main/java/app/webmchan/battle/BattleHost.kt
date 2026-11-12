// #battle
package app.webmchan.battle

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.webmchan.AppSettings
import app.webmchan.AppToggle
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
 * text and bars; the hand-drawn animation comes later. The party (with records) and the run in progress
 * are saved ([BattleStore]), so leaving the mode or closing the app keeps them; a saved run starts again
 * from the beginning of the fight it was in.
 */
@Composable
fun BattleHost(onBack: () -> Unit) {
    val ctx = LocalContext.current
    // Rebuilt whenever History changes (the list is observable state). Test cards are for trying the game without a History.
    val real by remember { derivedStateOf { BattleFromHistory.roster(BatchRunner.history.toList()) } }
    val testCards = remember { BattleTestCards.cards() }
    // The "Battle-Cards test fighters" switch on the Settings page.
    val useTestCards = AppSettings.get(AppToggle.BATTLE_TEST_CARDS)
    val roster = remember(real, useTestCards) { if (useTestCards) real + testCards else real }
    var save by remember { mutableStateOf(BattleStore.load(ctx)) }
    var picking by remember { mutableStateOf(false) }
    var session by remember { mutableStateOf<BattleSession?>(null) }

    fun update(s: BattleSave) {
        save = s
        BattleStore.save(ctx, s)
    }

    /** Keeps the records and the saved run up to date as the run goes. */
    fun wire(s: BattleSession, partyIds: List<Long>) {
        s.onFightEnded = { outcome, who -> update(save.recordFight(outcome, who)) }
        s.onSavePoint = { sess ->
            val point = sess.resumePoint()
            if (point == null) {
                var next = save.withRun(null)
                if (sess.phase == RunPhase.RUN_COMPLETE) next = next.recordRunCompleted(partyIds)
                update(next)
            } else {
                update(save.withRun(SavedRun(partyIds, sess.opponents, sess.boss, sess.runSeed, point, sess.resumeLevels())))
            }
        }
        s.announce()
    }

    val running = session
    when {
        running != null -> RunScreen(
            running,
            onExit = { session = null },
            onGiveUp = { update(save.withRun(null)); session = null },
        )
        picking -> PartyScreen(
            roster = roster,
            saved = save.party,
            hasSavedRun = save.run != null,
            onBack = { picking = false },
            onStart = { chosen ->
                val ids = chosen.map { it.id }
                val joined = save.withParty(chosen)
                // A card already in the party fights as its saved snapshot.
                val cards = ids.mapNotNull { joined.partyCard(it)?.card }
                val seed = BattleHash.mix(System.currentTimeMillis(), ids.fold(0L) { acc, id -> BattleHash.mix(acc, id) })
                val s = BattleSession(cards, BattleRun.opponentsFor(roster, ids.toSet()), seed, boss = BattleBoss.card)
                save = joined
                wire(s, ids)
                session = s
                picking = false
            },
        )
        else -> {
            val run = save.run
            BattlePreviewScreen(
                roster,
                onBack = onBack,
                onPlay = { picking = true },
                savedRun = run?.let { "Fight ${(it.point.fightIndex + 1).coerceAtMost(it.opponents.size)} of ${it.opponents.size} · ${it.point.wins} won. The fight you were in starts again." },
                onResume = {
                    if (run != null) {
                        val cards = run.partyIds.mapNotNull { save.partyCard(it)?.card }
                        if (cards.isNotEmpty()) {
                            val s = BattleSession(cards, run.opponents, run.seed, run.boss, run.point.fightIndex, run.point.wins, run.point.attempt, run.levels)
                            wire(s, run.partyIds)
                            session = s
                        }
                    }
                },
            )
        }
    }
}

// -- Picking a party ----------------------------------------------------------------------------

@Composable
private fun PartyScreen(
    roster: List<BattleCard>,
    saved: List<SavedPartyCard>,
    hasSavedRun: Boolean,
    onBack: () -> Unit,
    onStart: (List<BattleCard>) -> Unit,
) {
    BackHandler(onBack = onBack)
    val chosen = remember { mutableStateListOf<Long>() }
    var confirmReplace by remember { mutableStateOf(false) }
    val rosterIds = remember(roster) { roster.map { it.id }.toSet() }
    val savedIds = saved.map { it.card.id }.toSet()
    val fromHistory = roster.filter { it.id !in savedIds }
    // Saved cards fight as their snapshots; the rest are taken from the roster.
    val byId = (saved.map { it.card } + fromHistory).associateBy { it.id }
    // The party is in the order the cards were picked: the first one leads.
    val party = chosen.mapNotNull { byId[it] }
    val canStart = party.isNotEmpty()

    @Composable
    fun cardRow(card: BattleCard, record: CardRecord?) {
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
                    "Def ${card.stats.defence} · Sp.Def ${card.stats.spDefence} · Spd ${card.stats.speed}" + if (card.isDud) " · dud" else "",
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
            if (record != null && (record.fightsWon + record.fightsLost + record.runsCompleted) > 0) {
                Text(
                    "Won ${record.fightsWon} · Lost ${record.fightsLost} · Runs completed ${record.runsCompleted}",
                    style = MaterialTheme.typography.bodySmall,
                    color = P5.Amber,
                )
            }
            if (record != null && card.id !in rosterIds) {
                Text("No longer in History: kept in your party.", style = MaterialTheme.typography.bodySmall, color = P5.GreyDim)
            }
        }
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { P5Button("← Back", onClick = onBack, kind = P5Kind.Ghost) }
        item { P5Title("Pick your party") }
        item {
            Text(
                "Choose up to ${BattleRun.PARTY_MAX} cards. The first one you pick leads. Every History card left out " +
                    "becomes an opponent, weakest first (a short History is topped up with ready-made cards). " +
                    "Cards you take into a run are saved in your party, with their wins and losses.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item {
            P5Button(
                "Start (${party.size}/${BattleRun.PARTY_MAX})",
                onClick = { if (hasSavedRun) confirmReplace = true else onStart(party) },
                enabled = canStart,
            )
        }
        if (saved.isNotEmpty()) {
            item { Text("Your party cards", style = MaterialTheme.typography.titleSmall, color = P5.Amber) }
            items(saved, key = { "s${it.card.id}" }) { s -> cardRow(s.card, s.record) }
            item { Text("From History", style = MaterialTheme.typography.titleSmall, color = P5.Amber) }
        }
        items(fromHistory, key = { "h${it.id}" }) { card -> cardRow(card, null) }
        item { Spacer(Modifier.height(8.dp)) }
    }

    if (confirmReplace) {
        AlertDialog(
            onDismissRequest = { confirmReplace = false },
            shape = CutCornerShape(topStart = 20.dp, bottomEnd = 20.dp),
            containerColor = P5.Ink,
            titleContentColor = P5.White,
            textContentColor = P5.White,
            title = { Text("START A NEW RUN?", style = MaterialTheme.typography.titleMedium) },
            text = { Text("You have a run saved. Starting a new one replaces it.") },
            confirmButton = { P5Button("Start new run", onClick = { confirmReplace = false; onStart(party) }) },
            dismissButton = { P5Button("Cancel", onClick = { confirmReplace = false }, kind = P5Kind.Ghost) },
        )
    }
}

// -- A run --------------------------------------------------------------------------------------

/** How long each message stays up before the next one (a tap moves on sooner). */
private const val MESSAGE_MILLIS = 1200L

private enum class Menu { MOVES, TARGET, SWITCH, LEAVE }

@Composable
private fun RunScreen(session: BattleSession, onExit: () -> Unit, onGiveUp: () -> Unit) {
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

    // The move animation playing right now (if any), and how far through it is (0..1).
    var fx by remember { mutableStateOf<ActiveFx?>(null) }
    val fxT = remember { mutableFloatStateOf(0f) }
    // Where each fighter's panel is, in pixels inside the screen box, and the box itself.
    var foeCentre by remember { mutableStateOf(Offset.Zero) }
    var meCentre by remember { mutableStateOf(Offset.Zero) }
    var boxOrigin by remember { mutableStateOf(Offset.Zero) }
    var boxSize by remember { mutableStateOf(IntSize.Zero) }

    fun startFx(e: BattleEvent, after: BattleView) {
        if (AppSettings.get(AppToggle.ANIMATIONS_OFF)) { fx = null; return }
        fx = when (e) {
            is BattleEvent.Used -> {
                val def = BattleFx.def(e.moveId, BattleMoves.byId(e.moveId)?.kind ?: MoveKind.BASIC)
                val other = if (e.side == Side.PLAYER) Side.OPPONENT else Side.PLAYER
                ActiveFx(def, FxKind.MOVE, e.side, if (def.onSelf) e.side else other, FxPalette.forType(after.active(e.side).type))
            }
            is BattleEvent.Hit -> ActiveFx(BattleFx.hit, FxKind.HIT, e.side, e.side, FxPalette.forType(CardType.NONE))
            is BattleEvent.Fainted -> ActiveFx(BattleFx.faint, FxKind.FAINT, e.side, e.side, FxPalette.forType(CardType.NONE))
            else -> null
        }
        fxT.floatValue = 0f
    }

    fun advance() {
        if (shown >= queue.size) return
        val e = queue[shown]
        view = view.apply(e)
        message = e.text()
        startFx(e, view)
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
            val animated = if (AppSettings.get(AppToggle.ANIMATIONS_OFF)) null else queue[shown - 1] as? BattleEvent.Used
            val wait = if (animated == null) MESSAGE_MILLIS else maxOf(MESSAGE_MILLIS, BattleFx.def(animated.moveId, BattleMoves.byId(animated.moveId)?.kind ?: MoveKind.BASIC).millis + 200L)
            delay(wait)
            advance()
        }
    }
    // Drives the animation by hand from the display's frame clock, so Android's "animator duration scale"
    // (developer options) cannot speed it up, slow it down or switch it off. Only the app's own setting does.
    LaunchedEffect(fx) {
        val active = fx ?: return@LaunchedEffect
        val start = withFrameNanos { it }
        while (true) {
            val ms = (withFrameNanos { it } - start) / 1_000_000.0
            val t = ms / active.def.millis
            if (t >= 1.0) break
            fxT.floatValue = t.toFloat()
        }
        fx = null
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

    /** The animation's grid for the current screen size, with the fighters' panel centres on it. */
    fun fxContext(): FxCtx? {
        if (boxSize.width <= 0) return null
        val cell = boxSize.width.toFloat() / BattleFx.GRID_W
        val gh = Math.ceil(boxSize.height / cell.toDouble()).toInt()
        fun cellAt(o: Offset) = Pt(((o.x - boxOrigin.x) / cell).toDouble(), ((o.y - boxOrigin.y) / cell).toDouble())
        val user = fx?.userSide ?: Side.PLAYER
        val target = fx?.targetSide ?: Side.OPPONENT
        return FxCtx(cellAt(if (user == Side.PLAYER) meCentre else foeCentre), cellAt(if (target == Side.PLAYER) meCentre else foeCentre), BattleFx.GRID_W, gh)
    }

    /** How far this side's panel is knocked about by the animation: sideways, up/down (in cells), and opacity. */
    fun panelShift(side: Side): FxShift {
        val a = fx
        val resting = if (view.active(side).hp <= 0) 0.5 else 1.0
        val ctx = fxContext()
        if (a == null || ctx == null) return FxShift(userAlpha = resting, targetAlpha = resting)
        val t = fxT.floatValue.toDouble()
        val s = when (a.kind) {
            FxKind.MOVE -> BattleFx.shift(a.def, ctx, t)
            FxKind.HIT -> BattleFx.hitShift(t)
            FxKind.FAINT -> BattleFx.faintShift(t)
        }
        var dx = 0.0
        var dy = 0.0
        var alpha = resting
        if (a.kind == FxKind.MOVE) {
            if (side == a.userSide) { dx += s.userDx; dy += s.userDy; alpha = alpha * s.userAlpha }
            if (side == a.targetSide && a.targetSide != a.userSide) { dx += s.targetDx; dy += s.targetDy; alpha = alpha * s.targetAlpha }
        } else if (side == a.targetSide) {
            dx = s.targetDx; dy = s.targetDy; alpha = s.targetAlpha
        }
        return FxShift(userDx = dx, userDy = dy, userAlpha = alpha)
    }

    fun Modifier.fighterFx(side: Side, onCentre: (Offset) -> Unit): Modifier =
        onGloballyPositioned { onCentre(it.positionInRoot() + Offset(it.size.width / 2f, it.size.height / 2f)) }
            .graphicsLayer {
                val s = panelShift(side)
                val cell = if (boxSize.width > 0) boxSize.width.toFloat() / BattleFx.GRID_W else 0f
                translationX = (s.userDx * cell).toFloat()
                translationY = (s.userDy * cell).toFloat()
                alpha = s.userAlpha.toFloat()
            }

    Box(
        Modifier.fillMaxSize().onGloballyPositioned {
            boxOrigin = it.positionInRoot()
            boxSize = it.size
        },
    ) {
    Column(Modifier.fillMaxSize().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // Top: the opponent.
        Box(Modifier.fighterFx(Side.OPPONENT) { foeCentre = it }) {
            FighterPanel(
                foe,
                if (session.isBossFight) "Opponent · ??? · won ${session.wins}" else "Opponent · fight ${session.fightIndex + 1} of ${session.fightCount} · won ${session.wins}",
                thumbOnRight = true,
            )
        }

        // Middle: the messages, or whatever the player has to choose from.
        Box(Modifier.weight(1f).fillMaxWidth()) {
            val choosing = !busy && session.phase == RunPhase.FIGHTING
            when {
                busy -> MessageBox(message, hint = "tap to continue", onTap = { advance() })
                session.phase == RunPhase.FIGHT_WON -> {
                    val next = session.nextOpponent
                    val grew = session.levelUps.joinToString("\n") { "${it.name} grew to Lv ${it.to}" }
                    ResultPanel(
                        "You won!",
                        (if (grew.isNotEmpty()) grew + "\n\n" else "") +
                            if (next != null && next.id == BattleBoss.ID) "Something else is waiting for you... Your party is fully healed."
                            else "Next up: ${next?.name} (power ${next?.stats?.power}). Your party is fully healed.",
                    )
                }
                session.phase == RunPhase.FIGHT_DRAWN -> ResultPanel("A draw.", "Nobody won. The same opponent comes again.")
                session.phase == RunPhase.RUN_LOST -> ResultPanel(
                    "Run over",
                    "${session.opponent.name} beat your whole party. You won ${session.wins} of ${session.fightCount} fights.",
                )
                session.phase == RunPhase.RUN_COMPLETE -> ResultPanel(
                    "Run complete!",
                    if (session.hasBossFight) "You beat every opponent, and the ${BattleBoss.card.name} too." else "You beat all ${session.fightCount} opponents.",
                )
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
                choosing && menu == Menu.LEAVE -> Choices("Leave the run? It is saved, and the fight you are in starts again when you come back.") {
                    P5Button("Save and leave", onClick = onExit)
                    P5Button("Keep fighting", onClick = { menu = Menu.MOVES }, kind = P5Kind.Secondary)
                    P5Button("Give up for good", onClick = onGiveUp, kind = P5Kind.Ghost)
                }
                else -> MessageBox(message, hint = "", onTap = {})
            }
        }

        // Bottom: you, then the controls.
        Box(Modifier.fighterFx(Side.PLAYER) { meCentre = it }) { FighterPanel(me, "Yours") }
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
    // The hand-drawn effects, in whole squares on a 64-wide grid, over everything else.
    val playing = fx
    if (playing != null && boxSize.width > 0) {
        Canvas(Modifier.fillMaxSize()) {
            val ctx = fxContext() ?: return@Canvas
            val cell = size.width / BattleFx.GRID_W
            for (p in BattleFx.render(playing.def, ctx, fxT.floatValue.toDouble())) {
                drawRect(Color(playing.palette.color(p.slot)), Offset(p.x * cell, p.y * cell), Size(p.w * cell, p.h * cell))
            }
        }
    }
    }
}

private enum class FxKind { MOVE, HIT, FAINT }

/** An animation in progress: what it draws, who is using it, who it is aimed at, and its colours. */
private class ActiveFx(val def: FxDef, val kind: FxKind, val userSide: Side, val targetSide: Side, val palette: FxPalette)

/** The four moves in a 2x2 grid, then Switch and Leave underneath. */
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
            P5Button("Leave", onClick = onLeave, modifier = Modifier.weight(1f), enabled = enabled, kind = P5Kind.Ghost, compact = true)
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
private fun FighterPanel(f: FighterView, caption: String, thumbOnRight: Boolean = false) {
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
            if (!thumbOnRight) CardThumb(f.cardId, width = 64.dp, height = 40.dp)
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
                    Text("Lv ${f.level}", style = MaterialTheme.typography.labelMedium, color = P5.Grey)
                    P5Tag(f.type.label, color = color, filled = true)
                }
                Box(Modifier.fillMaxWidth().height(12.dp).background(P5.Line)) {
                    Box(Modifier.fillMaxWidth(frac).fillMaxHeight().background(bar))
                }
                Text("HP ${f.hp} / ${f.maxHp}", style = MaterialTheme.typography.bodySmall, color = P5.White)
            }
            if (thumbOnRight) CardThumb(f.cardId, width = 64.dp, height = 40.dp)
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
            "${f.name} · Lv ${f.level} · ${f.hp}/${f.maxHp}" + if (state.isNotEmpty()) " · $state" else "",
            onClick = { onPick(index) },
            enabled = index in allowed,
            kind = P5Kind.Secondary,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
