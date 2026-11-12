// #battle
package app.webmchan.battle

import kotlin.math.floor
import kotlin.math.max

/** The two sides of a fight. The player's party is [PLAYER]; whatever it fights is [OPPONENT]. */
enum class Side {
    PLAYER, OPPONENT;

    val other: Side get() = if (this == PLAYER) OPPONENT else PLAYER
}

/** What a side does on a turn: use one of the active card's four moves, or swap in another card. */
sealed class BattleAction {
    /**
     * [slot] is 0..3, the position in the active card's moves. [target] is a party position, needed only
     * for moves that [MoveKind.needsTarget] (-1 otherwise).
     */
    data class UseMove(val slot: Int, val target: Int = -1) : BattleAction()

    /** [partyIndex] is the position in the side's party of an unfainted card that isn't already out. */
    data class Switch(val partyIndex: Int) : BattleAction()
}

enum class Outcome { PLAYER_WON, OPPONENT_WON, DRAW }

/**
 * Everything that happens in a fight, in order, as plain data. The battle screen turns these into
 * text and animation; the tests read them to check the rules. [text] gives a one-line description.
 */
sealed class BattleEvent {
    data class Sent(val side: Side, val name: String) : BattleEvent()
    data class Used(val side: Side, val name: String, val move: String, val moveId: String = "") : BattleEvent()
    data class Missed(val side: Side, val name: String) : BattleEvent()

    /** [side] is who was hit. [effectiveness] is 2.0, 1.0 or 0.5. */
    data class Hit(
        val side: Side, val name: String, val damage: Int, val hpAfter: Int, val maxHp: Int,
        val effectiveness: Double, val critical: Boolean,
    ) : BattleEvent()

    data class Healed(val side: Side, val name: String, val amount: Int, val hpAfter: Int, val maxHp: Int) : BattleEvent()

    /** [change] is how many stages it really moved (0 when it was already at its limit). */
    /** A card brought back from fainted. */
    data class Revived(val side: Side, val name: String, val hpAfter: Int, val maxHp: Int) : BattleEvent()

    data class StatMoved(val side: Side, val name: String, val stat: StatId, val change: Int) : BattleEvent()
    data class Afflicted(val side: Side, val name: String, val status: StatusEffect) : BattleEvent()
    data class StatusEnded(val side: Side, val name: String, val status: StatusEffect) : BattleEvent()
    data class LostTurn(val side: Side, val name: String, val status: StatusEffect) : BattleEvent()
    data class StatusHurt(val side: Side, val name: String, val damage: Int, val hpAfter: Int, val maxHp: Int) : BattleEvent()
    data class Failed(val side: Side, val name: String, val reason: String) : BattleEvent()
    data class Fainted(val side: Side, val name: String) : BattleEvent()
    data class Ended(val outcome: Outcome) : BattleEvent()
}

fun BattleEvent.text(): String = when (this) {
    is BattleEvent.Sent -> "$name was sent out!"
    is BattleEvent.Used -> "$name used $move!"
    is BattleEvent.Missed -> "$name missed!"
    is BattleEvent.Hit -> "$name took $damage damage." +
        (if (critical) " A critical hit!" else "") +
        (if (effectiveness > 1.0) " It's super effective!" else if (effectiveness < 1.0) " It's not very effective..." else "")
    is BattleEvent.Healed -> "$name recovered $amount HP."
    is BattleEvent.Revived -> "$name is back in the fight!"
    is BattleEvent.StatMoved -> when {
        change == 0 -> "$name's ${stat.label} won't go any further."
        change > 0 -> "$name's ${stat.label} rose" + if (change > 1) " sharply!" else "!"
        else -> "$name's ${stat.label} fell" + if (change < -1) " sharply!" else "!"
    }
    is BattleEvent.Afflicted -> "$name is suffering ${status.label}!"
    is BattleEvent.StatusEnded -> "$name's ${status.label} wore off."
    is BattleEvent.LostTurn -> "$name lost its turn to ${status.label}."
    is BattleEvent.StatusHurt -> "$name took $damage damage from Artifacting."
    is BattleEvent.Failed -> "$name: $reason"
    is BattleEvent.Fainted -> "$name fainted!"
    is BattleEvent.Ended -> when (outcome) {
        Outcome.PLAYER_WON -> "You won the fight!"
        Outcome.OPPONENT_WON -> "You lost the fight."
        Outcome.DRAW -> "The fight was a draw."
    }
}

/** A card in a fight: its current HP, stat stages and status. Created by [Battle]; fresh every fight. */
class Fighter(val card: BattleCard) {
    val maxHp: Int = BattleTuning.maxHp(card.stats.hp)
    var hp: Int = maxHp
        internal set

    /** Buff and debuff stages, indexed by [StatId.ordinal]; 0 = unchanged. */
    val stages = IntArray(StatId.values().size)
    var status: StatusEffect? = null
        internal set

    /** Turns left: Buffering's lost turns, or how long any other status lasts. */
    var statusTurns: Int = 0
        internal set

    val fainted: Boolean get() = hp <= 0

    private fun band(id: StatId): Int = when (id) {
        StatId.ATTACK -> card.stats.attack
        StatId.SP_ATTACK -> card.stats.spAttack
        StatId.DEFENCE -> card.stats.defence
        StatId.SP_DEFENCE -> card.stats.spDefence
        StatId.SPEED -> card.stats.speed
    }

    /** The stat as it counts right now: its base, moved by stages, and cut by a status. */
    fun effective(id: StatId): Double {
        var v = BattleTuning.stat(band(id)) * BattleTuning.stageMultiplier(stages[id.ordinal])
        when (status) {
            StatusEffect.THERMAL_THROTTLE -> if (id == StatId.ATTACK || id == StatId.SP_ATTACK) v *= BattleTuning.THROTTLE_ATTACK
            StatusEffect.DROPPED_FRAMES -> if (id == StatId.SPEED) v *= BattleTuning.DROPPED_FRAMES_SPEED
            else -> {}
        }
        return max(1.0, v)
    }
}

/** The damage arithmetic, kept apart so the opponent's AI can estimate a hit without making one. */
object BattleMath {
    /**
     * Damage from [user] to [target] with a damaging [move]. [effectiveness] is the type chart's
     * multiplier. Pass critical = false and variance = 1.0 for the average hit. Never below 1 unless
     * the move has no power or the type chart says no damage.
     */
    fun damage(user: Fighter, target: Fighter, move: Move, effectiveness: Double, critical: Boolean, variance: Double): Int {
        if (!move.kind.isDamage || move.power <= 0 || effectiveness <= 0.0) return 0
        val special = move.kind == MoveKind.SPECIAL
        val a = user.effective(if (special) StatId.SP_ATTACK else StatId.ATTACK)
        val d = target.effective(if (special) StatId.SP_DEFENCE else StatId.DEFENCE)
        val levelTerm = floor(2.0 * BattleTuning.LEVEL / 5.0) + 2.0
        val base = floor(floor(levelTerm * move.power * a / d) / 50.0) + 2.0
        val sameType = if (move.type != CardType.NONE && move.type == user.card.type) BattleTuning.SAME_TYPE_BONUS else 1.0
        val crit = if (critical) BattleTuning.CRIT_MULTIPLIER else 1.0
        return max(1, floor(base * sameType * effectiveness * crit * variance).toInt())
    }
}

/**
 * One fight, from the first turn to a result. Both sides are parties of one or more cards (a
 * Battle-Cards fight is the player's party against a single card). Everything random comes from the
 * [seed], so the same seed and the same choices always play out the same way.
 *
 * To play: each turn call [turn] with both sides' actions. When a card faints and its side has
 * another left, [needsSwitch] is true and [forceSwitch] must be called before the next turn. The
 * fight is over when [outcome] is not null.
 */
class Battle(playerCards: List<BattleCard>, opponentCards: List<BattleCard>, val seed: Long) {
    init {
        require(playerCards.isNotEmpty() && opponentCards.isNotEmpty()) { "Both sides need a card" }
    }

    private val rng = BattleRng(seed)
    private val playerParty = playerCards.map { Fighter(it) }
    private val opponentParty = opponentCards.map { Fighter(it) }
    private var playerOut = 0
    private var opponentOut = 0
    private val playerSeen = linkedSetOf(0)
    private val opponentSeen = linkedSetOf(0)
    private val log = ArrayList<BattleEvent>()

    /** Turns played so far. */
    var turns: Int = 0
        private set

    /** null while the fight goes on. */
    var outcome: Outcome? = null
        private set

    /** Every event so far, in order. */
    val events: List<BattleEvent> get() = log

    fun party(side: Side): List<Fighter> = if (side == Side.PLAYER) playerParty else opponentParty
    /** The party positions of every card of [side] that has been out at some point in this fight. */
    fun appeared(side: Side): Set<Int> = if (side == Side.PLAYER) playerSeen else opponentSeen

    fun activeIndex(side: Side): Int = if (side == Side.PLAYER) playerOut else opponentOut
    fun active(side: Side): Fighter = party(side)[activeIndex(side)]

    /** The positions in [side]'s party it could swap in right now. */
    fun legalSwitches(side: Side): List<Int> =
        party(side).indices.filter { it != activeIndex(side) && !party(side)[it].fainted }

    /** The party positions a move of [kind] could be aimed at: unfainted cards to heal, fainted ones to revive. */
    fun targetsFor(side: Side, kind: MoveKind): List<Int> = when (kind) {
        MoveKind.HEAL_ALLY -> party(side).indices.filter { !party(side)[it].fainted }
        MoveKind.REVIVE -> party(side).indices.filter { party(side)[it].fainted }
        else -> emptyList()
    }

    /** True when [side]'s active card has fainted and the fight is not over: it must swap before play goes on. */
    fun needsSwitch(side: Side): Boolean = outcome == null && active(side).fainted

    /** Plays one turn. Returns just the events of this turn. */
    fun turn(player: BattleAction, opponent: BattleAction): List<BattleEvent> {
        check(outcome == null) { "The fight is over" }
        check(!needsSwitch(Side.PLAYER) && !needsSwitch(Side.OPPONENT)) { "A fainted card must be replaced first" }
        validate(Side.PLAYER, player)
        validate(Side.OPPONENT, opponent)
        val start = log.size
        turns++
        val actions = mapOf(Side.PLAYER to player, Side.OPPONENT to opponent)

        // Swapping comes before any move, quicker side first.
        for (side in bySpeed()) (actions[side] as? BattleAction.Switch)?.let { switchTo(side, it.partyIndex) }

        // Then the moves, quicker side first (speed as it is after any swap).
        for (side in bySpeed()) {
            val action = actions[side] as? BattleAction.UseMove ?: continue
            if (outcome != null) break
            if (active(side).fainted) continue
            act(side, action.slot, action.target)
        }

        if (outcome == null) endOfTurn()
        if (outcome == null && turns >= BattleTuning.MAX_TURNS) finish(Outcome.DRAW)
        return log.subList(start, log.size).toList()
    }

    /** Swaps in [partyIndex] for a fainted card (see [needsSwitch]). Returns the new events. */
    fun forceSwitch(side: Side, partyIndex: Int): List<BattleEvent> {
        check(needsSwitch(side)) { "$side has no card to replace" }
        require(partyIndex in legalSwitches(side)) { "That card can't be sent out" }
        val start = log.size
        switchTo(side, partyIndex)
        return log.subList(start, log.size).toList()
    }

    // -- Internals ------------------------------------------------------------------------------

    private fun validate(side: Side, action: BattleAction) {
        when (action) {
            is BattleAction.UseMove -> {
                require(action.slot in active(side).card.moves.indices) { "No such move" }
                val kind = active(side).card.moves[action.slot].kind
                if (kind.needsTarget) require(action.target in targetsFor(side, kind)) { "That card can't be the target" }
            }
            is BattleAction.Switch -> require(action.partyIndex in legalSwitches(side)) { "That card can't be sent out" }
        }
    }

    /** The two sides, quicker first; an exact tie is settled by a coin flip. */
    private fun bySpeed(): List<Side> {
        val p = active(Side.PLAYER).effective(StatId.SPEED)
        val o = active(Side.OPPONENT).effective(StatId.SPEED)
        return when {
            p > o -> listOf(Side.PLAYER, Side.OPPONENT)
            o > p -> listOf(Side.OPPONENT, Side.PLAYER)
            rng.nextInt(2) == 0 -> listOf(Side.PLAYER, Side.OPPONENT)
            else -> listOf(Side.OPPONENT, Side.PLAYER)
        }
    }

    private fun switchTo(side: Side, index: Int) {
        // A card that leaves loses its buffs and debuffs; a status stays with it.
        active(side).stages.fill(0)
        if (side == Side.PLAYER) { playerOut = index; playerSeen.add(index) } else { opponentOut = index; opponentSeen.add(index) }
        log.add(BattleEvent.Sent(side, active(side).card.name))
    }

    private fun finish(result: Outcome) {
        outcome = result
        log.add(BattleEvent.Ended(result))
    }

    private fun checkEnd() {
        if (outcome != null) return
        val player = playerParty.any { !it.fainted }
        val opponent = opponentParty.any { !it.fainted }
        when {
            !player && !opponent -> finish(Outcome.DRAW)
            !player -> finish(Outcome.OPPONENT_WON)
            !opponent -> finish(Outcome.PLAYER_WON)
        }
    }

    private fun faint(side: Side) {
        log.add(BattleEvent.Fainted(side, active(side).card.name))
    }

    private fun clearStatus(side: Side) {
        val f = active(side)
        val status = f.status ?: return
        f.status = null
        f.statusTurns = 0
        log.add(BattleEvent.StatusEnded(side, f.card.name, status))
    }

    /** A roll of 0..99 against [percent]; true on a hit. */
    private fun roll(percent: Int): Boolean = rng.nextInt(100) < percent

    private fun act(side: Side, slot: Int, targetIndex: Int) {
        val user = active(side)
        val target = active(side.other)
        val move = user.card.moves[slot]

        // A status can cost the turn.
        when (user.status) {
            StatusEffect.BUFFERING -> {
                log.add(BattleEvent.LostTurn(side, user.card.name, StatusEffect.BUFFERING))
                user.statusTurns--
                if (user.statusTurns <= 0) clearStatus(side)
                return
            }
            StatusEffect.DROPPED_FRAMES -> if (roll(BattleTuning.DROPPED_FRAMES_FAIL_PERCENT)) {
                log.add(BattleEvent.LostTurn(side, user.card.name, StatusEffect.DROPPED_FRAMES))
                return
            }
            else -> {}
        }

        log.add(BattleEvent.Used(side, user.card.name, move.name, move.id))
        when (move.kind) {
            MoveKind.BASIC, MoveKind.SPECIAL -> strike(side, user, target, move)
            MoveKind.STATUS -> if (hits(side, user, move)) inflict(side.other, target, move.status)
            MoveKind.HEAL -> heal(side, user, move)
            MoveKind.HEAL_BENCH -> healBench(side, move)
            MoveKind.HEAL_ALLY -> healAlly(side, move, targetIndex)
            MoveKind.REVIVE -> if (hits(side, user, move)) revive(side, move, targetIndex)
            MoveKind.BUFF -> shift(side, user, move.stat, move.stages)
            MoveKind.DEBUFF -> if (hits(side, user, move)) shift(side.other, target, move.stat, move.stages)
        }
    }

    private fun hits(side: Side, user: Fighter, move: Move): Boolean {
        if (roll(move.accuracy)) return true
        log.add(BattleEvent.Missed(side, user.card.name))
        return false
    }

    private fun strike(side: Side, user: Fighter, target: Fighter, move: Move) {
        if (move.power <= 0) {
            log.add(BattleEvent.Failed(side, user.card.name, "It had no effect."))
            return
        }
        if (!hits(side, user, move)) return
        val effectiveness = TypeChart.multiplier(move.type, target.card.type)
        val critical = rng.nextInt(BattleTuning.CRIT_ONE_IN) == 0
        val variance = (BattleTuning.VARIANCE_MIN_PERCENT + rng.nextInt(101 - BattleTuning.VARIANCE_MIN_PERCENT)) / 100.0
        val dealt = BattleMath.damage(user, target, move, effectiveness, critical, variance).coerceAtMost(target.hp)
        target.hp -= dealt
        log.add(BattleEvent.Hit(side.other, target.card.name, dealt, target.hp, target.maxHp, effectiveness, critical))
        if (target.fainted) {
            faint(side.other)
            checkEnd()
        }
    }

    private fun inflict(side: Side, target: Fighter, status: StatusEffect?) {
        if (status == null) return
        if (target.status != null) {
            log.add(BattleEvent.Failed(side, target.card.name, "It already has a status."))
            return
        }
        val low: Int
        val high: Int
        if (status == StatusEffect.BUFFERING) {
            low = BattleTuning.BUFFERING_MIN_TURNS
            high = BattleTuning.BUFFERING_MAX_TURNS
        } else {
            low = BattleTuning.STATUS_MIN_TURNS
            high = BattleTuning.STATUS_MAX_TURNS
        }
        target.status = status
        target.statusTurns = low + rng.nextInt(high - low + 1)
        log.add(BattleEvent.Afflicted(side, target.card.name, status))
    }

    private fun heal(side: Side, user: Fighter, move: Move) {
        if (user.hp >= user.maxHp) {
            log.add(BattleEvent.Failed(side, user.card.name, "Its HP is already full."))
            return
        }
        val amount = max(1, user.maxHp * move.healPct / 100).coerceAtMost(user.maxHp - user.hp)
        user.hp += amount
        log.add(BattleEvent.Healed(side, user.card.name, amount, user.hp, user.maxHp))
    }

    private fun restore(side: Side, who: Fighter, move: Move) {
        if (who.fainted || who.hp >= who.maxHp) return
        val amount = max(1, who.maxHp * move.healPct / 100).coerceAtMost(who.maxHp - who.hp)
        who.hp += amount
        log.add(BattleEvent.Healed(side, who.card.name, amount, who.hp, who.maxHp))
    }

    private fun healBench(side: Side, move: Move) {
        val bench = party(side).indices.filter { it != activeIndex(side) && !party(side)[it].fainted && party(side)[it].hp < party(side)[it].maxHp }
        if (bench.isEmpty()) {
            log.add(BattleEvent.Failed(side, active(side).card.name, "Nobody on the bench needs it."))
            return
        }
        for (i in bench) restore(side, party(side)[i], move)
    }

    private fun healAlly(side: Side, move: Move, index: Int) {
        val who = party(side).getOrNull(index)
        if (who == null || who.fainted || who.hp >= who.maxHp) {
            log.add(BattleEvent.Failed(side, active(side).card.name, "It had no effect."))
            return
        }
        restore(side, who, move)
    }

    private fun revive(side: Side, move: Move, index: Int) {
        val who = party(side).getOrNull(index)
        if (who == null || !who.fainted) {
            log.add(BattleEvent.Failed(side, active(side).card.name, "It had no effect."))
            return
        }
        who.hp = max(1, who.maxHp * move.healPct / 100)
        who.status = null
        who.statusTurns = 0
        who.stages.fill(0)
        log.add(BattleEvent.Revived(side, who.card.name, who.hp, who.maxHp))
    }

    private fun shift(side: Side, who: Fighter, stat: StatId?, stages: Int) {
        if (stat == null) return
        val before = who.stages[stat.ordinal]
        val after = (before + stages).coerceIn(-BattleTuning.MAX_STAGE, BattleTuning.MAX_STAGE)
        who.stages[stat.ordinal] = after
        log.add(BattleEvent.StatMoved(side, who.card.name, stat, after - before))
    }

    private fun endOfTurn() {
        for (side in bySpeed()) {
            val f = active(side)
            if (f.fainted) continue
            if (f.status == StatusEffect.ARTIFACTING) {
                val dealt = max(1, f.maxHp / BattleTuning.ARTIFACTING_ONE_IN).coerceAtMost(f.hp)
                f.hp -= dealt
                log.add(BattleEvent.StatusHurt(side, f.card.name, dealt, f.hp, f.maxHp))
                if (f.fainted) {
                    faint(side)
                    continue
                }
            }
            // Buffering counts down on the turns it takes; the rest wear off with time.
            val status = f.status
            if (status != null && status != StatusEffect.BUFFERING) {
                f.statusTurns--
                if (f.statusTurns <= 0) clearStatus(side)
            }
        }
        // Both sides are checked together, so two cards lost to Artifacting on one turn make a draw.
        checkEnd()
    }
}
