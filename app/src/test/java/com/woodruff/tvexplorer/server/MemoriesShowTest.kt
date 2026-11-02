package com.woodruff.tvexplorer.server

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.woodruff.tvexplorer.photos.FamilyMemories
import com.woodruff.tvexplorer.player.ImageViewerActivity
import fi.iki.elonen.NanoHTTPD
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSettings
import java.io.File

/**
 * Show on TV from the phone's Family Memories (finding 47): the viewer gets the 300 photos around the one shown, not
 * every photo, and while it's up with that photo among its own, each swipe on the phone sends only where to go.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class MemoriesShowTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val routes = CompanionRoutes(ctx)
    private lateinit var photos: List<File>

    @Before fun manyPhotos() {
        ShadowSettings.setCanDrawOverlays(true)
        val dir = FamilyMemories.photos.apply { mkdirs() }
        // 400 photos, named the way phones name them (so each one's date comes from its name), newest last.
        photos = (0 until 400).map { n -> File(dir, "IMG_20240101_%02d%02d00.jpg".format(n / 60, n % 60)).apply { writeBytes(byteArrayOf(1)) } }
    }

    @After fun clean() { ImageViewerActivity.showing(null); photos.forEach { it.delete() } }

    private fun show(f: File) {
        val r = routes.answerFor(phoneRequest("/api/memories/show", NanoHTTPD.Method.POST, body = JSONObject().put("path", f.path)))
        assertEquals(r.bodyText(), NanoHTTPD.Response.Status.OK, r.status)
    }

    @Test fun theViewerGetsThePhotosAroundTheOneShown() {
        val one = photos[200].canonicalFile
        show(one)
        val i = shadowOf(app).nextStartedActivity
        val paths = i.getStringArrayListExtra("paths")!!
        assertEquals(300, paths.size)
        assertEquals(one.path, i.getStringExtra("start"))
        assertTrue(one.path in paths)
        // In the phone's order (newest first), with the one shown in the middle.
        assertEquals(150, paths.indexOf(one.path))
        assertTrue(i.flags and Intent.FLAG_ACTIVITY_SINGLE_TOP != 0)
    }

    @Test fun whileItsUpEachSwipeSendsOnlyWhereToGo() {
        show(photos[200].canonicalFile)
        val list = shadowOf(app).nextStartedActivity.getStringArrayListExtra("paths")!!
        ImageViewerActivity.showing(list)   // the viewer is in front with those photos
        val next = photos[201].canonicalFile
        show(next)
        val follow = shadowOf(app).nextStartedActivity
        assertNull("no list this time", follow.getStringArrayListExtra("paths"))
        assertEquals(next.path, follow.getStringExtra("start"))
        assertTrue(follow.flags and Intent.FLAG_ACTIVITY_SINGLE_TOP != 0)
        // Far beyond its photos: the photos around that one, again.
        show(photos[0].canonicalFile)
        assertEquals(300, shadowOf(app).nextStartedActivity.getStringArrayListExtra("paths")!!.size)
        // Left the front: a whole list again.
        ImageViewerActivity.showing(null, list)
        show(next)
        assertNotNull(shadowOf(app).nextStartedActivity.getStringArrayListExtra("paths"))
    }

    @Test fun anIntentWithOnlyWhereToGoKeepsTheViewersPhotos() {
        val current = listOf("/a.jpg", "/b.jpg", "/c.jpg")
        assertSame(current, ImageViewerActivity.photosFor(current, null, "/b.jpg"))
        assertEquals(listOf("/z.jpg"), ImageViewerActivity.photosFor(current, null, "/z.jpg"))
        assertEquals(listOf("/x.jpg"), ImageViewerActivity.photosFor(current, arrayListOf("/x.jpg"), "/x.jpg"))
        assertNull(ImageViewerActivity.photosFor(current, null, null))
        assertNull(ImageViewerActivity.photosFor(emptyList(), arrayListOf(), null))
    }

    @Test fun onlyTheViewerInFrontCounts() {
        val front = listOf("/a.jpg")
        val behind = listOf("/b.jpg")
        ImageViewerActivity.showing(front)
        ImageViewerActivity.showing(null, behind)   // another one leaving: the one in front stays
        assertTrue(ImageViewerActivity.shows("/a.jpg"))
        assertFalse(ImageViewerActivity.shows("/b.jpg"))
        ImageViewerActivity.showing(null, front)
        assertFalse(ImageViewerActivity.shows("/a.jpg"))
    }
}
