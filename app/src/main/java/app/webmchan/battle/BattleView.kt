// #battle
package app.webmchan.battle

/** One card as the screen shows it at a moment in the fight. */
data class FighterView(
    val cardId: Long,
    val name: String,
    val type: CardType,
    val hp: Int,
    val maxHp: Int,
    val status: StatusEffect?,
    /** Buff and debuff stages, indexed by [StatId.ordinal]. */
    val stages: List<Int>,
    val level: Int = BattleTuning.LEVEL,
) {
    val fainted: Boolean get() = hp <= 0
}

/**
 * What the battle screen shows: both parties' cards and who is out. The engine finishes a whole turn
 * at once, but the screen replays it one message at a time, so it keeps its own picture and moves it
 * forward one [BattleEvent] at a time with [apply]. Applying every event of a turn to the picture from
 * before the turn gives the picture after it (BattleViewTest checks this against the engine).
 */
data class BattleView(
    val player: List<FighterView>,
    val opponent: List<FighterView>,
    val playerOut: Int,
    val opponentOut: Int,
) {
    fun party(side: Side): List<FighterView> = if (side == Side.PLAYER) player else opponent
    fun activeIndex(side: Side): Int = if (side == Side.PLAYER) playerOut else opponentOut
    fun active(side: Side): FighterView = party(side)[activeIndex(side)]

    /** The picture after [e] has happened. Events that change nothing on screen return it unchanged. */
    fun apply(e: BattleEvent): BattleView = when (e) {
        is BattleEvent.Sent -> {
            val old = activeIndex(e.side)
            val i = indexOf(e.side, e.name, preferActive = false)
            // A card that leaves loses its buffs and debuffs, as in the engine.
            val cleared = change(e.side, old) { it.copy(stages = ZERO) }
            cleared.copy(
                playerOut = if (e.side == Side.PLAYER) i else cleared.playerOut,
                opponentOut = if (e.side == Side.OPPONENT) i else cleared.opponentOut,
            )
        }
        is BattleEvent.Hit -> setHp(e.side, e.name, e.hpAfter)
        is BattleEvent.StatusHurt -> setHp(e.side, e.name, e.hpAfter)
        is BattleEvent.Healed -> setHp(e.side, e.name, e.hpAfter)
        is BattleEvent.Revived -> change(e.side, indexOf(e.side, e.name)) { it.copy(hp = e.hpAfter, status = null, stages = ZERO) }
        is BattleEvent.Afflicted -> change(e.side, indexOf(e.side, e.name)) { it.copy(status = e.status) }
        is BattleEvent.StatusEnded -> change(e.side, indexOf(e.side, e.name)) { it.copy(status = null) }
        is BattleEvent.StatMoved -> change(e.side, indexOf(e.side, e.name)) {
            it.copy(stages = it.stages.toMutableList().also { s -> s[e.stat.ordinal] += e.change })
        }
        else -> this
    }

    private fun indexOf(side: Side, name: String, preferActive: Boolean = true): Int {
        val p = party(side)
        if (preferActive && p[activeIndex(side)].name == name) return activeIndex(side)
        return p.indexOfFirst { it.name == name }.takeIf { it >= 0 } ?: activeIndex(side)
    }

    private fun setHp(side: Side, name: String, hp: Int) = change(side, indexOf(side, name)) { it.copy(hp = hp) }

    private fun change(side: Side, index: Int, f: (FighterView) -> FighterView): BattleView {
        val list = party(side).toMutableList()
        list[index] = f(list[index])
        return if (side == Side.PLAYER) copy(player = list) else copy(opponent = list)
    }

    companion object {
        private val ZERO = List(StatId.values().size) { 0 }

        fun of(battle: Battle): BattleView {
            fun view(f: Fighter) = FighterView(f.card.id, f.card.name, f.card.type, f.hp, f.maxHp, f.status, f.stages.toList(), f.level)
            return BattleView(
                battle.party(Side.PLAYER).map(::view),
                battle.party(Side.OPPONENT).map(::view),
                battle.activeIndex(Side.PLAYER),
                battle.activeIndex(Side.OPPONENT),
            )
        }
    }
}
