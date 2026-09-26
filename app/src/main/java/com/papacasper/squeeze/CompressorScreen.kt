package com.papacasper.squeeze

import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private const val SKIP_COMPRESSION_THRESHOLD_BYTES = 20L * 1024 * 1024

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
    data class Done(
        val originalBytes: Long,
        val resultFile: File,
        val mime: String,
        val fitsTarget: Boolean,
        val targetLabel: String,
        val suggestedName: String
    ) : UiState()
    data class Failed(val message: String) : UiState()
    data class Downloading(val message: String, val progress: Float) : UiState()
}

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
    var downloadUrl by remember { mutableStateOf("") }

    fun toast(message: String) = android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_SHORT).show()
    fun saveOrToast(action: () -> Unit) {
        try {
            action()
        } catch (e: Exception) {
            toast("Couldn't save: ${e.message ?: "unknown error"}")
        }
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    // Compression runs in CompressionService (a foreground service) so it survives the app
    // being backgrounded or its process being reclaimed; this just mirrors that shared state
    // into the local UiState the screen renders. Done/Failed are one-shot results: once shown
    // they're reset to Idle so a later launch or recomposition doesn't replay them.
    LaunchedEffect(Unit) {
        CompressionRepository.state.collect { s ->
            when (s) {
                is CompressionState.Working -> state = UiState.Working(
                    s.originalBytes, s.message, s.progress, s.indeterminate, workingThumbnail, s.uri, s.mime
                )
                is CompressionState.Done -> {
                    if (s.resultFile.exists()) {
                        state = UiState.Done(
                            s.originalBytes, s.resultFile, s.mime, s.fitsTarget, s.targetLabel, s.suggestedName
                        )
                    }
                    CompressionRepository.state.compareAndSet(s, CompressionState.Idle)
                }
                is CompressionState.Failed -> {
                    state = UiState.Failed(s.message)
                    CompressionRepository.state.compareAndSet(s, CompressionState.Idle)
                }
                CompressionState.Idle -> if (state is UiState.Working) state = UiState.Idle
            }
        }
    }

    fun selectFile(uri: Uri) {
        val mime = context.contentResolver.getType(uri) ?: ""
        // A shared/picked Uri can be unreadable (permission revoked, provider gone): show an error, don't crash.
        val size = try {
            context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: 0L
        } catch (e: Exception) {
            state = UiState.Failed("Couldn't open that file (${e.javaClass.simpleName}). Try picking it again.")
            return
        }
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

    // Downloading runs in DownloadService (a foreground service), same pattern as compression.
    // On success, the downloaded file drops straight into the normal file-selected/compress flow.
    LaunchedEffect(Unit) {
        DownloadRepository.state.collect { s ->
            when (s) {
                is DownloadState.Working -> state = UiState.Downloading(s.message, s.progress)
                is DownloadState.Done -> {
                    selectFile(s.uri)
                    DownloadRepository.state.compareAndSet(s, DownloadState.Idle)
                }
                is DownloadState.Failed -> {
                    state = UiState.Failed(s.message)
                    DownloadRepository.state.compareAndSet(s, DownloadState.Idle)
                }
                DownloadState.Idle -> if (state is UiState.Downloading) state = UiState.Idle
            }
        }
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
        if (CompressionRepository.state.value is CompressionState.Working) {
            toast("A compression is already running")
            return
        }
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
            val isWorking = state is UiState.Working || state is UiState.Downloading

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
                    OutlinedTextField(
                        value = downloadUrl,
                        onValueChange = { downloadUrl = it },
                        label = { Text("Video URL") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedButton(
                        onClick = {
                            DownloadService.start(context, downloadUrl.trim())
                            downloadUrl = ""
                        },
                        enabled = downloadUrl.isNotBlank(),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp)
                    ) {
                        Icon(Icons.Filled.FileDownload, contentDescription = null, modifier = Modifier.size(20.dp))
                        Text("  Download")
                    }
                }

                is UiState.Downloading -> {
                    DownloadingCard(
                        message = s.message,
                        progress = s.progress,
                        onCancel = { DownloadService.cancel(context) }
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
                    if (s.originalBytes <= SKIP_COMPRESSION_THRESHOLD_BYTES) {
                        Text(
                            "Already under ${formatSize(SKIP_COMPRESSION_THRESHOLD_BYTES)} — compression is optional",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                        OutlinedButton(
                            onClick = {
                                val name = queryDisplayName(context.contentResolver, s.uri) ?: "squeeze_original"
                                saveOrToast {
                                    SaveUtils.saveUriToDownloads(context, s.uri, name, s.mime)
                                    toast("Saved to Downloads/Squeeze")
                                }
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Filled.FileDownload, contentDescription = null, modifier = Modifier.size(18.dp))
                            Text("  Save as-is")
                        }
                        OutlinedButton(
                            onClick = {
                                val name = queryDisplayName(context.contentResolver, s.uri) ?: "squeeze_original"
                                saveOrToast {
                                    val savedUri = SaveUtils.saveUriToDownloads(context, s.uri, name, s.mime)
                                    context.startActivity(
                                        android.content.Intent.createChooser(
                                            SaveUtils.shareIntent(savedUri, s.mime),
                                            "Share file"
                                        )
                                    )
                                }
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Filled.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                            Text("  Share as-is")
                        }
                    }
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
                                saveOrToast {
                                    SaveUtils.saveToDownloads(context, s.resultFile, s.suggestedName, s.mime)
                                    toast("Saved to Downloads/Squeeze")
                                }
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Filled.FileDownload, contentDescription = null, modifier = Modifier.size(18.dp))
                            Text("  Save")
                        }
                        Button(
                            onClick = {
                                saveOrToast {
                                    val uri = SaveUtils.saveToDownloads(context, s.resultFile, s.suggestedName, s.mime)
                                    context.startActivity(
                                        android.content.Intent.createChooser(
                                            SaveUtils.shareIntent(uri, s.mime),
                                            "Share compressed file"
                                        )
                                    )
                                }
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

