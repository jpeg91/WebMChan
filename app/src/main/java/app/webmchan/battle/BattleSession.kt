// #battle
package app.webmchan.battle

/** Where a run (a gauntlet of fights) stands. */
enum class RunPhase {
    /** A fight is in progress. */
    FIGHTING,

    /** The last fight was won and there are more opponents. */
    FIGHT_WON,

    /** The last fight was a draw; the same opponent comes again. */
    FIGHT_DRAWN,

    /** The whole party fainted. The run is over. */
    RUN_LOST,

    /** Every opponent was beaten. */
    RUN_COMPLETE,
}

/**
 * One run, start to finish, without any screen: the player's [party] against [opponents] one at a time
 * (build the list with [BattleRun.opponentOrder]). Each fight starts with everyone at full health.
 * The screen calls [play], [replace] and [next]; the session picks the opponent's moves with the AI,
 * keeps the events of the latest turn for the screen to show, and tracks how far the run has got.
 *
 * Everything is derived from [runSeed], so a run can be rebuilt exactly (Stage 4 saves it).
 */
class BattleSession(val party: List<BattleCard>, val opponents: List<BattleCard>, val runSeed: Long) {
    init {
        require(party.isNotEmpty()) { "A run needs a party" }
        require(opponents.isNotEmpty()) { "A run needs an opponent" }
    }

    /** Which opponent is being fought now (0-based). */
    var fightIndex: Int = 0
        private set

    /** Fights won so far in this run. */
    var wins: Int = 0
        private set

    var phase: RunPhase = RunPhase.FIGHTING
        private set

    /** The events of the most recent turn (or forced swap, or the start of the fight), for the screen's text box. */
    var lastEvents: List<BattleEvent> = emptyList()
        private set

    private var attempt = 0
    var battle: Battle = newBattle()
        private set
    private var ai: BattleAi = newAi()

    val opponent: BattleCard get() = opponents[fightIndex]
    val fightCount: Int get() = opponents.size

    /** True while the player has to pick a replacement before anything else can happen. */
    val mustSwitch: Boolean get() = phase == RunPhase.FIGHTING && battle.needsSwitch(Side.PLAYER)

    private fun fightSeed(): Long = BattleHash.mix(BattleHash.mix(runSeed, fightIndex.toLong()), attempt.toLong())

    private fun newBattle(): Battle {
        val b = Battle(party, listOf(opponents[fightIndex]), fightSeed())
        return b
    }

    private fun newAi(): BattleAi = BattleAi(fightSeed())

    init {
        lastEvents = listOf(BattleEvent.Sent(Side.OPPONENT, opponent.name), BattleEvent.Sent(Side.PLAYER, battle.active(Side.PLAYER).card.name))
    }

    /** The player does [action] this turn and the opponent answers. Returns false (and does nothing) if not allowed now. */
    fun play(action: BattleAction): Boolean {
        if (phase != RunPhase.FIGHTING || battle.needsSwitch(Side.PLAYER)) return false
        lastEvents = battle.turn(action, ai.chooseAction(battle, Side.OPPONENT))
        settle()
        return true
    }

    /** After a faint, the player sends out the card at [partyIndex]. Returns false if it isn't a legal choice now. */
    fun replace(partyIndex: Int): Boolean {
        if (!mustSwitch || partyIndex !in battle.legalSwitches(Side.PLAYER)) return false
        lastEvents = battle.forceSwitch(Side.PLAYER, partyIndex)
        settle()
        return true
    }

    /** Moves on after a win or a draw: the next opponent, or the same one again after a draw. */
    fun next(): Boolean {
        when (phase) {
            RunPhase.FIGHT_WON -> { fightIndex++; attempt = 0 }
            RunPhase.FIGHT_DRAWN -> attempt++
            else -> return false
        }
        battle = newBattle()
        ai = newAi()
        phase = RunPhase.FIGHTING
        lastEvents = listOf(BattleEvent.Sent(Side.OPPONENT, opponent.name), BattleEvent.Sent(Side.PLAYER, battle.active(Side.PLAYER).card.name))
        return true
    }

    private fun settle() {
        // The opponent is a single card, but if it ever had a bench, it replaces fainted cards itself.
        if (battle.outcome == null && battle.needsSwitch(Side.OPPONENT)) {
            lastEvents = lastEvents + battle.forceSwitch(Side.OPPONENT, ai.chooseReplacement(battle, Side.OPPONENT))
        }
        when (battle.outcome) {
            Outcome.PLAYER_WON -> {
                wins++
                phase = if (fightIndex >= opponents.lastIndex) RunPhase.RUN_COMPLETE else RunPhase.FIGHT_WON
            }
            Outcome.OPPONENT_WON -> phase = RunPhase.RUN_LOST
            Outcome.DRAW -> phase = RunPhase.FIGHT_DRAWN
            null -> {}
        }
    }
}
