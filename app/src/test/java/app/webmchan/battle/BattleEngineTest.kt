package app.webmchan.battle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Checks of the Battle-Cards rules (Stage 2). Plain JVM tests: no phone needed (./gradlew test). */
class BattleEngineTest {

    private fun move(id: String) = BattleMoves.byId(id) ?: error("No move $id")

    private val strike = move("keyframe_strike")   // Mainstream basic 65
    private val special = move("profile_pulse")    // Mainstream special 70, 95%
    private val plain = move("chroma_crunch")      // typeless basic 50
    private val wait = move("denoise")             // harmless buff

    private fun stats(hp: Int = 4, atk: Int = 4, spa: Int = 4, def: Int = 4, spd: Int = 4, spe: Int = 4) =
        BattleStats(hp, atk, spa, def, spd, spe)

    private fun card(
        id: Long = 1L, type: CardType = CardType.MAINSTREAM, s: BattleStats = stats(),
        moves: List<Move> = listOf(strike, plain, wait, wait),
    ) = BattleCard(id, listOf(id), "Card$id", "f$id.webm", type, s, moves, false, 1, emptySet())

    private fun fresh(a: BattleCard = card(1), b: BattleCard = card(2), seed: Long = 7L) = Battle(listOf(a), listOf(b), seed)

    private fun use(slot: Int) = BattleAction.UseMove(slot)

    // -- Numbers --------------------------------------------------------------------------------

    @Test fun bandsBecomeStrongerStatsInOrder() {
        for (b in 1 until BAND_COUNT) {
            assertTrue(BattleTuning.stat(b + 1) > BattleTuning.stat(b))
            assertTrue(BattleTuning.maxHp(b + 1) > BattleTuning.maxHp(b))
        }
        assertEquals(1.0, BattleTuning.stageMultiplier(0), 1e-9)
        assertEquals(1.5, BattleTuning.stageMultiplier(1), 1e-9)
        assertEquals(2.0, BattleTuning.stageMultiplier(2), 1e-9)
        assertEquals(0.5, BattleTuning.stageMultiplier(-2), 1e-9)
        assertEquals(4.0, BattleTuning.stageMultiplier(9), 1e-9)
    }

    // -- Damage ---------------------------------------------------------------------------------

    private fun dmg(user: BattleCard, target: BattleCard, m: Move, crit: Boolean = false, variance: Double = 1.0): Int {
        val b = fresh(user, target)
        val eff = TypeChart.multiplier(m.type, target.type)
        return BattleMath.damage(b.active(Side.PLAYER), b.active(Side.OPPONENT), m, eff, crit, variance)
    }

    @Test fun sameTypeMovesHitHarder() {
        val mainstream = card(1, CardType.MAINSTREAM)
        val wild = card(1, CardType.WILD)
        val target = card(2, CardType.NONE)
        assertTrue(dmg(mainstream, target, strike) > dmg(wild, target, strike))
    }

    @Test fun typeAdvantageRaisesAndResistanceLowersDamage() {
        val user = card(1, CardType.NONE)
        val beaten = CardType.values().first { it != CardType.NONE && TypeChart.multiplier(CardType.MAINSTREAM, it) > 1.0 }
        val resists = CardType.values().first { it != CardType.NONE && TypeChart.multiplier(CardType.MAINSTREAM, it) < 1.0 }
        val neutral = card(2, CardType.NONE)
        val base = dmg(user, neutral, strike)
        val up = dmg(user, card(2, beaten), strike)
        val down = dmg(user, card(2, resists), strike)
        val upWant = base * TypeChart.SUPER_EFFECTIVE
        val downWant = base * TypeChart.RESISTED
        assertTrue("super effective: $up vs $base", Math.abs(up - upWant) <= 2.0)
        assertTrue("resisted: $down vs $base", Math.abs(down - downWant) <= 2.0)
    }

    @Test fun criticalHitsAndVarianceScaleDamage() {
        val a = card(1)
        val t = card(2)
        assertTrue(dmg(a, t, strike, crit = true) > dmg(a, t, strike))
        assertTrue(dmg(a, t, strike, variance = 0.85) < dmg(a, t, strike, variance = 1.0))
    }

    @Test fun basicUsesDefenceAndSpecialUsesSpecialDefence() {
        val a = card(1, s = stats(atk = 4, spa = 4))
        val tankPhysical = card(2, s = stats(def = 8, spd = 1))
        assertTrue(dmg(a, tankPhysical, special) > dmg(a, tankPhysical, strike))
        val tankSpecial = card(2, s = stats(def = 1, spd = 8))
        assertTrue(dmg(a, tankSpecial, strike) > dmg(a, tankSpecial, special))
    }

    @Test fun damageIsNeverBelowOneAndPowerlessMovesDoNothing() {
        val weak = card(1, s = stats(atk = 1))
        val wall = card(2, s = stats(def = 8))
        assertTrue(dmg(weak, wall, plain, variance = 0.85) >= 1)
        assertEquals(0, dmg(weak, wall, wait))
    }

    @Test fun everyMoveInThePoolIsWellFormed() {
        for (m in BattleMoves.all) {
            assertTrue(m.id, m.accuracy in 1..100)
            when (m.kind) {
                MoveKind.BASIC, MoveKind.SPECIAL -> assertTrue(m.id, m.power > 0)
                MoveKind.STATUS -> assertNotNull(m.id, m.status)
                MoveKind.HEAL -> assertTrue(m.id, m.healPct in 1..100)
                MoveKind.BUFF -> assertTrue(m.id, m.stat != null && m.stages > 0)
                MoveKind.DEBUFF -> assertTrue(m.id, m.stat != null && m.stages < 0)
            }
        }
        assertEquals(BattleMoves.all.size, BattleMoves.all.map { it.id }.toSet().size)
    }

    // -- Turn order -----------------------------------------------------------------------------

    @Test fun fasterCardActsFirst() {
        val fast = card(1, s = stats(spe = 8))
        val slow = card(2, s = stats(spe = 1))
        val b = fresh(fast, slow)
        val used = b.turn(use(0), use(0)).filterIsInstance<BattleEvent.Used>()
        assertEquals(Side.PLAYER, used[0].side)
        val b2 = fresh(slow, fast)
        val used2 = b2.turn(use(0), use(0)).filterIsInstance<BattleEvent.Used>()
        assertEquals(Side.OPPONENT, used2[0].side)
    }

    @Test fun speedTiesAreRandomButFollowTheSeed() {
        val firsts = (1L..40L).map { seed ->
            fresh(seed = seed).turn(use(0), use(0)).filterIsInstance<BattleEvent.Used>()[0].side
        }
        assertTrue(firsts.contains(Side.PLAYER) && firsts.contains(Side.OPPONENT))
        val again = (1L..40L).map { seed ->
            fresh(seed = seed).turn(use(0), use(0)).filterIsInstance<BattleEvent.Used>()[0].side
        }
        assertEquals(firsts, again)
    }

    @Test fun aFaintedCardDoesNotGetItsMove() {
        val killer = card(1, s = stats(atk = 8, spe = 8))
        val victim = card(2, s = stats(hp = 1, def = 1, spe = 1))
        val b = fresh(killer, victim)
        b.turn(use(0), use(0))
        val used = b.events.filterIsInstance<BattleEvent.Used>()
        assertTrue(used.none { it.side == Side.OPPONENT })
        assertEquals(Outcome.PLAYER_WON, b.outcome)
    }

    // -- Hits, misses -------------------------------------------------------------------------

    @Test fun accuracyDecidesHitsAcrossManySeeds() {
        val shaky = move("overshoot") // 70%
        val a = card(1, moves = listOf(shaky, strike, plain, wait), s = stats(hp = 8))
        val t = card(2, s = stats(hp = 8, def = 8))
        var hits = 0
        var misses = 0
        for (seed in 1L..400L) {
            val ev = fresh(a, t, seed).turn(use(0), use(3))
            hits += ev.count { it is BattleEvent.Hit && it.side == Side.OPPONENT }
            misses += ev.count { it is BattleEvent.Missed && it.side == Side.PLAYER }
        }
        val rate = hits / 400.0
        assertTrue("hit rate $rate", rate in 0.60..0.80)
        assertEquals(400, hits + misses)
    }

    @Test fun criticalHitsHappenAboutOneInSixteen() {
        var crits = 0
        var total = 0
        for (seed in 1L..600L) {
            for (e in fresh(card(1, s = stats(hp = 8)), card(2, s = stats(hp = 8, def = 8)), seed).turn(use(0), use(3)))
                if (e is BattleEvent.Hit && e.side == Side.OPPONENT) { total++; if (e.critical) crits++ }
        }
        val rate = crits / total.toDouble()
        assertTrue("crit rate $rate", rate in 0.03..0.11)
    }

    @Test fun damageNeverTakesMoreThanTheTargetHas() {
        val b = fresh(card(1, s = stats(atk = 8, spe = 8)), card(2, s = stats(hp = 1, def = 1)))
        var guard = 0
        while (b.outcome == null && guard++ < 20) b.turn(use(0), use(0))
        for (e in b.events) if (e is BattleEvent.Hit) assertTrue(e.hpAfter >= 0)
        assertEquals(0, b.active(Side.OPPONENT).hp)
    }

    // -- Support moves --------------------------------------------------------------------------

    @Test fun healRestoresHpButNotPastMax() {
        val heal = move("remux")
        val a = card(1, moves = listOf(strike, heal, plain, wait), s = stats(spe = 1))
        val b = fresh(a, card(2, s = stats(spe = 8)))
        // Healing at full HP does nothing.
        val ev = b.turn(use(1), use(3))
        assertTrue(ev.any { it is BattleEvent.Failed && it.side == Side.PLAYER })
        // After being hit, a heal gives back about half and never overflows.
        var guard = 0
        while (b.active(Side.PLAYER).hp == b.active(Side.PLAYER).maxHp && guard++ < 20) b.turn(use(0), use(0))
        val before = b.active(Side.PLAYER).hp
        b.turn(use(1), use(3))
        val after = b.active(Side.PLAYER).hp
        assertTrue(after <= b.active(Side.PLAYER).maxHp)
        assertTrue(after > before || b.events.any { it is BattleEvent.Fainted })
    }

    @Test fun buffRaisesAndDebuffLowersByAStageAndStopsAtSix() {
        val up = move("two_pass_prep")
        val down = move("downscale")
        val a = card(1, moves = listOf(up, down, plain, wait))
        val b = fresh(a, card(2, moves = listOf(wait, wait, wait, wait)))
        repeat(8) { b.turn(use(0), use(3)) }
        assertEquals(BattleTuning.MAX_STAGE, b.active(Side.PLAYER).stages[StatId.ATTACK.ordinal])
        assertTrue(b.events.any { it is BattleEvent.StatMoved && it.change == 0 })
        repeat(8) { b.turn(use(1), use(3)) }
        assertTrue(b.active(Side.OPPONENT).stages[StatId.ATTACK.ordinal] in -BattleTuning.MAX_STAGE..0)
        assertTrue(b.active(Side.OPPONENT).stages[StatId.ATTACK.ordinal] < 0)
    }

    @Test fun stagesChangeDamage() {
        val up = move("two_pass_prep")
        val a = card(1, moves = listOf(up, strike, plain, wait))
        val b = fresh(a, card(2, s = stats(hp = 8, def = 8)))
        val before = BattleMath.damage(b.active(Side.PLAYER), b.active(Side.OPPONENT), strike, 1.0, false, 1.0)
        b.turn(use(0), use(1))
        b.turn(use(0), use(1))
        val after = BattleMath.damage(b.active(Side.PLAYER), b.active(Side.OPPONENT), strike, 1.0, false, 1.0)
        assertTrue(after > before)
    }

    // -- Statuses -------------------------------------------------------------------------------

    private fun afflicted(status: String, victimMoves: List<Move> = listOf(plain, plain, plain, plain), seedRange: LongRange = 1L..200L): Battle {
        val caster = card(1, moves = listOf(move(status), wait, wait, wait), s = stats(spe = 8, hp = 8))
        val victim = card(2, moves = victimMoves, s = stats(spe = 1, hp = 8, def = 8))
        for (seed in seedRange) {
            val b = fresh(caster, victim, seed)
            b.turn(use(0), use(0))
            if (b.active(Side.OPPONENT).status != null) return b
        }
        error("Never landed $status")
    }

    @Test fun bufferingCostsTurnsThenWearsOff() {
        val b = afflicted("buffering")
        assertEquals(StatusEffect.BUFFERING, b.active(Side.OPPONENT).status)
        var lost = 0
        var guard = 0
        while (b.active(Side.OPPONENT).status != null && guard++ < 10) {
            val ev = b.turn(use(3), use(0))
            lost += ev.count { it is BattleEvent.LostTurn }
        }
        assertTrue("lost $lost", lost >= 0)
        assertNull(b.active(Side.OPPONENT).status)
        assertTrue(b.events.any { it is BattleEvent.StatusEnded })
    }

    @Test fun bufferingMakesTheCardLoseItsMove() {
        val b = afflicted("buffering")
        val ev = b.turn(use(3), use(0))
        if (b.events.any { it is BattleEvent.StatusEnded }) return
        assertTrue(ev.any { it is BattleEvent.LostTurn && it.side == Side.OPPONENT })
        assertTrue(ev.none { it is BattleEvent.Used && it.side == Side.OPPONENT })
    }

    @Test fun droppedFramesHalvesSpeed() {
        val b = afflicted("frame_drop")
        val f = b.active(Side.OPPONENT)
        assertEquals(BattleTuning.stat(1) * BattleTuning.DROPPED_FRAMES_SPEED, f.effective(StatId.SPEED), 1e-9)
    }

    @Test fun droppedFramesFailsAboutAQuarterOfMoves() {
        val caster = card(1, moves = listOf(move("frame_drop"), wait, wait, wait), s = stats(spe = 8, hp = 8))
        val victim = card(2, moves = listOf(wait, wait, wait, wait), s = stats(spe = 1, hp = 8))
        var lost = 0
        var tried = 0
        for (seed in 1L..300L) {
            val b = fresh(caster, victim, seed)
            b.turn(use(0), use(0))
            if (b.active(Side.OPPONENT).status != StatusEffect.DROPPED_FRAMES) continue
            val ev = b.turn(use(3), use(0))
            if (b.active(Side.OPPONENT).status == null && ev.any { it is BattleEvent.StatusEnded }) { /* may have ended after acting */ }
            tried++
            if (ev.any { it is BattleEvent.LostTurn }) lost++
        }
        val rate = lost / tried.toDouble()
        assertTrue("rate $rate over $tried", rate in 0.12..0.40)
    }

    @Test fun artifactingHurtsAnEighthEachTurn() {
        val b = afflicted("artifact_spray")
        val f = b.active(Side.OPPONENT)
        val hurt = b.events.filterIsInstance<BattleEvent.StatusHurt>().first()
        assertEquals(f.maxHp / BattleTuning.ARTIFACTING_ONE_IN, hurt.damage)
    }

    @Test fun thermalThrottleCutsAttackAndSpecialAttack() {
        val b = afflicted("thermal_throttle")
        val f = b.active(Side.OPPONENT)
        assertEquals(BattleTuning.stat(4) * BattleTuning.THROTTLE_ATTACK, f.effective(StatId.ATTACK), 1e-9)
        assertEquals(BattleTuning.stat(4) * BattleTuning.THROTTLE_ATTACK, f.effective(StatId.SP_ATTACK), 1e-9)
        assertEquals(BattleTuning.stat(8).toDouble(), f.effective(StatId.DEFENCE), 1e-9)
    }

    @Test fun aStatusCannotBeStackedOnAnother() {
        val caster = card(1, moves = listOf(move("thermal_throttle"), move("artifact_spray"), wait, wait), s = stats(spe = 8, hp = 8))
        val victim = card(2, moves = listOf(wait, wait, wait, wait), s = stats(spe = 1, hp = 8))
        for (seed in 1L..200L) {
            val b = fresh(caster, victim, seed)
            b.turn(use(0), use(0))
            val first = b.active(Side.OPPONENT).status ?: continue
            val ev = b.turn(use(1), use(0))
            if (b.active(Side.OPPONENT).status == first || ev.any { it is BattleEvent.StatusEnded }) {
                assertTrue(ev.none { it is BattleEvent.Afflicted })
                return
            }
        }
        error("No scenario found")
    }

    @Test fun statusesWearOffByThemselves() {
        val b = afflicted("thermal_throttle", victimMoves = listOf(wait, wait, wait, wait))
        var guard = 0
        while (b.active(Side.OPPONENT).status != null && guard++ < 10) b.turn(use(3), use(0))
        assertNull(b.active(Side.OPPONENT).status)
    }

    // -- Switching and fainting ---------------------------------------------------------------

    private fun party(vararg c: BattleCard) = c.toList()

    @Test fun switchingResolvesBeforeMovesAndResetsStages() {
        val up = move("two_pass_prep")
        val lead = card(1, moves = listOf(up, strike, plain, wait))
        val bench = card(3)
        val b = Battle(party(lead, bench), party(card(2, s = stats(spe = 8))), 5L)
        b.turn(use(0), use(0))
        assertEquals(1, b.active(Side.PLAYER).stages[StatId.ATTACK.ordinal])
        val ev = b.turn(BattleAction.Switch(1), use(0))
        assertTrue(ev[0] is BattleEvent.Sent)
        assertEquals(1, b.activeIndex(Side.PLAYER))
        // The one who came in takes the hit, not the one who left.
        val hit = ev.filterIsInstance<BattleEvent.Hit>().first { it.side == Side.PLAYER }
        assertEquals("Card3", hit.name)
        assertEquals(0, b.party(Side.PLAYER)[0].stages[StatId.ATTACK.ordinal])
    }

    @Test fun aFaintedCardMustBeReplacedBeforeTheNextTurn() {
        val b = Battle(party(card(1, s = stats(hp = 1, def = 1, spe = 1)), card(3)), party(card(2, s = stats(atk = 8, spe = 8))), 9L)
        var guard = 0
        while (!b.needsSwitch(Side.PLAYER) && b.outcome == null && guard++ < 20) b.turn(use(0), use(0))
        assertTrue(b.needsSwitch(Side.PLAYER))
        try { b.turn(use(0), use(0)); error("should have refused") } catch (e: IllegalStateException) { /* expected */ }
        assertEquals(listOf(1), b.legalSwitches(Side.PLAYER))
        b.forceSwitch(Side.PLAYER, 1)
        assertFalse(b.needsSwitch(Side.PLAYER))
        assertNull(b.outcome)
    }

    @Test fun cannotSwitchToAFaintedOrActiveCard() {
        val b = Battle(party(card(1), card(3)), party(card(2)), 3L)
        try { b.turn(BattleAction.Switch(0), use(0)); error("active") } catch (e: IllegalArgumentException) { }
        try { b.turn(BattleAction.Switch(5), use(0)); error("range") } catch (e: IllegalArgumentException) { }
    }

    @Test fun theFightEndsWhenOneSideIsOut() {
        val b = fresh(card(1, s = stats(atk = 8, spe = 8)), card(2, s = stats(hp = 1, def = 1)))
        var guard = 0
        while (b.outcome == null && guard++ < 30) b.turn(use(0), use(0))
        assertEquals(Outcome.PLAYER_WON, b.outcome)
        assertTrue(b.events.last() is BattleEvent.Ended)
        try { b.turn(use(0), use(0)); error("over") } catch (e: IllegalStateException) { }
    }

    @Test fun artifactingOnBothCardsAtOnceIsADraw() {
        val spray = move("artifact_spray")
        val frail = card(1, moves = listOf(spray, spray, spray, spray), s = stats(hp = 1))
        val frail2 = card(2, moves = listOf(spray, spray, spray, spray), s = stats(hp = 1))
        var drew = false
        for (seed in 1L..300L) {
            val b = fresh(frail, frail2, seed)
            var guard = 0
            while (b.outcome == null && guard++ < 60) b.turn(use(0), use(0))
            assertNotNull(b.outcome)
            if (b.outcome == Outcome.DRAW) drew = true
        }
        assertTrue(drew)
    }

    // -- Determinism ----------------------------------------------------------------------------

    @Test fun theSameSeedPlaysTheSameFight() {
        val a = card(1, s = stats(atk = 5, spa = 6, spe = 5))
        val t = card(2, CardType.WILD, s = stats(def = 5, hp = 6))
        val first = BattleAi(11L).playOut(Battle(listOf(a), listOf(t), 77L).also { }).let { it }
        val x = Battle(listOf(a), listOf(t), 77L).also { BattleAi(11L).playOut(it) }
        val y = Battle(listOf(a), listOf(t), 77L).also { BattleAi(11L).playOut(it) }
        assertEquals(x.events, y.events)
        assertEquals(first, x.outcome)
    }

    @Test fun differentSeedsPlayDifferently() {
        val a = card(1)
        val t = card(2)
        val logs = (1L..10L).map { s -> Battle(listOf(a), listOf(t), s).also { BattleAi(s).playOut(it) }.events }.toSet()
        assertTrue(logs.size > 1)
    }

    @Test fun everyEventHasText() {
        val b = Battle(listOf(card(1)), listOf(card(2)), 1L)
        BattleAi(1L).playOut(b)
        for (e in b.events) assertTrue(e.text().isNotBlank())
    }

    // -- AI -------------------------------------------------------------------------------------

    @Test fun aiAlwaysPicksALegalMoveAndAlwaysFinishes() {
        for (seed in 1L..200L) {
            val a = card(1, s = stats(atk = (seed % 8).toInt() + 1))
            val b = Battle(party(a, card(3)), party(card(2), card(4)), seed)
            val outcome = BattleAi(seed).playOut(b)
            assertNotNull(outcome)
            assertTrue(b.turns < BattleTuning.MAX_TURNS)
        }
    }

    @Test fun aiPrefersASuperEffectiveHit() {
        val beaten = CardType.values().first { it != CardType.NONE && TypeChart.multiplier(CardType.MAINSTREAM, it) > 1.0 }
        val me = card(1, moves = listOf(strike, plain, wait, wait))
        val b = fresh(me, card(2, beaten, s = stats(hp = 8, def = 8)))
        var superHit = 0
        var neutralHit = 0
        for (seed in 1L..400L) {
            when ((BattleAi(seed).chooseAction(b, Side.PLAYER) as BattleAction.UseMove).slot) {
                0 -> superHit++
                1 -> neutralHit++
            }
        }
        assertTrue("super $superHit vs neutral $neutralHit", superHit > neutralHit * 2)
    }

    @Test fun aiDoesNotHealAtFullHealth() {
        val me = card(1, moves = listOf(move("remux"), strike, plain, wait))
        val b = fresh(me, card(2))
        for (seed in 1L..200L) assertTrue((BattleAi(seed).chooseAction(b, Side.PLAYER) as BattleAction.UseMove).slot != 0)
    }

    @Test fun aiReplacementIsTheStrongestCardLeft() {
        val b = Battle(party(card(1, s = stats(hp = 1, def = 1, spe = 1)), card(3, s = stats(1, 1, 1, 1, 1, 1)), card(4, s = stats(8, 8, 8, 8, 8, 8))),
            party(card(2, s = stats(atk = 8, spe = 8))), 4L)
        var guard = 0
        while (!b.needsSwitch(Side.PLAYER) && guard++ < 20) b.turn(use(0), use(0))
        assertEquals(2, BattleAi(1L).chooseReplacement(b, Side.PLAYER))
    }

    // -- Runs -----------------------------------------------------------------------------------

    @Test fun opponentsAreEveryNonPartyCardWeakestFirst() {
        val roster = (1L..12L).map { id -> card(id, s = stats(hp = (id % 8).toInt() + 1, atk = ((id * 3) % 8).toInt() + 1)) }
        val order = BattleRun.opponentOrder(roster, setOf(1L, 2L))
        assertEquals(10, order.size)
        assertTrue(order.none { it.id == 1L || it.id == 2L })
        assertEquals(order.map { it.stats.power }, order.map { it.stats.power }.sorted())
        assertEquals(order, BattleRun.opponentOrder(roster.reversed(), setOf(1L, 2L)))
    }

    @Test fun fullHealBetweenFightsMeansEveryFightStartsFresh() {
        val a = card(1)
        val first = Battle(listOf(a), listOf(card(2)), 1L).also { BattleAi(1L).playOut(it) }
        val second = Battle(listOf(a), listOf(card(3)), 2L)
        assertEquals(second.active(Side.PLAYER).maxHp, second.active(Side.PLAYER).hp)
        assertNotNull(first.outcome)
    }
}
