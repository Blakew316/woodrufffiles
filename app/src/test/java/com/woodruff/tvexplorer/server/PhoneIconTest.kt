package com.woodruff.tvexplorer.server

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

/** Each TV's web app is labelled with the TV's name on a phone's Home Screen, and nothing else changes. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class PhoneIconTest {
    private val page = File("src/main/assets/web/index.html").readText()
    private val manifest = File("src/main/assets/web/manifest.webmanifest").readText()

    @Test fun thePageIsLabelledWithTheTvsName() {
        val html = PhoneIcon.render(page, "k3y", "Living Room")
        assertTrue(html.contains("""<meta name="apple-mobile-web-app-title" content="Living Room">"""))
        assertTrue(html.contains("var TVX_KEY = 'k3y';"))
        assertTrue(html.contains("/manifest.webmanifest?v=2&amp;n=${PhoneIcon.tag("Living Room")}\""))
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
        assertEquals(original.getJSONArray("icons").toString(), o.getJSONArray("icons").toString())
    }
}
