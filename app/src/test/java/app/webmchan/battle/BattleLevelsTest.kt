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
        assertEquals(listOf(31, 30, 31), after)
    }

    @Test fun cardsBehindCatchUpAndCardsFarAheadStop() {
        assertTrue(BattleLevels.gain(30, 50) > BattleLevels.gain(30, 31))
        assertEquals(0, BattleLevels.gain(60, 50))
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
}
