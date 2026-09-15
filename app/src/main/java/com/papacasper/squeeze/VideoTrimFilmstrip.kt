package com.papacasper.squeeze

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * TikTok-stitch-style trim control: a filmstrip of thumbnails spanning the whole video with a
 * draggable bracketed window over it (dimmed outside the selection). Trims both ends, capped at
 * [VideoToGifConverter.MAX_DURATION_MS].
 */
@Composable
fun VideoTrimFilmstrip(
    uri: Uri,
    durationMs: Long,
    trimStartMs: Long,
    trimEndMs: Long,
    onTrimChange: (Long, Long) -> Unit
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val clipMs = VideoToGifConverter.MAX_DURATION_MS
    var thumbnails by remember { mutableStateOf<List<Bitmap>>(emptyList()) }

    LaunchedEffect(uri, durationMs) {
        if (durationMs <= 0) return@LaunchedEffect
        val frameCount = 12
        val frames = withContext(Dispatchers.IO) {
            val retriever = MediaMetadataRetriever()
            val list = mutableListOf<Bitmap>()
            try {
                retriever.setDataSource(context, uri)
                for (i in 0 until frameCount) {
                    val t = (durationMs.toDouble() * i / frameCount).toLong().coerceAtLeast(0)
                    retriever.getFrameAtTime(t * 1000L, MediaMetadataRetriever.OPTION_CLOSEST)?.let { list.add(it) }
                }
            } catch (e: Exception) {
                // leave list as-is; filmstrip just stays blank
            } finally {
                retriever.release()
            }
            list
        }
        thumbnails = frames
    }

    val currentStart by rememberUpdatedState(trimStartMs)
    val currentEnd by rememberUpdatedState(trimEndMs)
    val currentDuration by rememberUpdatedState(durationMs)

    val primary = MaterialTheme.colorScheme.primary
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Trim clip (${clipMs / 1000}s max)", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        Text(
            "${formatDuration(trimStartMs)} – ${formatDuration(trimEndMs)} of ${formatDuration(durationMs)} (${formatDuration(trimEndMs - trimStartMs)})",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            val widthPx = with(density) { maxWidth.toPx() }
            val handleWidthDp = 14.dp
            val handleWidthPx = with(density) { handleWidthDp.toPx() }

            Row(modifier = Modifier.fillMaxSize()) {
                thumbnails.forEach { bmp ->
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                        contentScale = ContentScale.Crop
                    )
                }
            }

            if (widthPx > 0f && durationMs > 0) {
                val startX = (trimStartMs.toFloat() / durationMs * widthPx).coerceIn(0f, widthPx)
                val endX = (trimEndMs.toFloat() / durationMs * widthPx).coerceIn(0f, widthPx)

                // Dim the discarded portions on either side of the selection.
                Box(
                    Modifier
                        .offset { IntOffset(0, 0) }
                        .width(with(density) { startX.toDp() })
                        .fillMaxHeight()
                        .background(Color.Black.copy(alpha = 0.65f))
                )
                Box(
                    Modifier
                        .offset { IntOffset(endX.roundToInt(), 0) }
                        .width(with(density) { (widthPx - endX).toDp() })
                        .fillMaxHeight()
                        .background(Color.Black.copy(alpha = 0.65f))
                )
                // Whole-window drag: grabbing the middle of the selection shifts both handles
                // together, keeping the clip length fixed (like TikTok's stitch trimmer).
                val onWindowDrag = rememberDraggableState { delta ->
                    val deltaMs = (delta / widthPx * currentDuration).toLong()
                    val windowMs = currentEnd - currentStart
                    var newStart = (currentStart + deltaMs).coerceIn(0L, currentDuration - windowMs)
                    onTrimChange(newStart, newStart + windowMs)
                }

                // Selection outline, TikTok-style: accent border top/bottom, bracket handles at the sides.
                Box(
                    Modifier
                        .offset { IntOffset(startX.roundToInt(), 0) }
                        .width(with(density) { (endX - startX).toDp() })
                        .fillMaxHeight()
                        .border(3.dp, primary, RoundedCornerShape(4.dp))
                        .draggable(orientation = Orientation.Horizontal, state = onWindowDrag)
                )

                val onStartDrag = rememberDraggableState { delta ->
                    val deltaMs = (delta / widthPx * currentDuration).toLong()
                    var newStart = (currentStart + deltaMs).coerceIn(0L, currentEnd - 500L)
                    if (currentEnd - newStart > clipMs) newStart = currentEnd - clipMs
                    onTrimChange(newStart.coerceAtLeast(0L), currentEnd)
                }
                val onEndDrag = rememberDraggableState { delta ->
                    val deltaMs = (delta / widthPx * currentDuration).toLong()
                    var newEnd = (currentEnd + deltaMs).coerceIn(currentStart + 500L, currentDuration)
                    if (newEnd - currentStart > clipMs) newEnd = currentStart + clipMs
                    onTrimChange(currentStart, newEnd.coerceAtMost(currentDuration))
                }

                Box(
                    Modifier
                        .offset { IntOffset((startX - handleWidthPx / 2f).roundToInt(), 0) }
                        .width(handleWidthDp)
                        .fillMaxHeight()
                        .background(primary, RoundedCornerShape(6.dp))
                        .draggable(orientation = Orientation.Horizontal, state = onStartDrag)
                )
                Box(
                    Modifier
                        .offset { IntOffset((endX - handleWidthPx / 2f).roundToInt(), 0) }
                        .width(handleWidthDp)
                        .fillMaxHeight()
                        .background(primary, RoundedCornerShape(6.dp))
                        .draggable(orientation = Orientation.Horizontal, state = onEndDrag)
                )
            }
        }
    }
}

private fun formatDuration(ms: Long): String {
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}
