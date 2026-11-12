package app.webmchan.battle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BattleFxTest {
    private val ids = (BattleMoves.all + BattleMoves.dudMove).map { it.id }

    @Test fun everyMoveHasItsOwnAnimation() {
        val missing = ids.filter { it !in BattleFx.ids }
        assertTrue("moves without an animation: $missing", missing.isEmpty())
    }

    private fun contexts(): List<FxCtx> {
        val gh = 120
        return listOf(
            FxCtx(Pt(32.0, 100.0), Pt(32.0, 20.0), BattleFx.GRID_W, gh), // you -> opponent
            FxCtx(Pt(32.0, 20.0), Pt(32.0, 100.0), BattleFx.GRID_W, gh), // opponent -> you
            FxCtx(Pt(32.0, 100.0), Pt(32.0, 100.0), BattleFx.GRID_W, gh), // on yourself
        )
    }

    @Test fun framesStayOnTheGridAndAreNotEmpty() {
        for (id in ids) {
            val def = BattleFx.def(id)
            for (ctx in contexts()) {
                var most = 0
                for (i in 0..40) {
                    val px = BattleFx.render(def, ctx, i / 40.0)
                    most = maxOf(most, px.size)
                    assertTrue("$id: too many squares (${px.size})", px.size <= 2500)
                    for (p in px) assertTrue("$id: off the grid $p", p.x >= 0 && p.y >= 0 && p.x + p.w <= ctx.gw && p.y + p.h <= ctx.gh && p.w > 0 && p.h > 0)
                }
                assertTrue("$id draws almost nothing ($most)", most >= 5)
            }
        }
    }

    @Test fun lungeStartsAndEndsAtRest() {
        val ctx = contexts()[0]
        for (id in ids) {
            val def = BattleFx.def(id)
            val a = BattleFx.shift(def, ctx, 0.0)
            val b = BattleFx.shift(def, ctx, 1.0)
            assertTrue("$id start", Math.abs(a.userDx) + Math.abs(a.userDy) < 1e-9)
            assertTrue("$id end", Math.abs(b.userDx) + Math.abs(b.userDy) < 1e-9)
        }
    }

    @Test fun typePalettesLookDifferent() {
        val mains = CardType.values().filter { it != CardType.NONE }.map { FxPalette.forType(it).color(Slot.MAIN) }
        assertEquals(mains.size, mains.toSet().size)
    }
}
