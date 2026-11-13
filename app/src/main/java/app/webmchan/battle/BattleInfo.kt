// #battle
package app.webmchan.battle

/** One line of a card's stat table: the band (1..8), and the real number it comes to at the card's level. */
data class StatRow(val label: String, val band: Int, val value: Int, val source: String)

/**
 * Plain-words explanations for the screens that tell the player what is going on: the inspect page,
 * the type chart and the "how it works" page. No screen code here, so the wording is tested.
 */
object BattleInfo {

    /** The six stats of [card] at [level], with where in the video each one comes from. */
    fun statRows(card: BattleCard, level: Int): List<StatRow> {
        val s = card.stats
        return listOf(
            StatRow("HP", s.hp, BattleTuning.maxHp(s.hp, level), "Duration and how hard the video was squeezed"),
            StatRow("Attack", s.attack, BattleTuning.stat(s.attack, level), "Pixels (resolution)"),
            StatRow("Sp. Attack", s.spAttack, BattleTuning.stat(s.spAttack, level), "Bitrate"),
            StatRow("Defence", s.defence, BattleTuning.stat(s.defence, level), "How full the size limit was"),
            StatRow("Sp. Defence", s.spDefence, BattleTuning.stat(s.spDefence, level), "Frame rate"),
            StatRow("Speed", s.speed, BattleTuning.stat(s.speed, level), "How fast the conversion ran"),
        )
    }

    /** The types [t]'s moves are super effective against. */
    fun strongAgainst(t: CardType): List<CardType> = CardType.fighting.filter { TypeChart.beats(t, it) }

    /** The types whose moves are super effective against [t]. */
    fun weakTo(t: CardType): List<CardType> = CardType.fighting.filter { TypeChart.beats(it, t) }

    /** What kind of video makes a card of this type. */
    fun sourceOf(t: CardType): String = when (t) {
        CardType.MAINSTREAM -> "MP4, MOV and friends"
        CardType.MATROSKA -> "MKV and WebM"
        CardType.LEGACY -> "AVI, WMV, FLV, MPEG and other older formats"
        CardType.AUDIO -> "Sound-only files"
        CardType.WILD -> "GIFs and anything unrecognised"
        CardType.NONE -> "Nothing in particular: neutral against everything"
    }

    /** Every type's relation to every other, for the chart: the multiplier a move of [attacker] has against [defender]. */
    fun chartCell(attacker: CardType, defender: CardType): Double = TypeChart.multiplier(attacker, defender)

    private fun stages(n: Int) = if (n == 1) "1 stage" else "$n stages"

    /** What a move does, in a sentence or two. */
    fun describe(m: Move): String {
        val acc = if (m.accuracy < 100) " ${m.accuracy}% accurate." else ""
        val typed = if (m.type != CardType.NONE) " Hits as ${m.type.label}, and harder (x${BattleTuning.SAME_TYPE_BONUS}) when the user is that type too." else ""
        return when (m.kind) {
            MoveKind.BASIC -> "A physical hit of power ${m.power}: the user's Attack against the target's Defence.$typed$acc"
            MoveKind.SPECIAL -> "A special hit of power ${m.power}: the user's Sp. Attack against the target's Sp. Defence.$typed$acc"
            MoveKind.STATUS -> {
                val st = m.status
                if (st == null) "Does nothing.$acc" else "Tries to inflict ${st.label} on the target. ${statusDetail(st)}.$acc"
            }
            MoveKind.HEAL -> "Heals the user for ${m.healPct}% of its maximum HP.$acc"
            MoveKind.HEAL_BENCH -> "Heals every standing card on your bench for ${m.healPct}% of its maximum HP. Not the user.$acc"
            MoveKind.HEAL_ALLY -> "Heals one standing party member of your choice (the user too) for ${m.healPct}% of its maximum HP.$acc"
            MoveKind.REVIVE -> "Brings one fainted party member back with ${m.healPct}% of its maximum HP.$acc"
            MoveKind.BUFF -> "Raises the user's ${m.stat?.label ?: "stat"} by ${stages(m.stages)}.$acc"
            MoveKind.DEBUFF -> "Lowers the target's ${m.stat?.label ?: "stat"} by ${stages(-m.stages)}.$acc"
        }
    }

    /** What a status does, with the numbers. */
    fun statusDetail(s: StatusEffect): String = when (s) {
        StatusEffect.BUFFERING -> "Loses ${BattleTuning.BUFFERING_MIN_TURNS} to ${BattleTuning.BUFFERING_MAX_TURNS} turns"
        StatusEffect.DROPPED_FRAMES -> "Speed halved, and ${BattleTuning.DROPPED_FRAMES_FAIL_PERCENT}% of the time it fails to act, for ${BattleTuning.STATUS_MIN_TURNS} to ${BattleTuning.STATUS_MAX_TURNS} turns"
        StatusEffect.ARTIFACTING -> "Loses 1/${BattleTuning.ARTIFACTING_ONE_IN} of its maximum HP at the end of every turn, for ${BattleTuning.STATUS_MIN_TURNS} to ${BattleTuning.STATUS_MAX_TURNS} turns"
        StatusEffect.THERMAL_THROTTLE -> "Attack and Sp. Attack cut to about two thirds, for ${BattleTuning.STATUS_MIN_TURNS} to ${BattleTuning.STATUS_MAX_TURNS} turns"
    }

    /** How a stat stage changes the stat, e.g. "+2: x2.0". */
    fun stageText(stage: Int): String = "%s%d: x%.2f".format(if (stage > 0) "+" else "", stage, BattleTuning.stageMultiplier(stage))

    /** The "how it works" page: a heading and a few lines each. */
    val howItWorks: List<Pair<String, String>> = listOf(
        "Turns" to "Each turn you pick a move (or swap cards, which costs your turn). The faster card acts first, using Speed after any stat changes and statuses.",
        "Damage" to "Physical moves use Attack against Defence, special moves Sp. Attack against Sp. Defence. A move of the user's own type hits ${BattleTuning.SAME_TYPE_BONUS}x as hard. Every hit varies by 85 to 100 percent, and about 1 in ${BattleTuning.CRIT_ONE_IN} is a critical hit (x${BattleTuning.CRIT_MULTIPLIER}).",
        "Types" to "The five types sit in a ring and each beats the next two round it, so every pairing favours exactly one side. A super effective move does x${TypeChart.SUPER_EFFECTIVE}, a resisted one x${TypeChart.RESISTED}. Neutral cards are never helped or hurt.",
        "Where stats come from" to "A card's stats are read from the video it was made from: HP from duration and compression, Attack from pixels, Sp. Attack from bitrate, Defence from how full the size limit was, Sp. Defence from frame rate and Speed from how fast the conversion ran.",
        "Stat changes" to "Buffs and debuffs move a stat up to ${BattleTuning.MAX_STAGE} stages either way: +1 is x1.5, +2 is x2, -1 is x0.67, -2 is x0.5. They last until the fight ends.",
        "Levels" to "Your cards start every run at Lv ${BattleLevels.START_LEVEL}. Opponents get tougher as the run goes on. A card that takes part in a win levels up, one that is behind catches up faster, and one far ahead stops gaining. Cards left on the bench do not level.",
        "Between fights" to "Damage carries over. After a win every card still standing recovers ${BattleTuning.HEAL_BETWEEN_FIGHTS_PCT}% of its HP. A fainted card stays fainted for the rest of the run unless a revive move (Restore Point) brings it back, so healing moves matter.",
        "Leaving" to "Leaving a run saves it. When you come back the fight you were in starts again, with your party as it was before that fight.",
    )
}
