package com.papacasper.squeeze

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.content.pm.ServiceInfo
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
        val suggestedName: String,
        /** Parts 2..n when the video was split; [resultFile] is part 1. */
        val extraFiles: List<File> = emptyList(),
        /** Resolution / frame rate / audio the winning pass used; empty for images and GIFs. */
        val settings: String = ""
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
    private var lastState: CompressionState.Working? = null

    // Bound (never used for calls) only so the UI process learns if this process dies mid-job.
    override fun onBind(intent: Intent?): IBinder? = android.os.Binder()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            job?.cancel()
            return START_NOT_STICKY
        }

        if (intent?.action == ACTION_SYNC) {
            lastState?.let { CompressionBridge.publish(applicationContext, it) }
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
        val trimmed = intent.getBooleanExtra(EXTRA_TRIMMED, false)
        val floors = BitrateMath.Floors(
            minHeight = intent.getIntExtra(EXTRA_MIN_HEIGHT, 720),
            minFps = intent.getFloatExtra(EXTRA_MIN_FPS, BitrateMath.MIN_FPS),
            minAudioBitrate = intent.getLongExtra(EXTRA_MIN_AUDIO, BitrateMath.AUDIO_STEPS.last())
        )
        val parts = intent.getIntExtra(EXTRA_PARTS, 1).coerceIn(1, BitrateMath.MAX_PARTS)

        start(uri, mime, originalBytes, targetBytes, targetLabel, toGif, trimStartMs, trimDurationMs, trimmed, floors, parts)
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
        trimDurationMs: Long,
        trimmed: Boolean,
        floors: BitrateMath.Floors,
        parts: Int
    ) {
        createChannel()
        val isVideo = mime.startsWith("video")
        val isGif = mime == "image/gif"
        val videoToGif = isVideo && toGif

        // startForegroundService() obliges us to call startForeground() even if we then refuse the job.
        // mediaProcessing (API 35+) is the type meant for transcoding; dataSync is time-capped there.
        val notification = buildNotification("Starting compression...", 0, indeterminate = true)
        when {
            // Platform call, not ServiceCompat: ServiceCompat.startForeground with this type crashed on-device ("FGS type none", core 1.16).
            Build.VERSION.SDK_INT >= 35 ->
                startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING)
            Build.VERSION.SDK_INT >= 29 ->
                startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            else -> startForeground(NOTIF_ID, notification)
        }
        if (job?.isActive == true) return
        publishState(CompressionState.Working(
            originalBytes, "Starting compression...", 0f, indeterminate = !isVideo && !isGif, uri = uri, mime = mime
        ))

        ExitDiagnostics.jobStarted(applicationContext, queryDisplayName(contentResolver, uri) ?: uri.lastPathSegment ?: "a file")
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
                // With parts, part 1 doubles as the primary result file.
                val partFiles = if (isVideo && parts > 1 && !videoToGif) {
                    List(parts) { File(outDir, "compressed-part${it + 1}.mp4") }
                } else listOf(outFile)
                val resultFile = partFiles.first()
                val resultMime = when {
                    videoToGif -> "image/gif"
                    isVideo -> "video/mp4"
                    isGif -> "image/gif"
                    else -> "image/jpeg"
                }

                var settings = ""
                fun onProgress(msg: String, fraction: Float) {
                    publishState(CompressionState.Working(
                        originalBytes, msg, fraction, indeterminate = false, uri = uri, mime = mime
                    ))
                    val percent = (fraction * 100).toInt()
                    if (notificationThrottle.shouldPost(android.os.SystemClock.elapsedRealtime(), percent, msg)) {
                        notify(buildNotification(msg, percent, indeterminate = false))
                    }
                }

                withContext(Dispatchers.IO) {
                    when {
                        videoToGif -> VideoToGifConverter.convert(applicationContext, uri, targetBytes, outFile, trimStartMs, trimDurationMs) { msg, fraction ->
                            onProgress(msg, fraction)
                        }
                        isVideo && parts > 1 -> {
                            val windowStart = if (trimmed) trimStartMs else 0L
                            val windowLen = if (trimmed) trimDurationMs else queryVideoDurationMs(applicationContext, uri)
                            for (i in 0 until parts) {
                                val partFile = partFiles[i]
                                VideoCompressor.compress(
                                    applicationContext, uri, targetBytes, partFile,
                                    trimStartMs = windowStart + windowLen * i / parts,
                                    trimDurationMs = windowLen * (i + 1) / parts - windowLen * i / parts,
                                    floors = floors,
                                    onSettings = { if (i == 0) settings = it }
                                ) { msg, fraction ->
                                    onProgress("Part ${i + 1} of $parts: $msg", (i + fraction) / parts)
                                }
                            }
                        }
                        isVideo -> VideoCompressor.compress(
                            applicationContext, uri, targetBytes, outFile,
                            trimStartMs = if (trimmed) trimStartMs else 0L,
                            trimDurationMs = if (trimmed) trimDurationMs else 0L,
                            floors = floors,
                            onSettings = { settings = it }
                        ) { msg, fraction ->
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

                val fits = partFiles.all { it.length() <= targetBytes }
                val sourceName = queryDisplayName(contentResolver, uri) ?: outFile.name
                CompressionBridge.publish(
                    applicationContext,
                    CompressionState.Done(
                        originalBytes = originalBytes,
                        resultFile = resultFile,
                        mime = resultMime,
                        fitsTarget = fits,
                        targetLabel = targetLabel,
                        suggestedName = squeezedName(queryDisplayName(contentResolver, uri), outFile),
                        extraFiles = partFiles.drop(1),
                        settings = settings
                    ),
                    sourceName
                )
                notify(buildNotification(if (fits) "Done — fits under $targetLabel" else "Done — still over $targetLabel", 100, indeterminate = false))
            } catch (e: CancellationException) {
                publishState(CompressionState.Idle)
            } catch (e: OutOfMemoryError) {
                publishState(CompressionState.Failed(
                    "Not enough memory to process this file. Try a smaller or shorter one."
                ))
            } catch (e: Exception) {
                publishState(CompressionState.Failed(e.message ?: "Unknown error during compression"))
            } finally {
                ExitDiagnostics.jobFinished(applicationContext)
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

    private fun publishState(state: CompressionState) {
        lastState = state as? CompressionState.Working
        CompressionBridge.publish(applicationContext, state)
    }

    private val notificationThrottle = NotificationThrottle()

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
        const val ACTION_SYNC = "com.papacasper.squeeze.action.SYNC"
        private const val EXTRA_URI = "uri"
        private const val EXTRA_MIME = "mime"
        private const val EXTRA_ORIGINAL_BYTES = "original_bytes"
        private const val EXTRA_TARGET_BYTES = "target_bytes"
        private const val EXTRA_TARGET_LABEL = "target_label"
        private const val EXTRA_TO_GIF = "to_gif"
        private const val EXTRA_TRIM_START = "trim_start"
        private const val EXTRA_TRIM_DURATION = "trim_duration"
        private const val EXTRA_TRIMMED = "trimmed"
        private const val EXTRA_MIN_HEIGHT = "min_height"
        private const val EXTRA_MIN_FPS = "min_fps"
        private const val EXTRA_MIN_AUDIO = "min_audio"
        private const val EXTRA_PARTS = "parts"

        fun start(
            context: Context,
            uri: Uri,
            mime: String,
            originalBytes: Long,
            targetBytes: Long,
            targetLabel: String,
            toGif: Boolean,
            trimStartMs: Long,
            trimDurationMs: Long,
            trimmed: Boolean = false,
            floors: BitrateMath.Floors = BitrateMath.Floors(),
            parts: Int = 1
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
                .putExtra(EXTRA_TRIMMED, trimmed)
                .putExtra(EXTRA_MIN_HEIGHT, floors.minHeight)
                .putExtra(EXTRA_MIN_FPS, floors.minFps)
                .putExtra(EXTRA_MIN_AUDIO, floors.minAudioBitrate)
                .putExtra(EXTRA_PARTS, parts)
            context.startForegroundService(intent)
        }

        fun cancel(context: Context) {
            val intent = Intent(context, CompressionService::class.java).setAction(ACTION_CANCEL)
            context.startService(intent)
        }
    }
}
