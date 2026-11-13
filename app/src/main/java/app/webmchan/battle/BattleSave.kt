// #battle
package app.webmchan.battle

/** What a party card has done. Only cards in the party have one. */
data class CardRecord(val fightsWon: Int = 0, val fightsLost: Int = 0, val runsCompleted: Int = 0)

/** A party card as it was when it joined: a snapshot, so it survives its History card being deleted. */
data class SavedPartyCard(val card: BattleCard, val record: CardRecord = CardRecord())

/** A run in progress. The party is by id (the snapshots are in [BattleSave.party]); the opponents are snapshots. */
data class SavedRun(
    val partyIds: List<Long>,
    val opponents: List<BattleCard>,
    val boss: BattleCard?,
    val seed: Long,
    val point: RunPoint,
    /** The party's levels at that point, in party order (empty in older saves: everyone starts fresh). */
    val levels: List<Int> = emptyList(),
    /** The party's HP at that point, in party order (empty in older saves: everyone at full health). */
    val hp: List<Int> = emptyList(),
)

/**
 * Everything Battle-Cards keeps between visits: the party cards (with records) and the run in
 * progress, if any. Immutable: each change returns a new one for the caller to keep and save.
 */
data class BattleSave(
    val party: List<SavedPartyCard> = emptyList(),
    val run: SavedRun? = null,
    /** Totals and bests across every run. */
    val records: RunRecords = RunRecords(),
) {

    fun partyCard(id: Long): SavedPartyCard? = party.firstOrNull { it.card.id == id }

    /** Adds any of [cards] that aren't in the party yet (a card already there keeps its snapshot and record). */
    fun withParty(cards: List<BattleCard>): BattleSave {
        val fresh = cards.filter { c -> party.none { it.card.id == c.id } }.map { SavedPartyCard(it) }
        return if (fresh.isEmpty()) this else copy(party = party + fresh)
    }

    /** Counts a finished fight for each of the cards that took part, and in the totals. */
    fun recordFight(outcome: Outcome, participants: List<BattleCard>): BattleSave {
        if (outcome == Outcome.DRAW) return this
        val ids = participants.map { it.id }.toSet()
        val totals = if (outcome == Outcome.PLAYER_WON) records.copy(fightsWon = records.fightsWon + 1) else records.copy(fightsLost = records.fightsLost + 1)
        return copy(records = totals, party = party.map {
            if (it.card.id !in ids) it
            else if (outcome == Outcome.PLAYER_WON) it.copy(record = it.record.copy(fightsWon = it.record.fightsWon + 1))
            else it.copy(record = it.record.copy(fightsLost = it.record.fightsLost + 1))
        })
    }

    fun recordRunCompleted(partyIds: List<Long>): BattleSave =
        copy(party = party.map { if (it.card.id in partyIds) it.copy(record = it.record.copy(runsCompleted = it.record.runsCompleted + 1)) else it })

    fun withRun(run: SavedRun?): BattleSave = copy(run = run)

    fun recordRunStarted(): BattleSave = copy(records = records.copy(runsStarted = records.runsStarted + 1))

    /** A run that ended (won through, lost, or given up) with [wins] fights won by [partyNames]. */
    fun recordRunEnded(wins: Int, partyNames: List<String>, completed: Boolean, beatBoss: Boolean): BattleSave {
        var r = records.copy(
            runsEnded = records.runsEnded + 1,
            runsCompleted = records.runsCompleted + if (completed) 1 else 0,
            bossesBeaten = records.bossesBeaten + if (beatBoss) 1 else 0,
        )
        if (wins > r.bestRunWins) r = r.copy(bestRunWins = wins, bestRunParty = partyNames)
        return copy(records = r)
    }

    /** Notes the highest level any party card has reached. */
    fun recordLevels(names: List<String>, levels: List<Int>): BattleSave {
        val best = levels.indices.maxByOrNull { levels[it] } ?: return this
        return if (levels[best] > records.highestLevel) copy(records = records.copy(highestLevel = levels[best], highestLevelName = names[best])) else this
    }
}

/** Battle-Cards totals and bests, across all runs. */
data class RunRecords(
    val runsStarted: Int = 0,
    /** Runs that finished one way or another: won through, lost, or given up. */
    val runsEnded: Int = 0,
    val runsCompleted: Int = 0,
    val bossesBeaten: Int = 0,
    val fightsWon: Int = 0,
    val fightsLost: Int = 0,
    /** The most fights won in one run, and the party that did it. */
    val bestRunWins: Int = 0,
    val bestRunParty: List<String> = emptyList(),
    val highestLevel: Int = 0,
    val highestLevelName: String = "",
)

/** Turns a [BattleSave] into text and back. A damaged or unreadable save gives an empty one rather than an error. */
object BattleSaveCodec {
    private const val VERSION = 1

    fun encode(save: BattleSave): String = MiniJson.write(
        mapOf(
            "version" to VERSION,
            "party" to save.party.map { mapOf("card" to encodeCard(it.card), "won" to it.record.fightsWon, "lost" to it.record.fightsLost, "runs" to it.record.runsCompleted) },
            "records" to save.records.let {
                mapOf(
                    "started" to it.runsStarted, "ended" to it.runsEnded, "completed" to it.runsCompleted, "bosses" to it.bossesBeaten,
                    "won" to it.fightsWon, "lost" to it.fightsLost, "best" to it.bestRunWins, "bestParty" to it.bestRunParty,
                    "level" to it.highestLevel, "levelName" to it.highestLevelName,
                )
            },
            "run" to save.run?.let { r ->
                mapOf(
                    "party" to r.partyIds,
                    "opponents" to r.opponents.map { encodeCard(it) },
                    "boss" to r.boss?.let { encodeCard(it) },
                    "seed" to r.seed,
                    "fight" to r.point.fightIndex,
                    "wins" to r.point.wins,
                    "attempt" to r.point.attempt,
                    "levels" to r.levels,
                    "hp" to r.hp,
                )
            },
        ),
    )

    fun decode(text: String): BattleSave = try {
        val root = MiniJson.read(text) as Map<*, *>
        val party = (root["party"] as? List<*>).orEmpty().mapNotNull { e ->
            val m = e as? Map<*, *> ?: return@mapNotNull null
            val c = decodeCard(m["card"] as? Map<*, *> ?: return@mapNotNull null) ?: return@mapNotNull null
            SavedPartyCard(c, CardRecord(int(m["won"]), int(m["lost"]), int(m["runs"])))
        }
        val run = (root["run"] as? Map<*, *>)?.let { r ->
            val opponents = (r["opponents"] as? List<*>).orEmpty().mapNotNull { decodeCard(it as? Map<*, *> ?: return@mapNotNull null) }
            val ids = (r["party"] as? List<*>).orEmpty().mapNotNull { (it as? Number)?.toLong() }
            // A run that can't be rebuilt in full (a missing card) is dropped, not half-restored.
            if (opponents.isEmpty() || ids.isEmpty() || ids.any { id -> party.none { it.card.id == id } }) null
            else SavedRun(ids, opponents, (r["boss"] as? Map<*, *>)?.let { decodeCard(it) }, (r["seed"] as? Number)?.toLong() ?: 0L,
                RunPoint(int(r["fight"]), int(r["wins"]), int(r["attempt"])),
                (r["levels"] as? List<*>).orEmpty().mapNotNull { (it as? Number)?.toInt() },
                (r["hp"] as? List<*>).orEmpty().mapNotNull { (it as? Number)?.toInt() })
        }
        val records = (root["records"] as? Map<*, *>)?.let { m ->
            RunRecords(
                int(m["started"]), int(m["ended"]), int(m["completed"]), int(m["bosses"]), int(m["won"]), int(m["lost"]), int(m["best"]),
                (m["bestParty"] as? List<*>).orEmpty().mapNotNull { it as? String }, int(m["level"]), m["levelName"] as? String ?: "",
            )
        } ?: RunRecords()
        BattleSave(party, run, records)
    } catch (_: Exception) {
        BattleSave()
    }

    private fun int(v: Any?): Int = (v as? Number)?.toInt() ?: 0

    private fun encodeCard(c: BattleCard): Map<String, Any?> = mapOf(
        "id" to c.id,
        "members" to c.memberIds,
        "name" to c.name,
        "source" to c.sourceName,
        "type" to c.type.name,
        "stats" to listOf(c.stats.hp, c.stats.attack, c.stats.spAttack, c.stats.defence, c.stats.spDefence, c.stats.speed),
        "moves" to c.moves.map { it.id },
        "audio" to c.isAudio,
        "parts" to c.parts,
        "missing" to c.missing.map { it.name },
        "dud" to c.isDud,
    )

    private fun decodeCard(m: Map<*, *>): BattleCard? {
        val id = (m["id"] as? Number)?.toLong() ?: return null
        val stats = (m["stats"] as? List<*>)?.mapNotNull { (it as? Number)?.toInt() }
        if (stats == null || stats.size != 6) return null
        val moves = (m["moves"] as? List<*>).orEmpty().mapNotNull { BattleMoves.byId(it as? String ?: return@mapNotNull null) }
        val dud = m["dud"] as? Boolean ?: false
        return BattleCard(
            id = id,
            memberIds = (m["members"] as? List<*>)?.mapNotNull { (it as? Number)?.toLong() }?.ifEmpty { null } ?: listOf(id),
            name = m["name"] as? String ?: "Card",
            sourceName = m["source"] as? String ?: "",
            type = CardType.values().firstOrNull { it.name == m["type"] } ?: CardType.WILD,
            stats = BattleStats(stats[0], stats[1], stats[2], stats[3], stats[4], stats[5]),
            // A card whose moves can't be found any more still fights, with the do-nothing move.
            moves = moves.ifEmpty { listOf(BattleMoves.dudMove) },
            isAudio = m["audio"] as? Boolean ?: false,
            parts = (m["parts"] as? Number)?.toInt() ?: 1,
            missing = (m["missing"] as? List<*>).orEmpty().mapNotNull { n -> Missing.values().firstOrNull { it.name == n } }.toSet(),
            isDud = dud,
        )
    }
}
