// #battle
package app.webmchan.battle

/**
 * The shapes Battle-Cards is made of: types, moves, statuses and the card itself. Plain Kotlin with
 * nothing from Android, so the whole game can be checked with ordinary JVM tests. Every name shown to
 * the player is a working name and lives in one place here, so renaming one is a one-line change.
 */

/** How many steps each stat is cut into (1 = weakest, [BAND_COUNT] = strongest). */
const val BAND_COUNT = 8

/**
 * The five card types, one per family of source container. [NONE] is for the secret boss and for
 * moves that are neutral against everything: it never gains or loses from the type chart.
 */
enum class CardType(val label: String) {
    MAINSTREAM("Mainstream"), // MP4, MOV and friends
    MATROSKA("Matroska"), // MKV, WebM
    LEGACY("Legacy"), // AVI, WMV, FLV, MPEG and other older formats
    AUDIO("Audio"), // sound-only sources
    WILD("Wild"), // GIF and anything not recognised
    NONE("Neutral");

    companion object {
        /** The five real types, in chart order (see [TypeChart]). */
        val fighting: List<CardType> = listOf(MAINSTREAM, MATROSKA, LEGACY, AUDIO, WILD)
    }
}

/**
 * Who beats whom. The five types sit in a ring, and each beats the next two round it, so every
 * pairing of two different types favours exactly one of them and nothing is immune.
 */
object TypeChart {
    const val SUPER_EFFECTIVE = 1.25
    const val NEUTRAL = 1.0
    const val RESISTED = 0.8

    /** True if a [attacker] move is super effective against [defender]. */
    fun beats(attacker: CardType, defender: CardType): Boolean {
        val ring = CardType.fighting
        val a = ring.indexOf(attacker)
        val d = ring.indexOf(defender)
        if (a < 0 || d < 0) return false
        val step = (d - a + ring.size) % ring.size
        return step == 1 || step == 2
    }

    /** The damage multiplier for an [attacker] move hitting a [defender]: [SUPER_EFFECTIVE], 1 or [RESISTED]. */
    fun multiplier(attacker: CardType, defender: CardType): Double = when {
        attacker == CardType.NONE || defender == CardType.NONE || attacker == defender -> NEUTRAL
        beats(attacker, defender) -> SUPER_EFFECTIVE
        else -> RESISTED
    }
}

/** What a move does. */
enum class MoveKind {
    /** Damage worked out from the user's Attack and the target's Defence. */
    BASIC,

    /** Damage worked out from the user's Special Attack and the target's Special Defence. */
    SPECIAL,

    /** Puts a [StatusEffect] on the target. */
    STATUS,

    /** Gives back part of the user's HP. */
    HEAL,

    /** Gives back part of the HP of every unfainted card on the user's bench (not the card using it). */
    HEAL_BENCH,

    /** Gives back part of the HP of one unfainted party member the player chooses (the user included). */
    HEAL_ALLY,

    /** Brings one fainted party member back with [Move.healPct] of its HP. */
    REVIVE,

    /** Raises one of the user's stats. */
    BUFF,

    /** Lowers one of the target's stats. */
    DEBUFF;

    val isDamage: Boolean get() = this == BASIC || this == SPECIAL

    /** True for moves that need the player to name a party member (see [BattleAction.UseMove.target]). */
    val needsTarget: Boolean get() = this == HEAL_ALLY || this == REVIVE
}

/** The four status effects. A card carries at most one, and it clears when the fight ends. */
enum class StatusEffect(val label: String, val blurb: String) {
    BUFFERING("Buffering", "Loses its turn now and then"),
    DROPPED_FRAMES("Dropped Frames", "Slower, and sometimes fails to act"),
    ARTIFACTING("Artifacting", "Loses a little HP every turn"),
    THERMAL_THROTTLE("Thermal Throttle", "Hits weaker while it lasts"),
}

/** A stat a buff or debuff can change. HP is never changed this way. */
enum class StatId(val label: String) {
    ATTACK("Attack"),
    SP_ATTACK("Sp. Attack"),
    DEFENCE("Defence"),
    SP_DEFENCE("Sp. Defence"),
    SPEED("Speed"),
}

/**
 * One move. The numbers are starting values for Stage 2 (the battle engine), where they are tuned
 * against thousands of simulated fights. Only the fields that suit the [kind] are used:
 * [power] for damage, [status] for STATUS, [healPct] for HEAL, [stat] and [stages] for BUFF/DEBUFF.
 */
data class Move(
    /** A short stable id, the key the move is stored and looked up by. Never changes once shipped. */
    val id: String,
    val name: String,
    val kind: MoveKind,
    /** The type a damaging move hits as. [CardType.NONE] is neutral against everything. */
    val type: CardType = CardType.NONE,
    val power: Int = 0,
    /** Percent chance to hit. */
    val accuracy: Int = 100,
    val status: StatusEffect? = null,
    /** HEAL, HEAL_BENCH, HEAL_ALLY, REVIVE: percent of the user's maximum HP given back. */
    val healPct: Int = 0,
    val stat: StatId? = null,
    /** BUFF: positive. DEBUFF: negative. */
    val stages: Int = 0,
)

/** A short plain-words line for a move, e.g. "Sp. Attack 70 · Mainstream · 95%" or "Heals 50% of HP". */
fun Move.summary(): String {
    val acc = if (accuracy < 100) " · $accuracy%" else ""
    val type = if (type != CardType.NONE) " · ${type.label}" else ""
    return when (kind) {
        MoveKind.BASIC -> "Attack $power$type$acc"
        MoveKind.SPECIAL -> "Sp. Attack $power$type$acc"
        MoveKind.STATUS -> "Inflicts ${status?.label ?: "a status"}$acc"
        MoveKind.HEAL -> "Heals $healPct% of HP"
        MoveKind.HEAL_BENCH -> "Heals the bench by $healPct% of HP"
        MoveKind.HEAL_ALLY -> "Heals a chosen card by $healPct% of HP"
        MoveKind.REVIVE -> "Revives a fainted card at $healPct% HP$acc"
        MoveKind.BUFF -> "Raises ${stat?.label ?: "a stat"} of the user" + if (stages > 1) " by $stages" else ""
        MoveKind.DEBUFF -> "Lowers ${stat?.label ?: "a stat"} of the target$acc"
    }
}

/**
 * The six stats, each as a band from 1 to [BAND_COUNT]. Bands, not raw numbers: Stage 2 turns a band
 * into the real value the damage formula uses, so retuning the game never needs the cards rebuilt.
 */
data class BattleStats(
    val hp: Int,
    val attack: Int,
    val spAttack: Int,
    val defence: Int,
    val spDefence: Int,
    val speed: Int,
) {
    /** The card's overall strength: all six bands added up. Opponents are sorted by this. */
    val power: Int get() = hp + attack + spAttack + defence + spDefence + speed
}

/**
 * What a stat could not be worked out from, because the saved History card doesn't hold it (cards
 * made before Battle-Cards existed). The stat then sits in the middle band instead.
 */
enum class Missing(val label: String) {
    SOURCE_SIZE("source size"),
    CONVERSION_TIME("conversion time"),
    SOURCE_CONTAINER("source format"),
    SIZE_LIMIT("size limit"),
}

/** A card as the battle sees it. Built from History by [BattleDerive]; nothing here is saved yet. */
data class BattleCard(
    /** The id of the History card this was made from (the first part's, for a split video). Stable. */
    val id: Long,
    /** Every History card folded into this one (more than one for a split video). */
    val memberIds: List<Long>,
    /** The invented creature name. */
    val name: String,
    /** The saved file's name, so the card can be matched back to what made it. */
    val sourceName: String,
    val type: CardType,
    val stats: BattleStats,
    /** Four moves; the first is always a damaging move of the card's own type. */
    val moves: List<Move>,
    val isAudio: Boolean,
    val parts: Int,
    val missing: Set<Missing>,
)
