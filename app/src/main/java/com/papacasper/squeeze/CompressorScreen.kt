package com.papacasper.squeeze

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.util.Size
import android.widget.VideoView
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
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
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.roundToLong

private enum class Preset(val label: String, val short: String, val bytes: Long) {
    TEXT_RCS("Text (RCS)", "100 MB", 100L * 1024 * 1024),
    EMAIL("Email", "20 MB", 20L * 1024 * 1024),
    FREE("Discord Free", "20 MB", 20L * 1024 * 1024),
    BASIC("Discord Nitro Basic", "50 MB", 50L * 1024 * 1024),
    NITRO("Discord Nitro", "500 MB", 500L * 1024 * 1024),
    CHAT_APP("WhatsApp / Telegram", "2 GB", 2L * 1024 * 1024 * 1024)
}

private sealed class UiState {
    data object Idle : UiState()
    data class FileSelected(val uri: Uri, val mime: String, val originalBytes: Long, val thumbnail: Bitmap?) : UiState()
    data class Working(
        val originalBytes: Long,
        val message: String,
        val progress: Float,
        val indeterminate: Boolean,
        val thumbnail: Bitmap?,
        val uri: Uri,
        val mime: String
    ) : UiState()
    data class Done(val originalBytes: Long, val resultFile: File, val mime: String, val fitsTarget: Boolean, val targetLabel: String) : UiState()
    data class Failed(val message: String) : UiState()
}

// 1MB..2GB on a log scale, since the range spans 3 orders of magnitude.
private const val CUSTOM_MIN_BYTES = 1L * 1024 * 1024
private const val CUSTOM_MAX_BYTES = 2L * 1024 * 1024 * 1024

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CompressorScreen(initialUri: Uri? = null) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<UiState>(UiState.Idle) }
    var workingThumbnail by remember { mutableStateOf<Bitmap?>(null) }
    var showHistory by remember { mutableStateOf(false) }
    var customSliderFraction by remember { mutableStateOf(0.3f) }
    var convertToGif by remember { mutableStateOf(false) }
    var videoDurationMs by remember { mutableStateOf(0L) }
    var trimStartMs by remember { mutableStateOf(0L) }
    var trimEndMs by remember { mutableStateOf(0L) }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    // Compression runs in CompressionService (a foreground service) so it survives the app
    // being backgrounded or its process being reclaimed; this just mirrors that shared state
    // into the local UiState the screen renders.
    LaunchedEffect(Unit) {
        CompressionRepository.state.collect { s ->
            when (s) {
                is CompressionState.Working -> state = UiState.Working(
                    s.originalBytes, s.message, s.progress, s.indeterminate, workingThumbnail, s.uri, s.mime
                )
                is CompressionState.Done -> state = UiState.Done(
                    s.originalBytes, s.resultFile, s.mime, s.fitsTarget, s.targetLabel
                )
                is CompressionState.Failed -> state = UiState.Failed(s.message)
                CompressionState.Idle -> if (state is UiState.Working) state = UiState.Idle
            }
        }
    }

    fun selectFile(uri: Uri) {
        val mime = context.contentResolver.getType(uri) ?: ""
        val size = context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: 0L
        convertToGif = false
        videoDurationMs = 0L
        trimStartMs = 0L
        trimEndMs = 0L
        state = UiState.FileSelected(uri, mime, size, thumbnail = null)
        scope.launch {
            val thumb = withContext(Dispatchers.IO) { decodeThumbnail(context.contentResolver, uri, mime) }
            val current = state
            if (current is UiState.FileSelected && current.uri == uri) {
                state = current.copy(thumbnail = thumb)
            }
        }
        if (mime.startsWith("video")) {
            scope.launch {
                val duration = withContext(Dispatchers.IO) { queryVideoDurationMs(context, uri) }
                videoDurationMs = duration
                trimEndMs = duration.coerceAtMost(VideoToGifConverter.MAX_DURATION_MS)
            }
        }
    }

    LaunchedEffect(initialUri) {
        if (initialUri != null) selectFile(initialUri)
    }

    val pickLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) selectFile(uri)
    }

    fun runCompression(
        uri: Uri,
        mime: String,
        originalBytes: Long,
        thumbnail: Bitmap?,
        targetBytes: Long,
        targetLabel: String,
        toGif: Boolean,
        trimStartMs: Long,
        trimDurationMs: Long
    ) {
        val isVideo = mime.startsWith("video")
        val isGif = mime == "image/gif"
        workingThumbnail = thumbnail
        state = UiState.Working(
            originalBytes,
            "Starting compression...",
            0f,
            indeterminate = !isVideo && !isGif,
            thumbnail = thumbnail,
            uri = uri,
            mime = mime
        )
        if (Build.VERSION.SDK_INT >= 33) {
            notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
        CompressionService.start(
            context, uri, mime, originalBytes, targetBytes, targetLabel, toGif, trimStartMs, trimDurationMs
        )
    }

    if (showHistory) {
        HistoryDialog(onDismiss = { showHistory = false })
    }

    // Without this, Android's back gesture/button exits the app entirely from any screen
    // (file selected, working, done, failed) since there's no navigation back stack here.
    // Compression itself keeps running in CompressionService regardless, so just returning
    // to the picker is safe even mid-compression.
    BackHandler(enabled = state !is UiState.Idle) {
        state = UiState.Idle
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Squeeze", fontWeight = FontWeight.SemiBold) },
                actions = {
                    IconButton(onClick = {
                        val intent = android.content.Intent(
                            android.content.Intent.ACTION_VIEW,
                            Uri.parse("https://ko-fi.com/papacasper")
                        )
                        context.startActivity(intent)
                    }) {
                        Icon(Icons.Filled.Favorite, contentDescription = "Support on Ko-fi", tint = MaterialTheme.colorScheme.onPrimary)
                    }
                    IconButton(onClick = { showHistory = true }) {
                        Icon(Icons.Filled.History, contentDescription = "History", tint = MaterialTheme.colorScheme.onPrimary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            val isWorking = state is UiState.Working

            OutlinedButton(
                onClick = { pickLauncher.launch("*/*") },
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                enabled = !isWorking
            ) {
                Icon(Icons.Filled.FileUpload, contentDescription = null, modifier = Modifier.size(20.dp))
                Text("  Pick image or video")
            }

            when (val s = state) {
                is UiState.Idle -> {
                    Text(
                        "Pick a file, then choose a target size. The app will re-encode it to fit.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                is UiState.FileSelected -> {
                    val needsTrim = convertToGif && videoDurationMs > VideoToGifConverter.MAX_DURATION_MS
                    if (s.mime.startsWith("video")) {
                        VideoPreviewPlayer(
                            uri = s.uri,
                            trimStartMs = if (needsTrim) trimStartMs else 0L,
                            trimEndMs = if (needsTrim) trimEndMs else videoDurationMs.coerceAtLeast(1L)
                        )
                    } else {
                        ThumbnailPreview(s.thumbnail)
                    }
                    InfoCard(label = "Selected file", size = s.originalBytes)
                    if (s.mime.startsWith("video")) {
                        VideoModeToggle(
                            convertToGif = convertToGif,
                            onChange = { convertToGif = it }
                        )
                    }
                    if (needsTrim) {
                        VideoTrimFilmstrip(
                            uri = s.uri,
                            durationMs = videoDurationMs,
                            trimStartMs = trimStartMs,
                            trimEndMs = trimEndMs,
                            onTrimChange = { start, end -> trimStartMs = start; trimEndMs = end }
                        )
                    }
                    PresetButtons(enabled = true) { preset ->
                        runCompression(s.uri, s.mime, s.originalBytes, s.thumbnail, preset.bytes, "${preset.label} (${preset.short})", convertToGif, trimStartMs, trimEndMs - trimStartMs)
                    }
                    CustomSizeSlider(
                        fraction = customSliderFraction,
                        onFractionChange = { customSliderFraction = it },
                        enabled = true,
                        onCompress = { bytes, label ->
                            runCompression(s.uri, s.mime, s.originalBytes, s.thumbnail, bytes, label, convertToGif, trimStartMs, trimEndMs - trimStartMs)
                        }
                    )
                }

                is UiState.Working -> {
                    if (s.mime.startsWith("video")) {
                        VideoPreviewPlayer(uri = s.uri, trimStartMs = 0L, trimEndMs = Long.MAX_VALUE)
                    } else {
                        ThumbnailPreview(s.thumbnail)
                    }
                    InfoCard(label = "Original file", size = s.originalBytes)
                    WorkingCard(message = s.message, progress = s.progress, indeterminate = s.indeterminate)
                    OutlinedButton(
                        onClick = { CompressionService.cancel(context) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Filled.Cancel, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text("  Cancel")
                    }
                }

                is UiState.Done -> {
                    InfoCard(label = "Original file", size = s.originalBytes)
                    ResultCard(
                        originalBytes = s.originalBytes,
                        resultBytes = s.resultFile.length(),
                        fitsTarget = s.fitsTarget,
                        targetLabel = s.targetLabel
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                        Button(
                            onClick = {
                                SaveUtils.saveToDownloads(context, s.resultFile, s.mime)
                                android.widget.Toast.makeText(
                                    context,
                                    "Saved to Downloads/Squeeze",
                                    android.widget.Toast.LENGTH_SHORT
                                ).show()
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Filled.FileDownload, contentDescription = null, modifier = Modifier.size(18.dp))
                            Text("  Save")
                        }
                        Button(
                            onClick = {
                                val uri = SaveUtils.saveToDownloads(context, s.resultFile, s.mime)
                                context.startActivity(
                                    android.content.Intent.createChooser(
                                        SaveUtils.shareIntent(uri, s.mime),
                                        "Share compressed file"
                                    )
                                )
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Filled.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                            Text("  Share")
                        }
                    }
                    OutlinedButton(
                        onClick = { state = UiState.Idle },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Compress another file")
                    }
                }

                is UiState.Failed -> {
                    ErrorCard(message = s.message)
                    OutlinedButton(
                        onClick = { state = UiState.Idle },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Try again")
                    }
                }
            }
        }
    }
}

@Composable
private fun ThumbnailPreview(thumbnail: Bitmap?) {
    if (thumbnail != null) {
        Image(
            bitmap = thumbnail.asImageBitmap(),
            contentDescription = "Preview",
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxWidth()
                .height(160.dp)
                .clip(RoundedCornerShape(12.dp))
        )
    }
}

@Composable
private fun VideoModeToggle(convertToGif: Boolean, onChange: (Boolean) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Output", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            val shape = RoundedCornerShape(14.dp)
            val modifier = Modifier.weight(1f).height(48.dp)
            if (!convertToGif) {
                Button(onClick = { onChange(false) }, shape = shape, modifier = modifier) { Text("Compress video") }
                OutlinedButton(onClick = { onChange(true) }, shape = shape, modifier = modifier) { Text("Convert to GIF") }
            } else {
                OutlinedButton(onClick = { onChange(false) }, shape = shape, modifier = modifier) { Text("Compress video") }
                Button(onClick = { onChange(true) }, shape = shape, modifier = modifier) { Text("Convert to GIF") }
            }
        }
    }
}

/**
 * Default video preview: a playable video with a play/pause button. When [trimEndMs] is smaller
 * than the full video, playback loops within [trimStartMs]..[trimEndMs] so the user can preview
 * exactly the clip that will be converted; otherwise it just plays from the start.
 */
@Composable
private fun VideoPreviewPlayer(uri: Uri, trimStartMs: Long, trimEndMs: Long) {
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
            kotlinx.coroutines.delay(50)
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
                    .background(Color.Black.copy(alpha = 0.45f), shape = androidx.compose.foundation.shape.CircleShape)
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

/**
 * TikTok-stitch-style trim control: a filmstrip of thumbnails spanning the whole video with a
 * draggable bracketed window over it (dimmed outside the selection). Trims both ends, capped at
 * [VideoToGifConverter.MAX_DURATION_MS].
 */
@Composable
private fun VideoTrimFilmstrip(
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

@Composable
private fun PresetButtons(enabled: Boolean, onPick: (Preset) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Target size", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        Preset.entries.forEach { preset ->
            Button(
                onClick = { onPick(preset) },
                enabled = enabled,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(preset.label)
                    Text(preset.short, style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

/** Log-scale slider from 1MB to 2GB, since a linear slider can't usefully address 3 orders of magnitude. */
@Composable
private fun CustomSizeSlider(
    fraction: Float,
    onFractionChange: (Float) -> Unit,
    enabled: Boolean,
    onCompress: (Long, String) -> Unit
) {
    val minLog = log10(CUSTOM_MIN_BYTES.toDouble())
    val maxLog = log10(CUSTOM_MAX_BYTES.toDouble())
    val bytes = 10.0.pow(minLog + fraction * (maxLog - minLog)).roundToLong()
        .coerceIn(CUSTOM_MIN_BYTES, CUSTOM_MAX_BYTES)

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
        Text("Custom size", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        Text(formatSize(bytes), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Slider(
            value = fraction,
            onValueChange = onFractionChange,
            enabled = enabled
        )
        OutlinedButton(
            onClick = { onCompress(bytes, "Custom (${formatSize(bytes)})") },
            enabled = enabled,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
        ) {
            Text("Compress to ${formatSize(bytes)}")
        }
    }
}

@Composable
private fun InfoCard(label: String, size: Long) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(formatSize(size), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun WorkingCard(message: String, progress: Float, indeterminate: Boolean) {
    val animatedProgress by animateFloatAsState(targetValue = progress, label = "progress")
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (indeterminate) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    CircularProgressIndicator(
                        progress = { animatedProgress },
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp
                    )
                }
                Text(message, style = MaterialTheme.typography.bodyMedium)
            }
            if (!indeterminate) {
                LinearProgressIndicator(
                    progress = { animatedProgress },
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    "${(animatedProgress * 100).toInt()}%",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun ResultCard(originalBytes: Long, resultBytes: Long, fitsTarget: Boolean, targetLabel: String) {
    val reduction = if (originalBytes > 0) (1.0 - resultBytes.toDouble() / originalBytes.toDouble()) * 100 else 0.0
    val accent = if (fitsTarget) Color(0xFF2E7D32) else Color(0xFFC62828)

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = accent.copy(alpha = 0.10f))
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(
                    if (fitsTarget) Icons.Filled.CheckCircle else Icons.Filled.Error,
                    contentDescription = null,
                    tint = accent
                )
                Text(
                    if (fitsTarget) "Fits under $targetLabel" else "Still over $targetLabel",
                    style = MaterialTheme.typography.titleMedium,
                    color = accent,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Text(
                "${formatSize(resultBytes)}  (down %.0f%% from ${formatSize(originalBytes)})".format(reduction),
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

@Composable
private fun ErrorCard(message: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFC62828).copy(alpha = 0.10f))
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Filled.Error, contentDescription = null, tint = Color(0xFFC62828))
                Text("Compression failed", style = MaterialTheme.typography.titleMedium, color = Color(0xFFC62828))
            }
            Text(message, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HistoryDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val entries = remember { HistoryStore.getEntries(context) }
    val dateFormat = remember { SimpleDateFormat("MMM d, h:mm a", Locale.getDefault()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            OutlinedButton(onClick = onDismiss) { Text("Close") }
        },
        title = { Text("History") },
        text = {
            if (entries.isEmpty()) {
                Text("No compressions yet.", style = MaterialTheme.typography.bodyMedium)
            } else {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    entries.forEach { e ->
                        Column {
                            Text(e.fileName, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "${formatSize(e.originalBytes)} → ${formatSize(e.resultBytes)}  ·  ${e.targetLabel}  ·  ${if (e.fitsTarget) "fit" else "over"}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                dateFormat.format(Date(e.timestampMs)),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        HorizontalDivider()
                    }
                }
            }
        }
    )
}

fun queryDisplayName(resolver: ContentResolver, uri: Uri): String? {
    return resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
    }
}

private fun queryVideoDurationMs(context: android.content.Context, uri: Uri): Long {
    val retriever = MediaMetadataRetriever()
    return try {
        retriever.setDataSource(context, uri)
        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
    } catch (e: Exception) {
        0L
    } finally {
        retriever.release()
    }
}

private fun decodeThumbnail(resolver: ContentResolver, uri: Uri, mime: String): Bitmap? {
    return try {
        if (mime.startsWith("video")) {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(resolver.openFileDescriptor(uri, "r")?.fileDescriptor)
                retriever.getFrameAtTime(0)
            } finally {
                retriever.release()
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            resolver.loadThumbnail(uri, Size(384, 384), null)
        } else {
            resolver.openInputStream(uri)?.use { input ->
                val opts = BitmapFactory.Options().apply { inSampleSize = 4 }
                BitmapFactory.decodeStream(input, null, opts)
            }
        }
    } catch (e: Exception) {
        null
    }
}

private fun formatSize(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB")
    var value = bytes.toDouble()
    var unitIndex = 0
    while (value >= 1024 && unitIndex < units.lastIndex) {
        value /= 1024
        unitIndex++
    }
    return "%.1f %s".format(value, units[unitIndex])
}
