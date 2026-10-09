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
import androidx.core.content.FileProvider
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLException
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

sealed class DownloadState {
    data object Idle : DownloadState()
    data class Working(val message: String, val progress: Float) : DownloadState()
    data class Done(val uri: Uri, val mime: String) : DownloadState()
    data class Failed(val message: String) : DownloadState()
}

/** Shared across the Activity and [DownloadService] so a download survives the app backgrounding. */
object DownloadRepository {
    val state = MutableStateFlow<DownloadState>(DownloadState.Idle)
}

class DownloadService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            YoutubeDL.getInstance().destroyProcessById(PROCESS_ID)
            job?.cancel()
            return START_NOT_STICKY
        }

        val url = intent?.getStringExtra(EXTRA_URL) ?: return START_NOT_STICKY
        start(url)
        return START_NOT_STICKY
    }

    private fun start(url: String) {
        createChannel()
        startForeground(NOTIF_ID, buildNotification("Starting download...", 0, indeterminate = true))
        DownloadRepository.state.value = DownloadState.Working("Starting download...", 0f)

        job = serviceScope.launch {
            try {
                val outDir = File(cacheDir, "downloads").apply {
                    deleteRecursively()
                    mkdirs()
                }

                fun onProgress(msg: String, fraction: Float) {
                    DownloadRepository.state.value = DownloadState.Working(msg, fraction)
                    val percent = (fraction * 100).toInt()
                    if (notificationThrottle.shouldPost(android.os.SystemClock.elapsedRealtime(), percent, msg)) {
                        notify(buildNotification(msg, percent, indeterminate = false))
                    }
                }

                val slideshow = if (TikTokSlideshow.isTikTokUrl(url)) {
                    onProgress("Checking the post...", 0f)
                    withContext(Dispatchers.IO) { SlideshowDownload.fetchPost(url.trim()) }
                } else null
                if (slideshow != null) {
                    buildSlideshow(slideshow, outDir, ::onProgress)
                } else withContext(Dispatchers.IO) {
                    fun runDownload() {
                        val request = YoutubeDLRequest(url).apply {
                            addOption("-f", DownloadFormat.selector())
                            addOption("--merge-output-format", "mp4")
                            addOption("--no-playlist")
                            addOption("-o", File(outDir, "download.%(ext)s").absolutePath)
                        }
                        YoutubeDL.getInstance().execute(request, PROCESS_ID) { progress, _, line ->
                            onProgress(line.ifBlank { "Downloading..." }, (progress / 100f).coerceIn(0f, 1f))
                        }
                    }
                    try {
                        runDownload()
                    } catch (e: YoutubeDLException) {
                        // Sites change constantly and a stale yt-dlp is the usual reason a working URL
                        // suddenly fails, so try an update, then retry once either way: YouTube also
                        // hands out stream URLs that 403 at random, and a fresh attempt gets new ones.
                        // A user cancel kills the process, which also lands here, so bail out first if
                        // the job is no longer active.
                        currentCoroutineContext().ensureActive()
                        onProgress("Download failed; updating yt-dlp and retrying...", 0f)
                        runCatching {
                            YoutubeDL.getInstance().updateYoutubeDL(applicationContext, YoutubeDL.UpdateChannel._STABLE)
                        }
                        currentCoroutineContext().ensureActive()
                        outDir.listFiles()?.forEach { it.deleteRecursively() }
                        runDownload()
                    }
                }

                val resultFile = outDir.listFiles()?.firstOrNull()
                    ?: throw IllegalStateException("Download finished but produced no file")
                val mime = DownloadFormat.mimeFor(resultFile.name)
                if (!mime.startsWith("video") && !mime.startsWith("image")) {
                    throw IllegalStateException(
                        if (TikTokSlideshow.isTikTokUrl(url))
                            "This TikTok is a photo slideshow and its pictures couldn't be fetched, only the sound. Try again later or from another network."
                        else "That link only has audio; Squeeze compresses videos and images."
                    )
                }
                val uri = FileProvider.getUriForFile(applicationContext, "$packageName.fileprovider", resultFile)

                DownloadRepository.state.value = DownloadState.Done(uri, mime)
                notify(buildNotification("Download complete", 100, indeterminate = false))
            } catch (e: CancellationException) {
                DownloadRepository.state.value = DownloadState.Idle
            } catch (e: YoutubeDLException) {
                DownloadRepository.state.value = DownloadState.Failed(e.message ?: "Download failed")
            } catch (e: Exception) {
                DownloadRepository.state.value = DownloadState.Failed(e.message ?: "Unknown error during download")
            } finally {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private suspend fun buildSlideshow(post: TikTokSlideshow.Post, outDir: File, onProgress: (String, Float) -> Unit) {
        val work = File(outDir, "slides").apply { mkdirs() }
        val images = withContext(Dispatchers.IO) {
            post.imageUrls.mapIndexed { i, imageUrl ->
                onProgress("Downloading picture ${i + 1} of ${post.imageUrls.size}...", i / post.imageUrls.size.toFloat() * 0.6f)
                File(work, "img%03d.jpg".format(i)).also { SlideshowDownload.downloadTo(imageUrl, it) }
            }
        }
        val audio = post.audioUrl?.let { audioUrl ->
            onProgress("Downloading the sound...", 0.6f)
            // The picture-only post is still worth making if the soundtrack is gone.
            runCatching { withContext(Dispatchers.IO) { File(work, "audio").also { SlideshowDownload.downloadTo(audioUrl, it) } } }.getOrNull()
        }
        onProgress("Putting the slideshow together...", 0.7f)
        SlideshowVideo.build(applicationContext, images, audio, post.audioSec, File(outDir, "download.mp4"))
        work.deleteRecursively()
    }

    override fun onDestroy() {
        super.onDestroy()
        job?.cancel()
        serviceScope.cancel()
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
            val channel = NotificationChannel(CHANNEL_ID, "Download progress", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }

    companion object {
        private const val CHANNEL_ID = "download"
        private const val NOTIF_ID = 43
        private const val PROCESS_ID = "download"

        const val ACTION_CANCEL = "com.papacasper.squeeze.action.CANCEL_DOWNLOAD"
        private const val EXTRA_URL = "url"

        fun start(context: Context, url: String) {
            val intent = Intent(context, DownloadService::class.java).putExtra(EXTRA_URL, url)
            context.startForegroundService(intent)
        }

        fun cancel(context: Context) {
            val intent = Intent(context, DownloadService::class.java).setAction(ACTION_CANCEL)
            context.startService(intent)
        }
    }
}
