// #battle
package app.webmchan.battle

/**
 * The computer's choices, for the opponent and for auto-played fights. "Lightly weighted": every
 * move is possible, but a move that makes sense right now is likelier. It leans towards super
 * effective and knockout hits, healing when low, and away from pointless ones (a status on a card
 * that already has one, healing at full HP, buffs already stacked). It never swaps by choice, only
 * when a card has fainted.
 *
 * Its own random source is separate from the fight's, so choosing a move never changes the dice.
 */
class BattleAi(seed: Long) {
    private val rng = BattleRng(BattleHash.mix(seed, AI_SALT))

    fun chooseAction(battle: Battle, side: Side): BattleAction {
        val user = battle.active(side)
        val target = battle.active(side.other)
        val weights = user.card.moves.map { weight(user, target, it) }
        val total = weights.sum()
        if (total <= 0.0) return BattleAction.UseMove(0)
        var pick = rng.nextInt(1_000_000) / 1_000_000.0 * total
        for (i in weights.indices) {
            pick -= weights[i]
            if (pick < 0.0) return BattleAction.UseMove(i)
        }
        return BattleAction.UseMove(weights.lastIndex)
    }

    /** The party position to send out after a faint: the strongest card left (the first, on a tie). */
    fun chooseReplacement(battle: Battle, side: Side): Int =
        battle.legalSwitches(side).maxByOrNull { battle.party(side)[it].card.stats.power }
            ?: error("Nothing left to send out")

    /** Plays [battle] to the end with this AI on both sides, and returns the result. */
    fun playOut(battle: Battle): Outcome {
        while (battle.outcome == null) {
            when {
                battle.needsSwitch(Side.PLAYER) -> battle.forceSwitch(Side.PLAYER, chooseReplacement(battle, Side.PLAYER))
                battle.needsSwitch(Side.OPPONENT) -> battle.forceSwitch(Side.OPPONENT, chooseReplacement(battle, Side.OPPONENT))
                else -> battle.turn(chooseAction(battle, Side.PLAYER), chooseAction(battle, Side.OPPONENT))
            }
        }
        return battle.outcome!!
    }

    private fun weight(user: Fighter, target: Fighter, move: Move): Double = when (move.kind) {
        MoveKind.BASIC, MoveKind.SPECIAL -> {
            val effectiveness = TypeChart.multiplier(move.type, target.card.type)
            var w = when {
                effectiveness > 1.0 -> 2.0
                effectiveness < 1.0 -> 0.5
                else -> 1.0
            }
            // A hit that would finish the target is worth going for.
            if (BattleMath.damage(user, target, move, effectiveness, critical = false, variance = 1.0) >= target.hp) w *= 3.0
            w
        }
        MoveKind.STATUS -> if (target.status != null) 0.1 else 1.0
        MoveKind.HEAL -> when {
            user.hp >= user.maxHp -> 0.0
            user.hp * 10 < user.maxHp * 4 -> 3.0
            user.hp * 10 < user.maxHp * 7 -> 0.6
            else -> 0.1
        }
        MoveKind.BUFF -> if ((move.stat?.let { user.stages[it.ordinal] } ?: 0) >= 2) 0.2 else 0.8
        MoveKind.DEBUFF -> if ((move.stat?.let { target.stages[it.ordinal] } ?: 0) <= -2) 0.2 else 0.8
    }

    private companion object {
        const val AI_SALT = 0x41494CL
    }
}
