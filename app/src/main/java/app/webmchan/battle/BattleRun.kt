// #battle
package app.webmchan.battle

/** The rules of a run (a gauntlet through the roster) that don't depend on any screen. */
object BattleRun {
    /** The most cards a player can take into a run. */
    const val PARTY_MAX = 6

    /**
     * The opponents of a run, easiest first: every roster card that isn't in the party, sorted by
     * power rating. Cards of equal power are ordered by a hash of their id, so the order is fixed for
     * a given roster but not simply oldest-first.
     */
    fun opponentOrder(roster: List<BattleCard>, partyIds: Set<Long>): List<BattleCard> =
        roster
            .filter { it.id !in partyIds }
            .sortedWith(compareBy<BattleCard> { it.stats.power }.thenBy { BattleHash.mix(it.id, ORDER_SALT) })

    private const val ORDER_SALT = 0x0DD5L
}
