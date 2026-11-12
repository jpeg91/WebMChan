// #battle
package app.webmchan.battle

/**
 * Every number the battle rules use, in one place, so balance is changed by editing this file and
 * nothing else. The values were tuned by simulating thousands of fights (see BattleBalanceTest).
 *
 * Cards have a level during a run (see [BattleLevels]); [LEVEL] is the level used when none is given
 * (plain fights and the balance tests), so the card's bands alone decide how strong it is. A band (1..8) becomes a real stat through [BASE_BY_BAND] and the same formula the
 * classic handheld battlers used, with the extras (individual values and training) left out.
 */
object BattleTuning {
    /** The level a card fights at when none is given. */
    const val LEVEL = 50

    /** The base value for each band; index 0 is band 1. */
    val BASE_BY_BAND = intArrayOf(30, 45, 60, 75, 90, 105, 120, 135)

    private fun base(band: Int): Int = BASE_BY_BAND[band.coerceIn(1, BAND_COUNT) - 1]

    /** Maximum HP for an HP band. */
    fun maxHp(band: Int, level: Int = LEVEL): Int = base(band) * 4 * level / 100 + level + 10

    /** Attack, Special Attack, Defence, Special Defence or Speed for a band. */
    fun stat(band: Int, level: Int = LEVEL): Int = base(band) * 2 * level / 100 + 5

    // -- Damage ---------------------------------------------------------------------------------

    /** A move of the user's own type hits this much harder. */
    const val SAME_TYPE_BONUS = 1.5

    /** A critical hit's chance is 1 in this many. */
    const val CRIT_ONE_IN = 16
    const val CRIT_MULTIPLIER = 1.5

    /** Each hit is scaled by a random 85..100 percent. */
    const val VARIANCE_MIN_PERCENT = 85

    // -- Stat stages ----------------------------------------------------------------------------

    /** A buff or debuff can move a stat this many stages either way. */
    const val MAX_STAGE = 6

    /** The multiplier for a stage: +1 is 1.5x, +2 is 2x ... -1 is 0.67x, -2 is 0.5x ... */
    fun stageMultiplier(stage: Int): Double {
        val s = stage.coerceIn(-MAX_STAGE, MAX_STAGE)
        return if (s >= 0) (2.0 + s) / 2.0 else 2.0 / (2.0 - s)
    }

    // -- Statuses -------------------------------------------------------------------------------

    /** Buffering costs this many turns (a random number from the first to the second, inclusive). */
    const val BUFFERING_MIN_TURNS = 1
    const val BUFFERING_MAX_TURNS = 3

    /** The other statuses wear off after a random number of the card's own turns in this range. */
    const val STATUS_MIN_TURNS = 3
    const val STATUS_MAX_TURNS = 5

    /** Dropped Frames: speed is multiplied by this, and the card fails to act this often. */
    const val DROPPED_FRAMES_SPEED = 0.5
    const val DROPPED_FRAMES_FAIL_PERCENT = 25

    /** Artifacting costs this fraction (1 in N) of maximum HP at the end of every turn. */
    const val ARTIFACTING_ONE_IN = 8

    /** Thermal Throttle multiplies Attack and Special Attack by this. */
    const val THROTTLE_ATTACK = 0.67

    // -- Safety ---------------------------------------------------------------------------------

    /** A fight that is still going after this many turns is called a draw (it should never happen). */
    const val MAX_TURNS = 200
}
