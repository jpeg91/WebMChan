// #theme
package app.webmchan

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwitchColors
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * WebMChan's look: a Persona 5 inspired theme. Black everywhere, white text, red accents, slanted
 * shapes and heavy italic type. This file only holds the look (colours, type, shapes and a few
 * styled building blocks). None of the app's behaviour lives here.
 */

/** The palette. Red is for fills and big accents; [RedBright] is the red that is safe for small text. */
object P5 {
    val Black = Color(0xFF080808)
    val Ink = Color(0xFF151515)
    val Panel = Color(0xFF232323)
    val Line = Color(0xFF4A4A4A)
    val Red = Color(0xFFE60012)
    val RedBright = Color(0xFFFF4553)
    val RedDeep = Color(0xFF7A000B)
    val White = Color(0xFFFFFFFF)
    val Grey = Color(0xFFC4C4C4)
    val GreyDim = Color(0xFF727272)
    val Amber = Color(0xFFFFC21A)
}

private val P5ColorScheme = darkColorScheme(
    primary = P5.Red,
    onPrimary = P5.White,
    primaryContainer = P5.RedDeep,
    onPrimaryContainer = P5.White,
    secondary = P5.White,
    onSecondary = P5.Black,
    tertiary = P5.Amber,
    onTertiary = P5.Black,
    background = P5.Black,
    onBackground = P5.White,
    surface = P5.Black,
    onSurface = P5.White,
    surfaceVariant = P5.Panel,
    onSurfaceVariant = P5.Grey,
    surfaceContainerHigh = P5.Ink,
    outline = P5.Grey,
    outlineVariant = P5.Line,
    error = Color(0xFFFF6B6B),
    onError = P5.Black,
)

private val Heavy = FontFamily.SansSerif

private val P5Typography = Typography(
    headlineMedium = TextStyle(
        fontFamily = Heavy, fontWeight = FontWeight.Black, fontStyle = FontStyle.Italic,
        fontSize = 30.sp, lineHeight = 32.sp, letterSpacing = (-0.5).sp,
    ),
    titleMedium = TextStyle(
        fontFamily = Heavy, fontWeight = FontWeight.Black, fontStyle = FontStyle.Italic,
        fontSize = 19.sp, lineHeight = 23.sp, letterSpacing = 0.5.sp,
    ),
    titleSmall = TextStyle(
        fontFamily = Heavy, fontWeight = FontWeight.ExtraBold,
        fontSize = 15.sp, lineHeight = 19.sp,
    ),
    bodyMedium = TextStyle(fontFamily = Heavy, fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontFamily = Heavy, fontSize = 12.5.sp, lineHeight = 17.sp),
    labelMedium = TextStyle(
        fontFamily = Heavy, fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic,
        fontSize = 13.sp, lineHeight = 16.sp, letterSpacing = 0.4.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = Heavy, fontWeight = FontWeight.Black, fontStyle = FontStyle.Italic,
        fontSize = 14.sp, lineHeight = 17.sp, letterSpacing = 0.6.sp,
    ),
)

@Composable
fun P5Theme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = P5ColorScheme, typography = P5Typography, content = content)
}

/** A parallelogram: the top edge is pushed right by [slant], the bottom edge left. */
internal class SlantShape(private val slant: Dp) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val s = with(density) { slant.toPx() }.coerceAtMost(size.width / 3f)
        return Outline.Generic(slantPath(size.width, size.height, s))
    }
}

internal fun slantPath(w: Float, h: Float, s: Float, dx: Float = 0f, dy: Float = 0f): Path = Path().apply {
    moveTo(s + dx, dy)
    lineTo(w + dx, dy)
    lineTo(w - s + dx, h + dy)
    lineTo(dx, h + dy)
    close()
}

/**
 * The backdrop of every screen: solid black with faint diagonal pinstripes and a couple of red
 * wedges in the corners. It's drawn behind the content, so it stays put while the content scrolls.
 * Also makes plain text white by default.
 */
@Composable
fun P5Background(content: @Composable () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(P5.Black)
            .drawP5Backdrop(),
    ) {
        // Keeps the content clear of the status bar, the navigation bar and any camera cut-out
        // (Android 15 draws apps edge to edge), plus a little breathing room top and bottom. The
        // backdrop above stays full screen. Every page wraps itself in its own P5Background, so
        // this covers all of them.
        Box(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.displayCutout))
                .padding(vertical = 8.dp),
        ) {
            CompositionLocalProvider(LocalContentColor provides P5.White) { content() }
        }
    }
}

private fun Modifier.drawP5Backdrop(): Modifier = this.drawBehindCompat { w, h, px ->
    // Pinstripes.
    val gap = px(22f)
    var x = -h
    while (x < w) {
        drawLine(Color.White.copy(alpha = 0.03f), Offset(x, h), Offset(x + h, 0f), strokeWidth = px(1.5f))
        x += gap
    }
    // Top right: a dark wedge with a bright one on top of it.
    drawPath(
        Path().apply { moveTo(w * 0.40f, 0f); lineTo(w, 0f); lineTo(w, h * 0.15f); close() },
        P5.RedDeep,
    )
    drawPath(
        Path().apply { moveTo(w * 0.66f, 0f); lineTo(w, 0f); lineTo(w, h * 0.09f); close() },
        P5.Red,
    )
    // Bottom left.
    drawPath(
        Path().apply { moveTo(0f, h); lineTo(0f, h * 0.90f); lineTo(w * 0.30f, h); close() },
        P5.RedDeep,
    )
}

/**
 * Draws behind the content, handing the block the width, the height and a function that turns
 * dp numbers into pixels (so shapes can be described in dp).
 */
private fun Modifier.drawBehindCompat(
    block: DrawScope.(w: Float, h: Float, px: (Float) -> Float) -> Unit,
): Modifier = this.drawBehind {
    val d = density
    block(size.width, size.height) { dp -> dp * d }
}

enum class P5Kind {
    /** Solid red. The main action. */
    Primary,

    /** Black with a white outline. */
    Secondary,

    /** Just red text. */
    Ghost,
}

/** A slanted, shouty button. The label is shown in capitals. */
@Composable
fun P5Button(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    kind: P5Kind = P5Kind.Primary,
    /** A smaller button (shorter, tighter, smaller text) for crowded rows. */
    compact: Boolean = false,
    /** Smaller still than [compact]: a short one-line label, for a row of several small buttons. */
    dense: Boolean = false,
) {
    val shape = remember { SlantShape(if (dense) 5.dp else if (compact) 6.dp else 9.dp) }
    val fill = when (kind) {
        P5Kind.Primary -> if (enabled) P5.Red else P5.Panel
        P5Kind.Secondary -> P5.Black
        P5Kind.Ghost -> Color.Transparent
    }
    val textColor = when {
        !enabled -> P5.GreyDim
        kind == P5Kind.Ghost -> P5.RedBright
        else -> P5.White
    }
    val borderColor = if (enabled) P5.White else P5.Line
    Box(
        modifier
            .heightIn(min = if (dense) 30.dp else if (compact) 34.dp else if (kind == P5Kind.Ghost) 40.dp else 46.dp)
            .then(
                if (kind != P5Kind.Ghost && enabled) {
                    Modifier.drawBehindCompat { w, h, px ->
                        val s = px(if (compact) 6f else 9f).coerceAtMost(w / 3f)
                        drawPath(
                            slantPath(w, h, s, px(if (compact) 3f else 4f), px(if (compact) 3f else 4f)),
                            if (kind == P5Kind.Primary) P5.RedDeep else P5.Red.copy(alpha = 0.8f),
                        )
                    }
                } else Modifier
            )
            .clip(shape)
            .background(fill)
            .then(if (kind == P5Kind.Ghost) Modifier else Modifier.border(if (compact) 1.5.dp else 2.dp, borderColor, shape))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(
                horizontal = if (dense) 8.dp else if (compact) 6.dp else if (kind == P5Kind.Ghost) 10.dp else 16.dp,
                vertical = if (dense) 4.dp else if (compact) 5.dp else 9.dp,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label.uppercase(),
            color = textColor,
            style = if (dense) {
                MaterialTheme.typography.labelLarge.copy(fontSize = 10.sp, lineHeight = 12.sp, letterSpacing = 0.2.sp)
            } else if (compact) {
                MaterialTheme.typography.labelLarge.copy(fontSize = 11.sp, lineHeight = 13.sp, letterSpacing = 0.2.sp)
            } else MaterialTheme.typography.labelLarge,
            maxLines = if (dense) 1 else Int.MAX_VALUE,
            softWrap = !dense,
            textAlign = TextAlign.Center,
        )
    }
}

/** The small square buttons on a card (move up, move down, remove, more, fewer). */
@Composable
fun P5SquareButton(
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    accent: Boolean = false,
    /** The button's outer size; the card header uses a smaller one so the title gets more room. */
    size: Dp = 42.dp,
) {
    val shape = remember { SlantShape(6.dp) }
    val color = when {
        !enabled -> P5.Line
        accent -> P5.RedBright
        else -> P5.White
    }
    Box(
        Modifier
            .size(size)
            .padding(3.dp)
            .clip(shape)
            .background(if (enabled) P5.Panel else P5.Black)
            .border(1.5.dp, color, shape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (enabled) color else P5.GreyDim, style = MaterialTheme.typography.titleSmall)
    }
}

/**
 * A small slanted label for a status or category (Waiting, Done, a quality bracket). Outlined in
 * [color]; with [filled] the whole tag is [color] and the text is black.
 */
@Composable
fun P5Tag(text: String, color: Color, modifier: Modifier = Modifier, filled: Boolean = false) {
    val shape = remember { SlantShape(5.dp) }
    Box(
        modifier
            .clip(shape)
            .background(if (filled) color else P5.Black)
            .border(1.5.dp, color, shape)
            .padding(horizontal = 12.dp, vertical = 2.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text.uppercase(),
            color = if (filled) (if (color == P5.Red || color == P5.Black) P5.White else P5.Black) else color,
            style = MaterialTheme.typography.labelMedium.copy(fontSize = 11.sp, lineHeight = 14.sp, letterSpacing = 0.5.sp),
        )
    }
}

/** The marker in front of a choice: a diamond, filled red when picked. */
@Composable
fun P5Diamond(selected: Boolean) {
    Box(Modifier.padding(top = 4.dp).size(22.dp), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .size(12.dp)
                .rotate(45f)
                .background(if (selected) P5.Red else Color.Transparent)
                .border(2.dp, if (selected) P5.Red else P5.GreyDim),
        )
    }
}

/** A section heading: a red slanted banner, tilted a touch, with a hard white shadow. */
@Composable
fun P5SectionTitle(title: String, modifier: Modifier = Modifier) {
    val shape = remember { SlantShape(10.dp) }
    Box(
        modifier
            .padding(end = 6.dp, bottom = 5.dp)
            .graphicsLayer {
                rotationZ = -1.5f
                transformOrigin = TransformOrigin(0f, 0.5f)
            }
            .drawBehindCompat { w, h, px ->
                val s = px(10f).coerceAtMost(w / 3f)
                drawPath(slantPath(w, h, s, px(4f), px(4f)), P5.White)
            }
            .clip(shape)
            .background(P5.Red)
            .padding(horizontal = 20.dp, vertical = 5.dp),
    ) {
        Text(title.uppercase(), color = P5.White, style = MaterialTheme.typography.titleMedium)
    }
}

/** The big title at the top of a screen: heavy italic capitals with a red drop shadow. */
@Composable
fun P5Title(text: String, modifier: Modifier = Modifier, size: TextUnit = 34.sp, upperCase: Boolean = true) {
    Text(
        if (upperCase) text.uppercase() else text,
        modifier = modifier,
        color = P5.White,
        style = MaterialTheme.typography.headlineMedium.copy(
            fontSize = size,
            lineHeight = size * 1.05f,
            shadow = Shadow(color = P5.Red, offset = Offset(5f, 5f), blurRadius = 0f),
        ),
    )
}

/** A card with two cut corners and a coloured stripe down its left edge. */
@Composable
fun P5Card(
    modifier: Modifier = Modifier,
    accent: Color = P5.Red,
    /**
     * For the card being converted: where the light running round its border is, 0..1 per lap.
     * Read while drawing, so the card isn't recomposed as it moves. Null = the plain border.
     */
    runPhase: (() -> Float)? = null,
    verticalArrangement: Arrangement.Vertical = Arrangement.spacedBy(4.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = remember { CutCornerShape(topStart = 16.dp, bottomEnd = 16.dp) }
    Column(
        modifier
            .clip(shape)
            .background(P5.Ink)
            .drawBehindCompat { _, _, px ->
                drawRect(accent, size = Size(px(6f), size.height))
            }
            .border(1.5.dp, if (runPhase != null) P5.RedDeep else P5.Line, shape)
            .then(
                if (runPhase == null) Modifier else Modifier.drawWithContent {
                    drawContent()
                    drawRunningBorder(runPhase())
                }
            )
            .padding(start = 18.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
        verticalArrangement = verticalArrangement,
        content = content,
    )
}

/**
 * A comet of red light running clockwise round the card's cut-corner outline: a bright head with a
 * tail that fades behind it. [phase] is the head's place on the outline, 0..1 for one lap.
 */
private fun DrawScope.drawRunningBorder(phase: Float) {
    val c = 16.dp.toPx()
    val w = size.width
    val h = size.height
    val outline = Path().apply {
        moveTo(c, 0f); lineTo(w, 0f); lineTo(w, h - c); lineTo(w - c, h); lineTo(0f, h); lineTo(0f, c); close()
    }
    val measure = PathMeasure().apply { setPath(outline, false) }
    val len = measure.length
    val head = (phase - phase.toInt()) * len
    val stroke = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Butt)
    // Longest and faintest first, so the bright head ends up on top.
    val tail = listOf(0.34f to 0.22f, 0.22f to 0.45f, 0.12f to 0.8f, 0.05f to 1f)
    for ((share, alpha) in tail) {
        val color = if (share <= 0.05f) P5.RedBright else P5.Red
        val from = head - share * len
        val seg = Path()
        if (from >= 0f) {
            measure.getSegment(from, head, seg, true)
        } else {
            measure.getSegment(len + from, len, seg, true)
            measure.getSegment(0f, head, seg, true)
        }
        drawPath(seg, color.copy(alpha = alpha), style = stroke)
    }
}

/**
 * A progress bar: slanted, black, filled with red candy stripes. Takes the progress as a function
 * so only the drawing is redone as it changes. Driven by plain numbers, not by Compose's
 * animation system, so it moves even when the phone has animations turned off.
 */
@Composable
fun P5Progress(progress: () -> Float, modifier: Modifier = Modifier) {
    Canvas(modifier.fillMaxWidth().height(18.dp)) {
        val w = size.width
        val h = size.height
        val s = 9.dp.toPx().coerceAtMost(w / 3f)
        val outline = slantPath(w, h, s)
        drawPath(outline, P5.Panel)
        val p = progress().coerceIn(0f, 1f)
        if (p > 0f) {
            clipPath(outline) {
                clipRect(right = w * p) {
                    drawRect(P5.Red)
                    val step = 16.dp.toPx()
                    var x = -h
                    while (x < w) {
                        drawLine(P5.RedDeep, Offset(x, h), Offset(x + h, 0f), strokeWidth = 5.dp.toPx())
                        x += step
                    }
                }
            }
        }
        drawPath(outline, P5.White, style = Stroke(width = 2.dp.toPx()))
    }
}

@Composable
fun p5SwitchColors(): SwitchColors = SwitchDefaults.colors(
    checkedThumbColor = P5.White,
    checkedTrackColor = P5.Red,
    checkedBorderColor = P5.White,
    uncheckedThumbColor = P5.Grey,
    uncheckedTrackColor = P5.Panel,
    uncheckedBorderColor = P5.Grey,
    disabledCheckedThumbColor = P5.GreyDim,
    disabledCheckedTrackColor = P5.Panel,
    disabledCheckedBorderColor = P5.Line,
    disabledUncheckedThumbColor = P5.GreyDim,
    disabledUncheckedTrackColor = P5.Black,
    disabledUncheckedBorderColor = P5.Line,
)
