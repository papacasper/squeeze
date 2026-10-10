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
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
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
fun CompressorScreen(initialUri: Uri? = null, initialUris: List<Uri> = emptyList(), initialUrl: String? = null, quickShare: Boolean = false) {
    val context = LocalContext.current
    val vm: CompressorViewModel = viewModel()
    val state = vm.state
    var showHistory by remember { mutableStateOf(false) }
    var showAbout by remember { mutableStateOf(false) }
    var showAccounts by remember { mutableStateOf(false) }

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

    LaunchedEffect(quickShare) { if (quickShare) vm.autoCompress = true }
    LaunchedEffect(initialUri) { vm.onInitialUri(initialUri) }
    LaunchedEffect(initialUris) { vm.onInitialUris(initialUris) }
    LaunchedEffect(initialUrl) { vm.onInitialUrl(initialUrl) }

    val pickLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        vm.selectFiles(uris)
    }

    fun notifyPermission() {
        if (Build.VERSION.SDK_INT >= 33) {
            notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
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

    // A target the video can't reach is stopped before it costs minutes of encoding; see [UnreachableDialog].
    var unreachable by remember { mutableStateOf<UnreachableTarget?>(null) }

    fun requestCompression(file: UiState.FileSelected, targetBytes: Long, targetLabel: String) {
        val a = if (file.mime.startsWith("video") && vm.partsFor(targetBytes, file.originalBytes) == 1) {
            vm.assessTarget(targetBytes, file.originalBytes)
        } else null
        if (a?.level == BitrateMath.Feasibility.UNREACHABLE) {
            unreachable = UnreachableTarget(
                file, targetBytes, targetLabel, a.minBytes,
                parts = vm.partsToFit(targetBytes, file.originalBytes),
                lowerHeight = FloorsStore.HEIGHTS.filter { it < vm.floors.minHeight }.maxOrNull()
            )
            return
        }
        runCompression(file, targetBytes, targetLabel)
    }

    QuickShareEffect(vm, state, onToast = ::toast, onFile = ::requestCompression) { files, bytes, label ->
        if (vm.startBatch(files, bytes, label)) notifyPermission()
    }

    unreachable?.let { u ->
        UnreachableDialog(
            u,
            minHeight = vm.floors.minHeight,
            onSplit = {
                unreachable = null
                vm.splitLongVideos = true
                runCompression(u.file, u.targetBytes, u.targetLabel)
            },
            onLowerFloor = { h ->
                unreachable = null
                vm.updateFloors(vm.floors.copy(minHeight = h))
                requestCompression(u.file, u.targetBytes, u.targetLabel)
            },
            onAnyway = {
                unreachable = null
                runCompression(u.file, u.targetBytes, u.targetLabel)
            },
            onDismiss = { unreachable = null }
        )
    }

    if (showAbout) AboutDialog(onDismiss = { showAbout = false })
    if (showHistory) HistoryDialog(onDismiss = { showHistory = false })
    if (showAccounts) AccountsDialog(onDismiss = { showAccounts = false })

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
                    IconButton(onClick = { showAccounts = true }) {
                        Icon(Icons.Filled.AccountCircle, contentDescription = "Site logins", tint = MaterialTheme.colorScheme.onPrimary)
                    }
                    IconButton(onClick = { showAbout = true }) {
                        Icon(Icons.Filled.Info, contentDescription = "About and licenses", tint = MaterialTheme.colorScheme.onPrimary)
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
            val isWorking = state is UiState.Working || state is UiState.Downloading || state is UiState.Importing

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
                    vm.updateAvailable?.let { v -> UpdateBanner(v) { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, Uri.parse(UpdateCheck.RELEASES_PAGE))) } }
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

                is UiState.Importing -> {
                    DownloadingCard(
                        message = s.message,
                        progress = s.progress.coerceAtLeast(0f),
                        onCancel = { vm.cancelImport() }
                    )
                }

                is UiState.FileSelected -> {
                    val canTrim = s.mime.startsWith("video") && vm.videoDurationMs > 2 * TrimMath.MIN_CLIP_MS
                    if (s.mime.startsWith("video")) {
                        VideoPreviewPlayer(
                            uri = s.uri,
                            trimStartMs = if (canTrim) vm.trimStartMs else 0L,
                            trimEndMs = if (canTrim) vm.trimEndMs else vm.videoDurationMs.coerceAtLeast(1L)
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
                            onChange = { vm.setGifMode(it) }
                        )
                    }
                    if (canTrim) {
                        VideoTrimFilmstrip(
                            uri = s.uri,
                            durationMs = vm.videoDurationMs,
                            trimStartMs = vm.trimStartMs,
                            trimEndMs = vm.trimEndMs,
                            maxClipMs = if (vm.convertToGif) VideoToGifConverter.MAX_DURATION_MS else Long.MAX_VALUE,
                            onTrimChange = { start, end -> vm.setTrim(start, end) }
                        )
                    }
                    if (s.mime.startsWith("video") && !vm.convertToGif) {
                        FloorsCard(vm.floors) { vm.updateFloors(it) }
                        SplitToggle(vm.splitLongVideos) { vm.splitLongVideos = it }
                    }
                    PresetButtons(enabled = true, last = vm.lastPreset, hint = { preset ->
                        feasibilityHint(vm, s, preset.bytes)
                    }, info = { preset ->
                        estimateLine(vm, s, preset.bytes)
                    }) { preset ->
                        vm.rememberTarget(preset)
                        requestCompression(s, preset.bytes, "${preset.label} (${preset.short})")
                    }
                    CustomSizeSlider(
                        fraction = vm.customSliderFraction,
                        onFractionChange = { vm.customSliderFraction = it },
                        enabled = true,
                        onCompress = { bytes, label ->
                            vm.rememberTarget(null)
                            requestCompression(s, bytes, label)
                        }
                    )
                }

                is UiState.BatchSelected -> {
                    BatchFilesCard(s.files, s.skipped)
                    Text(
                        "Every file is squeezed on its own to fit under the size you pick.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (s.files.any { it.mime.startsWith("video") }) {
                        VideoModeToggle(vm.convertToGif) { vm.setGifMode(it) }
                        if (!vm.convertToGif) {
                            SplitToggle(vm.splitLongVideos) { vm.splitLongVideos = it }
                            FloorsCard(vm.floors) { vm.updateFloors(it) }
                        }
                    }
                    PresetButtons(enabled = true, last = vm.lastPreset) { preset ->
                        vm.rememberTarget(preset)
                        if (vm.startBatch(s.files, preset.bytes, "${preset.label} (${preset.short})")) notifyPermission()
                        else toast("A compression is already running")
                    }
                    CustomSizeSlider(
                        fraction = vm.customSliderFraction,
                        onFractionChange = { vm.customSliderFraction = it },
                        enabled = true,
                        onCompress = { bytes, label ->
                            vm.rememberTarget(null)
                            if (vm.startBatch(s.files, bytes, label)) notifyPermission()
                            else toast("A compression is already running")
                        }
                    )
                }

                is UiState.BatchDone -> {
                    BatchResultCard(s.items, s.targetLabel)
                    BatchDoneActions(
                        s.items,
                        onSave = { files -> saveOrToast { files.forEach { (f, name, mime) -> SaveUtils.saveToDownloads(context, f, name, mime) }; toast("Saved ${files.size} files to Downloads/Squeeze") } },
                        onShare = { files ->
                            saveOrToast {
                                val uris = files.map { (f, name, mime) -> SaveUtils.saveToDownloads(context, f, name, mime) }
                                context.startActivity(
                                    android.content.Intent.createChooser(SaveUtils.shareMultipleIntent(uris, BatchResult.shareMime(s.items)), "Share compressed files")
                                )
                            }
                        }
                    )
                    OutlinedButton(onClick = { vm.reset() }, modifier = Modifier.fillMaxWidth()) {
                        Text("Compress more files")
                    }
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
                    val allFiles = listOf(s.resultFile) + s.extraFiles
                    val names = allFiles.indices.map {
                        if (allFiles.size == 1) s.suggestedName else BitrateMath.partName(s.suggestedName, it, allFiles.size)
                    }
                    if (s.mime.startsWith("video")) {
                        allFiles.forEach { f ->
                            VideoPreviewPlayer(uri = android.net.Uri.fromFile(f), trimStartMs = 0L, trimEndMs = Long.MAX_VALUE)
                        }
                    }
                    InfoCard(label = "Original file", size = s.originalBytes)
                    if (s.settings.isNotEmpty()) {
                        Text(if (KeepOriginal.isKept(s.settings)) s.settings else "Made with: ${s.settings}", style = MaterialTheme.typography.bodyMedium)
                    }
                    if (allFiles.size > 1) {
                        Text("Split into ${allFiles.size} parts, each under ${s.targetLabel}", style = MaterialTheme.typography.bodyMedium)
                    }
                    ResultCard(
                        originalBytes = s.originalBytes,
                        resultBytes = allFiles.sumOf { it.length() },
                        fitsTarget = s.fitsTarget,
                        targetLabel = s.targetLabel
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                        Button(
                            onClick = {
                                saveOrToast {
                                    allFiles.forEachIndexed { i, f -> SaveUtils.saveToDownloads(context, f, names[i], s.mime) }
                                    toast(if (allFiles.size > 1) "Saved ${allFiles.size} parts to Downloads/Squeeze" else "Saved to Downloads/Squeeze")
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
                                    val uris = allFiles.mapIndexed { i, f -> SaveUtils.saveToDownloads(context, f, names[i], s.mime) }
                                    context.startActivity(
                                        android.content.Intent.createChooser(
                                            if (uris.size > 1) SaveUtils.shareMultipleIntent(uris, s.mime) else SaveUtils.shareIntent(uris[0], s.mime),
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
                    ErrorCard(message = s.message, title = s.title)
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


/** "About 720p · 24 fps · audio 64 kbps" for a target that is fine as is; null when there is nothing to say. */
private fun estimateLine(vm: CompressorViewModel, file: UiState.FileSelected, targetBytes: Long): String? {
    val probe = vm.sourceProbe ?: return null
    if (vm.convertToGif || !file.mime.startsWith("video")) return null
    val durationMs = (vm.trimEndMs - vm.trimStartMs).takeIf { it > 0 } ?: vm.videoDurationMs
    if (durationMs <= 0) return null
    val e = BitrateMath.estimate(
        targetBytes, durationMs / 1000.0, probe.audioBitrate, probe.pixels, probe.height, file.originalBytes, vm.floors
    )
    return "About " + BitrateMath.describe(e, vm.floors)
}
