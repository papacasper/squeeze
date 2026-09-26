package com.papacasper.squeeze

/** Encode-pass budget: every retry re-decodes the whole source, which is brutal for huge frames. */
object AttemptBudget {
    private const val UHD_PIXELS = 3840L * 2160L

    /** Sources bigger than 4K get a short retry budget; everything else keeps [default]. */
    fun forSource(width: Int, height: Int, default: Int): Int =
        if (width.toLong() * height > UHD_PIXELS) minOf(default, 3) else default
}

object DecodeCheck {
    /** Null when the phone can decode this stream, otherwise a user-facing reason it can't. */
    fun problem(context: android.content.Context, uri: android.net.Uri): String? {
        val extractor = android.media.MediaExtractor()
        val format = try {
            extractor.setDataSource(context, uri, null)
            (0 until extractor.trackCount).map { extractor.getTrackFormat(it) }
                .firstOrNull { it.getString(android.media.MediaFormat.KEY_MIME)?.startsWith("video/") == true }
        } catch (e: Exception) {
            null
        } finally {
            extractor.release()
        }
        format ?: return null  // unreadable here; let Transformer report its own error
        val mime = format.getString(android.media.MediaFormat.KEY_MIME) ?: return null
        if (!format.containsKey(android.media.MediaFormat.KEY_WIDTH) || !format.containsKey(android.media.MediaFormat.KEY_HEIGHT)) return null
        val width = format.getInteger(android.media.MediaFormat.KEY_WIDTH)
        val height = format.getInteger(android.media.MediaFormat.KEY_HEIGHT)
        val fps = if (format.containsKey(android.media.MediaFormat.KEY_FRAME_RATE)) {
            runCatching { format.getInteger(android.media.MediaFormat.KEY_FRAME_RATE) }
                .recoverCatching { format.getFloat(android.media.MediaFormat.KEY_FRAME_RATE).toInt() }
                .getOrNull()
        } else null

        val decoders = android.media.MediaCodecList(android.media.MediaCodecList.REGULAR_CODECS).codecInfos
            .filter { !it.isEncoder && it.supportedTypes.any { t -> t.equals(mime, ignoreCase = true) } }
        val label = "${mime.removePrefix("video/").uppercase()} ${width}x$height" + (fps?.let { " @ ${it}fps" } ?: "")
        if (decoders.isEmpty()) {
            return "This phone has no decoder for $label. Compress it on a PC with squeeze-cli instead."
        }
        val ok = decoders.any { info ->
            val caps = info.getCapabilitiesForType(mime).videoCapabilities ?: return@any false
            fun fits(w: Int, h: Int) =
                if (fps != null && fps > 0) caps.areSizeAndRateSupported(w, h, fps.toDouble()) else caps.isSizeSupported(w, h)
            fits(width, height) || fits(height, width)
        }
        return if (ok) null
        else "This phone can't decode $label. Compress it on a PC with squeeze-cli instead, or use a lower-resolution copy."
    }
}
