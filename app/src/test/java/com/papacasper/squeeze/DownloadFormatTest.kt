package com.papacasper.squeeze

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadFormatTest {
    @Test fun defaultCapsAt1080() {
        assertEquals("bv*[height<=1080]+ba/b[height<=1080]/bv*+ba/b", DownloadFormat.selector())
    }

    @Test fun fallsBackToUncappedWhenNothingFits() {
        assertTrue(DownloadFormat.selector(720).endsWith("/bv*+ba/b"))
        assertTrue(DownloadFormat.selector(720).startsWith("bv*[height<=720]"))
    }

    @Test fun smallTargetOnLongClipDownloads720() {
        // Discord Free (20 MiB) over the 597 s test video leaves ~150 kbps of video: no point fetching 1080p.
        assertEquals(720, DownloadFormat.heightFor(20L * 1024 * 1024, 597.0, 720))
        assertEquals(480, DownloadFormat.heightFor(20L * 1024 * 1024, 597.0, 480))
    }

    @Test fun roomyOrUnknownTargetKeeps1080() {
        assertEquals(1080, DownloadFormat.heightFor(500L * 1024 * 1024, 60.0, 720))
        assertEquals(1080, DownloadFormat.heightFor(null, 60.0, 720))
        assertEquals(1080, DownloadFormat.heightFor(20L * 1024 * 1024, 0.0, 720))
        assertEquals(1080, DownloadFormat.heightFor(10L * 1024 * 1024, 10.0, 1080))
    }

    @Test fun unsupportedSiteSaysSoPlainly() {
        val m = DownloadFormat.friendlyError("ERROR: Unsupported URL: https://example.com/page")
        assertTrue(m.startsWith("Squeeze can't download from this site"))
    }

    @Test fun otherErrorsLoseTheYtDlpPrefix() {
        assertEquals("unable to download video data: HTTP Error 403: Forbidden",
            DownloadFormat.friendlyError("ERROR: [youtube] abc123: unable to download video data: HTTP Error 403: Forbidden"))
        assertTrue(DownloadFormat.friendlyError("ERROR: [instagram] x: Requested content is not available").contains("isn't available"))
    }

    @Test fun ytDlpUpdateIsWeekly() {
        assertTrue(YtDlpUpdater.isDue(0, 1_000))
        assertTrue(!YtDlpUpdater.isDue(1_000, 1_000 + YtDlpUpdater.INTERVAL_MS - 1))
        assertTrue(YtDlpUpdater.isDue(1_000, 1_000 + YtDlpUpdater.INTERVAL_MS))
    }
}
