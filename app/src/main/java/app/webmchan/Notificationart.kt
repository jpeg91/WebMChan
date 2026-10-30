package app.webmchan

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.toArgb
import kotlin.math.ceil

/**
 * Pictures for the notification, drawn with the same colours and shapes as the app's own widgets
 * (see theme.kt), because a notification can't use Compose and RemoteViews can't outline text or
 * draw slanted shapes. They are small bitmaps, redrawn each time the notification updates (about
 * once a second).
 *  - [percent] is the red 💯-style counter ([P5Percent]).
 *  - [progressBar] is the slanted candy-striped bar ([P5Progress]).
 */
internal object NotificationArt {
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
     * shadow and two slanted underlines. [value] null shows dashes, dimmed (the first pass has no
     * percentage), exactly as [P5Percent] does.
     */
    fun percent(ctx: Context, value: Int?): Bitmap {
        val d = ctx.resources.displayMetrics.density
        val digits = value?.coerceIn(0, 100)?.toString() ?: "--"
        val fill = (if (value != null) P5.Red else P5.RedDeep).toArgb()
        val outline = (if (value != null) P5.White else P5.GreyDim).toArgb()

        val bigSize = 26f
        val smallSize = 14f
        val bigStroke = 2.6f
        val smallStroke = 2f

        val digitsW = textPaint(bigSize, d, fill, 0f).measureText(digits)
        val pctW = textPaint(smallSize, d, fill, 0f).measureText("%")
        val bounds = Rect()
        textPaint(bigSize, d, fill, 0f).getTextBounds("0", 0, 1, bounds)
        val capH = bounds.height().toFloat()

        val padX = 5f * d
        val padY = 5f * d
        val gap = 1f * d
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

        // Hard shadow, white outline, then the red fill on top.
        c.drawText(digits, digitsX + shadow, baseline + shadow, textPaint(bigSize, d, Color.BLACK, bigStroke))
        c.drawText(digits, digitsX, baseline, textPaint(bigSize, d, outline, bigStroke))
        c.drawText(digits, digitsX, baseline, textPaint(bigSize, d, fill, 0f))
        c.drawText("%", pctX + shadow, pctBaseline + shadow, textPaint(smallSize, d, Color.BLACK, smallStroke))
        c.drawText("%", pctX, pctBaseline, textPaint(smallSize, d, outline, smallStroke))
        c.drawText("%", pctX, pctBaseline, textPaint(smallSize, d, fill, 0f))

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