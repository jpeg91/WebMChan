package app.webmchan.battle

import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/**
 * Balance checks: thousands of computer-vs-computer fights on a made-up History, to catch rules
 * that make fights endless, one-sided or decided by a single move. Run with ./gradlew test; the
 * report is printed (use --info to see it). Edit BattleTuning to change the balance.
 */
class BattleBalanceTest {

    /** A rough imitation of a real History: varied lengths, sizes, frame rates, encode speeds, a few sound files. */
    private fun roster(n: Int, seed: Long = 42L): List<BattleCard> {
        val rnd = Random(seed)
        val containers = listOf("MP4", "MP4", "MP4", "MP4", "Matroska/WebM", "Matroska/WebM", "AVI", "FLV", "GIF", "MOV")
        val rungs = listOf(240 to 426, 360 to 640, 480 to 854, 540 to 960, 720 to 1280, 1080 to 1920)
        return (1..n).map { id ->
            val audio = rnd.nextInt(12) == 0
            val sec = (3 + Math.exp(rnd.nextDouble() * Math.log(115.0))).coerceAtMost(120.0)
            val limit = if (rnd.nextInt(4) == 0) 4_000_000L else 6_000_000L
            val hard = rnd.nextInt(3) != 0
            val fill = if (hard) 0.88 + rnd.nextDouble() * 0.115 else 0.05 + rnd.nextDouble() * 0.5
            val bytes = (limit * fill).toLong()
            val rung = rungs[minOf(5, maxOf(0, (Math.log(bytes * 8.0 / sec / 1000.0 / 80.0) / Math.log(2.0)).toInt() - 1 + rnd.nextInt(2)))]
            val fps = if (audio) 1.0 else listOf(24.0, 25.0, 30.0, 30.0, 30.0, 60.0, 15.0, 10.0)[rnd.nextInt(8)]
            val srcBytes = (bytes * (2 + Math.exp(rnd.nextDouble() * Math.log(60.0)))).toLong()
            val rtf = 0.03 + rnd.nextDouble() * rnd.nextDouble() * 3.0
            val kbps = (bytes * 8.0 / sec / 1000.0).toInt()
            BattleDerive.derive(
                CardInput(
                    id.toLong(), listOf(id.toLong()), "c$id.webm", audio, if (audio) "MP3" else containers[rnd.nextInt(containers.size)], "", srcBytes,
                    listOf(PartInput(sec, rung.second, rung.first, fps, bytes, kbps, limit, (sec / rtf * 1000).toLong())),
                ),
            )
        }
    }

    private class Result(val outcome: Outcome, val turns: Int)

    private fun fight(a: BattleCard, b: BattleCard, seed: Long): Result {
        val battle = Battle(listOf(a), listOf(b), seed)
        BattleAi(seed * 31 + 1).playOut(battle)
        return Result(battle.outcome!!, battle.turns)
    }

    /** Random pairs of different cards, played both ways round so who is "player" doesn't matter. */
    private class Sample(val a: BattleCard, val b: BattleCard, val aWon: Boolean?, val turns: Int)

    private fun samples(cards: List<BattleCard>, count: Int, seed: Long = 5L): List<Sample> {
        val rnd = Random(seed)
        val out = ArrayList<Sample>()
        repeat(count) { i ->
            val a = cards[rnd.nextInt(cards.size)]
            var b = cards[rnd.nextInt(cards.size)]
            while (b.id == a.id) b = cards[rnd.nextInt(cards.size)]
            val r = fight(a, b, i + 1L)
            out.add(Sample(a, b, when (r.outcome) { Outcome.PLAYER_WON -> true; Outcome.OPPONENT_WON -> false; Outcome.DRAW -> null }, r.turns))
        }
        return out
    }

    @Test fun fightsAreAReasonableLengthAndNeverStall() {
        val s = samples(roster(300), 6000)
        val avg = s.map { it.turns }.average()
        val draws = s.count { it.aWon == null } / s.size.toDouble()
        val longest = s.maxOf { it.turns }
        val short = s.count { it.turns <= 2 } / s.size.toDouble()
        println("BALANCE length: avg=%.1f longest=%d draws=%.2f%% over2turns=%.1f%%".format(avg, longest, draws * 100, short * 100))
        assertTrue("average length $avg", avg in 4.0..14.0)
        assertTrue("draws $draws", draws < 0.01)
    }

    @Test fun strongerCardsWinMoreButNotAlways() {
        val s = samples(roster(300), 8000)
        fun rate(minGap: Int, maxGap: Int): Double {
            val picked = s.filter { Math.abs(it.a.stats.power - it.b.stats.power) in minGap..maxGap && it.aWon != null }
            val won = picked.count { (it.aWon == true) == (it.a.stats.power > it.b.stats.power) }
            return won / picked.size.toDouble()
        }
        val even = rate(1, 3)
        val mid = rate(4, 8)
        val big = rate(9, 40)
        println("BALANCE power: stronger wins gap1-3=%.0f%% gap4-8=%.0f%% gap9+=%.0f%%".format(even * 100, mid * 100, big * 100))
        assertTrue("grows with the gap: $even $mid $big", even < mid && mid < big)
        assertTrue("big gap should usually win: $big", big in 0.65..0.97)
        assertTrue("close gap near even: $even", even in 0.45..0.68)
    }

    @Test fun typeAdvantageHelpsButDoesNotDecide() {
        val s = samples(roster(300), 10000)
        var adv = 0
        var advWins = 0
        for (x in s) {
            if (x.aWon == null || Math.abs(x.a.stats.power - x.b.stats.power) > 4) continue
            val up = TypeChart.multiplier(x.a.type, x.b.type)
            val down = TypeChart.multiplier(x.b.type, x.a.type)
            if (up > 1.0 && down <= 1.0) { adv++; if (x.aWon) advWins++ }
            if (down > 1.0 && up <= 1.0) { adv++; if (!x.aWon) advWins++ }
        }
        val rate = advWins / adv.toDouble()
        println("BALANCE type: advantage wins %.0f%% of %d near-equal fights".format(rate * 100, adv))
        assertTrue("type advantage rate $rate", rate in 0.54..0.80)
    }

    @Test fun noSingleMoveOrTypeDecidesTheFight() {
        val cards = roster(300)
        val s = samples(cards, 24000, seed = 9L)
        val overall = 0.5
        val byMove = HashMap<String, IntArray>() // wins, fights
        for (x in s) {
            if (x.aWon == null) continue
            for ((card, won) in listOf(x.a to x.aWon, x.b to !x.aWon)) {
                // Compare like with like: only fights between cards within 4 power of each other.
                if (Math.abs(x.a.stats.power - x.b.stats.power) > 4) continue
                for (m in card.moves) {
                    val e = byMove.getOrPut(m.id) { IntArray(2) }
                    if (won) e[0]++
                    e[1]++
                }
            }
        }
        val rows = byMove.filter { it.value[1] >= 200 }.map { it.key to it.value[0] / it.value[1].toDouble() }.sortedBy { it.second }
        println("BALANCE moves (win rate among near-equal fights): " + rows.joinToString(", ") { "%s %.0f%%".format(it.first, it.second * 100) })
        // (Types are judged loosely: in a ring, whichever type beats the most common type in a History wins a little more.)
        // A type's own moves are judged with the type below; here only the shared moves.
        val sharedIds = BattleMoves.all.map { it.id }.filter { id -> BattleMoves.signatureFor(CardType.MAINSTREAM).none { it.id == id } && CardType.values().none { t -> BattleMoves.signatureFor(t).any { it.id == id } } }.toSet()
        val tooStrongOrWeak = rows.filter { it.first in sharedIds && Math.abs(it.second - overall) > 0.15 }

        val byType = CardType.values().filter { it != CardType.NONE }.map { t ->
            var w = 0
            var n = 0
            for (x in s) {
                if (x.aWon == null || Math.abs(x.a.stats.power - x.b.stats.power) > 4) continue
                if (x.a.type == t) { n++; if (x.aWon) w++ }
                if (x.b.type == t) { n++; if (!x.aWon) w++ }
            }
            t to w / n.toDouble()
        }
        println("BALANCE types: " + byType.joinToString(", ") { "%s %.0f%%".format(it.first.label, it.second * 100) })
        assertTrue("shared moves out of range: $tooStrongOrWeak", tooStrongOrWeak.isEmpty())
        for ((t, r) in byType) assertTrue("${t.label} win rate $r", Math.abs(r - overall) <= 0.15)
    }

    @Test fun aPartyOfSixGetsASensibleDistanceThroughAGauntlet() {
        val cards = roster(120)
        val rnd = Random(3)
        val lengths = ArrayList<Int>()
        for (run in 0 until 60) {
            val party = cards.shuffled(rnd).take(6)
            val opponents = BattleRun.opponentOrder(cards, party.map { it.id }.toSet())
            var beaten = 0
            for ((i, foe) in opponents.withIndex()) {
                val battle = Battle(party, listOf(foe), run * 1000L + i)
                BattleAi(run * 77L + i).playOut(battle)
                if (battle.outcome != Outcome.PLAYER_WON) break
                beaten++
            }
            lengths.add(beaten)
        }
        val avg = lengths.average()
        val sorted = lengths.sorted()
        println("BALANCE gauntlet: random party of 6 beat on average %.0f of %d (min %d, median %d, max %d, swept all %d/%d runs)".format(avg, cards.size - 6, sorted.first(), sorted[sorted.size / 2], sorted.last(), lengths.count { it >= cards.size - 6 }, lengths.size))
        // A random party should get somewhere, but should not usually sweep the whole history.
        assertTrue("average $avg", avg > 5.0)
    }
}
