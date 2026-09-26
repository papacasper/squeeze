package com.papacasper.squeeze

import android.app.Application
import android.util.Log
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLException

class SqueezeApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // The compression service runs in ":compress"; only the UI process owns the UI-side state.
        if (!CompressionBridge.isMainProcess(this)) return
        CompressionBridge.install(this)
        ExitDiagnostics.takeInterruptedJobMessage(this)?.let {
            CompressionRepository.state.value = CompressionState.Failed(it)
        }
        try {
            YoutubeDL.getInstance().init(this)
            FFmpeg.getInstance().init(this)
        } catch (e: YoutubeDLException) {
            Log.e("SqueezeApplication", "Failed to initialize yt-dlp/ffmpeg", e)
        }
    }
}
