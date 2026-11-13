package app.webmchan.battle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BattleInfoTest {
    private fun card(id: Long, v: Int, type: CardType = CardType.NONE) =
        BattleCard(id, listOf(id), "Card $id", "f$id.webm", type, BattleStats(v, v, v, v, v, v), BattleMoves.pick(type, BattleHash.of("c$id"), BattleHash.of("p$id")), false, 1, emptySet())

    @Test fun everyMoveHasADescription() {
        for (m in BattleMoves.all + BattleMoves.dudMove) {
            val d = BattleInfo.describe(m)
            assertTrue("${m.id}: '$d'", d.length > 8)
            assertTrue("${m.id} says undefined values: '$d'", !d.contains("null"))
        }
    }

    @Test fun statRowsMatchTheEngineNumbers() {
        val rows = BattleInfo.statRows(card(1, 6), 40)
        assertEquals(6, rows.size)
        assertEquals(BattleTuning.maxHp(6, 40), rows[0].value)
        assertEquals(BattleTuning.stat(6, 40), rows[5].value)
        assertTrue(rows.all { it.band == 6 })
    }

    @Test fun typeRelationsAreMirrorImages() {
        for (t in CardType.fighting) {
            assertEquals(2, BattleInfo.strongAgainst(t).size)
            assertEquals(2, BattleInfo.weakTo(t).size)
            for (o in BattleInfo.strongAgainst(t)) assertTrue(t in BattleInfo.weakTo(o))
        }
        assertTrue(BattleInfo.strongAgainst(CardType.NONE).isEmpty())
        assertEquals(TypeChart.SUPER_EFFECTIVE, BattleInfo.chartCell(CardType.MAINSTREAM, CardType.MATROSKA), 1e-9)
        assertEquals(1.0, BattleInfo.chartCell(CardType.MAINSTREAM, CardType.MAINSTREAM), 1e-9)
    }

    @Test fun helpPagesAreFilledIn() {
        assertTrue(BattleInfo.howItWorks.size >= 6)
        assertTrue(BattleInfo.howItWorks.all { it.first.isNotBlank() && it.second.length > 20 })
        for (t in CardType.values()) assertTrue(BattleInfo.sourceOf(t).isNotBlank())
        for (s in StatusEffect.values()) assertTrue(BattleInfo.statusDetail(s).isNotBlank())
    }

    // -- Records ------------------------------------------------------------------------------

    @Test fun recordsCountRunsFightsAndBests() {
        var s = BattleSave(listOf(SavedPartyCard(card(1, 4)), SavedPartyCard(card(2, 4))))
        s = s.recordRunStarted()
        s = s.recordFight(Outcome.PLAYER_WON, listOf(card(1, 4)))
        s = s.recordFight(Outcome.PLAYER_WON, listOf(card(1, 4), card(2, 4)))
        s = s.recordFight(Outcome.DRAW, listOf(card(1, 4)))
        s = s.recordFight(Outcome.OPPONENT_WON, listOf(card(2, 4)))
        s = s.recordRunEnded(2, listOf("Card 1", "Card 2"), completed = false, beatBoss = false)
        s = s.recordRunEnded(1, listOf("Card 3"), completed = true, beatBoss = true) // worse: the best stays
        assertEquals(RunRecords(1, 2, 1, 1, 2, 1, 2, listOf("Card 1", "Card 2")), s.records)
        assertEquals(2, s.partyCard(1)!!.record.fightsWon)
        s = s.recordLevels(listOf("A", "B"), listOf(33, 41)).recordLevels(listOf("A", "B"), listOf(50, 12)).recordLevels(listOf("A", "B"), listOf(10, 10))
        assertEquals(50, s.records.highestLevel)
        assertEquals("A", s.records.highestLevelName)
    }

    @Test fun recordsSurviveSavingAndOldSavesStillLoad() {
        val s = BattleSave(listOf(SavedPartyCard(card(1, 4))), null, RunRecords(5, 4, 2, 1, 30, 12, 9, listOf("x", "y"), 61, "Boss Slayer"))
        assertEquals(s.records, BattleSaveCodec.decode(BattleSaveCodec.encode(s)).records)
        val old = BattleSaveCodec.encode(s.copy(records = RunRecords())).replace(Regex("\"records\":\\{[^}]*\\},?"), "")
        assertEquals(RunRecords(), BattleSaveCodec.decode(old).records)
    }
}
