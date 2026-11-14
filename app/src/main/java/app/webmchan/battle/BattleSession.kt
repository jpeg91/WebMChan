// #battle
package app.webmchan.battle

/** Where a run (a gauntlet of fights) stands. */
enum class RunPhase {
    /** A new fight is about to start and the player picks which card to send out first. */
    PICKING_LEAD,

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

/** A party card that gained levels from a win. */
data class LevelUp(val name: String, val from: Int, val to: Int)

/** Where a saved run starts again: the fight, how many fights were won so far, and which attempt (after draws). */
data class RunPoint(val fightIndex: Int, val wins: Int, val attempt: Int)

/**
 * One run, start to finish, without any screen: the player's [party] against [opponents] one at a time
 * (build the list with [BattleRun.opponentOrder]). Damage carries over from fight to fight (see [hp]).
 * The screen calls [play], [replace] and [next]; the session picks the opponent's moves with the AI,
 * keeps the events of the latest turn for the screen to show, and tracks how far the run has got.
 *
 * Everything is derived from [runSeed], so a run can be rebuilt exactly (Stage 4 saves it).
 */
class BattleSession(
    party: List<BattleCard>,
    val opponents: List<BattleCard>,
    val runSeed: Long,
    /** The secret last opponent, fought only after every other card in a run of more than [BOSS_MIN_BATTLES] battles (or any run with [forceBoss]). */
    val boss: BattleCard? = null,
    /** Where to pick a saved run up: the fight to start, how many were won, and (after draws) which attempt. */
    startFight: Int = 0,
    startWins: Int = 0,
    startAttempt: Int = 0,
    /** The party's levels when a saved run is picked up (see [resumeLevels]); everyone starts fresh if null or the wrong length. */
    startLevels: List<Int>? = null,
    /** True to let the player choose who leads each fight (with a party of one there is nothing to choose). */
    private val leadPicking: Boolean = false,
    /** The party's HP when a saved run is picked up (see [resumeHp]); everyone starts at full health if null or the wrong length. */
    startHp: List<Int>? = null,
    /** For testing: the boss is fought whatever the length of the run (the Settings "boss test" switch). */
    val forceBoss: Boolean = false,
) {
    /**
     * The party. A fight's messages and bars find a card by its name, so no two cards may share one: the
     * first keeps its name and later repeats get "(2)", "(3)" ... (see [BattleDerive.uniqueNames]).
     */
    val party: List<BattleCard> = BattleDerive.uniqueNames(party)

    init {
        require(party.isNotEmpty()) { "A run needs a party" }
        require(opponents.isNotEmpty()) { "A run needs an opponent" }
    }

    /** True when this run ends with the boss. */
    val hasBossFight: Boolean = boss != null && (forceBoss || opponents.size > BOSS_MIN_BATTLES)

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

    /** Which party card starts the current fight in front. */
    private var lead = 0

    /** The party's levels now, in party order. They only change when a fight is won. */
    var levels: List<Int> = startLevels?.takeIf { it.size == party.size } ?: List(party.size) { BattleLevels.START_LEVEL }
        private set

    /** Who levelled up in the fight just won (empty otherwise): name, level before, level after. */
    var levelUps: List<LevelUp> = emptyList()
        private set

    /**
     * The party's HP now, in party order. A fight starts with these and they only change when a fight is won
     * (a draw starts again from the same values): the survivors keep their damage, recover a little
     * ([BattleTuning.HEAL_BETWEEN_FIGHTS_PCT]), and fainted cards stay fainted unless a move revived them in the fight.
     */
    var hp: List<Int> = startHp?.takeIf { it.size == party.size }?.mapIndexed { i, h -> h.coerceIn(0, maxHpOf(i, levels[i])) }
        ?: party.indices.map { maxHpOf(it, levels[it]) }
        private set

    /** How much each card recovered after the fight just won, by party position (empty otherwise). */
    var recovered: List<Int> = emptyList()
        private set

    private fun maxHpOf(i: Int, level: Int): Int = BattleTuning.maxHp(party[i].stats.hp, level)

    /** The party positions that can still fight. */
    val standing: List<Int> get() = hp.indices.filter { hp[it] > 0 }

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
        RunPhase.FIGHTING, RunPhase.PICKING_LEAD -> RunPoint(fightIndex, wins, attempt)
        RunPhase.FIGHT_WON -> RunPoint(fightIndex + 1, wins, 0)
        RunPhase.FIGHT_DRAWN -> RunPoint(fightIndex, wins, attempt + 1)
        RunPhase.RUN_LOST, RunPhase.RUN_COMPLETE -> null
    }

    /** The opponent's level in the current fight. */
    val foeLevel: Int get() = BattleLevels.foeLevel(fightIndex, opponents.size, isBossFight)

    /** The party's levels at the point a saved run starts again from (see [resumePoint]). */
    fun resumeLevels(): List<Int> = levels

    /** The party's HP at the point a saved run starts again from. */
    fun resumeHp(): List<Int> = hp

    /** True while the player has to pick a replacement before anything else can happen. */
    val mustSwitch: Boolean get() = phase == RunPhase.FIGHTING && battle.needsSwitch(Side.PLAYER)

    private fun fightSeed(): Long = BattleHash.mix(BattleHash.mix(runSeed, fightIndex.toLong()), attempt.toLong())

    private fun newBattle(): Battle {
        return Battle(party, listOf(allFights[fightIndex]), fightSeed(), levels, listOf(foeLevel), lead, hp)
    }

    private fun newAi(): BattleAi = BattleAi(fightSeed())

    private fun openingEvents() = listOf(BattleEvent.Sent(Side.OPPONENT, opponent.name), BattleEvent.Sent(Side.PLAYER, battle.active(Side.PLAYER).card.name))

    private val picksLead: Boolean get() = leadPicking && standing.size > 1

    init {
        if (picksLead) phase = RunPhase.PICKING_LEAD else lastEvents = openingEvents()
    }

    /** At the start of a fight the player sends out the card at [partyIndex]. Returns false if it isn't the time for that. */
    fun chooseLead(partyIndex: Int): Boolean {
        if (phase != RunPhase.PICKING_LEAD || partyIndex !in standing) return false
        lead = partyIndex
        battle = newBattle()
        ai = newAi()
        phase = RunPhase.FIGHTING
        lastEvents = openingEvents()
        return true
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
        levelUps = emptyList()
        recovered = emptyList()
        lead = 0
        battle = newBattle()
        ai = newAi()
        if (picksLead) {
            phase = RunPhase.PICKING_LEAD
            lastEvents = emptyList()
        } else {
            phase = RunPhase.FIGHTING
            lastEvents = openingEvents()
        }
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
                val before = levels
                levels = BattleLevels.afterWin(before, battle.appeared(Side.PLAYER), foeLevel)
                levelUps = before.indices.filter { levels[it] > before[it] }.map { LevelUp(party[it].name, before[it], levels[it]) }
                // Damage carries over. A card that levelled up gains the extra HP, then the survivors recover a little.
                val left = battle.party(Side.PLAYER).map { it.hp }
                val next = ArrayList<Int>()
                val gained = ArrayList<Int>()
                for (i in party.indices) {
                    var h = left[i]
                    if (h > 0) {
                        h += maxHpOf(i, levels[i]) - maxHpOf(i, before[i])
                        val max = maxHpOf(i, levels[i])
                        val heal = maxOf(1, (max * BattleTuning.HEAL_BETWEEN_FIGHTS_PCT + 99) / 100)
                        val after = minOf(max, h + heal)
                        gained.add(after - h)
                        h = after
                    } else gained.add(0)
                    next.add(h)
                }
                hp = next
                recovered = gained
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
