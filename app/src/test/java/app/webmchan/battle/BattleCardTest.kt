package app.webmchan.battle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Checks of the Battle-Cards card engine. Plain JVM tests: no phone needed (./gradlew test). */
class BattleCardTest {

    private fun part(
        sec: Double = 20.0, w: Int = 1280, h: Int = 720, fps: Double = 30.0, bytes: Long = 5_000_000L,
        kbps: Int = 1500, limit: Long = 6_000_000L, ms: Long = 40_000L,
    ) = PartInput(sec, w, h, fps, bytes, kbps, limit, ms)

    private fun input(
        id: Long = 1L, container: String = "MP4", ext: String = "", audio: Boolean = false,
        srcBytes: Long = 60_000_000L, parts: List<PartInput> = listOf(part()),
    ) = CardInput(id, listOf(id), "clip-$id.webm", audio, container, ext, srcBytes, parts)

    // -- Hash -----------------------------------------------------------------------------------

    @Test fun hashGivesTheSameFixedValuesOnEveryPhone() {
        // Fixed values (also worked out independently in Python). If these ever change, every
        // card's moves and name would change with them, so a change here must be deliberate.
        assertEquals(1802279903890499594L, BattleHash.of("battle"))
        assertEquals(-780787492076525413L, BattleHash.of(""))
        assertEquals(8285998730943407452L, BattleHash.mix(1L, 2L))
        assertNotEquals(BattleHash.mix(1L, 2L), BattleHash.mix(2L, 1L))
        assertNotEquals(BattleHash.of("battle"), BattleHash.of("battlf"))
    }

    @Test fun randomSourceRepeatsForTheSameSeed() {
        val seeded = BattleRng(99L)
        assertEquals(4824385676517010403L, seeded.nextLong())
        assertEquals(583982616703494564L, seeded.nextLong())
        val a = BattleRng(99L)
        val b = BattleRng(99L)
        repeat(20) { assertEquals(a.nextInt(1000), b.nextInt(1000)) }
        val r = BattleRng(7L)
        repeat(500) { assertTrue(r.nextInt(5) in 0..4) }
    }

    // -- Bands ----------------------------------------------------------------------------------

    @Test fun bandsRiseWithTheValueAndStayInRange() {
        val sets = listOf(
            BattleBands.DURATION_SEC, BattleBands.COMPRESSION_RATIO, BattleBands.PIXELS, BattleBands.BITRATE_KBPS,
            BattleBands.SIZE_FILL, BattleBands.FRAME_RATE, BattleBands.REAL_TIME_FACTOR,
        )
        for (cuts in sets) {
            assertEquals(BAND_COUNT - 1, cuts.size)
            assertEquals(1, BattleBands.band(-5.0, cuts))
            assertEquals(BAND_COUNT, BattleBands.band(1e12, cuts))
            var last = 0
            var v = 0.0
            while (v < cuts.last() * 2) {
                val b = BattleBands.band(v, cuts)
                assertTrue(b >= last)
                last = b
                v += cuts.last() / 200.0
            }
        }
    }

    @Test fun sensibleValuesLandWhereExpected() {
        assertEquals(6, BattleBands.attack(1280.0 * 720))
        assertEquals(8, BattleBands.attack(1920.0 * 1080))
        assertEquals(4, BattleBands.attack(854.0 * 480))
        assertEquals(5, BattleBands.spDefence(30.0))
        assertEquals(8, BattleBands.spDefence(60.0))
        assertEquals(1, BattleBands.spDefence(2.0))
    }

    @Test fun unknownStatsSitInTheMiddle() {
        assertEquals(BattleBands.NEUTRAL_BAND, BattleBands.attack(0.0))
        assertEquals(BattleBands.NEUTRAL_BAND, BattleBands.spAttack(0.0))
        assertEquals(BattleBands.NEUTRAL_BAND, BattleBands.defence(null))
        assertEquals(BattleBands.NEUTRAL_BAND, BattleBands.spDefence(0.0))
        assertEquals(BattleBands.NEUTRAL_BAND, BattleBands.speed(null))
    }

    @Test fun hpUsesLengthAloneWhenTheSqueezeIsUnknown() {
        assertEquals(BattleBands.band(30.0, BattleBands.DURATION_SEC), BattleBands.hp(30.0, null))
        assertTrue(BattleBands.hp(30.0, 50.0) > BattleBands.hp(30.0, null))
    }

    // -- Types ----------------------------------------------------------------------------------

    @Test fun everyTypeBeatsTwoAndLosesToTwo() {
        val ring = CardType.fighting
        for (a in ring) {
            assertEquals(2, ring.count { TypeChart.beats(a, it) })
            assertEquals(2, ring.count { TypeChart.beats(it, a) })
            assertFalse(TypeChart.beats(a, a))
            for (d in ring.filter { it != a }) {
                // Exactly one side of any pairing is favoured.
                assertTrue(TypeChart.beats(a, d) != TypeChart.beats(d, a))
                assertEquals(1.0 / TypeChart.multiplier(d, a), TypeChart.multiplier(a, d), 1e-9)
            }
        }
    }

    @Test fun neutralAndSameTypeNeverChangeDamage() {
        for (t in CardType.values()) {
            assertEquals(1.0, TypeChart.multiplier(t, t), 0.0)
            assertEquals(1.0, TypeChart.multiplier(CardType.NONE, t), 0.0)
            assertEquals(1.0, TypeChart.multiplier(t, CardType.NONE), 0.0)
        }
    }

    @Test fun containersMapToTheirFamilies() {
        assertEquals(CardType.MAINSTREAM, BattleDerive.typeFor("MP4", "", false))
        assertEquals(CardType.MAINSTREAM, BattleDerive.typeFor("mov,mp4,m4a,3gp,3g2,mj2", "", false))
        assertEquals(CardType.MATROSKA, BattleDerive.typeFor("Matroska/WebM", "", false))
        assertEquals(CardType.LEGACY, BattleDerive.typeFor("AVI", "", false))
        assertEquals(CardType.LEGACY, BattleDerive.typeFor("MPEG-TS", "", false))
        assertEquals(CardType.LEGACY, BattleDerive.typeFor("FLV", "", false))
        assertEquals(CardType.WILD, BattleDerive.typeFor("GIF", "", false))
        assertEquals(CardType.AUDIO, BattleDerive.typeFor("MP3", "", true))
        assertEquals(CardType.AUDIO, BattleDerive.typeFor("FLAC", "", false))
    }

    @Test fun anExtensionIsOnlyUsedWhenThereIsNoContainer() {
        assertEquals(CardType.LEGACY, BattleDerive.typeFor("", "avi", false))
        assertEquals(CardType.MAINSTREAM, BattleDerive.typeFor("MP4", "avi", false))
    }

    @Test fun extensionsAreReadFromNamesOrLeftBlank() {
        assertEquals("mp4", BattleDerive.extensionOf("clip.MP4"))
        assertEquals("mp4", BattleDerive.extensionOf("primary:Download/clip.mp4"))
        assertEquals("", BattleDerive.extensionOf("1234"))
        assertEquals("", BattleDerive.extensionOf(null))
        assertEquals("", BattleDerive.extensionOf("a.b c"))
        assertEquals("", BattleDerive.extensionOf("name."))
        assertEquals("", BattleDerive.extensionOf("archive.toolongext"))
    }

    @Test fun unknownSourcesFallBackSensibly() {
        assertEquals(CardType.WILD, BattleDerive.typeFor("", "", false))
        assertEquals(CardType.AUDIO, BattleDerive.typeFor("", "", true))
        assertEquals(CardType.WILD, BattleDerive.typeFor("SOMETHINGNEW", "", false))
    }

    // -- Moves ----------------------------------------------------------------------------------

    @Test fun everyMoveIdIsUniqueAndEveryTypeHasOwnMoves() {
        assertEquals(BattleMoves.all.size, BattleMoves.all.map { it.id }.toSet().size)
        for (t in CardType.fighting) {
            val own = BattleMoves.signatureFor(t)
            assertTrue(own.size >= 3)
            assertTrue(own.all { it.kind.isDamage && it.type == t })
        }
        assertTrue(BattleMoves.all.any { it.kind == MoveKind.HEAL })
        assertTrue(BattleMoves.all.any { it.kind == MoveKind.STATUS })
        assertTrue(BattleMoves.all.any { it.kind == MoveKind.BUFF && it.stat == StatId.SP_DEFENCE })
        assertTrue(BattleMoves.all.any { it.kind == MoveKind.DEBUFF && it.stat == StatId.SP_DEFENCE })
    }

    @Test fun everyMoveCarriesTheFieldsItsKindNeeds() {
        for (m in BattleMoves.all) {
            when (m.kind) {
                MoveKind.BASIC, MoveKind.SPECIAL -> assertTrue(m.power > 0)
                MoveKind.STATUS -> assertTrue(m.status != null)
                MoveKind.HEAL -> assertTrue(m.healPct in 1..100)
                MoveKind.BUFF -> assertTrue(m.stat != null && m.stages > 0)
                MoveKind.DEBUFF -> assertTrue(m.stat != null && m.stages < 0)
            }
            assertTrue(m.accuracy in 1..100)
        }
    }

    @Test fun aCardGetsFourDistinctMovesAndTheFirstIsItsOwnTypeAndDamaging() {
        for (type in CardType.fighting) {
            for (seed in 1L..120L) {
                val moves = BattleMoves.pick(type, BattleHash.of("c$seed"), BattleHash.of("p$seed"))
                assertEquals(BattleMoves.SLOTS, moves.size)
                assertEquals(BattleMoves.SLOTS, moves.map { it.id }.toSet().size)
                assertTrue(moves[0].kind.isDamage && moves[0].type == type)
                assertTrue(moves.count { it.kind.isDamage } >= 2)
            }
        }
    }

    @Test fun theSameSeedsAlwaysGiveTheSameMoves() {
        val a = BattleMoves.pick(CardType.AUDIO, 1234L, 99L)
        val b = BattleMoves.pick(CardType.AUDIO, 1234L, 99L)
        assertEquals(a, b)
    }

    @Test fun cousinsShareTheirCoreMovesAndDifferInTheRest() {
        val core = BattleHash.of("core")
        val sets = (1L..200L).map { BattleMoves.pick(CardType.MATROSKA, core, BattleHash.of("p$it")) }
        for (s in sets) {
            for (slot in 0 until BattleMoves.CORE_SLOTS) assertEquals(sets[0][slot], s[slot])
        }
        assertTrue(sets.map { it.map { m -> m.id } }.toSet().size > 1)
    }

    @Test fun movesDescribeThemselvesInPlainWords() {
        fun say(id: String) = BattleMoves.byId(id)!!.summary()
        assertEquals("Attack 65 · Mainstream", say("keyframe_strike"))
        assertEquals("Sp. Attack 70 · Mainstream · 95%", say("profile_pulse"))
        assertEquals("Sp. Attack 55", say("bitrate_drain"))
        assertEquals("Inflicts Buffering · 80%", say("buffering"))
        assertEquals("Heals 50% of HP", say("remux"))
        assertEquals("Raises Sp. Defence of the user", say("deblock"))
        assertEquals("Lowers Sp. Defence of the target", say("banding"))
    }

    // -- Spread ---------------------------------------------------------------------------------

    @Test fun theSpreadCountsEveryCardOncePerStat() {
        val cards = (1L..40L).map {
            BattleDerive.derive(
                input(id = it, container = listOf("MP4", "AVI", "GIF", "MP3", "Matroska/WebM")[(it % 5).toInt()],
                    parts = listOf(part(sec = it.toDouble(), fps = if (it % 2 == 0L) 30.0 else 60.0))),
            )
        }
        val stats = BattleSpread.stats(cards)
        assertEquals(6, stats.size)
        for (h in stats) {
            assertEquals(BAND_COUNT, h.counts.size)
            assertEquals(cards.size, h.counts.sum())
        }
        assertEquals(cards.size, BattleSpread.types(cards).sumOf { it.second })
        assertEquals(5, BattleSpread.types(cards).size)
        assertEquals(8, BattleSpread.types(cards).first { it.first == CardType.MAINSTREAM }.second)
    }

    // -- Deriving a card ------------------------------------------------------------------------

    @Test fun theSameInputAlwaysMakesTheSameCard() {
        assertEquals(BattleDerive.derive(input()), BattleDerive.derive(input()))
    }

    @Test fun everyStatStaysInsideTheBands() {
        val extremes = listOf(
            part(sec = 0.5, w = 16, h = 16, fps = 1.0, bytes = 1_000L, kbps = 1, limit = 6_000_000L, ms = 900_000L),
            part(sec = 900.0, w = 1920, h = 1080, fps = 120.0, bytes = 99_000_000L, kbps = 90_000, limit = 6_000_000L, ms = 1L),
        )
        for (p in extremes) {
            val s = BattleDerive.derive(input(parts = listOf(p))).stats
            for (v in listOf(s.hp, s.attack, s.spAttack, s.defence, s.spDefence, s.speed)) assertTrue(v in 1..BAND_COUNT)
            assertTrue(s.power in 6..(6 * BAND_COUNT))
        }
    }

    @Test fun statsFollowTheConversionData() {
        val small = BattleDerive.derive(input(parts = listOf(part(w = 426, h = 240, kbps = 150, fps = 12.0, bytes = 1_000_000L)))).stats
        val big = BattleDerive.derive(input(parts = listOf(part(w = 1920, h = 1080, kbps = 3500, fps = 60.0, bytes = 5_900_000L)))).stats
        assertTrue(big.attack > small.attack)
        assertTrue(big.spAttack > small.spAttack)
        assertTrue(big.defence > small.defence)
        assertTrue(big.spDefence > small.spDefence)
        val slow = BattleDerive.derive(input(parts = listOf(part(sec = 20.0, ms = 400_000L)))).stats
        val quick = BattleDerive.derive(input(parts = listOf(part(sec = 20.0, ms = 8_000L)))).stats
        assertTrue(quick.speed > slow.speed)
    }

    @Test fun missingHistoryDataIsMarkedAndSitsInTheMiddle() {
        val old = BattleDerive.derive(
            input(container = "", ext = "", srcBytes = 0L, parts = listOf(part(ms = 0L, limit = 0L))),
        )
        assertEquals(
            setOf(Missing.SOURCE_SIZE, Missing.CONVERSION_TIME, Missing.SOURCE_CONTAINER, Missing.SIZE_LIMIT),
            old.missing,
        )
        assertEquals(BattleBands.NEUTRAL_BAND, old.stats.speed)
        assertEquals(BattleBands.NEUTRAL_BAND, old.stats.defence)
        assertTrue(BattleDerive.derive(input()).missing.isEmpty())
    }

    @Test fun aSplitVideoFoldsIntoOneCard() {
        val parts = listOf(
            part(sec = 80.0, bytes = 3_000_000L, ms = 100_000L),
            part(sec = 80.0, bytes = 5_900_000L, ms = 400_000L), // the tightest fit and the slowest
            part(sec = 80.0, bytes = 4_000_000L, ms = 150_000L),
        )
        val card = BattleDerive.derive(input(parts = parts, srcBytes = 200_000_000L))
        assertEquals(3, card.parts)
        // Tightest fill wins (5.9 of 6 MB), and the slowest part sets the speed.
        assertEquals(BattleBands.defence(5_900_000.0 / 6_000_000.0), card.stats.defence)
        assertEquals(BattleBands.speed(80.0 / 400.0), card.stats.speed)
        // The lengths add up: 240 s is the top length band, and squeeze uses all the output bytes.
        assertEquals(BattleBands.hp(240.0, 200_000_000.0 / 12_900_000.0), card.stats.hp)
    }

    @Test fun aSoundCardUsesTheWholeFilesBitrateNotThePicturesOwn() {
        val audio = BattleDerive.derive(
            input(audio = true, container = "MP3", parts = listOf(part(sec = 100.0, w = 640, h = 360, fps = 2.0, bytes = 1_200_000L, kbps = 3))),
        )
        assertEquals(CardType.AUDIO, audio.type)
        assertEquals(BattleBands.spAttack(1_200_000.0 * 8 / 1000 / 100.0), audio.stats.spAttack)
        assertEquals(1, audio.stats.spDefence) // a still picture: the lowest frame-rate band
    }

    @Test fun twoCopiesOfOneConversionAreCousins() {
        val a = BattleDerive.derive(input(id = 10L))
        val b = BattleDerive.derive(input(id = 11L))
        assertEquals(a.type, b.type)
        assertEquals(a.stats, b.stats)
        assertEquals(a.moves.take(BattleMoves.CORE_SLOTS), b.moves.take(BattleMoves.CORE_SLOTS))
        assertNotEquals(a.id, b.id)
    }

    @Test fun differentConversionsMakeDifferentCards() {
        val a = BattleDerive.derive(input(container = "MP4"))
        val b = BattleDerive.derive(input(container = "AVI"))
        assertNotEquals(a.type, b.type)
    }

    @Test fun namesAreOriginalWordsMadeOfAPrefixAndASuffix() {
        for (id in 1L..300L) {
            val card = BattleDerive.derive(input(id = id, container = listOf("MP4", "AVI", "GIF", "MP3", "Matroska/WebM")[(id % 5).toInt()]))
            assertTrue(card.name.length in 4..14)
            assertTrue(card.name[0].isUpperCase())
        }
    }

    @Test fun powerRatingSortsStrongerCardsLater() {
        val weak = BattleDerive.derive(input(parts = listOf(part(sec = 2.0, w = 320, h = 180, fps = 10.0, kbps = 100, bytes = 500_000L, ms = 200_000L)), srcBytes = 600_000L))
        val strong = BattleDerive.derive(input(parts = listOf(part(sec = 120.0, w = 1920, h = 1080, fps = 60.0, kbps = 3800, bytes = 5_950_000L, ms = 20_000L)), srcBytes = 900_000_000L))
        assertTrue(strong.stats.power > weak.stats.power)
    }
}
