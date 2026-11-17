package com.woodruff.tvexplorer.screensaver

import android.app.Application
import android.os.Environment
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import kotlin.random.Random

/**
 * The slideshow's photos: up to its limit, each as likely as any other however many there are, so with more photos
 * than that it doesn't always show the same first ones (and reaches DCIM after Pictures).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class SlideshowPhotosTest {
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val pictures get() = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "Trip")
    private val camera get() = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), "Camera")

    @After fun clean() {
        pictures.deleteRecursively()
        camera.deleteRecursively()
        Appearance.setSlideshowFolder(app, null)
        // Compose hears of those writes now: left for after the test, the next tests' screens stopped updating.
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun photos(dir: File, n: Int, name: String) = (0 until n).map { File(dir.apply { mkdirs() }, "$name $it.jpg").apply { writeBytes(ByteArray(4)) } }

    @Test fun fewerThanTheLimitAllCome() {
        val all = photos(pictures, 5, "Beach")
        Appearance.setSlideshowFolder(app, pictures.path)
        assertEquals(all.toSet(), Appearance.photos(app, limit = 20).toSet())
    }

    @Test fun moreThanTheLimitAreSampledFromAllOfThem() {
        val all = photos(pictures, 30, "Beach") + photos(camera, 30, "Party")
        val seen = HashSet<File>()
        val random = Random(7)
        repeat(100) {
            val some = Appearance.photos(app, limit = 10, random = random)
            assertEquals(10, some.size)
            assertEquals(10, some.toSet().size)
            seen += some
        }
        // Every photo turns up sooner or later, the camera's (walked after Pictures) as well.
        assertTrue(seen.containsAll(all))
    }

    @Test fun eachIsAsLikelyAsAnyOther() {
        val random = Random(11)
        val counts = IntArray(10)
        repeat(10_000) { sample((0 until 10).asSequence(), 3, random).forEach { counts[it]++ } }
        // 3,000 each on average.
        counts.forEach { assertTrue("$it", it in 2_700..3_300) }
        assertEquals(listOf(1, 2), sample(sequenceOf(1, 2), 3, random).sorted())
    }
}
