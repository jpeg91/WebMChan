// #Notificationart
package app.webmchan

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.Typeface
import android.os.SystemClock
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.toArgb
import kotlin.math.ceil

/**
 * Pictures for the notification, drawn with the same colours and shapes as the app's own widgets
 * (see theme.kt), because a notification can't use Compose and RemoteViews can't outline text or
 * draw slanted shapes. They are small bitmaps, redrawn each time the notification updates (about
 * once a second).
 *  - [percent] is the red 💯-style counter ([P5Percent]), or the pixel hourglass ([P5Hourglass]) while
 *    there is no percentage yet.
 *  - [progressBar] is the slanted candy-striped bar ([P5Progress]).
 */
/** The pictures the status bar can show for the app (see [NotificationArt.statusIcon]). */
enum class StatusIcon { WORKING, PAUSED, DONE, FAILED }

internal object NotificationArt {
    // ---- Status bar icons ----
    // Android only uses the shape of a status bar icon (opaque or not) and tints it itself, so these
    // are plain white shapes on transparent, with a fainter white for the glass of the hourglass.
    // Drawn on a 72 x 72 grid scaled to the icon (24 dp), in the app's slanted style.

    /** A 3 x 5 digit font (1 = block), drawn slanted. */
    private val DIGITS = mapOf(
        '0' to "111101101101111", '1' to "010110010010111", '2' to "111001111100111",
        '3' to "111001111001111", '4' to "101101111001001", '5' to "111100111001111",
        '6' to "111100111101111", '7' to "111001001001001", '8' to "111101111101111",
        '9' to "111101111001111",
    )

    /**
     * The status bar icon, 24 dp square. WORKING is the hourglass while there is no [percent],
     * otherwise the percent as two big slanted digits (up to 99) over a slanted underline, like the
     * readout on the card. PAUSED, DONE and FAILED are slanted pause bars, a tick and a starburst
     * with an exclamation mark. The hourglass's sand level comes from the clock, or stays full when
     * animations are off.
     */
    fun statusIcon(ctx: Context, kind: StatusIcon, percent: Int? = null): Bitmap {
        val size = (24f * ctx.resources.displayMetrics.density).toInt().coerceAtLeast(24)
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.scale(size / 72f, size / 72f)
        val solid = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        val faint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(70, 255, 255, 255) }
        fun poly(paint: Paint, vararg xy: Float) {
            val path = Path()
            path.moveTo(xy[0], xy[1])
            for (i in 2 until xy.size step 2) path.lineTo(xy[i], xy[i + 1])
            path.close()
            c.drawPath(path, paint)
        }
        when (kind) {
            StatusIcon.WORKING -> if (percent == null) {
                // 16 frames: the sand runs from the top bulb to the bottom one, then it starts again.
                val frame = if (AppSettings.get(AppToggle.ANIMATIONS_OFF)) 0
                    else ((SystemClock.elapsedRealtime() / 500L) * 2L).toInt() % P5Hourglass.FRAMES
                val left = if (frame >= 15) 0f else 1f - frame / 15f // how much sand is still in the top
                val cx = 36f
                fun half(y: Float) = if (y < 36f) 20f - (y - 9f) / 27f * 17f else 3f + (y - 36f) / 27f * 17f
                // The glass, faintly, then the sand and a thin stream.
                poly(faint, cx - 20, 9f, cx + 20, 9f, cx + 3, 36f, cx + 20, 63f, cx - 20, 63f, cx - 3, 36f)
                if (left > 0.02f) {
                    val y = 9f + (1f - left) * 25f
                    poly(solid, cx - half(y) + 2.5f, y, cx + half(y) - 2.5f, y, cx + 1.5f, 35f, cx - 1.5f, 35f)
                }
                val low = 1f - left
                if (low > 0.02f) {
                    val y = 62f - low * 25f
                    poly(solid, cx - half(y) + 2.5f, y, cx + half(y) - 2.5f, y, cx + 17.5f, 62f, cx - 17.5f, 62f)
                    if (left > 0.02f) poly(solid, cx - 1.2f, 36f, cx + 1.2f, 36f, cx + 1.2f, y, cx - 1.2f, y)
                }
                // The slanted caps and the glass's edges.
                poly(solid, cx - 27, 2f, cx + 27, 2f, cx + 24, 9f, cx - 30, 9f)
                poly(solid, cx - 27, 63f, cx + 27, 63f, cx + 24, 70f, cx - 30, 70f)
                val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 3.2f; strokeJoin = Paint.Join.MITER
                }
                val l = Path().apply { moveTo(cx - 19f, 9f); lineTo(cx - 3f, 36f); lineTo(cx - 19f, 63f) }
                val r = Path().apply { moveTo(cx + 19f, 9f); lineTo(cx + 3f, 36f); lineTo(cx + 19f, 63f) }
                c.drawPath(l, edge)
                c.drawPath(r, edge)
            } else {
                // Big slanted digits over one slanted underline, using as much of the icon as they can.
                val text = percent.coerceIn(0, 99).toString()
                val n = text.length
                val slant = 0.22f
                val top = 3.5f
                val height = 50f
                val rowH = height / 5f
                val cw = if (n == 1) 12f else 8.8f
                val gap = cw * 0.6f
                val textW = n * 3 * cw + (n - 1) * gap
                val lean = height * slant
                val left = (72f - (textW + lean)) / 2f
                val bottom = top + height
                val path = Path()
                for ((k, digit) in text.withIndex()) {
                    val bits = DIGITS[digit] ?: continue
                    val x0 = left + k * (3 * cw + gap)
                    for (i in bits.indices) {
                        if (bits[i] != '1') continue
                        val x = x0 + (i % 3) * cw
                        val y0 = top + (i / 3) * rowH
                        val y1 = y0 + rowH + 0.3f // a touch of overlap, so no seams show
                        val s0 = (bottom - y0) * slant
                        val s1 = (bottom - y1) * slant
                        path.moveTo(x + s0, y0)
                        path.lineTo(x + cw + s0 + 0.3f, y0)
                        path.lineTo(x + cw + s1 + 0.3f, y1)
                        path.lineTo(x + s1, y1)
                        path.close()
                    }
                }
                c.drawPath(path, solid)
                val u0 = bottom + 5f
                val u1 = u0 + 8f
                poly(solid, left + 6f, u0, left + textW + lean, u0, left + textW + lean - 6f, u1, left, u1)
            }
            StatusIcon.PAUSED -> {
                poly(solid, 18.5f, 4f, 35.5f, 4f, 27.5f, 68f, 10.5f, 68f)
                poly(solid, 44.5f, 4f, 61.5f, 4f, 53.5f, 68f, 36.5f, 68f)
            }
            StatusIcon.DONE -> {
                poly(solid, 5f, 40f, 16f, 30f, 31f, 47f, 31f, 59f)
                poly(solid, 26f, 59f, 34f, 48f, 70f, 8f, 70f, 22f)
            }
            StatusIcon.FAILED -> {
                // A jagged starburst with the exclamation mark cut out of it.
                val burst = Path()
                for (i in 0 until 16) {
                    val radius = if (i % 2 == 0) 35f else 22f
                    val angle = Math.PI * 2 * i / 16 - Math.PI / 2 + 0.1
                    val x = 36f + radius * Math.cos(angle).toFloat()
                    val y = 36f + radius * Math.sin(angle).toFloat()
                    if (i == 0) burst.moveTo(x, y) else burst.lineTo(x, y)
                }
                burst.close()
                c.drawPath(burst, solid)
                val cut = Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR) }
                poly(cut, 31f, 15f, 41f, 15f, 39f, 43f, 33f, 43f)
                poly(cut, 32f, 48f, 40f, 48f, 40f, 56f, 32f, 56f)
            }
        }
        return bmp
    }

    /** The heavy italic face the app's type uses (FontFamily.SansSerif, Black, italic). */
    private val heavy: Typeface by lazy { Typeface.create(Typeface.SANS_SERIF, 900, true) }

    private fun textPaint(sizeDp: Float, density: Float, argb: Int, strokeDp: Float): Paint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = heavy
            textSize = sizeDp * density
            color = argb
            style = if (strokeDp > 0f) Paint.Style.STROKE else Paint.Style.FILL
            strokeWidth = strokeDp * density
            strokeJoin = Paint.Join.ROUND
            letterSpacing = -0.03f
        }

    /**
     * The big percentage: heavy red italic digits tilted a little, a white outline, a hard black
     * shadow and two slanted underlines. [value] null (no percentage yet) shows the animated
     * hourglass instead, exactly as [P5Percent] does; its frame comes from the clock, so each
     * update of the notification shows the next step.
     */
    fun percent(ctx: Context, value: Int?): Bitmap {
        val d = ctx.resources.displayMetrics.density
        val hourglass = value == null
        val digits = value?.coerceIn(0, 100)?.toString() ?: ""
        val fill = P5.Red.toArgb()
        val outline = P5.White.toArgb()

        val bigSize = 26f
        val smallSize = 14f
        val bigStroke = 2.6f
        val smallStroke = 2f

        val bounds = Rect()
        textPaint(bigSize, d, fill, 0f).getTextBounds("0", 0, 1, bounds)
        val capH = bounds.height().toFloat()
        // The hourglass is as tall as the digits would be, and as wide as it is tall.
        val cell = capH / P5Hourglass.SIZE
        val digitsW = if (hourglass) capH else textPaint(bigSize, d, fill, 0f).measureText(digits)
        val pctW = if (hourglass) 0f else textPaint(smallSize, d, fill, 0f).measureText("%")

        val padX = 5f * d
        val padY = 5f * d
        val gap = if (hourglass) 0f else 1f * d
        val shadow = 1.5f * d
        val barH = 2f * d
        val barGap = 1.5f * d
        val underW = 52f * d

        val contentW = digitsW + gap + pctW
        val rowW = maxOf(contentW, underW)
        val baseline = padY + capH
        val underTop = baseline + 3f * d

        val w = ceil(rowW + 2 * padX + shadow).toInt()
        val h = ceil(underTop + 2 * barH + barGap + shadow + padY).toInt()
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.rotate(-6f, w / 2f, h / 2f)

        // Right aligned, like the Column in P5Percent.
        val right = padX + rowW
        val digitsX = right - contentW
        val pctX = digitsX + digitsW + gap
        val pctBaseline = baseline - 2f * d

        if (hourglass) {
            // Hard shadow, white glass, then the red sand, in the place the digits would take.
            // Still when the Settings page has animations off.
            val frame = if (AppSettings.get(AppToggle.ANIMATIONS_OFF)) 0
                else ((SystemClock.elapsedRealtime() / 500L) * 2L).toInt() % P5Hourglass.FRAMES
            val sand = P5Hourglass.sand(frame)
            val top = baseline - capH
            val px = Paint() // no anti-aliasing: these are hard pixels
            fun draw(cells: List<Pair<Int, Int>>, argb: Int, dx: Float) {
                px.color = argb
                for ((col, row) in cells) {
                    val x = digitsX + col * cell + dx
                    val y = top + row * cell + dx
                    c.drawRect(x, y, x + cell, y + cell, px)
                }
            }
            draw(P5Hourglass.outline, Color.BLACK, shadow)
            draw(sand, Color.BLACK, shadow)
            draw(P5Hourglass.outline, outline, 0f)
            draw(sand, fill, 0f)
        } else {
            // Hard shadow, white outline, then the red fill on top.
            c.drawText(digits, digitsX + shadow, baseline + shadow, textPaint(bigSize, d, Color.BLACK, bigStroke))
            c.drawText(digits, digitsX, baseline, textPaint(bigSize, d, outline, bigStroke))
            c.drawText(digits, digitsX, baseline, textPaint(bigSize, d, fill, 0f))
            c.drawText("%", pctX + shadow, pctBaseline + shadow, textPaint(smallSize, d, Color.BLACK, smallStroke))
            c.drawText("%", pctX, pctBaseline, textPaint(smallSize, d, outline, smallStroke))
            c.drawText("%", pctX, pctBaseline, textPaint(smallSize, d, fill, 0f))
        }

        // The two underlines, the lower one shorter and pushed right.
        val slant = 3.4f * d
        val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        fun bar(width: Float, x: Float, y: Float) {
            barPaint.color = Color.BLACK
            c.drawPath(slantPath(width, barH, slant, x + shadow, y + shadow).asAndroidPath(), barPaint)
            barPaint.color = fill
            c.drawPath(slantPath(width, barH, slant, x, y).asAndroidPath(), barPaint)
        }
        bar(underW, right - underW, underTop)
        bar(underW * 0.7f, right - underW * 0.7f, underTop + barH + barGap)
        return bmp
    }

    /**
     * The slanted progress bar: black panel, red candy stripes up to [fraction] (0..1), white
     * outline, like [P5Progress]. [fraction] null (no percentage yet) shows the panel with dim
     * stripes all the way across.
     */
    fun progressBar(ctx: Context, fraction: Float?): Bitmap {
        val d = ctx.resources.displayMetrics.density
        // The notification is about as wide as the screen, minus its margins; the ImageView stretches
        // the picture to fit, so being a little off only changes the slant very slightly.
        val w = (ctx.resources.displayMetrics.widthPixels - 56f * d).toInt()
            .coerceIn((200f * d).toInt(), (600f * d).toInt())
        val h = (14f * d).toInt()
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)

        val inset = 1f * d
        val shape = slantPath(w - 2 * inset, h - 2 * inset, 7f * d, inset, inset).asAndroidPath()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.style = Paint.Style.FILL
        paint.color = P5.Panel.toArgb()
        c.drawPath(shape, paint)

        fun stripes(color: Int) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 4f * d
            paint.color = color
            val step = 13f * d
            var x = -h.toFloat()
            while (x < w) {
                c.drawLine(x, h.toFloat(), x + h, 0f, paint)
                x += step
            }
        }

        c.save()
        c.clipPath(shape)
        if (fraction == null) {
            stripes(P5.RedDeep.copy(alpha = 0.55f).toArgb())
        } else if (fraction > 0f) {
            c.save()
            c.clipRect(0f, 0f, w * fraction.coerceIn(0f, 1f), h.toFloat())
            paint.style = Paint.Style.FILL
            paint.color = P5.Red.toArgb()
            c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)
            stripes(P5.RedDeep.toArgb())
            c.restore()
        }
        c.restore()

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.5f * d
        paint.color = P5.White.toArgb()
        c.drawPath(shape, paint)
        return bmp
    }
}
