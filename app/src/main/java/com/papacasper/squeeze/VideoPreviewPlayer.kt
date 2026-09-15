package com.papacasper.squeeze

import android.net.Uri
import android.widget.VideoView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay

/**
 * Default video preview: a playable video with a play/pause button. When [trimEndMs] is smaller
 * than the full video, playback loops within [trimStartMs]..[trimEndMs] so the user can preview
 * exactly the clip that will be converted; otherwise it just plays from the start.
 */
@Composable
fun VideoPreviewPlayer(uri: Uri, trimStartMs: Long, trimEndMs: Long) {
    var videoView by remember { mutableStateOf<VideoView?>(null) }
    var isPlaying by remember { mutableStateOf(false) }

    // Keep playback confined to the current trim window and looping: if the user drags the trim
    // handles while playing, snap back inside the (possibly moved) window; and once playback
    // reaches the end of the window (or the video completes on its own, which pauses it), seek
    // back to the start and resume so the preview replays instead of just stopping.
    LaunchedEffect(isPlaying, trimStartMs, trimEndMs) {
        val vv = videoView ?: return@LaunchedEffect
        if (!isPlaying) return@LaunchedEffect
        if (vv.currentPosition < trimStartMs || vv.currentPosition >= trimEndMs) {
            vv.seekTo(trimStartMs.toInt())
            vv.start()
        }
        while (isPlaying) {
            if (vv.currentPosition >= trimEndMs || !vv.isPlaying) {
                vv.seekTo(trimStartMs.toInt())
                vv.start()
            }
            delay(50)
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(200.dp)
                .clip(RoundedCornerShape(12.dp))
        ) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    VideoView(ctx).apply {
                        tag = uri
                        setVideoURI(uri)
                        setOnPreparedListener { mp ->
                            mp.isLooping = false
                            seekTo(trimStartMs.toInt())
                        }
                        videoView = this
                    }
                },
                update = { view ->
                    if (view.tag != uri) {
                        view.tag = uri
                        isPlaying = false
                        view.setVideoURI(uri)
                        view.setOnPreparedListener { mp ->
                            mp.isLooping = false
                            mp.seekTo(trimStartMs.toInt())
                        }
                    }
                }
            )
            IconButton(
                onClick = {
                    val vv = videoView ?: return@IconButton
                    if (isPlaying) {
                        vv.pause()
                        isPlaying = false
                    } else {
                        if (vv.currentPosition < trimStartMs || vv.currentPosition >= trimEndMs) {
                            vv.seekTo(trimStartMs.toInt())
                        }
                        vv.start()
                        isPlaying = true
                    }
                },
                modifier = Modifier
                    .align(Alignment.Center)
                    .background(Color.Black.copy(alpha = 0.45f), shape = CircleShape)
            ) {
                Icon(
                    imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (isPlaying) "Pause preview" else "Play preview",
                    tint = Color.White
                )
            }
        }
    }
}
