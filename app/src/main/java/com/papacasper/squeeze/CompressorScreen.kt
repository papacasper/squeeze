package com.papacasper.squeeze

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
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel

private const val SKIP_COMPRESSION_THRESHOLD_BYTES = 20L * 1024 * 1024

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CompressorScreen(initialUri: Uri? = null) {
    val context = LocalContext.current
    val vm: CompressorViewModel = viewModel()
    val state = vm.state
    var showHistory by remember { mutableStateOf(false) }

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

    LaunchedEffect(initialUri) { vm.onInitialUri(initialUri) }

    val pickLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) vm.selectFile(uri)
    }

    fun runCompression(file: UiState.FileSelected, targetBytes: Long, targetLabel: String) {
        if (!vm.startCompression(file, targetBytes, targetLabel)) {
            toast("A compression is already running")
            return
        }
        if (Build.VERSION.SDK_INT >= 33) {
            notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    if (showHistory) {
        HistoryDialog(onDismiss = { showHistory = false })
    }

    // Without this, Android's back gesture/button exits the app entirely from any screen
    // (file selected, working, done, failed) since there's no navigation back stack here.
    // Compression itself keeps running in CompressionService regardless, so just returning
    // to the picker is safe even mid-compression.
    BackHandler(enabled = state !is UiState.Idle) { vm.reset() }

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
                        value = vm.downloadUrl,
                        onValueChange = { vm.downloadUrl = it },
                        label = { Text("Video URL") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedButton(
                        onClick = { vm.startDownload() },
                        enabled = vm.downloadUrl.isNotBlank(),
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
                    val needsTrim = vm.convertToGif && vm.videoDurationMs > VideoToGifConverter.MAX_DURATION_MS
                    if (s.mime.startsWith("video")) {
                        VideoPreviewPlayer(
                            uri = s.uri,
                            trimStartMs = if (needsTrim) vm.trimStartMs else 0L,
                            trimEndMs = if (needsTrim) vm.trimEndMs else vm.videoDurationMs.coerceAtLeast(1L)
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
                            convertToGif = vm.convertToGif,
                            onChange = { vm.convertToGif = it }
                        )
                    }
                    if (needsTrim) {
                        VideoTrimFilmstrip(
                            uri = s.uri,
                            durationMs = vm.videoDurationMs,
                            trimStartMs = vm.trimStartMs,
                            trimEndMs = vm.trimEndMs,
                            onTrimChange = { start, end -> vm.trimStartMs = start; vm.trimEndMs = end }
                        )
                    }
                    PresetButtons(enabled = true) { preset ->
                        runCompression(s, preset.bytes, "${preset.label} (${preset.short})")
                    }
                    CustomSizeSlider(
                        fraction = vm.customSliderFraction,
                        onFractionChange = { vm.customSliderFraction = it },
                        enabled = true,
                        onCompress = { bytes, label ->
                            runCompression(s, bytes, label)
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
                        onClick = { vm.reset() },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Compress another file")
                    }
                }

                is UiState.Failed -> {
                    ErrorCard(message = s.message)
                    OutlinedButton(
                        onClick = { vm.reset() },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Try again")
                    }
                }
            }
        }
    }
}

