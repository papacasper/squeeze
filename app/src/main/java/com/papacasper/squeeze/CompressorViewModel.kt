package com.papacasper.squeeze

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

internal sealed class UiState {
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
        val suggestedName: String,
        val extraFiles: List<File> = emptyList(),
        val settings: String = ""
    ) : UiState()
    data class Failed(val message: String) : UiState()
    data class Downloading(val message: String, val progress: Float) : UiState()
    /** Copying a cloud-backed file (e.g. from Google Photos) to local storage; progress < 0 means unknown. */
    data class Importing(val message: String, val progress: Float) : UiState()
}

/**
 * Owns everything [CompressorScreen] renders. Compression and downloads run in foreground
 * services; this mirrors their shared repositories into [state] and holds the picker/trim inputs.
 */
internal class CompressorViewModel(application: Application) : AndroidViewModel(application) {

    var state by mutableStateOf<UiState>(UiState.Idle)
        private set
    var customSliderFraction by mutableStateOf(TargetStore.loadFraction(application, 0.3f))
    var lastPreset by mutableStateOf(TargetStore.lastPreset(application))
    var convertToGif by mutableStateOf(false)
        private set
    var videoDurationMs by mutableStateOf(0L)
        private set
    var trimStartMs by mutableStateOf(0L)
        private set
    var trimEndMs by mutableStateOf(0L)
        private set
    var downloadUrl by mutableStateOf("")
    /** Lowest quality the user accepts; persisted so it survives restarts. */
    var floors by mutableStateOf(FloorsStore.load(application))
        private set

    /** Off by default: splitting changes what the user gets (several files), so it is opt-in. */
    var splitLongVideos by mutableStateOf(false)

    /** Parts the current selection would be cut into for [targetBytes] (1 = no split). */
    fun partsFor(targetBytes: Long, originalBytes: Long): Int {
        val probe = sourceProbe ?: return 1
        if (!splitLongVideos || convertToGif) return 1
        val ms = (trimEndMs - trimStartMs).takeIf { it > 0 } ?: videoDurationMs
        if (ms <= 0) return 1
        return BitrateMath.partsNeeded(targetBytes, ms / 1000.0, probe.audioBitrate, probe.pixels, probe.height, originalBytes, floors)
    }

    fun rememberTarget(preset: Preset?) {
        lastPreset = preset
        TargetStore.save(getApplication(), preset, customSliderFraction)
    }

    fun updateFloors(new: BitrateMath.Floors) {
        floors = new
        FloorsStore.save(getApplication(), new)
    }
    /** Audio/resolution facts for the feasibility hints; null for images or unreadable sources. */
    var sourceProbe by mutableStateOf<SourceProbe?>(null)
        private set

    private var workingThumbnail: Bitmap? = null
    private var handledInitialUri: Uri? = null
    private var handledInitialUrl: String? = null

    init {
        // Done/Failed are one-shot results: once shown they're reset to Idle so a later
        // launch or recomposition doesn't replay them.
        viewModelScope.launch {
            CompressionRepository.state.collect { s ->
                when (s) {
                    is CompressionState.Working -> state = UiState.Working(
                        s.originalBytes, s.message, s.progress, s.indeterminate, workingThumbnail, s.uri, s.mime
                    )
                    is CompressionState.Done -> {
                        if (s.resultFile.exists()) {
                            state = UiState.Done(
                                s.originalBytes, s.resultFile, s.mime, s.fitsTarget, s.targetLabel, s.suggestedName, s.extraFiles, s.settings
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
        // A finished download drops straight into the normal file-selected/compress flow.
        viewModelScope.launch {
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
    }

    /** A link shared into the app pre-fills the download field; the user still taps Download. */
    fun onInitialUrl(url: String?) {
        if (url == null || url == handledInitialUrl) return
        handledInitialUrl = url
        downloadUrl = url
    }

    /** Handles the Uri the app was launched/shared with, once. */
    fun onInitialUri(uri: Uri?) {
        if (uri == null || uri == handledInitialUri) return
        handledInitialUri = uri
        selectFile(uri)
    }

    private var importJob: kotlinx.coroutines.Job? = null

    fun cancelImport() {
        importJob?.cancel()
        importJob = null
        if (state is UiState.Importing) state = UiState.Idle
    }

    fun selectFile(uri: Uri) {
        val context = getApplication<Application>()
        importJob?.cancel()
        // Provider calls can block for a long time (cloud-backed files), so nothing here runs on the main thread.
        importJob = viewModelScope.launch {
            val mime: String
            var local = uri
            var size: Long
            try {
                mime = withContext(Dispatchers.IO) { context.contentResolver.getType(uri) } ?: ""
                if (withContext(Dispatchers.IO) { FileImport.needsImport(context, uri) }) {
                    state = UiState.Importing("Copying from the source app...", -1f)
                    local = withContext(Dispatchers.IO) {
                        FileImport.importToCache(context, uri) { fraction ->
                            state = UiState.Importing(
                                if (fraction >= 0f) "Copying from the source app... ${(fraction * 100).toInt()}%" else "Copying from the source app...",
                                fraction
                            )
                        }
                    }
                }
                // A shared/picked Uri can be unreadable (permission revoked, provider gone): show an error, don't crash.
                size = withContext(Dispatchers.IO) {
                    context.contentResolver.openAssetFileDescriptor(local, "r")?.use { it.length } ?: 0L
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                state = UiState.Failed(
                    if (e is java.io.IOException && e.message != null) e.message!!
                    else "Couldn't open that file (${e.javaClass.simpleName}). Try picking it again."
                )
                return@launch
            }
            finishSelect(local, mime, size)
        }
    }

    private fun finishSelect(uri: Uri, mime: String, size: Long) {
        val context = getApplication<Application>()
        convertToGif = false
        videoDurationMs = 0L
        sourceProbe = null
        trimStartMs = 0L
        trimEndMs = 0L
        state = UiState.FileSelected(uri, mime, size, thumbnail = null)
        viewModelScope.launch {
            val thumb = withContext(Dispatchers.IO) { decodeThumbnail(context.contentResolver, uri, mime) }
            val current = state
            if (current is UiState.FileSelected && current.uri == uri) {
                state = current.copy(thumbnail = thumb)
            }
        }
        if (mime.startsWith("video")) {
            viewModelScope.launch {
                val duration = withContext(Dispatchers.IO) { queryVideoDurationMs(context, uri) }
                videoDurationMs = duration
                trimEndMs = duration
                sourceProbe = withContext(Dispatchers.IO) { VideoCompressor.probe(context, uri) }
            }
        }
    }

    /** GIF output is capped at [VideoToGifConverter.MAX_DURATION_MS], so switching to it shortens the window. */
    fun setGifMode(enabled: Boolean) {
        convertToGif = enabled
        if (enabled) setTrim(trimStartMs, trimEndMs)
    }

    fun setTrim(startMs: Long, endMs: Long) {
        val (start, end) = if (convertToGif) {
            TrimMath.capLength(startMs, endMs, VideoToGifConverter.MAX_DURATION_MS)
        } else {
            startMs to endMs
        }
        trimStartMs = start
        trimEndMs = end
    }

    /** Starts the compression service for [file]; false if one is already running. */
    fun startCompression(file: UiState.FileSelected, targetBytes: Long, targetLabel: String): Boolean {
        val parts = if (file.mime.startsWith("video")) partsFor(targetBytes, file.originalBytes) else 1
        if (CompressionRepository.state.value is CompressionState.Working) return false
        val isVideo = file.mime.startsWith("video")
        val isGif = file.mime == "image/gif"
        workingThumbnail = file.thumbnail
        state = UiState.Working(
            file.originalBytes,
            "Starting compression...",
            0f,
            indeterminate = !isVideo && !isGif,
            thumbnail = file.thumbnail,
            uri = file.uri,
            mime = file.mime
        )
        CompressionService.start(
            getApplication(), file.uri, file.mime, file.originalBytes, targetBytes, targetLabel,
            convertToGif, trimStartMs, trimEndMs - trimStartMs,
            trimmed = TrimMath.isTrimmed(trimStartMs, trimEndMs, videoDurationMs),
            floors = floors,
            parts = parts
        )
        return true
    }

    fun startDownload() {
        DownloadService.start(getApplication(), downloadUrl.trim())
        downloadUrl = ""
    }

    fun reset() {
        state = UiState.Idle
    }
}
