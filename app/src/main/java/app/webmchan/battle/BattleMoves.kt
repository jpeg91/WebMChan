// #battle
package app.webmchan.battle

/**
 * The move pool, and how a card gets its four moves from its seeds.
 *
 * Move one is always a damaging move of the card's own type, so every card can fight. The other three
 * come from a shared pool of typeless damage, status, heal, buff and debuff moves (plus the card's
 * other own-type moves), chosen by hash for variety. The first [CORE_SLOTS] moves come from the
 * card's core seed (shared by every card with the same stats and type), the rest from its personal
 * seed, so two copies of the same conversion are cousins: the same bones, a little different.
 *
 * Names are original jokes on codec and encoding vocabulary. Numbers are Stage 2 starting values.
 */
object BattleMoves {
    /** How many of the four slots are decided by the core seed (the rest by the personal one). */
    const val CORE_SLOTS = 2

    const val SLOTS = 4

    private fun hit(id: String, name: String, kind: MoveKind, type: CardType, power: Int, accuracy: Int = 100) =
        Move(id, name, kind, type = type, power = power, accuracy = accuracy)

    private fun status(id: String, name: String, effect: StatusEffect, accuracy: Int) =
        Move(id, name, MoveKind.STATUS, status = effect, accuracy = accuracy)

    private fun heal(id: String, name: String, pct: Int) =
        Move(id, name, MoveKind.HEAL, healPct = pct)

    private fun buff(id: String, name: String, stat: StatId, stages: Int = 1) =
        Move(id, name, MoveKind.BUFF, stat = stat, stages = stages)

    private fun debuff(id: String, name: String, stat: StatId, accuracy: Int = 100) =
        Move(id, name, MoveKind.DEBUFF, stat = stat, stages = -1, accuracy = accuracy)

    /** Each type's own damaging moves. Move one of a card is one of these. */
    private val signature: Map<CardType, List<Move>> = mapOf(
        CardType.MAINSTREAM to listOf(
            hit("keyframe_strike", "Keyframe Strike", MoveKind.BASIC, CardType.MAINSTREAM, 65),
            hit("baseline_bash", "Baseline Bash", MoveKind.BASIC, CardType.MAINSTREAM, 55),
            hit("profile_pulse", "Profile Pulse", MoveKind.SPECIAL, CardType.MAINSTREAM, 70, 95),
        ),
        CardType.MATROSKA to listOf(
            hit("cluster_crush", "Cluster Crush", MoveKind.BASIC, CardType.MATROSKA, 65),
            hit("segment_slice", "Segment Slice", MoveKind.BASIC, CardType.MATROSKA, 70, 95),
            hit("track_torrent", "Track Torrent", MoveKind.SPECIAL, CardType.MATROSKA, 65),
        ),
        CardType.LEGACY to listOf(
            hit("interlace_lash", "Interlace Lash", MoveKind.BASIC, CardType.LEGACY, 60),
            hit("codec_clash", "Codec Clash", MoveKind.SPECIAL, CardType.LEGACY, 70, 90),
            hit("scanline_scrape", "Scanline Scrape", MoveKind.BASIC, CardType.LEGACY, 55),
        ),
        CardType.AUDIO to listOf(
            hit("waveform_lash", "Waveform Lash", MoveKind.SPECIAL, CardType.AUDIO, 65),
            hit("bass_drop", "Bass Drop", MoveKind.SPECIAL, CardType.AUDIO, 75, 90),
            hit("sample_slap", "Sample Slap", MoveKind.BASIC, CardType.AUDIO, 60),
        ),
        CardType.WILD to listOf(
            hit("palette_panic", "Palette Panic", MoveKind.SPECIAL, CardType.WILD, 60),
            hit("loop_lash", "Loop Lash", MoveKind.BASIC, CardType.WILD, 65),
            hit("frame_flicker", "Frame Flicker", MoveKind.BASIC, CardType.WILD, 55),
        ),
    )

    /** The shared pool: typeless damage and every kind of support move. */
    private val shared: List<Move> = listOf(
        // Damage that hits as no type (never super effective, never resisted).
        hit("bitrate_drain", "Bitrate Drain", MoveKind.SPECIAL, CardType.NONE, 55),
        hit("chroma_crunch", "Chroma Crunch", MoveKind.BASIC, CardType.NONE, 50),
        hit("two_pass_takedown", "Two-Pass Takedown", MoveKind.BASIC, CardType.NONE, 80, 85),
        hit("overshoot", "Overshoot", MoveKind.SPECIAL, CardType.NONE, 90, 70),
        // Status.
        status("buffering", "Buffering…", StatusEffect.BUFFERING, 80),
        status("frame_drop", "Frame Drop", StatusEffect.DROPPED_FRAMES, 90),
        status("artifact_spray", "Artifact Spray", StatusEffect.ARTIFACTING, 85),
        status("thermal_throttle", "Thermal Throttle", StatusEffect.THERMAL_THROTTLE, 90),
        // Heal.
        heal("remux", "Re-mux", 40),
        heal("cache_flush", "Cache Flush", 30),
        // Buffs.
        buff("bitrate_boost", "Bitrate Boost", StatId.SP_ATTACK),
        buff("two_pass_prep", "Two-Pass Prep", StatId.ATTACK),
        buff("denoise", "Denoise", StatId.DEFENCE),
        buff("deblock", "Deblock", StatId.SP_DEFENCE),
        buff("fast_preset", "Fast Preset", StatId.SPEED),
        // Debuffs.
        debuff("downscale", "Downscale", StatId.ATTACK),
        debuff("lowpass", "Lowpass", StatId.SP_ATTACK),
        debuff("crop", "Crop", StatId.DEFENCE),
        debuff("banding", "Banding", StatId.SP_DEFENCE),
        debuff("drop_fps", "Drop FPS", StatId.SPEED),
    )

    /** Every move in the game. */
    val all: List<Move> = signature.values.flatten() + shared

    private val byId: Map<String, Move> = all.associateBy { it.id }

    init {
        // A duplicate id would make two different moves share a saved record, so fail loudly.
        check(byId.size == all.size) { "Two moves share an id" }
    }

    fun byId(id: String): Move? = byId[id]

    /** The own-type damaging moves for [type] (empty for [CardType.NONE]). */
    fun signatureFor(type: CardType): List<Move> = signature[type].orEmpty()

    /**
     * The four moves for a card of [type]. [coreSeed] is the same for every card with the same
     * stats and type; [personalSeed] differs per card. The same seeds always give the same moves.
     */
    fun pick(type: CardType, coreSeed: Long, personalSeed: Long): List<Move> {
        val core = BattleRng(coreSeed)
        val personal = BattleRng(personalSeed)
        val own = signatureFor(type)
        val first = if (own.isEmpty()) core.pick(shared.filter { it.kind.isDamage }) else core.pick(own)
        // The pool for the other slots: the shared moves, and this type's other signature moves.
        val pool = shared + own.filter { it.id != first.id }
        val chosen = ArrayList<Move>(SLOTS)
        chosen.add(first)
        for (slot in 1 until SLOTS) {
            val rng = if (slot < CORE_SLOTS) core else personal
            chosen.add(rng.pick(pool.filter { p -> chosen.none { it.id == p.id } }))
        }
        // Never a card that can barely hurt anything: at least two moves must do damage.
        if (chosen.count { it.kind.isDamage } < 2) {
            val damaging = pool.filter { it.kind.isDamage && chosen.none { c -> c.id == it.id } }
            chosen[SLOTS - 1] = personal.pick(damaging)
        }
        return chosen
    }
}
