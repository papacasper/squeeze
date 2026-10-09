package com.papacasper.squeeze

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume

/** Builds a 1080x1920 slideshow mp4 from still images with an optional soundtrack, using Media3's Transformer. */
@OptIn(UnstableApi::class)
object SlideshowVideo {
    private const val WIDTH = 1080
    private const val HEIGHT = 1920

    suspend fun build(context: Context, images: List<File>, audio: File?, audioSec: Double, outFile: File) {
        require(images.isNotEmpty()) { "No images to build a slideshow from" }
        outFile.delete()
        val timing = TikTokSlideshow.timing(images.size, audioSec)
        val fit = Presentation.createForWidthAndHeight(WIDTH, HEIGHT, Presentation.LAYOUT_SCALE_TO_FIT)

        val imageItems = images.map { file ->
            EditedMediaItem.Builder(MediaItem.Builder().setUri(Uri.fromFile(file)).setMimeType(MimeTypes.IMAGE_JPEG).setImageDurationMs(timing.perImageMs).build())
                .setDurationUs(timing.perImageMs * 1000)
                .setFrameRate(30)
                .setEffects(Effects(emptyList<AudioProcessor>(), listOf(fit)))
                .build()
        }
        val sequences = mutableListOf(EditedMediaItemSequence.Builder(imageItems).build())
        if (audio != null) {
            val clip = MediaItem.Builder().setUri(Uri.fromFile(audio))
                .setClippingConfiguration(
                    MediaItem.ClippingConfiguration.Builder().setStartPositionMs(0).setEndPositionMs(timing.totalMs).build()
                ).build()
            sequences += EditedMediaItemSequence.Builder(EditedMediaItem.Builder(clip).build()).build()
        }
        val composition = Composition.Builder(sequences).build()

        val result: Result<Unit> = withContext(Dispatchers.Main.immediate) {
            suspendCancellableCoroutine { cont ->
                val transformer = Transformer.Builder(context)
                    .addListener(object : Transformer.Listener {
                        override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                            if (cont.isActive) cont.resume(Result.success(Unit))
                        }

                        override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                            if (cont.isActive) cont.resume(Result.failure(exportException))
                        }
                    })
                    .build()
                transformer.start(composition, outFile.absolutePath)
                cont.invokeOnCancellation { transformer.cancel() }
            }
        }
        result.getOrThrow()
    }
}
