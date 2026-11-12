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

/** Where a saved run starts again: the fight, how many fights were won so far, and which attempt (after draws). */
data class RunPoint(val fightIndex: Int, val wins: Int, val attempt: Int)

/**
 * One run, start to finish, without any screen: the player's [party] against [opponents] one at a time
 * (build the list with [BattleRun.opponentOrder]). Each fight starts with everyone at full health.
 * The screen calls [play], [replace] and [next]; the session picks the opponent's moves with the AI,
 * keeps the events of the latest turn for the screen to show, and tracks how far the run has got.
 *
 * Everything is derived from [runSeed], so a run can be rebuilt exactly (Stage 4 saves it).
 */
class BattleSession(
    val party: List<BattleCard>,
    val opponents: List<BattleCard>,
    val runSeed: Long,
    /** The secret last opponent, fought only after every other card in a run of more than [BOSS_MIN_BATTLES] battles. */
    val boss: BattleCard? = null,
    /** Where to pick a saved run up: the fight to start, how many were won, and (after draws) which attempt. */
    startFight: Int = 0,
    startWins: Int = 0,
    startAttempt: Int = 0,
) {
    init {
        require(party.isNotEmpty()) { "A run needs a party" }
        require(opponents.isNotEmpty()) { "A run needs an opponent" }
    }

    /** True when this run ends with the boss. */
    val hasBossFight: Boolean = boss != null && opponents.size > BOSS_MIN_BATTLES

    /** Every fight in the run, the boss's included. */
    private val allFights: List<BattleCard> = if (hasBossFight) opponents + boss!! else opponents

    /** Which opponent is being fought now (0-based). */
    var fightIndex: Int = startFight.coerceIn(0, allFights.lastIndex)
        private set

    /** Fights won so far in this run. */
    var wins: Int = startWins
        private set

    /** Called when a fight ends, with how it went and the party cards that took part in it. For keeping records. */
    var onFightEnded: ((Outcome, List<BattleCard>) -> Unit)? = null

    /** Called whenever there is a new point a saved run could be picked up from (see [resumePoint]). */
    var onSavePoint: ((BattleSession) -> Unit)? = null

    var phase: RunPhase = RunPhase.FIGHTING
        private set

    /** The events of the most recent turn (or forced swap, or the start of the fight), for the screen's text box. */
    var lastEvents: List<BattleEvent> = emptyList()
        private set

    private var attempt = startAttempt
    var battle: Battle = newBattle()
        private set
    private var ai: BattleAi = newAi()

    val opponent: BattleCard get() = allFights[fightIndex]

    /** True while the secret boss is being fought. */
    val isBossFight: Boolean get() = hasBossFight && fightIndex == allFights.lastIndex

    /** The number of fights to show the player. The boss isn't counted: it is a secret. */
    val fightCount: Int get() = opponents.size

    /** The opponent after the current one, if any (the boss stays hidden until it is next, and is then shown as a mystery). */
    val nextOpponent: BattleCard? get() = allFights.getOrNull(fightIndex + 1)

    /** Where a saved run would start again from: null once the run is over. A fight that has ended counts as done. */
    fun resumePoint(): RunPoint? = when (phase) {
        RunPhase.FIGHTING -> RunPoint(fightIndex, wins, attempt)
        RunPhase.FIGHT_WON -> RunPoint(fightIndex + 1, wins, 0)
        RunPhase.FIGHT_DRAWN -> RunPoint(fightIndex, wins, attempt + 1)
        RunPhase.RUN_LOST, RunPhase.RUN_COMPLETE -> null
    }

    /** True while the player has to pick a replacement before anything else can happen. */
    val mustSwitch: Boolean get() = phase == RunPhase.FIGHTING && battle.needsSwitch(Side.PLAYER)

    private fun fightSeed(): Long = BattleHash.mix(BattleHash.mix(runSeed, fightIndex.toLong()), attempt.toLong())

    private fun newBattle(): Battle {
        val b = Battle(party, listOf(allFights[fightIndex]), fightSeed())
        return b
    }

    private fun newAi(): BattleAi = BattleAi(fightSeed())

    init {
        lastEvents = listOf(BattleEvent.Sent(Side.OPPONENT, opponent.name), BattleEvent.Sent(Side.PLAYER, battle.active(Side.PLAYER).card.name))
    }

    /** Tells the listener the run's starting point; call once after setting [onSavePoint]. */
    fun announce() {
        onSavePoint?.invoke(this)
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
        onSavePoint?.invoke(this)
        return true
    }

    private fun settle() {
        // The opponent is a single card, but if it ever had a bench, it replaces fainted cards itself.
        if (battle.outcome == null && battle.needsSwitch(Side.OPPONENT)) {
            lastEvents = lastEvents + battle.forceSwitch(Side.OPPONENT, ai.chooseReplacement(battle, Side.OPPONENT))
        }
        val result = battle.outcome ?: return
        when (result) {
            Outcome.PLAYER_WON -> {
                wins++
                phase = if (fightIndex >= allFights.lastIndex) RunPhase.RUN_COMPLETE else RunPhase.FIGHT_WON
            }
            Outcome.OPPONENT_WON -> phase = RunPhase.RUN_LOST
            Outcome.DRAW -> phase = RunPhase.FIGHT_DRAWN
        }
        onFightEnded?.invoke(result, battle.appeared(Side.PLAYER).sorted().map { party[it] })
        onSavePoint?.invoke(this)
    }

    companion object {
        /** A run needs more battles than this for the boss to appear. */
        const val BOSS_MIN_BATTLES = 50
    }
}
