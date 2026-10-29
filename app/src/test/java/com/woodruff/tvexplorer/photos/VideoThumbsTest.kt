package com.woodruff.tvexplorer.photos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Video tiles' saved thumbnails: the size a frame is read at, their names, and which go when the folder is full. */
class VideoThumbsTest {
    @Test fun aFrameJustCoversTheTile() {
        assertEquals(640 to 360, VideoThumbs.frameSize(3840, 2160, 0, 640, 360))
        // Wider than the tile: as tall as it, so cropping the sides still fills it.
        assertEquals(864 to 360, VideoThumbs.frameSize(1920, 800, 0, 640, 360))
    }

    @Test fun portraitVideosStandUp() {
        // Phones store portrait video sideways, turned by 90°: as wide as the tile, so cropping fills it.
        assertEquals(640 to 1138, VideoThumbs.frameSize(1920, 1080, 90, 640, 360))
        assertEquals(640 to 1138, VideoThumbs.frameSize(1920, 1080, 270, 640, 360))
        assertEquals(640 to 1138, VideoThumbs.frameSize(1080, 1920, 0, 640, 360))
        assertEquals(640 to 360, VideoThumbs.frameSize(1920, 1080, 180, 640, 360))
    }

    @Test fun neverLargerThanTheVideo() = assertEquals(320 to 240, VideoThumbs.frameSize(320, 240, 0, 640, 360))

    @Test fun unknownSize() {
        assertNull(VideoThumbs.frameSize(0, 0, 0, 640, 360))
        assertNull(VideoThumbs.frameSize(1920, 0, 90, 640, 360))
    }

    @Test fun aChangedVideoGetsANewThumbnail() {
        val name = VideoThumbs.fileName("/storage/USB/Trip.mp4", 1_000_000, 1_700_000_000_000, 640, 360)
        assertTrue(name, name.matches(Regex("[0-9a-f]{40}\\.jpg")))
        assertEquals(name, VideoThumbs.fileName("/storage/USB/Trip.mp4", 1_000_000, 1_700_000_000_000, 640, 360))
        assertNotEquals(name, VideoThumbs.fileName("/storage/USB/Trip 2.mp4", 1_000_000, 1_700_000_000_000, 640, 360))
        assertNotEquals(name, VideoThumbs.fileName("/storage/USB/Trip.mp4", 1_000_001, 1_700_000_000_000, 640, 360))
        assertNotEquals(name, VideoThumbs.fileName("/storage/USB/Trip.mp4", 1_000_000, 1_700_000_000_001, 640, 360))
        assertNotEquals(name, VideoThumbs.fileName("/storage/USB/Trip.mp4", 1_000_000, 1_700_000_000_000, 960, 540))
    }

    @Test fun underTheCapNothingGoes() {
        val files = listOf(VideoThumbs.Entry("a.jpg", 40, 1), VideoThumbs.Entry("b.jpg", 60, 2))
        assertEquals(emptyList<String>(), VideoThumbs.toTrim(files, max = 100, keep = 80))
    }

    @Test fun pastTheCapTheLongestUnseenGoFirst() {
        val files = listOf(
            VideoThumbs.Entry("new.jpg", 30, 300),
            VideoThumbs.Entry("old.jpg", 30, 100),
            VideoThumbs.Entry("mid.jpg", 30, 200),
            VideoThumbs.Entry("older.jpg", 30, 50),
        )
        // 120 bytes against a cap of 100: down to 60 at most, the oldest first.
        assertEquals(listOf("older.jpg", "old.jpg"), VideoThumbs.toTrim(files, max = 100, keep = 60))
    }
}
