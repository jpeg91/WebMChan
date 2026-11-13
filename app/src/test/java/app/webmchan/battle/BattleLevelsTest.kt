package app.webmchan.battle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/** Levels: the opponent's curve, who levels up, how it saves, and that a run still has a sensible difficulty. */
class BattleLevelsTest {
    private fun card(id: Long, v: Int) =
        BattleCard(id, listOf(id), "Card $id", "f$id.webm", CardType.NONE, BattleStats(v, v, v, v, v, v), BattleMoves.pick(CardType.NONE, BattleHash.of("c$id"), BattleHash.of("p$id")), false, 1, emptySet())

    @Test fun opponentsGetTougherThenLevelOff() {
        val levels = (0 until 150).map { BattleLevels.foeLevel(it, 150) }
        assertEquals(BattleLevels.FIRST_FOE_LEVEL, levels.first())
        assertTrue(levels.zipWithNext().all { (a, b) -> b >= a })
        assertEquals(BattleLevels.LAST_FOE_LEVEL, levels.last())
        assertEquals(BattleLevels.LAST_FOE_LEVEL + BattleLevels.BOSS_BONUS, BattleLevels.foeLevel(0, 150, boss = true))
    }

    @Test fun onlyCardsThatTookPartLevelUp() {
        val after = BattleLevels.afterWin(listOf(30, 30, 30), setOf(0, 2), 30)
        assertEquals(listOf(30 + BattleLevels.gain(30, 30), 30, 30 + BattleLevels.gain(30, 30)), after)
    }

    @Test fun cardsBehindCatchUpAndCardsFarAheadStop() {
        assertTrue(BattleLevels.gain(30, 50) > BattleLevels.gain(30, 31))
        assertEquals(0, BattleLevels.gain(70, 50))
        assertTrue(BattleLevels.gain(50, 50) >= 1)
        assertEquals(BattleLevels.MAX_LEVEL, BattleLevels.afterWin(listOf(BattleLevels.MAX_LEVEL), setOf(0), 200).single())
    }

    @Test fun aHigherLevelCardWinsTheSameMatchup() {
        var wins = 0
        for (seed in 1L..60L) {
            val b = Battle(listOf(card(1, 4)), listOf(card(2, 4)), seed, listOf(45), listOf(30))
            BattleAi(seed).playOut(b)
            if (b.outcome == Outcome.PLAYER_WON) wins++
        }
        assertTrue("won $wins of 60", wins >= 55)
    }

    @Test fun anOrdinaryFightStillUsesTheDefaultLevel() {
        val f = Fighter(card(1, 4))
        assertEquals(BattleTuning.LEVEL, f.level)
        assertEquals(BattleTuning.maxHp(4), f.maxHp)
    }

    @Test fun aWinLevelsTheParticipantsAndTheLevelsAreSavedForResuming() {
        val s = BattleSession(listOf(card(1, 8), card(2, 8)), listOf(card(10, 1), card(11, 1)), 5L)
        assertEquals(listOf(BattleLevels.START_LEVEL, BattleLevels.START_LEVEL), s.levels)
        val ai = BattleAi(1)
        while (s.phase == RunPhase.FIGHTING) s.play(ai.chooseAction(s.battle, Side.PLAYER))
        assertEquals(RunPhase.FIGHT_WON, s.phase)
        assertTrue(s.levels[0] > BattleLevels.START_LEVEL)
        assertEquals(BattleLevels.START_LEVEL, s.levels[1]) // never left the bench
        assertEquals(listOf(LevelUp("Card 1", BattleLevels.START_LEVEL, s.levels[0])), s.levelUps)
        // Saved and rebuilt: the same levels come back.
        val run = SavedRun(listOf(1, 2), s.opponents, null, s.runSeed, s.resumePoint()!!, s.resumeLevels())
        val save = BattleSave(listOf(card(1, 8), card(2, 8)).map { SavedPartyCard(it) }, run)
        val back = BattleSaveCodec.decode(BattleSaveCodec.encode(save)).run!!
        assertEquals(s.levels, back.levels)
        val again = BattleSession(listOf(card(1, 8), card(2, 8)), back.opponents, back.seed, back.boss, back.point.fightIndex, back.point.wins, back.point.attempt, back.levels)
        assertEquals(s.levels, again.levels)
        // An old save with no levels starts everyone fresh.
        val old = BattleSession(listOf(card(1, 8), card(2, 8)), back.opponents, back.seed, null, 1, 1, 0, emptyList())
        assertEquals(listOf(BattleLevels.START_LEVEL, BattleLevels.START_LEVEL), old.levels)
    }

    @Test fun aLeveledRunGetsRandomPartiesPartWayThrough() {
        val rnd = Random(8)
        val cards = (1..60).map { card(it.toLong(), 1 + rnd.nextInt(8)) }
        val beaten = (0 until 12).map { run ->
            val party = cards.shuffled(rnd).take(6)
            val s = BattleSession(party, BattleRun.opponentOrder(cards, party.map { it.id }.toSet()), run * 3L)
            val ai = BattleAi(run + 1L)
            var guard = 0
            while (s.phase != RunPhase.RUN_LOST && s.phase != RunPhase.RUN_COMPLETE && guard++ < 50000) {
                when {
                    s.phase == RunPhase.FIGHT_WON || s.phase == RunPhase.FIGHT_DRAWN -> s.next()
                    s.mustSwitch -> s.replace(ai.chooseReplacement(s.battle, Side.PLAYER))
                    else -> s.play(ai.chooseAction(s.battle, Side.PLAYER))
                }
            }
            s.wins
        }
        val avg = beaten.average()
        println("LEVELS gauntlet: random parties beat %.1f of 54".format(avg))
        assertTrue("average $avg", avg in 8.0..50.0)
    }

    // -- Choosing who leads ---------------------------------------------------------------------

    @Test fun theChosenCardStartsEachFight() {
        val party = listOf(card(1, 8), card(2, 8), card(3, 8))
        val s = BattleSession(party, listOf(card(10, 1), card(11, 1)), 5L, leadPicking = true)
        assertEquals(RunPhase.PICKING_LEAD, s.phase)
        assertEquals(RunPoint(0, 0, 0), s.resumePoint()) // a save made now starts this fight again
        assertTrue(s.lastEvents.isEmpty())
        assertTrue(!s.play(BattleAction.UseMove(0))) // no moves before a card is out
        assertTrue(!s.chooseLead(7))
        assertTrue(s.chooseLead(2))
        assertEquals(RunPhase.FIGHTING, s.phase)
        assertEquals("Card 3", s.battle.active(Side.PLAYER).card.name)
        assertEquals(setOf(2), s.battle.appeared(Side.PLAYER))
        assertTrue(!s.chooseLead(0)) // only once per fight
        val ai = BattleAi(1)
        while (s.phase == RunPhase.FIGHTING) s.play(ai.chooseAction(s.battle, Side.PLAYER))
        assertEquals(RunPhase.FIGHT_WON, s.phase)
        assertTrue(s.levels[2] > BattleLevels.START_LEVEL)
        assertEquals(BattleLevels.START_LEVEL, s.levels[0])
        s.next()
        assertEquals(RunPhase.PICKING_LEAD, s.phase) // asked again for the next fight
        assertTrue(s.chooseLead(0))
        assertEquals("Card 1", s.battle.active(Side.PLAYER).card.name)
    }

    @Test fun aPartyOfOneAndTheDefaultNeverAsk() {
        assertEquals(RunPhase.FIGHTING, BattleSession(listOf(card(1, 4)), listOf(card(10, 4)), 1L, leadPicking = true).phase)
        assertEquals(RunPhase.FIGHTING, BattleSession(listOf(card(1, 4), card(2, 4)), listOf(card(10, 4)), 1L).phase)
    }

    // -- Damage carries over ----------------------------------------------------------------------

    @Test fun damageCarriesOverAndSurvivorsRecoverTenPercent() {
        val s = BattleSession(listOf(card(1, 5), card(2, 5)), listOf(card(10, 5), card(11, 5)), 7L)
        val ai = BattleAi(2)
        while (s.phase == RunPhase.FIGHTING) {
            if (s.mustSwitch) s.replace(ai.chooseReplacement(s.battle, Side.PLAYER)) else s.play(ai.chooseAction(s.battle, Side.PLAYER))
        }
        if (s.phase != RunPhase.FIGHT_WON) return // a lost fight ends the run; nothing to check
        val ended = s.battle.party(Side.PLAYER).map { it.hp }
        for (i in 0..1) {
            val max = BattleTuning.maxHp(5, s.levels[i])
            if (ended[i] > 0) {
                val grown = max - BattleTuning.maxHp(5, BattleLevels.START_LEVEL)
                val expected = minOf(max, ended[i] + grown + maxOf(1, (max * 10 + 99) / 100))
                assertEquals("card $i", expected, s.hp[i])
            } else {
                assertEquals(0, s.hp[i]) // fainted cards stay down
            }
        }
    }

    @Test fun aFaintedCardCannotBePickedToLeadAndAFightStartsWithTheSurvivors() {
        val party = listOf(card(1, 5), card(2, 5), card(3, 5))
        val s = BattleSession(party, listOf(card(10, 1)), 3L, startHp = listOf(0, 40, 60), leadPicking = true)
        assertEquals(listOf(1, 2), s.standing)
        assertEquals(RunPhase.PICKING_LEAD, s.phase)
        assertTrue(!s.chooseLead(0))
        assertTrue(s.chooseLead(1))
        assertEquals(listOf(0, 40, 60), s.battle.party(Side.PLAYER).map { it.hp })
        // With only one card left there is nothing to choose, and it is sent out.
        val last = BattleSession(party, listOf(card(10, 1)), 3L, startHp = listOf(0, 0, 25), leadPicking = true)
        assertEquals(RunPhase.FIGHTING, last.phase)
        assertEquals("Card 3", last.battle.active(Side.PLAYER).card.name)
    }

    @Test fun hpIsSavedWithTheRun() {
        val run = SavedRun(listOf(1L, 2L), listOf(card(10, 3)), null, 4L, RunPoint(1, 1, 0), listOf(31, 30), listOf(0, 77))
        val back = BattleSaveCodec.decode(BattleSaveCodec.encode(BattleSave(listOf(card(1, 4), card(2, 4)).map { SavedPartyCard(it) }, run))).run!!
        assertEquals(listOf(0, 77), back.hp)
    }
}
