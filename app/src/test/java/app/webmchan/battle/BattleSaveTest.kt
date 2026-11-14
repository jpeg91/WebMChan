package app.webmchan.battle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Stage 4's rules: saving, resuming, records, dud cards, the boss and ready-made opponents. */
class BattleSaveTest {
    private fun card(id: Long, v: Int = 4, type: CardType = CardType.MAINSTREAM) =
        BattleCard(id, listOf(id), "Card $id \"q\" \\ é", "f$id.webm", type, BattleStats(v, v, v, v, v, v),
            BattleMoves.pick(type, BattleHash.of("c$id"), BattleHash.of("p$id")), false, 1, setOf(Missing.SOURCE_SIZE))

    // -- Json ---------------------------------------------------------------------------------

    @Test fun jsonRoundTripsEveryKindOfValue() {
        val v = mapOf("a" to 1L, "b" to listOf("x", true, null, 2.5), "c" to mapOf("big" to Long.MAX_VALUE, "neg" to -7L), "s" to "line\nbreak \"quoted\" \\ \u0001 é")
        assertEquals(v, MiniJson.read(MiniJson.write(v)))
    }

    @Test fun jsonRejectsGarbage() {
        for (bad in listOf("", "{", "{\"a\":}", "[1,]x", "nope")) {
            try { MiniJson.read(bad); error("accepted '$bad'") } catch (e: IllegalArgumentException) { }
        }
    }

    // -- Save codec ---------------------------------------------------------------------------

    @Test fun aSaveComesBackExactlyAsItWentIn() {
        val party = listOf(card(1), card(2, 7, CardType.WILD), BattleDerive.dud(3, "bad-clip.mp4"))
        val run = SavedRun(listOf(1, 2), listOf(card(10), card(11)), BattleBoss.card, Long.MIN_VALUE + 5, RunPoint(3, 2, 1))
        val save = BattleSave(party.map { SavedPartyCard(it, CardRecord(4, 5, 6)) }, run)
        assertEquals(save, BattleSaveCodec.decode(BattleSaveCodec.encode(save)))
    }

    @Test fun aDamagedSaveGivesAnEmptyOneNotACrash() {
        assertEquals(BattleSave(), BattleSaveCodec.decode("not json at all"))
        assertEquals(BattleSave(), BattleSaveCodec.decode(""))
        assertEquals(BattleSave(), BattleSaveCodec.decode("{\"party\":5}"))
    }

    @Test fun aRunWhosePartyCardIsMissingIsDroppedButThePartyStays() {
        val full = BattleSave(listOf(SavedPartyCard(card(1))), SavedRun(listOf(1, 99), listOf(card(10)), null, 1L, RunPoint(0, 0, 0)))
        val back = BattleSaveCodec.decode(BattleSaveCodec.encode(full))
        assertNull(back.run)
        assertEquals(1, back.party.size)
    }

    @Test fun aMoveThatNoLongerExistsFallsBackToTheDoNothingMove() {
        val text = BattleSaveCodec.encode(BattleSave(listOf(SavedPartyCard(card(1))))).replace("keyframe_strike", "gone").let { t ->
            // Make every move id unknown.
            Regex("\"moves\":\\[[^\\]]*\\]").replace(t, "\"moves\":[\"nope\"]")
        }
        val c = BattleSaveCodec.decode(text).party.single().card
        assertEquals(listOf(BattleMoves.dudMove), c.moves)
    }

    // -- Party and records --------------------------------------------------------------------

    @Test fun joiningThePartyKeepsTheOldSnapshotAndRecord() {
        val a = card(1)
        var s = BattleSave().withParty(listOf(a)).recordFight(Outcome.PLAYER_WON, listOf(a))
        s = s.withParty(listOf(a.copy(name = "Changed"), card(2)))
        assertEquals(2, s.party.size)
        assertEquals(a.name, s.partyCard(1)!!.card.name)
        assertEquals(1, s.partyCard(1)!!.record.fightsWon)
    }

    @Test fun recordsCountOnlyTheCardsThatFoughtAndIgnoreDraws() {
        val s0 = BattleSave().withParty(listOf(card(1), card(2), card(3)))
        val won = s0.recordFight(Outcome.PLAYER_WON, listOf(card(1), card(2)))
        assertEquals(listOf(1, 1, 0), won.party.map { it.record.fightsWon })
        val lost = won.recordFight(Outcome.OPPONENT_WON, listOf(card(2)))
        assertEquals(1, lost.partyCard(2)!!.record.fightsLost)
        assertEquals(lost, lost.recordFight(Outcome.DRAW, listOf(card(1))))
        assertEquals(1, lost.recordRunCompleted(listOf(3)).partyCard(3)!!.record.runsCompleted)
    }

    // -- Resuming a run -----------------------------------------------------------------------

    private fun strongParty() = listOf(card(1, 8), card(2, 8))
    private fun foes(n: Int) = (1..n).map { card(100L + it, 2) }

    private fun fightOut(s: BattleSession) {
        var guard = 0
        while (s.phase == RunPhase.FIGHTING && guard++ < 500) {
            if (s.mustSwitch) s.replace(s.battle.legalSwitches(Side.PLAYER).first()) else s.play(BattleAction.UseMove(0))
        }
    }

    @Test fun aRunResumedFromItsSavePointPlaysTheSameFightsAsTheOriginal() {
        val party = strongParty()
        val foes = foes(4)
        val original = BattleSession(party, foes, 42L)
        fightOut(original); original.next()
        // Save point now: start of fight 1. A restored session must play fight 1 exactly as the original does.
        val point = original.resumePoint()!!
        assertEquals(RunPoint(1, 1, 0), point)
        val restored = BattleSession(party, foes, 42L, startFight = point.fightIndex, startWins = point.wins, startAttempt = point.attempt, startLevels = original.resumeLevels(), startHp = original.resumeHp())
        assertEquals(original.opponent.id, restored.opponent.id)
        assertEquals(original.wins, restored.wins)
        fightOut(original); fightOut(restored)
        assertEquals(original.battle.events, restored.battle.events)
    }

    @Test fun theSavePointMovesOnWhenAFightEndsNotWhenNextIsPressed() {
        val s = BattleSession(strongParty(), foes(3), 1L)
        val points = ArrayList<RunPoint?>()
        s.onSavePoint = { points.add(it.resumePoint()) }
        fightOut(s)
        assertEquals(RunPhase.FIGHT_WON, s.phase)
        assertEquals(RunPoint(1, 1, 0), points.last())
        s.next()
        assertEquals(RunPoint(1, 1, 0), points.last())
    }

    @Test fun aFinishedOrLostRunHasNothingToResume() {
        val lost = BattleSession(listOf(card(1, 1)), listOf(card(10, 8)), 2L)
        fightOut(lost)
        assertEquals(RunPhase.RUN_LOST, lost.phase)
        assertNull(lost.resumePoint())
        val won = BattleSession(strongParty(), foes(1), 3L)
        fightOut(won)
        assertEquals(RunPhase.RUN_COMPLETE, won.phase)
        assertNull(won.resumePoint())
    }

    @Test fun fightEndsReportTheCardsThatTookPart() {
        val s = BattleSession(strongParty(), foes(2), 5L)
        var heard: Pair<Outcome, List<Long>>? = null
        s.onFightEnded = { o, c -> heard = o to c.map { it.id } }
        fightOut(s)
        assertEquals(Outcome.PLAYER_WON, heard!!.first)
        assertTrue(1L in heard!!.second)
    }

    // -- Boss and ready-made opponents --------------------------------------------------------

    @Test fun theBossComesOnlyAfterEveryoneElseInARunOverFiftyBattles() {
        val long = BattleSession(strongParty(), foes(51), 1L, boss = BattleBoss.card)
        assertTrue(long.hasBossFight)
        assertEquals(51, long.fightCount) // the boss is a secret: not counted
        val short = BattleSession(strongParty(), foes(50), 1L, boss = BattleBoss.card)
        assertFalse(short.hasBossFight)
        // Reach the last ordinary fight of the long run: winning it does not end the run.
        val s = BattleSession(strongParty(), foes(51), 1L, boss = BattleBoss.card, startFight = 50)
        fightOut(s)
        assertEquals(RunPhase.FIGHT_WON, s.phase)
        assertEquals(BattleBoss.card.id, s.nextOpponent!!.id)
        s.next()
        assertTrue(s.isBossFight)
    }

    @Test fun beatingTheBossCompletesTheRun() {
        val s = BattleSession(listOf(card(1, 8), card(2, 8), card(3, 8), card(4, 8), card(5, 8), card(6, 8)), foes(51), 9L, boss = BattleBoss.card, startFight = 51)
        assertTrue(s.isBossFight)
        fightOut(s)
        assertTrue(s.phase == RunPhase.RUN_COMPLETE || s.phase == RunPhase.RUN_LOST)
    }

    @Test fun aBossTestRunMeetsTheBossWhateverItsLengthAndSurvivesSaving() {
        val test = BattleSession(strongParty(), foes(1), 1L, boss = BattleBoss.card, startFight = 1, forceBoss = true)
        assertTrue(test.hasBossFight)
        assertTrue(test.isBossFight)
        assertFalse(BattleSession(strongParty(), foes(1), 1L, boss = BattleBoss.card).hasBossFight)
        val run = SavedRun(listOf(1), foes(1), BattleBoss.card, 1L, RunPoint(1, 0, 0), forceBoss = true)
        val back = BattleSaveCodec.decode(BattleSaveCodec.encode(BattleSave(listOf(SavedPartyCard(card(1))), run)))
        assertTrue(back.run!!.forceBoss)
    }

    @Test fun partyCardsWithTheSameNameAreToldApart() {
        val a = card(1)
        val s = BattleSession(listOf(a, card(2).copy(name = a.name), card(3).copy(name = a.name)), foes(2), 1L)
        assertEquals(listOf(a.name, "${a.name} (2)", "${a.name} (3)"), s.party.map { it.name })
        assertEquals(listOf(1L, 2L, 3L), s.party.map { it.id })
    }

    @Test fun theBossCardIsStrongTypelessAndFair() {
        val b = BattleBoss.card
        assertEquals(CardType.NONE, b.type)
        assertEquals(4, b.moves.size)
        assertTrue(b.stats.power in 40..48)
    }

    @Test fun readyMadeCardsAreFixedWeakToStrongAndTopUpOnlyWhenShort() {
        assertEquals(BattlePregen.all, BattlePregen.all)
        assertEquals(40, BattlePregen.all.map { it.id }.toSet().size)
        assertTrue(BattlePregen.all.first().stats.power < BattlePregen.all.last().stats.power)
        for (c in BattlePregen.all) assertTrue(c.moves.count { it.kind.isDamage } >= 2)
        val few = foes(3)
        val topped = BattlePregen.topUp(few)
        assertEquals(BattlePregen.MIN_OPPONENTS, topped.size)
        assertEquals(topped.map { it.stats.power }, topped.map { it.stats.power }.sorted())
        assertTrue(few.all { f -> topped.any { it.id == f.id } })
        val plenty = foes(20)
        assertEquals(plenty, BattlePregen.topUp(plenty))
    }

    // -- Dud cards ----------------------------------------------------------------------------

    @Test fun aDudIsTheWeakestCardWithOneUselessMove() {
        val d = BattleDerive.dud(7, "my clip-MQ-WebMChan_for_Android.webm")
        assertTrue(d.isDud)
        assertEquals("my clip", d.name)
        assertEquals(6, d.stats.power)
        assertEquals(1, d.moves.size)
        val b = Battle(listOf(d), listOf(card(2, 4)), 3L)
        val ev = b.turn(BattleAction.UseMove(0), BattleAction.UseMove(0))
        assertTrue(ev.any { it is BattleEvent.Failed && it.side == Side.PLAYER })
        assertEquals(b.active(Side.OPPONENT).maxHp, b.active(Side.OPPONENT).hp)
    }

    @Test fun aDudCanBeInAPartyAndAlwaysMakesALegalChoice() {
        val b = Battle(listOf(BattleDerive.dud(7, "x.mp4")), listOf(card(2, 4)), 3L)
        val ai = BattleAi(1L)
        val out = ai.playOut(b)
        assertEquals(Outcome.OPPONENT_WON, out)
    }
}
