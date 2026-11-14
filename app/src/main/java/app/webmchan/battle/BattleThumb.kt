// #battle
package app.webmchan.battle

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.webmchan.P5
import app.webmchan.Thumbnails
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The app's launcher icon as a picture, drawn once and kept (null if it can't be drawn). */
private var appIconPicture: ImageBitmap? = null

private fun appIcon(ctx: Context): ImageBitmap? {
    appIconPicture?.let { return it }
    return try {
        val drawable = ctx.packageManager.getApplicationIcon(ctx.packageName)
        val size = 192
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        drawable.setBounds(0, 0, size, size)
        drawable.draw(android.graphics.Canvas(bmp))
        bmp.asImageBitmap().also { appIconPicture = it }
    } catch (_: Exception) {
        null
    }
}

/**
 * A card's picture: the same small thumbnail History shows for the video (the file is named after the
 * History card's id, which is the Battle-Card's id). Blank if the video has none. Test and ready-made cards
 * (and the boss) have no video, and negative ids, so they show the app icon.
 */
@Composable
fun CardThumb(cardId: Long, width: Dp = 64.dp, height: Dp = 40.dp, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val bitmap by produceState<ImageBitmap?>(null, cardId) {
        value = withContext(Dispatchers.IO) {
            if (cardId < 0) return@withContext appIcon(ctx)
            val f = Thumbnails.file(ctx, cardId)
            if (f.length() > 0) BitmapFactory.decodeFile(f.absolutePath)?.asImageBitmap() else null
        }
    }
    Box(
        modifier
            .size(width = width, height = height)
            .clip(androidx.compose.foundation.shape.CutCornerShape(topStart = 8.dp, bottomEnd = 8.dp))
            .background(Color.Black)
            .border(1.dp, P5.GreyDim, androidx.compose.foundation.shape.CutCornerShape(topStart = 8.dp, bottomEnd = 8.dp)),
        contentAlignment = Alignment.Center,
    ) {
        bitmap?.let { Image(it, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.size(width = width, height = height)) }
    }
}
