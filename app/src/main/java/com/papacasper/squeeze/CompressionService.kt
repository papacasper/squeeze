package com.papacasper.squeeze

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

sealed class CompressionState {
    data object Idle : CompressionState()
    data class Working(
        val originalBytes: Long,
        val message: String,
        val progress: Float,
        val indeterminate: Boolean,
        val uri: Uri,
        val mime: String
    ) : CompressionState()
    data class Done(
        val originalBytes: Long,
        val resultFile: File,
        val mime: String,
        val fitsTarget: Boolean,
        val targetLabel: String,
        val suggestedName: String
    ) : CompressionState()
    data class Failed(val message: String) : CompressionState()
}

/** Shared across the Activity and [CompressionService] so compression survives the app backgrounding. */
object CompressionRepository {
    val state = MutableStateFlow<CompressionState>(CompressionState.Idle)
}

class CompressionService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            job?.cancel()
            return START_NOT_STICKY
        }

        val uri: Uri = intent?.getParcelableExtra(EXTRA_URI) ?: return START_NOT_STICKY
        val mime = intent.getStringExtra(EXTRA_MIME) ?: return START_NOT_STICKY
        val originalBytes = intent.getLongExtra(EXTRA_ORIGINAL_BYTES, 0L)
        val targetBytes = intent.getLongExtra(EXTRA_TARGET_BYTES, 0L)
        val targetLabel = intent.getStringExtra(EXTRA_TARGET_LABEL) ?: ""
        val toGif = intent.getBooleanExtra(EXTRA_TO_GIF, false)
        val trimStartMs = intent.getLongExtra(EXTRA_TRIM_START, 0L)
        val trimDurationMs = intent.getLongExtra(EXTRA_TRIM_DURATION, 0L)

        start(uri, mime, originalBytes, targetBytes, targetLabel, toGif, trimStartMs, trimDurationMs)
        return START_NOT_STICKY
    }

    private fun start(
        uri: Uri,
        mime: String,
        originalBytes: Long,
        targetBytes: Long,
        targetLabel: String,
        toGif: Boolean,
        trimStartMs: Long,
        trimDurationMs: Long
    ) {
        createChannel()
        val isVideo = mime.startsWith("video")
        val isGif = mime == "image/gif"
        val videoToGif = isVideo && toGif

        // startForegroundService() obliges us to call startForeground() even if we then refuse the job.
        startForeground(NOTIF_ID, buildNotification("Starting compression...", 0, indeterminate = true))
        if (job?.isActive == true) return
        CompressionRepository.state.value = CompressionState.Working(
            originalBytes, "Starting compression...", 0f, indeterminate = !isVideo && !isGif, uri = uri, mime = mime
        )

        job = serviceScope.launch {
            try {
                val outDir = File(cacheDir, "compressed").apply {
                    deleteRecursively()
                    mkdirs()
                }
                val outFile = File(
                    outDir,
                    when {
                        videoToGif -> "converted.gif"
                        isVideo -> "compressed.mp4"
                        isGif -> "compressed.gif"
                        else -> "compressed.jpg"
                    }
                )
                val resultMime = when {
                    videoToGif -> "image/gif"
                    isVideo -> "video/mp4"
                    isGif -> "image/gif"
                    else -> "image/jpeg"
                }

                fun onProgress(msg: String, fraction: Float) {
                    CompressionRepository.state.value = CompressionState.Working(
                        originalBytes, msg, fraction, indeterminate = false, uri = uri, mime = mime
                    )
                    notify(buildNotification(msg, (fraction * 100).toInt(), indeterminate = false))
                }

                withContext(Dispatchers.IO) {
                    when {
                        videoToGif -> VideoToGifConverter.convert(applicationContext, uri, targetBytes, outFile, trimStartMs, trimDurationMs) { msg, fraction ->
                            onProgress(msg, fraction)
                        }
                        isVideo -> VideoCompressor.compress(applicationContext, uri, targetBytes, outFile) { msg, fraction ->
                            onProgress(msg, fraction)
                        }
                        isGif -> GifCompressor.compress(applicationContext, uri, targetBytes, outFile) { msg, fraction ->
                            onProgress(msg, fraction)
                        }
                        else -> {
                            onProgress("Compressing image...", 0f)
                            ImageCompressor.compress(applicationContext, uri, targetBytes, outFile)
                        }
                    }
                }

                val fits = outFile.length() <= targetBytes
                CompressionRepository.state.value = CompressionState.Done(
                    originalBytes = originalBytes,
                    resultFile = outFile,
                    mime = resultMime,
                    fitsTarget = fits,
                    targetLabel = targetLabel,
                    suggestedName = squeezedName(queryDisplayName(contentResolver, uri), outFile)
                )
                notify(buildNotification(if (fits) "Done — fits under $targetLabel" else "Done — still over $targetLabel", 100, indeterminate = false))

                HistoryStore.addEntry(
                    applicationContext,
                    HistoryEntry(
                        timestampMs = System.currentTimeMillis(),
                        fileName = queryDisplayName(contentResolver, uri) ?: outFile.name,
                        originalBytes = originalBytes,
                        resultBytes = outFile.length(),
                        targetLabel = targetLabel,
                        fitsTarget = fits
                    )
                )
            } catch (e: CancellationException) {
                CompressionRepository.state.value = CompressionState.Idle
            } catch (e: OutOfMemoryError) {
                CompressionRepository.state.value = CompressionState.Failed(
                    "Not enough memory to process this file. Try a smaller or shorter one."
                )
            } catch (e: Exception) {
                CompressionRepository.state.value = CompressionState.Failed(e.message ?: "Unknown error during compression")
            } finally {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        job?.cancel()
        serviceScope.cancel()
    }

    private fun notify(notification: Notification) {
        val manager = getSystemService(NotificationManager::class.java)
        manager?.notify(NOTIF_ID, notification)
    }

    private fun buildNotification(text: String, progress: Int, indeterminate: Boolean): Notification {
        val openIntent = packageManager.getLaunchIntentForPackage(packageName)
        val contentPendingIntent = openIntent?.let {
            PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE)
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Squeeze")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, progress, indeterminate)
            .setContentIntent(contentPendingIntent)
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Compression progress", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }

    companion object {
        private const val CHANNEL_ID = "compression"
        private const val NOTIF_ID = 42

        const val ACTION_CANCEL = "com.papacasper.squeeze.action.CANCEL"
        private const val EXTRA_URI = "uri"
        private const val EXTRA_MIME = "mime"
        private const val EXTRA_ORIGINAL_BYTES = "original_bytes"
        private const val EXTRA_TARGET_BYTES = "target_bytes"
        private const val EXTRA_TARGET_LABEL = "target_label"
        private const val EXTRA_TO_GIF = "to_gif"
        private const val EXTRA_TRIM_START = "trim_start"
        private const val EXTRA_TRIM_DURATION = "trim_duration"

        fun start(
            context: Context,
            uri: Uri,
            mime: String,
            originalBytes: Long,
            targetBytes: Long,
            targetLabel: String,
            toGif: Boolean,
            trimStartMs: Long,
            trimDurationMs: Long
        ) {
            val intent = Intent(context, CompressionService::class.java)
                .putExtra(EXTRA_URI, uri)
                .putExtra(EXTRA_MIME, mime)
                .putExtra(EXTRA_ORIGINAL_BYTES, originalBytes)
                .putExtra(EXTRA_TARGET_BYTES, targetBytes)
                .putExtra(EXTRA_TARGET_LABEL, targetLabel)
                .putExtra(EXTRA_TO_GIF, toGif)
                .putExtra(EXTRA_TRIM_START, trimStartMs)
                .putExtra(EXTRA_TRIM_DURATION, trimDurationMs)
            context.startForegroundService(intent)
        }

        fun cancel(context: Context) {
            val intent = Intent(context, CompressionService::class.java).setAction(ACTION_CANCEL)
            context.startService(intent)
        }
    }
}
