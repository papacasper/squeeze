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
        val suggestedName: String
    ) : UiState()
    data class Failed(val message: String) : UiState()
    data class Downloading(val message: String, val progress: Float) : UiState()
}

/**
 * Owns everything [CompressorScreen] renders. Compression and downloads run in foreground
 * services; this mirrors their shared repositories into [state] and holds the picker/trim inputs.
 */
internal class CompressorViewModel(application: Application) : AndroidViewModel(application) {

    var state by mutableStateOf<UiState>(UiState.Idle)
        private set
    var customSliderFraction by mutableStateOf(0.3f)
    var convertToGif by mutableStateOf(false)
    var videoDurationMs by mutableStateOf(0L)
        private set
    var trimStartMs by mutableStateOf(0L)
    var trimEndMs by mutableStateOf(0L)
    var downloadUrl by mutableStateOf("")

    private var workingThumbnail: Bitmap? = null
    private var handledInitialUri: Uri? = null

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

    /** Handles the Uri the app was launched/shared with, once. */
    fun onInitialUri(uri: Uri?) {
        if (uri == null || uri == handledInitialUri) return
        handledInitialUri = uri
        selectFile(uri)
    }

    fun selectFile(uri: Uri) {
        val context = getApplication<Application>()
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
                trimEndMs = duration.coerceAtMost(VideoToGifConverter.MAX_DURATION_MS)
            }
        }
    }

    /** Starts the compression service for [file]; false if one is already running. */
    fun startCompression(file: UiState.FileSelected, targetBytes: Long, targetLabel: String): Boolean {
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
            convertToGif, trimStartMs, trimEndMs - trimStartMs
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
