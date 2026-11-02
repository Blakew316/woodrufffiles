package com.woodruff.tvexplorer.server

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.math.abs

/** Each TV's web app is labelled with the TV's name on a phone's Home Screen, and nothing else changes. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class PhoneIconTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val page = File("src/main/assets/web/index.html").readText()
    private val manifest = File("src/main/assets/web/manifest.webmanifest").readText()

    @Test fun thePageIsLabelledWithTheTvsName() {
        val html = PhoneIcon.render(page, "k3y", "Living Room")
        assertTrue(html.contains("""<meta name="apple-mobile-web-app-title" content="Living Room">"""))
        assertTrue(html.contains("var TVX_KEY = 'k3y';"))
        assertTrue(html.contains("/manifest.webmanifest?v=2&amp;n=${PhoneIcon.tag("Living Room")}\""))
        // The Home Screen picture is this TV's; the page's own pictures stay the app's.
        assertTrue(html.contains("""<link rel="apple-touch-icon" href="/home-icon-180.png?v=2&amp;n=${PhoneIcon.tag("Living Room")}">"""))
        assertTrue(html.contains("""<link rel="icon" href="/icon-180.png?v=2">"""))
        assertTrue(html.contains("""<img src="/icon-180.png?v=2" alt="">"""))
        assertFalse(html.contains("__TVX_"))
        // The app inside keeps its own name.
        assertTrue(html.contains("<title>Woodruff Family</title>"))
    }

    @Test fun anUnnamedTvKeepsTheAppsName() {
        assertTrue(PhoneIcon.render(page, "k", PhoneIcon.APP_NAME).contains("""content="Woodruff Family">"""))
    }

    @Test fun namesAreSafeInThePage() {
        assertEquals("Mom &amp; Dad&#39;s &lt;TV&gt; &quot;1&quot;", PhoneIcon.escapeHtml("Mom & Dad's <TV> \"1\""))
        assertTrue(PhoneIcon.render(page, "k", "Elliot's").contains("""content="Elliot&#39;s">"""))
    }

    @Test fun aRenamedTvGetsAFreshManifestAddress() {
        assertEquals(8, PhoneIcon.tag("Elliot").length)
        assertEquals(PhoneIcon.tag("Elliot"), PhoneIcon.tag("Elliot"))
        assertNotEquals(PhoneIcon.tag("Elliot"), PhoneIcon.tag("Master Bedroom"))
    }

    @Test fun theManifestIsNamedAfterTheTvAndKeepsEverythingElse() {
        val o = JSONObject(PhoneIcon.manifest(manifest, "Master Bedroom"))
        assertEquals("Master Bedroom", o.getString("name"))
        assertEquals("Master Bedroom", o.getString("short_name"))
        val original = JSONObject(manifest)
        assertEquals(original.getString("display"), o.getString("display"))
        val icons = o.getJSONArray("icons")
        val tag = PhoneIcon.tag("Master Bedroom")
        assertEquals(original.getJSONArray("icons").length(), icons.length())
        for (i in 0 until icons.length()) {
            val src = icons.getJSONObject(i).getString("src")
            val size = icons.getJSONObject(i).getString("sizes").substringBefore('x')
            assertEquals("/home-icon-$size.png?v=2&n=$tag", src)
            assertTrue(PhoneIcon.PICTURES.containsKey(src.substringBefore('?')))
        }
    }

    @Test fun nameIsWrittenLikeWoodruffFamily() {
        assertEquals("WOODRUFF" to "FAMILY", PhoneIcon.wording("Woodruff Family"))
        assertEquals("KYLER\u2019S" to "ROOM", PhoneIcon.wording("Kyler's Room"))
        assertEquals("ELLIOTT\u2019S" to "ROOM", PhoneIcon.wording(" Elliott's   Room "))
        assertEquals("LIVING" to "ROOM", PhoneIcon.wording("Living Room"))
        assertEquals("MASTER" to "BEDROOM", PhoneIcon.wording("Master Bedroom"))
        assertEquals("THE BIG" to "DEN", PhoneIcon.wording("The Big Den"))
        val one = PhoneIcon.wording("Elliott")
        assertEquals("ELLIOTT", one.first)
        assertNull(one.second)
    }

    @Test fun anUnnamedTvGetsTheAppsOwnPicture() {
        val own = app.assets.open("web/icon-180.png").use { it.readBytes() }
        assertTrue(own.contentEquals(PhoneIcon.picture(app, 180, PhoneIcon.APP_NAME)))
    }

    /**
     * The words are written as on the app's picture: "WOODRUFF FAMILY" written onto the picture with
     * its wording taken out comes back close to the original. Also saves each family TV's picture
     * (build/phone-icons) to look at.
     */
    @Test fun theNameIsWrittenWhereWoodruffFamilyWas() {
        val original = app.assets.open("web/icon-512.png").use { BitmapFactory.decodeStream(it) }
        val base = app.assets.open("web/icon-base-512.png").use { BitmapFactory.decodeStream(it) }
        val redrawn = base.copy(Bitmap.Config.ARGB_8888, true)
        PhoneIcon.draw(app, Canvas(redrawn), 512, PhoneIcon.APP_NAME)
        val withWords = difference(redrawn, original, 376, 468)
        val withoutWords = difference(base, original, 376, 468)
        assertTrue("redrawn $withWords vs blank $withoutWords", withWords < withoutWords / 3)
        // Above the words (the emblem, the background), nothing changed.
        assertEquals(0.0, difference(base, original, 0, 360), 0.5)

        val out = File("build/phone-icons").apply { mkdirs() }
        for (name in listOf("Living Room", "Elliott's Room", "Kyler's Room", "Master Bedroom", "Elliott")) {
            for (size in PhoneIcon.PICTURES.values) {
                val png = PhoneIcon.picture(app, size, name)
                val bmp = BitmapFactory.decodeByteArray(png, 0, png.size)
                assertEquals(size, bmp.width)
                assertEquals(size, bmp.height)
                File(out, "${name.replace(Regex("[^A-Za-z]"), "")}-$size.png").writeBytes(png)
            }
        }
        File(out, "WoodruffFamily-redrawn-512.png").outputStream().use { redrawn.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** Mean difference per colour channel (0-255) between [a] and [b] in rows [y0] until [y1]. */
    private fun difference(a: Bitmap, b: Bitmap, y0: Int, y1: Int): Double {
        var sum = 0L
        var n = 0
        for (y in y0 until y1) for (x in 0 until a.width) {
            val p = a.getPixel(x, y)
            val q = b.getPixel(x, y)
            sum += abs((p shr 16 and 0xFF) - (q shr 16 and 0xFF)) + abs((p shr 8 and 0xFF) - (q shr 8 and 0xFF)) + abs((p and 0xFF) - (q and 0xFF))
            n += 3
        }
        return sum.toDouble() / n
    }
}
