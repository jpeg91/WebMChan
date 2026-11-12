// #battle
package app.webmchan.battle

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * Move animations, in the chunky style of the handheld originals.
 *
 * Everything is drawn as whole squares ("pixels") on a coarse grid that is GRID_W cells wide, played at
 * a stepped frame rate, using only five colours: four from the attacking card's type palette, plus
 * white. Nothing here knows about Compose or Android, so every animation can be checked by ordinary
 * tests and drawn to pictures. BattleFxLayer.kt plays them on screen.
 */

/** Which of a palette's colours a pixel uses. */
enum class Slot { DARK, MAIN, ACCENT, LIGHT, WHITE }

/** One square of an animation frame, in grid cells. */
data class Px(val x: Int, val y: Int, val w: Int, val h: Int, val slot: Slot)

/**
 * The colours an animation is drawn in (0xAARRGGBB). Each card type has its own, so a move looks
 * like it belongs to whoever uses it. To restyle a type, change its row in [forType].
 */
class FxPalette(val dark: Long, val main: Long, val accent: Long, val light: Long) {
    fun color(slot: Slot): Long = when (slot) {
        Slot.DARK -> dark
        Slot.MAIN -> main
        Slot.ACCENT -> accent
        Slot.LIGHT -> light
        Slot.WHITE -> 0xFFFFFFFF
    }

    companion object {
        fun forType(type: CardType): FxPalette = when (type) {
            CardType.MAINSTREAM -> FxPalette(0xFF7A1020, 0xFFE0314B, 0xFFFF8A5C, 0xFFFFD6C8)
            CardType.MATROSKA -> FxPalette(0xFF7A4A00, 0xFFF2A900, 0xFFFFE066, 0xFFFFF6CC)
            CardType.LEGACY -> FxPalette(0xFF39465A, 0xFF8FA3BF, 0xFFC4D2E6, 0xFFF0F5FC)
            CardType.AUDIO -> FxPalette(0xFF3D2A86, 0xFF9C86E8, 0xFFE0A8FF, 0xFFF4EAFF)
            CardType.WILD -> FxPalette(0xFF0B5A4A, 0xFF2DD4A7, 0xFFB8FF5C, 0xFFE8FFF5)
            CardType.NONE -> FxPalette(0xFF3A3A3A, 0xFF9A9A9A, 0xFFD0D0D0, 0xFFF4F4F4)
        }
    }
}

/** A point in grid cells. */
data class Pt(val x: Double, val y: Double)

/**
 * Where an animation happens: [from] is the user's card, [to] the target's (the same point for a move
 * on oneself), on a grid [gw] by [gh] cells.
 */
class FxCtx(val from: Pt, val to: Pt, val gw: Int, val gh: Int) {
    private val dx = to.x - from.x
    private val dy = to.y - from.y
    val dist: Double = sqrt(dx * dx + dy * dy)

    /** Unit vector from the user towards the target (pointing up if they are the same place). */
    val dirX: Double = if (dist < 1e-6) 0.0 else dx / dist
    val dirY: Double = if (dist < 1e-6) -1.0 else dy / dist
    val perpX: Double = -dirY
    val perpY: Double = dirX
    fun at(p: Double): Pt = Pt(from.x + dx * p, from.y + dy * p)
}

/** How the two cards' panels move while an animation plays (in cells and 0..1 opacity). */
data class FxShift(
    val userDx: Double = 0.0, val userDy: Double = 0.0,
    val targetDx: Double = 0.0, val targetDy: Double = 0.0,
    val userAlpha: Double = 1.0, val targetAlpha: Double = 1.0,
)

/** One animation: how long it runs, and what it draws at time `t` (0..1). */
class FxDef(
    val millis: Int,
    /** True if the user's card lunges at the target (physical moves). */
    val lunge: Boolean = false,
    /** True if it is aimed at the user's own card (heals, buffs). */
    val onSelf: Boolean = false,
    val draw: (FxCanvas, FxCtx, Double) -> Unit,
)

/** Collects the squares of one frame, dropping any outside the grid. */
class FxCanvas(val gw: Int, val gh: Int) {
    val px = ArrayList<Px>()

    fun rect(x: Int, y: Int, w: Int, h: Int, s: Slot) {
        val x0 = max(x, 0)
        val y0 = max(y, 0)
        val x1 = min(x + w, gw)
        val y1 = min(y + h, gh)
        if (x1 > x0 && y1 > y0) px.add(Px(x0, y0, x1 - x0, y1 - y0, s))
    }

    fun dot(x: Double, y: Double, s: Slot, size: Int = 1) = rect(x.toInt() - size / 2, y.toInt() - size / 2, size, size, s)

    fun disc(cx: Double, cy: Double, r: Double, s: Slot) {
        if (r < 0.5) { dot(cx, cy, s); return }
        val y0 = Math.floor(cy - r).toInt()
        val y1 = Math.ceil(cy + r).toInt()
        for (y in y0..y1) {
            val dy = y + 0.5 - cy
            val hw = r * r - dy * dy
            if (hw < 0) continue
            val w = sqrt(hw)
            val xa = Math.round(cx - w).toInt()
            val xb = Math.round(cx + w).toInt()
            rect(xa, y, max(1, xb - xa), 1, s)
        }
    }

    fun ring(cx: Double, cy: Double, r: Double, s: Slot, thick: Double = 1.0) {
        val ro = r
        val ri = max(0.0, r - thick)
        val y0 = Math.floor(cy - ro).toInt()
        val y1 = Math.ceil(cy + ro).toInt()
        for (y in y0..y1) {
            val dy = y + 0.5 - cy
            val o = ro * ro - dy * dy
            if (o < 0) continue
            val wo = sqrt(o)
            val i = ri * ri - dy * dy
            if (i <= 0) {
                rect(Math.round(cx - wo).toInt(), y, max(1, Math.round(2 * wo).toInt()), 1, s)
            } else {
                val wi = sqrt(i)
                val a = Math.round(cx - wo).toInt()
                val b = Math.round(cx - wi).toInt()
                val c = Math.round(cx + wi).toInt()
                val d = Math.round(cx + wo).toInt()
                rect(a, y, max(1, b - a), 1, s)
                rect(c, y, max(1, d - c), 1, s)
            }
        }
    }

    /** A line of squares, [thick] cells across. */
    fun line(x0: Double, y0: Double, x1: Double, y1: Double, s: Slot, thick: Int = 1) {
        val steps = max(abs(x1 - x0), abs(y1 - y0)).toInt().coerceAtLeast(1)
        for (i in 0..steps) {
            val p = i.toDouble() / steps
            rect(Math.round(x0 + (x1 - x0) * p).toInt() - thick / 2, Math.round(y0 + (y1 - y0) * p).toInt() - thick / 2, thick, thick, s)
        }
    }

    fun outline(x: Int, y: Int, w: Int, h: Int, s: Slot, thick: Int = 1) {
        rect(x, y, w, thick, s); rect(x, y + h - thick, w, thick, s)
        rect(x, y + thick, thick, h - 2 * thick, s); rect(x + w - thick, y + thick, thick, h - 2 * thick, s)
    }

    fun diamond(cx: Double, cy: Double, r: Int, s: Slot) {
        for (dy in -r..r) {
            val hw = r - abs(dy)
            rect(Math.round(cx).toInt() - hw, Math.round(cy).toInt() + dy, 2 * hw + 1, 1, s)
        }
    }

    // --- Sprites made of the above -------------------------------------------------------------

    /** A shaded ball: dark rim, main body, accent core, a bright glint. */
    fun orb(cx: Double, cy: Double, r: Double) {
        disc(cx, cy, r + 1, Slot.DARK)
        disc(cx, cy, r, Slot.MAIN)
        disc(cx + r * 0.1, cy + r * 0.1, r * 0.62, Slot.ACCENT)
        if (r >= 2) dot(cx - r * 0.35, cy - r * 0.4, Slot.WHITE, max(1, (r / 3).toInt()))
    }

    /** A four-point twinkle. */
    fun sparkle(cx: Double, cy: Double, s: Int, slot: Slot = Slot.WHITE) {
        val x = Math.round(cx).toInt()
        val y = Math.round(cy).toInt()
        rect(x - s, y, 2 * s + 1, 1, slot)
        rect(x, y - s, 1, 2 * s + 1, slot)
    }

    /** A burst of rays with a white heart; [p] is how far along (0..1) it is. */
    fun burst(cx: Double, cy: Double, p: Double, r: Double, rays: Int = 8) {
        if (p <= 0.0 || p >= 1.0) return
        val len = r * 1.25 * easeOut(p)
        val fade = if (p > 0.6) 1 else if (p > 0.35) 2 else 3
        for (i in 0 until rays) {
            val a = 2 * PI * i / rays + 0.3
            val inner = len * 0.35
            line(cx + cos(a) * inner, cy + sin(a) * inner, cx + cos(a) * len, cy + sin(a) * len, if (i % 2 == 0) Slot.ACCENT else Slot.MAIN, fade)
        }
        if (p < 0.7) { disc(cx, cy, r * 0.35 * (1 - p), Slot.WHITE) }
        if (p < 0.5) ring(cx, cy, len * 0.8, Slot.LIGHT, 1.0)
    }

    /** An arrow, filled, [size] cells tall (the head) with a short stem. */
    fun arrow(cx: Double, cy: Double, size: Int, up: Boolean, slot: Slot) {
        val x = Math.round(cx).toInt()
        val y = Math.round(cy).toInt()
        for (i in 0..size) {
            val hw = if (up) i else size - i
            rect(x - hw, y - size + i, 2 * hw + 1, 1, slot)
        }
        rect(x - 1, if (up) y + 1 else y - size - size / 2, 3, size / 2 + 1, slot)
    }

    fun plus(cx: Double, cy: Double, s: Int, slot: Slot) {
        val x = Math.round(cx).toInt()
        val y = Math.round(cy).toInt()
        rect(x - s, y - 1, 2 * s + 1, 3, slot)
        rect(x - 1, y - s, 3, 2 * s + 1, slot)
    }
}

// -- Small maths --------------------------------------------------------------------------------

internal fun clamp01(v: Double) = if (v < 0.0) 0.0 else if (v > 1.0) 1.0 else v
internal fun seg(t: Double, a: Double, b: Double) = clamp01((t - a) / (b - a))
internal fun lerp(a: Double, b: Double, p: Double) = a + (b - a) * p
internal fun easeOut(p: Double) = 1 - (1 - p) * (1 - p)
internal fun easeIn(p: Double) = p * p
internal fun noise(i: Int): Double {
    var h = i * -1640531535 + 0x7F4A7C15
    h = (h xor (h ushr 15)) * 0x2C1B3C6D
    h = (h xor (h ushr 12)) * 0x297A2D39
    h = h xor (h ushr 15)
    return (h and 0xFFFF) / 65535.0
}

/** True only while [t] is inside [a], [b]. */
private fun inside(t: Double, a: Double, b: Double) = t >= a && t < b

/**
 * The animations. [render] gives the squares of one frame; frames are stepped ([STEPS] a run), which
 * is what gives the choppy handheld look.
 */
object BattleFx {
    /** The grid is this many cells wide; its height follows the screen. */
    const val GRID_W = 64

    /** How many distinct frames an animation has. */
    const val STEPS = 20

    private fun step(t: Double) = Math.floor(clamp01(t) * STEPS).coerceAtMost(STEPS - 1.0) / STEPS + 0.5 / STEPS

    fun def(moveId: String, kind: MoveKind = MoveKind.BASIC): FxDef = table[moveId] ?: byKind(kind)

    fun render(def: FxDef, ctx: FxCtx, t: Double): List<Px> {
        val c = FxCanvas(ctx.gw, ctx.gh)
        def.draw(c, ctx, step(t))
        return c.px
    }

    /** How the cards' panels move at time [t] (a lunge for physical moves). */
    fun shift(def: FxDef, ctx: FxCtx, t: Double): FxShift {
        if (!def.lunge) return FxShift()
        val out = seg(t, 0.05, 0.25)
        val back = seg(t, 0.3, 0.5)
        val k = easeOut(out) * (1 - back) * 4.0
        return FxShift(userDx = ctx.dirX * k, userDy = ctx.dirY * k)
    }

    // The two effects every hit and faint gets, whatever the move.

    /** A hit lands: the target blinks and shakes. */
    val hit = FxDef(520) { _, _, _ -> }
    fun hitShift(t: Double): FxShift {
        val blink = if (((t * 10).toInt()) % 2 == 0 && t < 0.8) 0.25 else 1.0
        val shake = if (t < 0.7) sin(t * 40) * 1.2 * (1 - t) else 0.0
        return FxShift(targetDx = shake, targetAlpha = blink)
    }

    /** A card faints: it sinks and fades. */
    val faint = FxDef(700) { _, _, _ -> }
    fun faintShift(t: Double): FxShift = FxShift(targetDy = 4.0 * easeIn(seg(t, 0.1, 1.0)), targetAlpha = 1.0 - 0.5 * seg(t, 0.2, 1.0))

    // -- The move table ----------------------------------------------------------------------

    private fun fx(millis: Int, lunge: Boolean = false, onSelf: Boolean = false, draw: (FxCanvas, FxCtx, Double) -> Unit) = FxDef(millis, lunge, onSelf, draw)

    private fun byKind(kind: MoveKind): FxDef = when (kind) {
        MoveKind.BASIC, MoveKind.SPECIAL -> generic
        MoveKind.STATUS -> table.getValue("buffering")
        MoveKind.HEAL, MoveKind.HEAL_BENCH, MoveKind.HEAL_ALLY -> table.getValue("remux")
        MoveKind.REVIVE -> table.getValue("restore_point")
        MoveKind.BUFF -> table.getValue("two_pass_prep")
        MoveKind.DEBUFF -> table.getValue("downscale")
    }

    private val generic = fx(800, lunge = true) { c, x, t ->
        val p = seg(t, 0.1, 0.55)
        if (inside(t, 0.1, 0.55)) { val q = x.at(easeIn(p)); c.orb(q.x, q.y, 3.0) }
        c.burst(x.to.x, x.to.y, seg(t, 0.55, 1.0), 9.0)
    }

    private val table: Map<String, FxDef> = buildTable()

    /** Every move that has its own animation (the tests check this against the move pool). */
    val ids: Set<String> get() = table.keys

    private fun buildTable(): Map<String, FxDef> {
        val m = LinkedHashMap<String, FxDef>()

        // ---- Mainstream: a key slams down, a bar sweeps, pulses roll out ------------------------
        m["keyframe_strike"] = fx(1000, lunge = true) { c, x, t ->
            val p = seg(t, 0.15, 0.55)
            if (t < 0.55 && t >= 0.15) {
                val y = lerp(x.to.y - 18, x.to.y - 2, easeIn(p))
                val cx = x.to.x
                c.diamond(cx, y, 7, Slot.DARK); c.diamond(cx, y, 6, Slot.MAIN); c.diamond(cx - 1, y - 1, 3, Slot.ACCENT)
                c.rect(cx.toInt() - 1, y.toInt() - 1, 3, 3, Slot.DARK)
                c.rect(cx.toInt() - 1, y.toInt() + 6, 3, 8, Slot.DARK); c.rect(cx.toInt(), y.toInt() + 6, 1, 8, Slot.LIGHT)
            }
            c.burst(x.to.x, x.to.y, seg(t, 0.55, 1.0), 13.0)
        }
        m["baseline_bash"] = fx(950, lunge = true) { c, x, t ->
            val p = seg(t, 0.15, 0.6)
            if (inside(t, 0.15, 0.75)) {
                val reach = 30 * easeOut(p)
                val y = x.to.y
                c.rect((x.to.x - 30).toInt(), y.toInt() - 2, reach.toInt(), 5, Slot.DARK)
                c.rect((x.to.x - 30).toInt(), y.toInt() - 1, reach.toInt(), 3, Slot.MAIN)
                c.rect((x.to.x - 30).toInt(), y.toInt(), reach.toInt(), 1, Slot.LIGHT)
                c.rect((x.to.x - 30 + reach).toInt() - 2, y.toInt() - 3, 3, 7, Slot.WHITE)
            }
            c.burst(x.to.x, x.to.y, seg(t, 0.6, 1.0), 11.0, 6)
        }
        m["profile_pulse"] = fx(1100) { c, x, t ->
            for (i in 0 until 3) {
                val p = seg(t, 0.05 + i * 0.12, 0.55 + i * 0.12)
                if (p > 0 && p < 1) c.ring(x.from.x, x.from.y, 3 + 14 * p, if (i == 1) Slot.ACCENT else Slot.MAIN, 2.0)
            }
            val q = seg(t, 0.4, 0.8)
            if (q > 0 && q < 1) {
                val o = x.at(q); c.orb(o.x, o.y, 4.0)
            }
            for (i in 0 until 3) {
                val p = seg(t, 0.75 + i * 0.05, 1.0)
                if (p > 0 && p < 1) c.ring(x.to.x, x.to.y, 2 + 11 * p, if (i % 2 == 0) Slot.LIGHT else Slot.ACCENT, 2.0)
            }
        }

        // ---- Matroska: blocks collapse, slices, parallel tracks ---------------------------------
        m["cluster_crush"] = fx(1000, lunge = true) { c, x, t ->
            val p = easeIn(seg(t, 0.1, 0.6))
            if (t < 0.6) {
                for (i in 0 until 4) {
                    val sx = if (i % 2 == 0) -22.0 else 22.0
                    val sy = if (i < 2) -22.0 else 22.0
                    val bx = x.to.x + sx * (1 - p)
                    val by = x.to.y + sy * (1 - p)
                    c.rect(bx.toInt() - 4, by.toInt() - 4, 9, 9, Slot.DARK)
                    c.rect(bx.toInt() - 3, by.toInt() - 3, 7, 7, if (i % 2 == 0) Slot.MAIN else Slot.ACCENT)
                    c.rect(bx.toInt() - 3, by.toInt() - 3, 7, 2, Slot.LIGHT)
                }
            }
            c.burst(x.to.x, x.to.y, seg(t, 0.6, 1.0), 12.0, 12)
        }
        m["segment_slice"] = fx(950, lunge = true) { c, x, t ->
            for (i in 0 until 3) {
                val p = seg(t, 0.1 + i * 0.18, 0.4 + i * 0.18)
                if (p <= 0 || (t > 0.75 + i * 0.05)) continue
                val off = (i - 1) * 7.0
                val len = 22 * easeOut(p)
                val sx = x.to.x - 11 + off
                val sy = x.to.y - 14
                c.line(sx, sy, sx + len * 0.6, sy + len, Slot.DARK, 5)
                c.line(sx, sy, sx + len * 0.6, sy + len, if (i == 1) Slot.ACCENT else Slot.MAIN, 3)
                c.line(sx, sy, sx + len * 0.6, sy + len, Slot.WHITE, 1)
                if (len > 6) for (k in 1..3) c.dot(sx + len * 0.6 + k * 2, sy + len + k, Slot.LIGHT)
            }
            c.burst(x.to.x, x.to.y, seg(t, 0.65, 1.0), 10.0, 6)
        }
        m["track_torrent"] = fx(1100) { c, x, t ->
            val p = seg(t, 0.1, 0.8)
            for (lane in -2..2) {
                for (k in 0 until 7) {
                    val q = clamp01(p * 1.3 - k * 0.07 - abs(lane) * 0.03)
                    if (q <= 0 || q >= 1) continue
                    val pos = x.at(easeIn(q))
                    val ox = pos.x + x.perpX * lane * 4.0
                    val oy = pos.y + x.perpY * lane * 4.0
                    c.rect(ox.toInt() - 1, oy.toInt() - 1, 3, 3, if (lane % 2 == 0) Slot.MAIN else Slot.ACCENT)
                    c.dot(ox, oy, Slot.LIGHT)
                }
            }
            c.burst(x.to.x, x.to.y, seg(t, 0.7, 1.0), 11.0, 10)
        }

        // ---- Legacy: stripes, collisions, scanlines ---------------------------------------------
        m["interlace_lash"] = fx(950, lunge = true) { c, x, t ->
            val p = seg(t, 0.1, 0.7)
            if (p > 0 && t < 0.85) {
                val w = (26 * easeOut(p)).toInt()
                for (row in -9..9) {
                    val offset = if (row % 2 == 0) 0 else 6
                    val y = x.to.y.toInt() + row
                    val slot = if (row % 2 == 0) Slot.MAIN else Slot.ACCENT
                    c.rect(x.to.x.toInt() - 13 + offset, y, w.coerceAtMost(26 - offset), 1, slot)
                }
                c.rect(x.to.x.toInt() - 13, x.to.y.toInt() - 10, w, 1, Slot.WHITE)
                c.rect(x.to.x.toInt() - 13, x.to.y.toInt() + 10, w, 1, Slot.WHITE)
            }
            c.burst(x.to.x, x.to.y, seg(t, 0.7, 1.0), 9.0, 6)
        }
        m["codec_clash"] = fx(1100) { c, x, t ->
            val p = easeIn(seg(t, 0.1, 0.55))
            if (t < 0.55) {
                val dx = (1 - p) * 24
                for (side in listOf(-1, 1)) {
                    val bx = x.to.x + side * dx
                    c.rect(bx.toInt() - 5, x.to.y.toInt() - 5, 11, 11, Slot.DARK)
                    c.rect(bx.toInt() - 4, x.to.y.toInt() - 4, 9, 9, if (side < 0) Slot.MAIN else Slot.ACCENT)
                    c.rect(bx.toInt() - 2, x.to.y.toInt() - 2, 5, 5, Slot.LIGHT)
                }
            }
            val q = seg(t, 0.55, 1.0)
            c.burst(x.to.x, x.to.y, q, 14.0, 12)
            if (q > 0 && q < 0.8) for (i in 0 until 10) {
                val a = noise(i) * 2 * PI
                val r = 4 + 14 * easeOut(q) * (0.5 + noise(i + 40))
                c.rect((x.to.x + cos(a) * r).toInt(), (x.to.y + sin(a) * r).toInt(), 2, 2, if (i % 2 == 0) Slot.WHITE else Slot.MAIN)
            }
        }
        m["scanline_scrape"] = fx(1000, lunge = true) { c, x, t ->
            val p = seg(t, 0.1, 0.75)
            if (p > 0 && t < 0.9) {
                val y = lerp(x.to.y - 13, x.to.y + 13, p)
                for (k in 0..5) c.rect(x.to.x.toInt() - 15, (y - k * 2).toInt(), 31, 1, if (k == 0) Slot.WHITE else if (k < 3) Slot.ACCENT else Slot.DARK)
                for (k in 0..((p * 13).toInt())) c.rect(x.to.x.toInt() - 15, (x.to.y - 13 + k * 2).toInt(), 31, 1, Slot.MAIN)
            }
            c.burst(x.to.x, x.to.y, seg(t, 0.75, 1.0), 9.0, 6)
        }

        // ---- Audio: a ribbon, rings, notes ------------------------------------------------------
        m["waveform_lash"] = fx(1100) { c, x, t ->
            val p = seg(t, 0.05, 0.7)
            if (p > 0) {
                val n = (x.dist * easeOut(p)).toInt()
                for (i in 0..n) {
                    val q = i / x.dist
                    val base = x.at(q)
                    val amp = 5.0 * sin(PI * q) * (if (t > 0.7) 1 - seg(t, 0.7, 1.0) else 1.0)
                    val w = sin(q * 18 - t * 30) * amp
                    val px = base.x + x.perpX * w
                    val py = base.y + x.perpY * w
                    c.rect(px.toInt() - 1, py.toInt() - 1, 3, 3, Slot.DARK)
                    c.rect(px.toInt(), py.toInt(), 2, 2, if (i % 6 < 3) Slot.MAIN else Slot.ACCENT)
                }
            }
            c.burst(x.to.x, x.to.y, seg(t, 0.65, 1.0), 10.0, 8)
        }
        m["bass_drop"] = fx(1300) { c, x, t ->
            val p = easeIn(seg(t, 0.05, 0.45))
            if (t < 0.45) c.orb(x.to.x, lerp(x.to.y - 30, x.to.y, p), 5.0 + 2 * p)
            for (i in 0 until 4) {
                val q = seg(t, 0.45 + i * 0.1, 1.0)
                if (q > 0 && q < 1) c.ring(x.to.x, x.to.y, 2 + 22 * easeOut(q), listOf(Slot.WHITE, Slot.ACCENT, Slot.MAIN, Slot.DARK)[i], 2.0)
            }
        }
        m["sample_slap"] = fx(1000, lunge = true) { c, x, t ->
            for (i in 0 until 3) {
                val p = seg(t, 0.1 + i * 0.12, 0.6 + i * 0.1)
                if (p <= 0 || p >= 1) continue
                val pos = x.at(easeIn(p))
                val wob = sin(p * 9 + i) * 3
                val nx = pos.x + x.perpX * wob
                val ny = pos.y + x.perpY * wob
                // A note: head, stem, flag.
                c.disc(nx, ny + 3, 2.5, Slot.DARK); c.disc(nx, ny + 3, 1.6, if (i % 2 == 0) Slot.MAIN else Slot.ACCENT)
                c.rect(nx.toInt() + 2, ny.toInt() - 7, 2, 10, Slot.DARK); c.rect(nx.toInt() + 2, ny.toInt() - 7, 1, 10, Slot.LIGHT)
                c.rect(nx.toInt() + 3, ny.toInt() - 7, 4, 2, Slot.MAIN)
            }
            c.burst(x.to.x, x.to.y, seg(t, 0.65, 1.0), 10.0, 8)
        }

        // ---- Wild: confetti, loops, film frames -------------------------------------------------
        m["palette_panic"] = fx(1150) { c, x, t ->
            val slots = listOf(Slot.MAIN, Slot.ACCENT, Slot.LIGHT, Slot.WHITE, Slot.DARK)
            val grow = easeOut(seg(t, 0.05, 0.5))
            val fade = 1 - seg(t, 0.7, 1.0)
            for (i in 0 until 46) {
                if (noise(i + 99) > fade) continue
                val a = noise(i) * 2 * PI + t * 6
                val r = (3 + 17 * noise(i + 7)) * grow
                c.rect((x.to.x + cos(a) * r).toInt(), (x.to.y + sin(a) * r * 0.8).toInt(), 2 + (i % 2), 2 + (i % 2), slots[i % 5])
            }
            if (t < 0.4) { val q = x.at(easeIn(seg(t, 0.0, 0.4))); c.orb(q.x, q.y, 3.0) }
        }
        m["loop_lash"] = fx(1100, lunge = true) { c, x, t ->
            val p = seg(t, 0.1, 0.9)
            val turns = 2.2
            val n = (60 * p).toInt()
            for (i in 0..n) {
                val q = i / 60.0
                val a = q * turns * 2 * PI
                val r = lerp(19.0, 5.0, q)
                val px = x.to.x + cos(a) * r
                val py = x.to.y + sin(a) * r * 0.85
                c.rect(px.toInt() - 1, py.toInt() - 1, 3, 3, Slot.DARK)
                c.rect(px.toInt(), py.toInt(), 2, 2, if (i % 8 < 4) Slot.MAIN else Slot.ACCENT)
            }
            if (p > 0.95) c.burst(x.to.x, x.to.y, seg(t, 0.9, 1.0), 10.0)
        }
        m["frame_flicker"] = fx(1000, lunge = true) { c, x, t ->
            for (i in 0 until 4) {
                val on = inside(t, 0.08 + i * 0.18, 0.08 + i * 0.18 + 0.22)
                if (!on) continue
                val s = 26 - i * 4
                c.outline(x.to.x.toInt() - s / 2, x.to.y.toInt() - s / 2, s, s, if (i % 2 == 0) Slot.WHITE else Slot.MAIN, 2)
                c.outline(x.to.x.toInt() - s / 2 + 2, x.to.y.toInt() - s / 2 + 2, s - 4, s - 4, Slot.ACCENT, 1)
                for (k in 0 until s step 4) { c.rect(x.to.x.toInt() - s / 2 + k, x.to.y.toInt() - s / 2 - 3, 2, 2, Slot.DARK); c.rect(x.to.x.toInt() - s / 2 + k, x.to.y.toInt() + s / 2 + 1, 2, 2, Slot.DARK) }
            }
            c.burst(x.to.x, x.to.y, seg(t, 0.8, 1.0), 9.0, 6)
        }

        // ---- Typeless damage --------------------------------------------------------------------
        m["bitrate_drain"] = fx(1150) { c, x, t ->
            for (i in 0 until 14) {
                val p = clamp01(seg(t, 0.05, 0.85) * 1.4 - i * 0.05)
                if (p <= 0 || p >= 1) continue
                val pos = Pt(lerp(x.to.x, x.from.x, easeIn(p)), lerp(x.to.y, x.from.y, easeIn(p)))
                val w = sin(p * 10 + i) * 5 * (1 - p)
                c.rect((pos.x + x.perpX * w).toInt(), (pos.y + x.perpY * w).toInt(), 2, 2, if (i % 2 == 0) Slot.MAIN else Slot.LIGHT)
            }
            c.ring(x.to.x, x.to.y, 14 * (1 - seg(t, 0.05, 0.6)), Slot.ACCENT, 1.0)
            val g = seg(t, 0.7, 1.0)
            if (g > 0 && g < 1) c.burst(x.from.x, x.from.y, g, 8.0, 6)
        }
        m["chroma_crunch"] = fx(900, lunge = true) { c, x, t ->
            val p = easeIn(seg(t, 0.1, 0.5))
            if (t < 0.55) {
                val slots = listOf(Slot.MAIN, Slot.ACCENT, Slot.LIGHT)
                for (i in 0 until 3) {
                    val y = x.to.y - 12 + i * 9
                    val off = (1 - p) * 34 * (if (i % 2 == 0) -1 else 1)
                    c.rect((x.to.x + off).toInt() - 12, y.toInt(), 25, 6, Slot.DARK)
                    c.rect((x.to.x + off).toInt() - 11, y.toInt() + 1, 23, 4, slots[i])
                }
            }
            c.burst(x.to.x, x.to.y, seg(t, 0.5, 1.0), 12.0, 10)
        }
        m["two_pass_takedown"] = fx(1300, lunge = true) { c, x, t ->
            for (pass in 0 until 2) {
                val a = 0.08 + pass * 0.42
                val p = seg(t, a, a + 0.25)
                if (p > 0 && p < 1) {
                    val y = lerp(x.to.y - 12, x.to.y, easeIn(p))
                    c.rect(x.to.x.toInt() - 11, y.toInt() - 4, 23, 9, Slot.DARK)
                    c.rect(x.to.x.toInt() - 10, y.toInt() - 3, 21, 7, if (pass == 0) Slot.MAIN else Slot.ACCENT)
                    c.rect(x.to.x.toInt() - 10, y.toInt() - 3, 21, 2, Slot.LIGHT)
                }
                c.burst(x.to.x, x.to.y, seg(t, a + 0.25, a + 0.42), if (pass == 0) 10.0 else 15.0, if (pass == 0) 6 else 12)
            }
        }
        m["overshoot"] = fx(1300) { c, x, t ->
            val charge = seg(t, 0.0, 0.3)
            if (t < 0.3) { c.orb(x.from.x, x.from.y, 2 + 6 * charge); for (i in 0 until 6) { val a = i * PI / 3 + t * 9; c.dot(x.from.x + cos(a) * (12 - 8 * charge), x.from.y + sin(a) * (12 - 8 * charge), Slot.LIGHT) } }
            val p = seg(t, 0.3, 0.65)
            if (p > 0 && t < 0.8) {
                val far = Pt(x.to.x + x.dirX * 14 * easeOut(p), x.to.y + x.dirY * 14 * easeOut(p))
                val head = Pt(lerp(x.from.x, far.x, easeOut(p)), lerp(x.from.y, far.y, easeOut(p)))
                c.line(x.from.x, x.from.y, head.x, head.y, Slot.DARK, 9)
                c.line(x.from.x, x.from.y, head.x, head.y, Slot.MAIN, 7)
                c.line(x.from.x, x.from.y, head.x, head.y, Slot.ACCENT, 4)
                c.line(x.from.x, x.from.y, head.x, head.y, Slot.WHITE, 2)
            }
            c.burst(x.to.x, x.to.y, seg(t, 0.62, 1.0), 20.0, 12)
        }

        // ---- Status moves -----------------------------------------------------------------------
        m["buffering"] = fx(1300) { c, x, t ->
            val p = easeOut(seg(t, 0.05, 0.25))
            if (p > 0) {
                val n = 10
                val head = (t * 3.0 * n).toInt() % n
                val cy = x.to.y - 2
                for (i in 0 until n) {
                    val a = 2 * PI * i / n - PI / 2
                    val age = ((head - i + n) % n)
                    val slot = when { age == 0 -> Slot.WHITE; age < 3 -> Slot.ACCENT; age < 6 -> Slot.MAIN; else -> Slot.DARK }
                    val size = if (age < 3) 4 else 3
                    c.rect((x.to.x + cos(a) * 11 * p).toInt() - size / 2, (cy + sin(a) * 11 * p).toInt() - size / 2, size, size, slot)
                }
                for (i in 0 until 3) if (((t * 9).toInt() % 4) > i) c.rect(x.to.x.toInt() - 5 + i * 4, (cy + 15).toInt(), 3, 3, Slot.LIGHT)
            }
            if (t < 0.25) { val q = x.at(easeIn(seg(t, 0.0, 0.25))); c.orb(q.x, q.y, 2.0) }
        }
        m["frame_drop"] = fx(1200) { c, x, t ->
            val ghosts = 4
            for (i in 0 until ghosts) {
                val a = 0.12 + i * 0.16
                if (!inside(t, a, a + 0.1)) continue
                val ox = x.to.x + (i - 1.5) * 8
                c.outline(ox.toInt() - 6, x.to.y.toInt() - 9, 13, 19, if (i % 2 == 0) Slot.MAIN else Slot.ACCENT, 2)
                c.rect(ox.toInt() - 4, x.to.y.toInt() - 7, 9, 15, Slot.DARK)
            }
            for (k in 0 until 5) if (inside(t, 0.7 + k * 0.05, 0.75 + k * 0.05)) c.rect((x.to.x + (noise(k) - 0.5) * 24).toInt(), (x.to.y + (noise(k + 9) - 0.5) * 24).toInt(), 6, 2, Slot.WHITE)
            if (t > 0.5) c.rect(x.to.x.toInt() - 1, x.to.y.toInt() - 16, 3, 7, Slot.WHITE).also { c.rect(x.to.x.toInt() - 1, x.to.y.toInt() - 7, 3, 3, Slot.WHITE) }
        }
        m["artifact_spray"] = fx(1200) { c, x, t ->
            val p = seg(t, 0.05, 0.6)
            for (i in 0 until 30) {
                val q = clamp01(p * 1.3 - noise(i) * 0.3)
                if (q <= 0) continue
                val spread = (noise(i + 33) - 0.5) * 18 * q
                val pos = x.at(easeOut(q))
                val s = 1 + (i % 3)
                c.rect((pos.x + x.perpX * spread).toInt(), (pos.y + x.perpY * spread).toInt(), s, s, listOf(Slot.MAIN, Slot.ACCENT, Slot.LIGHT, Slot.DARK)[i % 4])
            }
            val tail = seg(t, 0.55, 1.0)
            if (tail > 0) for (i in 0 until 16) {
                if (noise(i + 200 + (t * 12).toInt() * 3) < tail * 0.9 + 0.1) c.rect((x.to.x + (noise(i + 5) - 0.5) * 24).toInt(), (x.to.y + (noise(i + 15) - 0.5) * 28).toInt(), 2, 2, if (i % 2 == 0) Slot.MAIN else Slot.WHITE)
            }
        }
        m["thermal_throttle"] = fx(1300) { c, x, t ->
            val p = seg(t, 0.05, 0.4)
            for (i in 0 until 9) {
                val bx = x.to.x - 12 + i * 3
                val h = (6 + 12 * noise(i + 4)) * p
                for (k in 0 until h.toInt()) {
                    val w = sin(k * 0.8 + t * 20 + i) * 1.5
                    val rise = (t * 16 + i * 3).toInt() % 6
                    val slot = if (k > h - 4) Slot.WHITE else if (k > h * 0.5) Slot.ACCENT else Slot.MAIN
                    if ((k + rise) % 6 != 0) c.rect((bx + w).toInt(), (x.to.y + 8 - k).toInt(), 2, 1, slot)
                }
            }
            // A thermometer.
            if (t > 0.4) { c.rect(x.to.x.toInt() + 14, x.to.y.toInt() - 12, 4, 16, Slot.DARK); c.rect(x.to.x.toInt() + 15, (x.to.y - 11 + 14 * (1 - seg(t, 0.4, 0.9))).toInt(), 2, (14 * seg(t, 0.4, 0.9)).toInt() + 1, Slot.MAIN); c.disc(x.to.x + 16, x.to.y + 6, 3.0, Slot.MAIN) }
        }

        // ---- Heals (on yourself) ----------------------------------------------------------------
        m["remux"] = fx(1200, onSelf = true) { c, x, t ->
            for (i in 0 until 6) {
                val p = clamp01(seg(t, 0.05, 0.8) * 1.3 - i * 0.09)
                if (p <= 0 || p >= 1) continue
                c.plus(x.to.x + (i - 2.5) * 5 + sin(p * 6 + i) * 2, x.to.y + 10 - 26 * p, if (i % 2 == 0) 3 else 2, if (i % 3 == 0) Slot.WHITE else if (i % 3 == 1) Slot.ACCENT else Slot.LIGHT)
            }
            val r = seg(t, 0.1, 0.7)
            if (r > 0 && r < 1) c.ring(x.to.x, x.to.y, 4 + 16 * r, Slot.MAIN, 1.0)
            for (i in 0 until 5) if (noise(i + (t * 10).toInt()) > 0.5 && t > 0.3) c.sparkle(x.to.x + (noise(i + 3) - 0.5) * 28, x.to.y + (noise(i + 8) - 0.5) * 20, 1)
        }
        m["cache_flush"] = fx(1200, onSelf = true) { c, x, t ->
            val p = seg(t, 0.05, 0.6)
            for (i in 0 until 24) {
                val a = noise(i) * 2 * PI + p * 9
                val r = (16 - 16 * p) + 2
                val q = (i / 24.0)
                if (q > 0.95 - p * 0.2) continue
                c.rect((x.to.x + cos(a + q * 6) * r * (1 - q * 0.3)).toInt(), (x.to.y + sin(a + q * 6) * r * 0.7 * (1 - q * 0.3)).toInt(), 2, 2, if (i % 2 == 0) Slot.MAIN else Slot.ACCENT)
            }
            val q = seg(t, 0.55, 1.0)
            if (q > 0) { c.plus(x.to.x, x.to.y - 6 * q, 5, Slot.WHITE); c.ring(x.to.x, x.to.y, 4 + 12 * q, Slot.LIGHT, 1.0) }
        }
        m["group_backup"] = fx(1300, onSelf = true) { c, x, t ->
            for (i in 0 until 3) {
                val q = seg(t, 0.05 + i * 0.12, 0.7 + i * 0.1)
                if (q > 0 && q < 1) c.ring(x.to.x, x.to.y, 3 + 30 * q, if (i == 1) Slot.ACCENT else Slot.MAIN, 2.0)
            }
            for (i in 0 until 8) {
                val a = i * PI / 4
                val q = seg(t, 0.15, 0.75)
                if (q > 0 && q < 1) c.plus(x.to.x + cos(a) * 24 * q, x.to.y + sin(a) * 18 * q, 2, if (i % 2 == 0) Slot.WHITE else Slot.LIGHT)
            }
        }
        m["patch_release"] = fx(1300, onSelf = true) { c, x, t ->
            val p = easeIn(seg(t, 0.05, 0.5))
            if (t < 0.5) {
                val y = lerp(x.to.y - 28, x.to.y - 2, p)
                c.rect(x.to.x.toInt() - 7, y.toInt() - 6, 15, 13, Slot.DARK)
                c.rect(x.to.x.toInt() - 6, y.toInt() - 5, 13, 11, Slot.MAIN)
                c.rect(x.to.x.toInt() - 6, y.toInt() - 5, 13, 3, Slot.LIGHT)
                c.plus(x.to.x, y + 1, 3, Slot.WHITE)
            }
            val q = seg(t, 0.5, 1.0)
            if (q > 0) {
                c.ring(x.to.x, x.to.y, 4 + 14 * q, Slot.ACCENT, 1.0)
                for (i in 0 until 8) c.sparkle(x.to.x + cos(i * PI / 4 + q) * 14 * q, x.to.y + sin(i * PI / 4 + q) * 11 * q, 1 + (i % 2), if (i % 2 == 0) Slot.WHITE else Slot.LIGHT)
            }
        }
        m["restore_point"] = fx(1600, onSelf = true) { c, x, t ->
            val p = seg(t, 0.05, 0.5)
            if (t < 0.8) {
                val w = (10 * easeOut(p)).toInt()
                val top = x.to.y.toInt() - 40
                c.rect(x.to.x.toInt() - w, top, 2 * w + 1, 54, Slot.DARK)
                c.rect(x.to.x.toInt() - w + 1, top, max(1, 2 * w - 1), 54, Slot.MAIN)
                c.rect(x.to.x.toInt() - w / 2, top, max(1, w), 54, Slot.LIGHT)
            }
            for (i in 0 until 10) {
                val q = clamp01(seg(t, 0.25, 0.95) * 1.2 - i * 0.07)
                if (q > 0 && q < 1) c.sparkle(x.to.x + (noise(i) - 0.5) * 20, x.to.y + 8 - 34 * q, 1 + (i % 3), if (i % 2 == 0) Slot.WHITE else Slot.ACCENT)
            }
            val r = seg(t, 0.55, 1.0)
            if (r > 0) { c.ring(x.to.x, x.to.y, 3 + 20 * r, Slot.WHITE, 2.0); c.plus(x.to.x, x.to.y, 4, Slot.WHITE) }
        }

        // ---- Buffs (up arrows on yourself, each its own flavour) -------------------------------
        fun arrows(c: FxCanvas, x: FxCtx, t: Double, up: Boolean, count: Int = 3, onTarget: Boolean = true) {
            val base = if (onTarget) x.to else x.from
            for (i in 0 until count) {
                val p = clamp01(seg(t, 0.25, 0.95) * 1.4 - i * 0.12)
                if (p <= 0 || p >= 1) continue
                val ax = base.x + (i - (count - 1) / 2.0) * 9
                val ay = if (up) base.y + 10 - 26 * p else base.y - 10 + 24 * p
                c.arrow(ax, ay, 5, up, Slot.DARK)
                c.arrow(ax, ay, 4, up, if (i % 2 == 0) Slot.ACCENT else Slot.MAIN)
            }
        }
        m["bitrate_boost"] = fx(1300, onSelf = true) { c, x, t ->
            val p = seg(t, 0.0, 0.5)
            for (i in 0 until 10) {
                val a = i * PI / 5 + t * 4
                val r = lerp(20.0, 6.0, p)
                if (p > 0 && t < 0.55) c.line(x.to.x + cos(a) * r * 1.6, x.to.y + sin(a) * r, x.to.x + cos(a) * r * 0.7, x.to.y + sin(a) * r * 0.5, if (i % 2 == 0) Slot.MAIN else Slot.ACCENT, 1)
            }
            if (t > 0.3 && t < 0.7) c.ring(x.to.x, x.to.y, 7.0, Slot.WHITE, 2.0)
            arrows(c, x, t, true)
        }
        m["two_pass_prep"] = fx(1300, onSelf = true) { c, x, t ->
            for (pass in 0 until 2) {
                val q = seg(t, 0.05 + pass * 0.3, 0.45 + pass * 0.3)
                if (q > 0 && q < 1) {
                    val y = lerp(x.to.y + 14, x.to.y - 14, q)
                    c.rect(x.to.x.toInt() - 14, y.toInt(), 29, 2, Slot.DARK)
                    c.rect(x.to.x.toInt() - 13, y.toInt(), 27, 1, if (pass == 0) Slot.MAIN else Slot.WHITE)
                }
            }
            arrows(c, x, t, true, 2)
        }
        m["denoise"] = fx(1300, onSelf = true) { c, x, t ->
            val p = easeOut(seg(t, 0.05, 0.55))
            val r = 16 * p
            if (p > 0 && t < 0.9) {
                for (i in 0 until 6) {
                    val a = i * PI / 3 + PI / 6
                    val b = a + PI / 3
                    c.line(x.to.x + cos(a) * r, x.to.y + sin(a) * r * 0.85, x.to.x + cos(b) * r, x.to.y + sin(b) * r * 0.85, Slot.DARK, 3)
                    c.line(x.to.x + cos(a) * r, x.to.y + sin(a) * r * 0.85, x.to.x + cos(b) * r, x.to.y + sin(b) * r * 0.85, if (i % 2 == 0) Slot.MAIN else Slot.ACCENT, 1)
                }
                for (i in 0 until 10) if (noise(i + (t * 14).toInt() * 7) > 0.6 + 0.4 * p) c.rect((x.to.x + (noise(i) - 0.5) * 36).toInt(), (x.to.y + (noise(i + 4) - 0.5) * 30).toInt(), 2, 2, Slot.LIGHT)
            }
            arrows(c, x, t, true, 2)
        }
        m["deblock"] = fx(1300, onSelf = true) { c, x, t ->
            val p = seg(t, 0.05, 0.6)
            for (gx in -3..3) for (gy in -2..2) {
                val idx = (gx + 3) * 5 + (gy + 2)
                val show = noise(idx) < p
                if (!show || t > 0.9) continue
                val tx = x.to.x + gx * 5
                val ty = x.to.y + gy * 5
                c.rect(tx.toInt() - 2, ty.toInt() - 2, 5, 5, Slot.DARK)
                c.rect(tx.toInt() - 1, ty.toInt() - 1, 3, 3, if ((gx + gy) % 2 == 0) Slot.MAIN else Slot.ACCENT)
            }
            arrows(c, x, t, true, 2)
        }
        m["fast_preset"] = fx(1200, onSelf = true) { c, x, t ->
            for (i in 0 until 8) {
                val p = clamp01(seg(t, 0.05, 0.7) * 1.3 - i * 0.06)
                if (p <= 0 || p >= 1) continue
                val y = x.to.y - 14 + i * 4
                val xx = lerp(x.to.x + 24, x.to.x - 24, p)
                c.rect(xx.toInt(), y.toInt(), 12, 2, Slot.DARK)
                c.rect(xx.toInt() + 1, y.toInt(), 10, 1, if (i % 2 == 0) Slot.MAIN else Slot.WHITE)
            }
            arrows(c, x, t, true)
        }

        // ---- Debuffs (down arrows on the target) ------------------------------------------------
        m["downscale"] = fx(1300) { c, x, t ->
            val p = easeOut(seg(t, 0.05, 0.6))
            for (i in 0 until 3) {
                val s = ((26 - i * 5) * (1 - 0.5 * p)).toInt()
                if (t < 0.9) c.outline(x.to.x.toInt() - s / 2, x.to.y.toInt() - s / 2, s, s, listOf(Slot.MAIN, Slot.ACCENT, Slot.LIGHT)[i], 2)
            }
            arrows(c, x, t, false, 3)
        }
        m["lowpass"] = fx(1300) { c, x, t ->
            val flat = easeOut(seg(t, 0.1, 0.7))
            for (i in 0..30) {
                val xx = x.to.x - 15 + i
                val y = x.to.y + sin(i * 0.6) * 8 * (1 - flat) * (1 - 0.4 * abs(i - 15) / 15.0)
                c.rect(xx.toInt(), y.toInt() - 1, 1, 3, Slot.DARK)
                c.rect(xx.toInt(), y.toInt(), 1, 2, if (flat > 0.7) Slot.ACCENT else Slot.MAIN)
            }
            arrows(c, x, t, false, 3)
        }
        m["crop"] = fx(1300) { c, x, t ->
            val p = easeOut(seg(t, 0.05, 0.6))
            val h = (14 - 7 * p).toInt()
            val cx = x.to.x.toInt()
            val cy = x.to.y.toInt()
            for (sx in listOf(-1, 1)) for (sy in listOf(-1, 1)) {
                val ox = cx + sx * h
                val oy = cy + sy * h
                c.rect(if (sx < 0) ox else ox - 6, oy - (if (sy < 0) 0 else 2), 7, 3, Slot.DARK)
                c.rect(if (sx < 0) ox else ox - 6, oy - (if (sy < 0) 0 else 2) + 1, 7, 1, Slot.WHITE)
                c.rect(ox - (if (sx < 0) 0 else 2), if (sy < 0) oy else oy - 6, 3, 7, Slot.DARK)
                c.rect(ox - (if (sx < 0) 0 else 2) + 1, if (sy < 0) oy else oy - 6, 1, 7, Slot.WHITE)
            }
            if (t > 0.5) c.outline(cx - h, cy - h, 2 * h + 1, 2 * h + 1, Slot.ACCENT, 1)
            arrows(c, x, t, false, 3)
        }
        m["banding"] = fx(1300) { c, x, t ->
            val p = seg(t, 0.05, 0.6)
            val slots = listOf(Slot.DARK, Slot.MAIN, Slot.ACCENT, Slot.LIGHT, Slot.ACCENT, Slot.MAIN, Slot.DARK)
            for (i in slots.indices) {
                if (i > p * slots.size) break
                c.rect(x.to.x.toInt() - 15, x.to.y.toInt() - 12 + i * 4, 31, 4, slots[i])
            }
            arrows(c, x, t, false, 3)
        }
        m["drop_fps"] = fx(1300) { c, x, t ->
            for (i in 0 until 5) {
                val fall = clamp01(seg(t, 0.1 + i * 0.1, 0.7 + i * 0.05))
                val y = x.to.y - 10 + 26 * easeIn(fall)
                val alive = fall < 1
                if (!alive) continue
                val xx = x.to.x - 16 + i * 8
                c.rect(xx.toInt() - 3, y.toInt() - 4, 7, 9, Slot.DARK)
                c.rect(xx.toInt() - 2, y.toInt() - 3, 5, 7, if (i % 2 == 0) Slot.MAIN else Slot.ACCENT)
                c.rect(xx.toInt() - 2, y.toInt() - 3, 5, 1, Slot.LIGHT)
            }
            arrows(c, x, t, false, 3)
        }

        // ---- Dud card ----------------------------------------------------------------------------
        m["segfault"] = fx(900, lunge = true) { c, x, t ->
            val p = seg(t, 0.1, 0.5)
            if (p > 0 && t < 0.85) {
                val s = (4 + 4 * p).toInt()
                c.line(x.to.x - s, x.to.y - s, x.to.x + s, x.to.y + s, Slot.DARK, 3)
                c.line(x.to.x - s, x.to.y + s, x.to.x + s, x.to.y - s, Slot.DARK, 3)
                c.line(x.to.x - s, x.to.y - s, x.to.x + s, x.to.y + s, Slot.MAIN, 1)
                c.line(x.to.x - s, x.to.y + s, x.to.x + s, x.to.y - s, Slot.MAIN, 1)
            }
            for (i in 0 until 4) { val q = seg(t, 0.5, 1.0); if (q > 0) c.disc(x.to.x + (i - 1.5) * 5, x.to.y - 10 - 6 * q - i, 2 - q, Slot.LIGHT) }
        }
        return m
    }
}
