package com.papacasper.squeeze

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TikTokSlideshowTest {
    private fun page(itemStruct: String) = """<html><body><script id="__UNIVERSAL_DATA_FOR_REHYDRATION__" type="application/json">
        {"__DEFAULT_SCOPE__":{"webapp.video-detail":{"itemInfo":{"itemStruct":$itemStruct}}}}</script></body></html>"""

    @Test
    fun parse_photoPostGivesImagesAndMusic() {
        val post = TikTokSlideshow.parse(page("""{"id":"1","imagePost":{"images":[
            {"imageURL":{"urlList":["https://p16.example/a.jpeg","https://p19.example/a.jpeg"]}},
            {"imageURL":{"urlList":["https://p16.example/b.jpeg"]}}]},
            "music":{"playUrl":"https://sf.example/song.mp3","duration":14}}"""))!!
        assertEquals(listOf("https://p16.example/a.jpeg", "https://p16.example/b.jpeg"), post.imageUrls)
        assertEquals("https://sf.example/song.mp3", post.audioUrl)
        assertEquals(14.0, post.audioSec, 0.001)
    }

    @Test
    fun parse_photoPostWithoutMusicStillWorks() {
        val post = TikTokSlideshow.parse(page("""{"imagePost":{"images":[{"imageURL":{"urlList":["https://x/a.jpg"]}}]}}"""))!!
        assertEquals(1, post.imageUrls.size)
        assertNull(post.audioUrl)
    }

    @Test
    fun parse_normalVideoIsNotASlideshow() {
        assertNull(TikTokSlideshow.parse(page("""{"video":{"playAddr":"https://x/v.mp4"},"music":{"playUrl":"https://x/m.mp3"}}""")))
    }

    @Test
    fun parse_garbageIsNull() {
        assertNull(TikTokSlideshow.parse("<html>blocked</html>"))
        assertNull(TikTokSlideshow.parse("""<script id="__UNIVERSAL_DATA_FOR_REHYDRATION__">{not json</script>"""))
    }

    @Test
    fun isTikTokUrl_matchesHostsOnly() {
        assertTrue(TikTokSlideshow.isTikTokUrl("https://www.tiktok.com/@u/photo/123"))
        assertTrue(TikTokSlideshow.isTikTokUrl("https://vm.tiktok.com/ZMabc/"))
        assertFalse(TikTokSlideshow.isTikTokUrl("https://example.com/?u=tiktok.com"))
        assertFalse(TikTokSlideshow.isTikTokUrl("https://nottiktok.com/x"))
    }

    @Test
    fun timing_spreadsImagesAcrossAudioWithinBounds() {
        assertEquals(TikTokSlideshow.Timing(5_000, 25_000), TikTokSlideshow.timing(5, 25.0))
        assertEquals(TikTokSlideshow.Timing(2_000, 20_000), TikTokSlideshow.timing(10, 5.0))   // too short: 2 s floor
        assertEquals(TikTokSlideshow.Timing(6_000, 12_000), TikTokSlideshow.timing(2, 60.0))   // too long: 6 s cap
        assertEquals(TikTokSlideshow.Timing(3_000, 9_000), TikTokSlideshow.timing(3, 0.0))     // no audio
    }

    @Test
    fun mimeFor_audioIsNotVideo() {
        assertEquals("audio/mp4", DownloadFormat.mimeFor("download.m4a"))
        assertEquals("audio/mpeg", DownloadFormat.mimeFor("download.mp3"))
        assertEquals("video/mp4", DownloadFormat.mimeFor("download.mp4"))
        assertEquals("video/mp4", DownloadFormat.mimeFor("download"))
    }
}

class TikTokVideoUrlTest {
    @Test
    fun photoUrlBecomesVideoUrl() {
        assertEquals(
            "https://www.tiktok.com/@kevinzimple/video/7691656733684534558",
            TikTokSlideshow.asVideoUrl("https://www.tiktok.com/@kevinzimple/photo/7691656733684534558?_r=1&_t=ZT-9AChwDPd79B")
        )
    }

    @Test
    fun otherUrlsAreLeftAlone() {
        assertNull(TikTokSlideshow.asVideoUrl("https://www.tiktok.com/@a/video/123"))
        assertNull(TikTokSlideshow.asVideoUrl("not a url"))
    }
}
