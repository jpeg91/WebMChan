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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.unit.dp

/**
 * The speech bubble above the log: the app telling the person, in plain words, what is going on. It
 * shows the latest message; tapping it opens a small window listing the earlier ones for this video.
 * A message that wants a second look is amber. Nothing is drawn until there is a message.
 */
@Composable
fun LogBubble(notes: List<UserNote>, header: String? = null, live: String? = null) {
    val hasHeader = !header.isNullOrBlank()
    if (notes.isEmpty() && !hasHeader && live == null) return
    val last = notes.lastOrNull()
    var open by remember { mutableStateOf(false) }
    val shape = CutCornerShape(topStart = 12.dp, bottomEnd = 12.dp)
    val attention = live == null && last?.attention == true
    val edge = if (attention) P5.Amber else P5.Line
    val fill = if (attention) P5.Amber.copy(alpha = 0.16f) else P5.Panel
    val message = live ?: last?.text
    val earlier = if (live != null) notes.size else notes.size - 1
    Column(Modifier.fillMaxWidth()) {
        Box(
            Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(fill)
                .border(1.5.dp, edge, shape)
                .clickable(enabled = notes.isNotEmpty()) { open = true }
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            Column {
                // Where the run is up to ("Video 2 of 5: attempt 1/7..."): smaller and greyed, above the message.
                if (hasHeader) {
                    Text(header!!, style = MaterialTheme.typography.bodySmall, color = P5.Grey)
                }
                if (message != null) {
                    Text(
                        message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = P5.White,
                        modifier = Modifier.padding(top = if (hasHeader) 4.dp else 0.dp),
                    )
                }
                if (earlier > 0) {
                    Text(
                        "Tap for earlier messages ($earlier)",
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

/** Small icon buttons for the corner of the log: copy, and bigger / smaller. Drawn here, so no icon library is needed. */
@Composable
fun LogCopyButton(onClick: () -> Unit) {
    LogIconBox("Copy log", onClick) {
        val u = size.width / 24f
        val stroke = Stroke(width = 2f * u, cap = StrokeCap.Round, join = StrokeJoin.Round)
        drawRoundRect(
            color = P5.White,
            topLeft = Offset(8f * u, 7f * u),
            size = Size(12f * u, 14f * u),
            cornerRadius = CornerRadius(2f * u, 2f * u),
            style = stroke,
        )
        val back = Path().apply {
            moveTo(15.5f * u, 3f * u)
            lineTo(6f * u, 3f * u)
            lineTo(4f * u, 5f * u)
            lineTo(4f * u, 16.5f * u)
            lineTo(5.5f * u, 17f * u)
        }
        drawPath(back, P5.White, style = stroke)
    }
}

/** Up arrow to make the log bigger, down arrow to make it smaller. */
@Composable
fun LogSizeButton(expanded: Boolean, onClick: () -> Unit) {
    LogIconBox(if (expanded) "Shrink log" else "Expand log", onClick) {
        val u = size.width / 24f
        val stroke = Stroke(width = 2f * u, cap = StrokeCap.Round, join = StrokeJoin.Round)
        val tipY = if (expanded) 19f else 5f
        val tailY = if (expanded) 5f else 19f
        val headY = if (expanded) 13f else 11f
        val arrow = Path().apply {
            moveTo(12f * u, tailY * u)
            lineTo(12f * u, tipY * u)
            moveTo(6f * u, headY * u)
            lineTo(12f * u, tipY * u)
            lineTo(18f * u, headY * u)
        }
        drawPath(arrow, P5.White, style = stroke)
    }
}

@Composable
private fun LogIconBox(label: String, onClick: () -> Unit, draw: androidx.compose.ui.graphics.drawscope.DrawScope.() -> Unit) {
    Box(
        Modifier
            .size(32.dp)
            .clickable(onClick = onClick)
            .semantics { contentDescription = label; role = Role.Button },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(18.dp)) { draw() }
    }
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
