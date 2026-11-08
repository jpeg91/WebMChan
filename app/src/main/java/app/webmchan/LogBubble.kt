// #lognotes
package app.webmchan

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.unit.dp

/**
 * The speech bubble above the log: the app telling the person, in plain words, what is going on. It
 * shows the latest message; tapping it opens a small window listing the earlier ones for this video.
 * A message that wants a second look is amber. Nothing is drawn until there is a message.
 */
@Composable
fun LogBubble(notes: List<UserNote>) {
    if (notes.isEmpty()) return
    val last = notes.last()
    var open by remember { mutableStateOf(false) }
    val shape = CutCornerShape(topStart = 12.dp, bottomEnd = 12.dp)
    val edge = if (last.attention) P5.Amber else P5.Line
    val fill = if (last.attention) P5.Amber.copy(alpha = 0.16f) else P5.Panel
    Column(Modifier.fillMaxWidth()) {
        Box(
            Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(fill)
                .border(1.5.dp, edge, shape)
                .clickable { open = true }
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            Column {
                Text(last.text, style = MaterialTheme.typography.bodyMedium, color = P5.White)
                if (notes.size > 1) {
                    Text(
                        "Tap for earlier messages (${notes.size - 1})",
                        style = MaterialTheme.typography.labelSmall,
                        color = P5.GreyDim,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
        // The tail, pointing down at the log the message comes from.
        Canvas(Modifier.padding(start = 24.dp).size(width = 18.dp, height = 9.dp)) {
            val tail = Path().apply {
                moveTo(0f, 0f)
                lineTo(size.width, 0f)
                lineTo(size.width * 0.25f, size.height)
                close()
            }
            drawPath(tail, edge)
        }
    }
    if (open) NotesWindow(notes) { open = false }
}

/** The earlier messages for this video, oldest first, scrolled to the newest. */
@Composable
private fun NotesWindow(notes: List<UserNote>, onClose: () -> Unit) {
    val listState = rememberLazyListState()
    LaunchedEffect(notes.size) {
        if (notes.isNotEmpty()) listState.scrollToItem(notes.lastIndex)
    }
    AlertDialog(
        onDismissRequest = onClose,
        shape = CutCornerShape(topStart = 20.dp, bottomEnd = 20.dp),
        containerColor = P5.Ink,
        titleContentColor = P5.White,
        textContentColor = P5.White,
        title = { Text("Messages".uppercase(), style = MaterialTheme.typography.titleMedium) },
        text = {
            LazyColumn(
                state = listState,
                modifier = Modifier.heightIn(max = 360.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(notes.size) { i -> NoteRow(notes[i]) }
            }
        },
        confirmButton = { P5Button("Close", onClick = onClose) },
    )
}

@Composable
private fun NoteRow(note: UserNote) {
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        Box(
            Modifier
                .width(3.dp)
                .fillMaxHeight()
                .background(if (note.attention) P5.Amber else P5.Line),
        )
        Text(
            note.text,
            style = MaterialTheme.typography.bodySmall,
            color = P5.White,
            modifier = Modifier.padding(start = 10.dp),
        )
    }
}
