package app.webmchan.battle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Checks of a run (Stage 3's rules, without the screen). */
class BattleSessionTest {
    private val strike = BattleMoves.byId("keyframe_strike")!!
    private val plain = BattleMoves.byId("chroma_crunch")!!

    private fun card(id: Long, s: BattleStats, type: CardType = CardType.NONE) =
        BattleCard(id, listOf(id), "Card$id", "f$id.webm", type, s, listOf(strike, plain, plain, plain), false, 1, emptySet())

    private fun stats(v: Int) = BattleStats(v, v, v, v, v, v)

    /** Plays the player's side with the first move (and the first card after a faint) until the phase changes. */
    private fun fightOut(s: BattleSession) {
        var guard = 0
        while (s.phase == RunPhase.FIGHTING && guard++ < 500) {
            if (s.mustSwitch) s.replace(s.battle.legalSwitches(Side.PLAYER).first()) else s.play(BattleAction.UseMove(0))
        }
    }

    @Test fun strongPartyWinsEveryFightAndCompletesTheRun() {
        val s = BattleSession(listOf(card(1, stats(8)), card(2, stats(8))), listOf(card(10, stats(1)), card(11, stats(2)), card(12, stats(3))), 5L)
        var fights = 0
        while (s.phase == RunPhase.FIGHTING) {
            fightOut(s)
            fights++
            if (s.phase == RunPhase.FIGHT_WON) assertTrue(s.next())
        }
        assertEquals(RunPhase.RUN_COMPLETE, s.phase)
        assertEquals(3, s.wins)
        assertEquals(3, fights)
    }

    @Test fun weakPartyLosesTheRunAndNothingMoreHappens() {
        val s = BattleSession(listOf(card(1, stats(1))), listOf(card(10, stats(8))), 6L)
        fightOut(s)
        assertEquals(RunPhase.RUN_LOST, s.phase)
        assertEquals(0, s.wins)
        assertFalse(s.play(BattleAction.UseMove(0)))
        assertFalse(s.next())
    }

    @Test fun everyFightStartsAtFullHealth() {
        val s = BattleSession(listOf(card(1, stats(8)), card(2, stats(8))), listOf(card(10, stats(3)), card(11, stats(3))), 7L)
        fightOut(s)
        assertEquals(RunPhase.FIGHT_WON, s.phase)
        s.next()
        for (f in s.battle.party(Side.PLAYER)) assertEquals(f.maxHp, f.hp)
        assertEquals(1, s.fightIndex)
        assertEquals("Card11", s.opponent.name)
    }

    @Test fun aFaintedCardMustBeReplacedBeforePlayContinues() {
        val s = BattleSession(listOf(card(1, stats(1)), card(2, stats(8))), listOf(card(10, stats(8))), 8L)
        var guard = 0
        while (!s.mustSwitch && s.phase == RunPhase.FIGHTING && guard++ < 100) s.play(BattleAction.UseMove(0))
        assertTrue(s.mustSwitch)
        assertFalse(s.play(BattleAction.UseMove(0)))
        assertFalse(s.replace(0))
        assertTrue(s.replace(1))
        assertFalse(s.mustSwitch)
    }

    @Test fun theSameSeedGivesTheSameRun() {
        fun run(): List<BattleEvent> {
            val s = BattleSession(listOf(card(1, stats(4)), card(2, stats(5))), listOf(card(10, stats(4))), 99L)
            fightOut(s)
            return s.battle.events
        }
        assertEquals(run(), run())
    }

    @Test fun eachTurnReportsItsOwnEvents() {
        val s = BattleSession(listOf(card(1, stats(8))), listOf(card(10, stats(1))), 3L)
        assertTrue(s.lastEvents.any { it is BattleEvent.Sent })
        s.play(BattleAction.UseMove(0))
        assertTrue(s.lastEvents.any { it is BattleEvent.Used })
        assertTrue(s.lastEvents.none { it is BattleEvent.Sent })
    }
}
