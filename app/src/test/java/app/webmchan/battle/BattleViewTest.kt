package app.webmchan.battle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The screen's picture, moved on one event at a time, must always end where the engine does. */
class BattleViewTest {
    private fun randomCard(id: Long, seed: Long): BattleCard {
        val rng = BattleRng(BattleHash.mix(seed, id))
        val types = CardType.values().filter { it != CardType.NONE }
        val type = types[rng.nextInt(types.size)]
        val stats = BattleStats(1 + rng.nextInt(8), 1 + rng.nextInt(8), 1 + rng.nextInt(8), 1 + rng.nextInt(8), 1 + rng.nextInt(8), 1 + rng.nextInt(8))
        val moves = BattleMoves.pick(type, BattleHash.mix(seed, id + 1000), BattleHash.mix(seed, id + 2000))
        return BattleCard(id, listOf(id), "Card$id", "f$id.webm", type, stats, moves, false, 1, emptySet())
    }

    @Test fun replayingEveryTurnsEventsMatchesTheEngineEveryTime() {
        var turnsChecked = 0
        for (seed in 1L..150L) {
            val party = (1L..3L).map { randomCard(it, seed) }
            val foes = (11L..13L).map { randomCard(it, seed) }
            val b = Battle(party, foes, seed)
            val ai = BattleAi(seed)
            var view = BattleView.of(b)
            var guard = 0
            while (b.outcome == null && guard++ < 300) {
                val events = when {
                    b.needsSwitch(Side.PLAYER) -> b.forceSwitch(Side.PLAYER, ai.chooseReplacement(b, Side.PLAYER))
                    b.needsSwitch(Side.OPPONENT) -> b.forceSwitch(Side.OPPONENT, ai.chooseReplacement(b, Side.OPPONENT))
                    else -> b.turn(ai.chooseAction(b, Side.PLAYER), ai.chooseAction(b, Side.OPPONENT))
                }
                for (e in events) view = view.apply(e)
                assertEquals("seed $seed after ${b.turns} turns", BattleView.of(b), view)
                turnsChecked++
            }
        }
        assertTrue(turnsChecked > 1000)
    }

    @Test fun partyCardsThatShareANameAreStillTrackedSeparatelyOnceMadeUnique() {
        var turnsChecked = 0
        for (seed in 1L..60L) {
            // Three party cards all called "Same", as can happen when saved snapshots keep older names.
            val raw = (1L..3L).map { randomCard(it, seed).copy(name = "Same") }
            val party = BattleDerive.uniqueNames(raw)
            assertEquals(listOf("Same", "Same (2)", "Same (3)"), party.map { it.name })
            val b = Battle(party, (11L..13L).map { randomCard(it, seed) }, seed)
            val ai = BattleAi(seed)
            var view = BattleView.of(b)
            var guard = 0
            while (b.outcome == null && guard++ < 300) {
                val events = when {
                    b.needsSwitch(Side.PLAYER) -> b.forceSwitch(Side.PLAYER, ai.chooseReplacement(b, Side.PLAYER))
                    b.needsSwitch(Side.OPPONENT) -> b.forceSwitch(Side.OPPONENT, ai.chooseReplacement(b, Side.OPPONENT))
                    else -> b.turn(ai.chooseAction(b, Side.PLAYER), ai.chooseAction(b, Side.OPPONENT))
                }
                for (e in events) view = view.apply(e)
                assertEquals("seed $seed after ${b.turns} turns", BattleView.of(b), view)
                turnsChecked++
            }
        }
        assertTrue(turnsChecked > 100)
    }

    @Test fun aHitMovesTheBarOnlyWhenItsEventIsApplied() {
        val a = randomCard(1, 3)
        val t = randomCard(2, 3)
        val b = Battle(listOf(a), listOf(t), 3L)
        val before = BattleView.of(b)
        val events = b.turn(BattleAction.UseMove(0), BattleAction.UseMove(0))
        assertEquals(before.active(Side.OPPONENT).hp, before.opponent[0].maxHp)
        val hit = events.filterIsInstance<BattleEvent.Hit>().first { it.side == Side.OPPONENT }
        val after = before.apply(hit)
        assertEquals(hit.hpAfter, after.active(Side.OPPONENT).hp)
        assertTrue(after.active(Side.OPPONENT).hp < before.active(Side.OPPONENT).hp)
    }
}
