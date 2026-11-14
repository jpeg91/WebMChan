package app.webmchan.battle

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.webmchan.BattleGreen
import app.webmchan.P5
import app.webmchan.P5Button
import kotlinx.coroutines.delay

/** The message shown only after the secret boss has been beaten. */
internal object BossEndingText {
    /** Shown at once, in the middle of the screen. */
    const val TITLE = "Conglaturation"

    /** Typed out character by character once [PAUSE_MILLIS] has gone by. */
    const val BODY = "Thank you for playing my game.\n" +
        "\n" +
        "The idea to use the history cards as 'satchel critters' came to me in a dream.\n" +
        "I'm glad you enjoyed it.\n" +
        "\n" +
        "Have honor. \n" +
        "Love yourself.\n" +
        "Enjoy beauty.\n" +
        "Drink milk.\n" +
        "\n" +
        "-jpeg91"

    /** How long the title stands alone before the rest starts to type. */
    const val PAUSE_MILLIS = 3000L

    /** The time between one typed character and the next. */
    const val CHAR_MILLIS = 45L
}

/**
 * The ending: "Conglaturation" in the middle of the screen, a three second pause, then the rest of the message
 * types out under it, character by character. Everything is centred. The whole text is laid out from the start
 * (the part not typed yet is invisible), so nothing jumps about as it grows. Continue appears at the end and
 * leads on to the ordinary "Run complete" page.
 */
@Composable
internal fun BossEnding(onContinue: () -> Unit) {
    val body = BossEndingText.BODY
    var typed by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        delay(BossEndingText.PAUSE_MILLIS)
        for (n in 1..body.length) {
            typed = n
            delay(BossEndingText.CHAR_MILLIS)
        }
    }
    Box(Modifier.fillMaxSize().background(P5.Black).padding(24.dp), contentAlignment = Alignment.Center) {
        Text(
            text = buildAnnotatedString {
                withStyle(SpanStyle(fontSize = 34.sp, fontWeight = FontWeight.Bold, color = BattleGreen.Red)) { append(BossEndingText.TITLE) }
                append("\n\n")
                append(body.substring(0, typed))
                withStyle(SpanStyle(color = Color.Transparent)) { append(body.substring(typed)) }
            },
            style = MaterialTheme.typography.bodyLarge,
            color = P5.White,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        if (typed >= body.length) {
            P5Button("Continue", onClick = onContinue, modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth())
        }
    }
}
