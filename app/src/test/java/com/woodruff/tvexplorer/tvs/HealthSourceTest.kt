package com.woodruff.tvexplorer.tvs

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

/** This TV's report is made from what Android and Woodruff already know, and says nothing secret. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class HealthSourceTest {
    private val c: Context = ApplicationProvider.getApplicationContext()

    @Test fun aNewTvsReport() {
        val r = Health.report(c, fresh = true)
        assertEquals(TvLinks.name(c), r.name)
        assertEquals(34, r.android)
        // No Google keys yet: nothing to sign in to, which isn't a problem.
        assertEquals("Not set up", r.signIns.getValue(HealthGrant.DRIVE).text)
        assertEquals("Not connected", r.signIns.getValue(HealthGrant.CAMERAS).text)
        assertEquals("3 keys not set", r.keys.text)
        assertEquals("Off", r.doorbell.text)
        assertNotNull(r.closedByAndroid)
        assertFalse(r.attention.any { it.id.startsWith("signin") })
        // What /api/status carries, and what a hub reads back from it.
        val status = TvLinks.status(c)
        assertTrue(status.has("health"))
        assertEquals(r.summary, HealthSummary.fromJson(status.getJSONObject("health")))
        assertTrue(status.has("board"))
    }

    @Test fun aCrashTheHandlerKeptIsTheLastCrashUntilThePhoneClearsIt() {
        val report = File(c.filesDir, "crash_report.txt")
        report.writeText(HealthTest.CRASH.replace("Nov 1, 9:41 PM", java.text.SimpleDateFormat("MMM d, h:mm a", java.util.Locale.US).format(java.util.Date())))
        try {
            val r = Health.report(c, fresh = true)
            assertNotNull(r.lastCrash)
            assertEquals("IllegalStateException in the player", r.lastCrash!!.summary)
            assertTrue(r.attention.any { it.id == "stability.crash" && it.fix == "crash" })
            report.delete()
            Health.crashesCleared(c)
            assertTrue(Health.report(c, fresh = true).attention.none { it.id == "stability.crash" })
        } finally {
            report.delete()
        }
    }
}
