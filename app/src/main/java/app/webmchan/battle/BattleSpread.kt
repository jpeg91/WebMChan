// #battle
package app.webmchan.battle

/**
 * How a roster is spread across the stat bands, for the preview page: if nearly every card sits in
 * one band of a stat, that stat's thresholds in [BattleBands] need moving. Pure counting, so it is
 * checked by plain tests.
 */
object BattleSpread {
    /** [counts] has [BAND_COUNT] entries: how many cards are in band 1, band 2 and so on. */
    class Histogram(val label: String, val counts: IntArray)

    fun stats(cards: List<BattleCard>): List<Histogram> = listOf(
        histogram("HP", cards) { it.stats.hp },
        histogram("Attack", cards) { it.stats.attack },
        histogram("Sp. Attack", cards) { it.stats.spAttack },
        histogram("Defence", cards) { it.stats.defence },
        histogram("Sp. Defence", cards) { it.stats.spDefence },
        histogram("Speed", cards) { it.stats.speed },
    )

    /** How many cards there are of each of the five types, in chart order. */
    fun types(cards: List<BattleCard>): List<Pair<CardType, Int>> =
        CardType.fighting.map { t -> t to cards.count { it.type == t } }

    private fun histogram(label: String, cards: List<BattleCard>, band: (BattleCard) -> Int): Histogram {
        val counts = IntArray(BAND_COUNT)
        for (c in cards) counts[(band(c) - 1).coerceIn(0, BAND_COUNT - 1)]++
        return Histogram(label, counts)
    }
}
