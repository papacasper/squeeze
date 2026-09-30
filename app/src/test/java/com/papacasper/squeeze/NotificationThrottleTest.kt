package com.papacasper.squeeze

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationThrottleTest {
    @Test
    fun firstPostAlwaysGoesThrough() {
        assertTrue(NotificationThrottle().shouldPost(0, 0, "a"))
    }

    @Test
    fun fastUpdatesWithinIntervalAreDropped() {
        val t = NotificationThrottle()
        assertTrue(t.shouldPost(0, 1, "a"))
        assertFalse(t.shouldPost(300, 2, "a"))
        assertFalse(t.shouldPost(900, 3, "a"))
    }

    @Test
    fun changedProgressPostsAfterInterval() {
        val t = NotificationThrottle()
        t.shouldPost(0, 1, "a")
        assertTrue(t.shouldPost(1_000, 2, "a"))
    }

    @Test
    fun unchangedProgressIsNeverReposted() {
        val t = NotificationThrottle()
        t.shouldPost(0, 5, "a")
        assertFalse(t.shouldPost(5_000, 5, "a"))
    }

    @Test
    fun changedMessageAlonePostsAfterInterval() {
        val t = NotificationThrottle()
        t.shouldPost(0, 5, "pass 1")
        assertTrue(t.shouldPost(1_500, 5, "pass 2"))
    }
}
