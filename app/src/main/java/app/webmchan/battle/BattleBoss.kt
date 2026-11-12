// #battle
package app.webmchan.battle

/**
 * The secret last opponent, and the spare opponents for a short History.
 *
 * The boss appears only at the end of a run of more than [BattleSession.BOSS_MIN_BATTLES] battles, after
 * every card that wasn't in the party has been beaten. It has no type, so no card gets an advantage
 * against it, and its stats are near the top.
 */
object BattleBoss {
    const val ID = -9000L

    val card: BattleCard = BattleCard(
        id = ID,
        memberIds = listOf(ID),
        name = "Lossless Master",
        sourceName = "master.raw",
        type = CardType.NONE,
        stats = BattleStats(hp = 8, attack = 8, spAttack = 8, defence = 8, spDefence = 8, speed = 8),
        moves = listOf("two_pass_takedown", "overshoot", "remux", "banding").map { BattleMoves.byId(it)!! },
        isAudio = false,
        parts = 1,
        missing = emptySet(),
    )
}

/**
 * Ready-made opponents, used to pad a run when History doesn't hold enough cards to make one worth
 * playing. They are fixed (the same every time) and span weak to strong, so they slot in by power
 * like any other card. They have negative ids, so they never clash with a History card.
 */
object BattlePregen {
    /** A run with fewer opponents than this is topped up with ready-made cards. */
    const val MIN_OPPONENTS = 12

    private const val FIRST_ID = -2000L

    /** Enough ready-made cards to top up an almost empty History. */
    val all: List<BattleCard> by lazy {
        val types = CardType.values().filter { it != CardType.NONE }
        (0 until 40).map { n ->
            val id = FIRST_ID - n
            val rng = BattleRng(BattleHash.mix(0x5EEDL, id))
            val type = types[n % types.size]
            // Spread evenly from weak to strong: the band centre rises with n.
            val centre = 1.5 + 6.0 * n / 39.0
            fun band() = (centre + rng.nextInt(3) - 1).toInt().coerceIn(1, BAND_COUNT)
            val stats = BattleStats(band(), band(), band(), band(), band(), band())
            val core = BattleHash.mix(0xC0DEL, id)
            val moves = BattleMoves.pick(type, core, BattleHash.mix(core, id))
            BattleCard(id, listOf(id), BattleDerive.nameFor(type, stats, core, BattleHash.mix(core, id)), "ready-made", type, stats, moves, false, 1, emptySet())
        }
    }

    /** [opponents] topped up to [MIN_OPPONENTS] with ready-made cards (evenly through the strength range), or unchanged if already enough. */
    fun topUp(opponents: List<BattleCard>): List<BattleCard> {
        if (opponents.size >= MIN_OPPONENTS) return opponents
        val need = MIN_OPPONENTS - opponents.size
        val step = all.size.toDouble() / need
        val extra = (0 until need).map { all[(it * step).toInt().coerceAtMost(all.lastIndex)] }
        // A stable sort: real cards keep their order against ready-made ones of equal power.
        return (opponents + extra).sortedBy { it.stats.power }
    }
}
