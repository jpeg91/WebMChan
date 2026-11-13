// #battle
package app.webmchan.battle

/**
 * Levels in a run. Every party card starts at [START_LEVEL]. The opponents get tougher as the run goes:
 * the first is at [FIRST_FOE_LEVEL], rising to [LAST_FOE_LEVEL], with the secret boss a little above the last.
 * A card that took part in a won fight levels up, one that is behind the opponent catches up faster, and one
 * well above it gains nothing, so a party that fights its way through stays close to the run's level. A card that sits on the bench does not
 * level, so it falls behind. Levels last for one run; every new run starts again from [START_LEVEL].
 */
object BattleLevels {
    const val START_LEVEL = 30
    const val FIRST_FOE_LEVEL = 30
    const val LAST_FOE_LEVEL = 42
    /** The boss is this many levels above the last ordinary opponent. */
    const val BOSS_BONUS = 0
    const val MAX_LEVEL = 100

    /** A card that took part in a win gains this many levels... */
    const val BASE_GAIN = 2

    /** ...plus one more for every this many levels it is behind the opponent. */
    const val CATCH_UP_EVERY = 4

    /** The opponent's level climbs this many levels every [STEP_EVERY] fights, until it reaches [LAST_FOE_LEVEL]. */
    const val STEP_UP = 1
    const val STEP_EVERY = 6

    /** A card this many levels above the opponent gains nothing from beating it. */
    const val OVERLEVEL_LIMIT = 15

    private fun ordinary(index: Int): Int = minOf(LAST_FOE_LEVEL, FIRST_FOE_LEVEL + index.coerceAtLeast(0) * STEP_UP / STEP_EVERY)

    /** The level of the opponent in fight [index] (0-based) of a run with [count] ordinary fights; the boss comes after them all. */
    fun foeLevel(index: Int, count: Int, boss: Boolean = false): Int =
        if (boss) minOf(MAX_LEVEL, ordinary(count - 1) + BOSS_BONUS) else ordinary(index)

    fun gain(level: Int, foeLevel: Int): Int =
        if (level >= foeLevel + OVERLEVEL_LIMIT) 0 else BASE_GAIN + maxOf(0, foeLevel - level) / CATCH_UP_EVERY

    /** The party's levels after a won fight against a [foeLevel] opponent, where only the cards in [tookPart] level up. */
    fun afterWin(levels: List<Int>, tookPart: Set<Int>, foeLevel: Int): List<Int> =
        levels.mapIndexed { i, l -> if (i in tookPart) minOf(MAX_LEVEL, l + gain(l, foeLevel)) else l }
}
