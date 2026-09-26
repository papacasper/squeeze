package com.papacasper.squeeze

import android.app.ActivityManager
import android.app.Application
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.content.ContextCompat
import java.io.File

/**
 * CompressionService runs in its own process (":compress") so that a native decoder crash or a
 * low-memory kill takes down only the job, not the UI. The two sides talk through explicit,
 * package-local broadcasts: the service publishes [CompressionState]s, and the UI process mirrors
 * them into [CompressionRepository]. The UI also binds to the service while a job runs, purely so
 * it is told if that process dies.
 */
object CompressionBridge {
    private const val ACTION_STATE = "com.papacasper.squeeze.action.COMPRESSION_STATE"
    private const val EXTRA_KIND = "kind"
    private const val EXTRA_ORIGINAL_BYTES = "original_bytes"
    private const val EXTRA_MESSAGE = "message"
    private const val EXTRA_PROGRESS = "progress"
    private const val EXTRA_INDETERMINATE = "indeterminate"
    private const val EXTRA_URI = "uri"
    private const val EXTRA_MIME = "mime"
    private const val EXTRA_PATH = "path"
    private const val EXTRA_FITS = "fits"
    private const val EXTRA_LABEL = "label"
    private const val EXTRA_NAME = "name"
    private const val EXTRA_SOURCE_NAME = "source_name"

    // ---- service side ----

    fun publish(context: Context, state: CompressionState, sourceName: String? = null) {
        val intent = Intent(ACTION_STATE).setPackage(context.packageName)
        when (state) {
            CompressionState.Idle -> intent.putExtra(EXTRA_KIND, "idle")
            is CompressionState.Working -> intent.putExtra(EXTRA_KIND, "working")
                .putExtra(EXTRA_ORIGINAL_BYTES, state.originalBytes).putExtra(EXTRA_MESSAGE, state.message)
                .putExtra(EXTRA_PROGRESS, state.progress).putExtra(EXTRA_INDETERMINATE, state.indeterminate)
                .putExtra(EXTRA_URI, state.uri).putExtra(EXTRA_MIME, state.mime)
            is CompressionState.Done -> intent.putExtra(EXTRA_KIND, "done")
                .putExtra(EXTRA_ORIGINAL_BYTES, state.originalBytes).putExtra(EXTRA_PATH, state.resultFile.absolutePath)
                .putExtra(EXTRA_MIME, state.mime).putExtra(EXTRA_FITS, state.fitsTarget)
                .putExtra(EXTRA_LABEL, state.targetLabel).putExtra(EXTRA_NAME, state.suggestedName)
                .putExtra(EXTRA_SOURCE_NAME, sourceName)
            is CompressionState.Failed -> intent.putExtra(EXTRA_KIND, "failed").putExtra(EXTRA_MESSAGE, state.message)
        }
        context.sendBroadcast(intent)
    }

    // ---- UI side ----

    private val main = Handler(Looper.getMainLooper())
    private var bound = false
    private lateinit var app: Application

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {}
        override fun onServiceDisconnected(name: ComponentName?) {
            // Only reached by the job's process dying: a normal finish unbinds before the service stops.
            unbind()
            if (CompressionRepository.state.value is CompressionState.Working) {
                val message = ExitDiagnostics.takeInterruptedJobMessage(app, jobProcessAlive = false)
                    ?: ExitMessages.describe(-1, "your file")
                CompressionRepository.state.value = CompressionState.Failed(message)
            }
        }
    }

    fun install(application: Application) {
        app = application
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) = handle(intent)
        }
        ContextCompat.registerReceiver(application, receiver, IntentFilter(ACTION_STATE), ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    private fun handle(intent: Intent) {
        when (intent.getStringExtra(EXTRA_KIND)) {
            "working" -> {
                bind()
                @Suppress("DEPRECATION")
                val uri = intent.getParcelableExtra<Uri>(EXTRA_URI) ?: return
                CompressionRepository.state.value = CompressionState.Working(
                    intent.getLongExtra(EXTRA_ORIGINAL_BYTES, 0L), intent.getStringExtra(EXTRA_MESSAGE).orEmpty(),
                    intent.getFloatExtra(EXTRA_PROGRESS, 0f), intent.getBooleanExtra(EXTRA_INDETERMINATE, false),
                    uri, intent.getStringExtra(EXTRA_MIME).orEmpty()
                )
            }
            "done" -> {
                unbind()
                val file = File(intent.getStringExtra(EXTRA_PATH).orEmpty())
                val original = intent.getLongExtra(EXTRA_ORIGINAL_BYTES, 0L)
                val fits = intent.getBooleanExtra(EXTRA_FITS, false)
                val label = intent.getStringExtra(EXTRA_LABEL).orEmpty()
                HistoryStore.addEntry(
                    app,
                    HistoryEntry(System.currentTimeMillis(), intent.getStringExtra(EXTRA_SOURCE_NAME) ?: file.name, original, file.length(), label, fits)
                )
                CompressionRepository.state.value = CompressionState.Done(
                    original, file, intent.getStringExtra(EXTRA_MIME).orEmpty(), fits, label, intent.getStringExtra(EXTRA_NAME).orEmpty()
                )
            }
            "failed" -> {
                unbind()
                CompressionRepository.state.value = CompressionState.Failed(intent.getStringExtra(EXTRA_MESSAGE).orEmpty())
            }
            "idle" -> {
                unbind()
                CompressionRepository.state.value = CompressionState.Idle
            }
        }
    }

    private fun bind() {
        if (bound) return
        bound = app.bindService(Intent(app, CompressionService::class.java), connection, 0)
    }

    private fun unbind() {
        if (!bound) return
        bound = false
        try { app.unbindService(connection) } catch (_: IllegalArgumentException) {}
    }

    fun isJobProcessAlive(context: Context): Boolean {
        val am = context.getSystemService(ActivityManager::class.java) ?: return false
        return am.runningAppProcesses?.any { it.processName == "${context.packageName}:compress" } == true
    }

    fun isMainProcess(app: Application): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) Application.getProcessName() == app.packageName else true
}
