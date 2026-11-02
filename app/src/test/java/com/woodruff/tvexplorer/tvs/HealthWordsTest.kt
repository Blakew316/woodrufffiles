package com.woodruff.tvexplorer.tvs

import com.woodruff.tvexplorer.tvs.HealthTest.HOUR
import com.woodruff.tvexplorer.tvs.HealthTest.MIN
import com.woodruff.tvexplorer.tvs.HealthTest.at
import com.woodruff.tvexplorer.tvs.HealthTest.clock
import com.woodruff.tvexplorer.tvs.HealthTest.now
import com.woodruff.tvexplorer.tvs.HealthTest.zone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** How a report words times, reasons, the doorbell and crashes. */
class HealthWordsTest {
    @Test fun howLongAgo() {
        val c = clock()
        assertEquals("just now", c.ago(now - 30_000))
        assertEquals("3 min ago", c.ago(now - 3 * MIN))
        assertEquals("2 h ago", c.ago(now - 2 * HOUR))
        assertEquals("14 h ago", c.ago(at(2026, 11, 2, 1, 0)))           // still today
        assertEquals("yesterday", c.ago(at(2026, 11, 1, 9, 0)))
        assertEquals("Oct 30", c.ago(at(2026, 10, 30, 9, 0)))
        // Just after midnight, last evening is hours ago, not "yesterday".
        assertEquals("3 h ago", clock(at(2026, 11, 2, 0, 30)).ago(at(2026, 11, 1, 21, 30)))
    }

    @Test fun momentsAsTheTvWritesThem() {
        val c = clock()
        assertEquals("3:10 AM", c.moment(at(2026, 11, 2, 3, 10)))
        assertEquals("yesterday", c.moment(at(2026, 11, 1, 23, 59)))
        assertEquals("Oct 30", c.moment(at(2026, 10, 30, 12, 0)))
        assertEquals("9:41 PM", clock(at(2026, 11, 1, 22, 0)).stamp(at(2026, 11, 1, 21, 41)))
        assertEquals("Yesterday, 9:41 PM", c.stamp(at(2026, 11, 1, 21, 41)))
        assertEquals("Oct 30, 9:41 PM", c.stamp(at(2026, 10, 30, 21, 41)))
        assertEquals("today", c.onDay(now - HOUR))
        assertEquals("yesterday", c.onDay(at(2026, 11, 1, 8, 0)))
        assertEquals("on Oct 30", c.onDay(at(2026, 10, 30, 8, 0)))
        // Across the new year.
        assertEquals("yesterday", clock(at(2027, 1, 1, 10, 0)).moment(at(2026, 12, 31, 10, 0)))
        assertEquals("Dec 30", clock(at(2027, 1, 1, 10, 0)).moment(at(2026, 12, 30, 10, 0)))
    }

    @Test fun updateFailuresInPlainWords() {
        assertNull(plainUpdateReason(null))
        assertNull(plainUpdateReason("  "))
        assertEquals("There wasn’t enough free space.", plainUpdateReason("INSTALL_FAILED_INSUFFICIENT_STORAGE: Failed to allocate"))
        assertEquals("The update isn’t signed like the Woodruff on this TV.", plainUpdateReason("INSTALL_FAILED_UPDATE_INCOMPATIBLE: signatures do not match"))
        assertEquals("Someone chose Cancel.", plainUpdateReason("INSTALL_FAILED_ABORTED: User rejected permissions"))
        assertEquals("The package installer said no.", plainUpdateReason("INSTALL_FAILED_INTERNAL_ERROR"))
        assertEquals("GitHub didn’t answer. It tries again by itself.", plainUpdateReason("GitHub answered 403"))
        assertEquals("The TV couldn’t reach GitHub. It tries again by itself.", plainUpdateReason("timeout"))
        // Updater's own words stay as they are, as sentences: it writes them to follow "last try:" in Settings.
        assertEquals("The update didn't install.", plainUpdateReason("The update didn't install"))
        assertEquals("Android asked for a press.", plainUpdateReason("Android asked for a press."))
        assertEquals("The TV is out of space.", plainUpdateReason("the TV is out of space"))
        assertEquals("This TV doesn’t allow it.", plainUpdateReason("this TV doesn’t allow it"))
        assertEquals("The TV’s install screen didn’t open.", plainUpdateReason("the TV’s install screen didn’t open"))
        assertEquals("Couldn’t connect · trying again soon.", plainUpdateReason("Couldn’t connect · trying again soon"))
    }

    @Test fun theDoorbellInTheReportsWords() {
        assertEquals("Off", doorbellFact(subscribed = false, status = "anything", networkUp = true).text)
        assertEquals("Watching", doorbellFact(subscribed = true, status = null, networkUp = true).text)
        // A network failure isn't the doorbell failing.
        doorbellFact(subscribed = true, status = "Can't reach Google right now.", networkUp = true).let {
            assertEquals("Can’t reach Google", it.text); assertEquals(DoorbellState.UNREACHABLE, it.state)
        }
        assertEquals("Offline", doorbellFact(subscribed = true, status = "Unable to resolve host \"pubsub.googleapis.com\"", networkUp = false).text)
        assertEquals(DoorbellState.UNREACHABLE, doorbellFact(subscribed = true, status = "Pub/Sub error 503", networkUp = true).state)
        doorbellFact(subscribed = true, status = "Google refused the doorbell subscription. Connect the cameras again (to allow Pub/Sub) and check the subscription name.", networkUp = true).let {
            assertEquals("Google refused", it.text); assertEquals(DoorbellState.FAILING, it.state)
        }
        assertEquals("Subscription not found", doorbellFact(subscribed = true, status = "Subscription not found. Check its name.", networkUp = true).text)
        assertEquals("Cameras not connected", doorbellFact(subscribed = true, status = "Connect your cameras first", networkUp = true).text)
    }

    @Test fun aCrashInAFewWords() {
        assertEquals("IllegalStateException in the player", crashSummary(HealthTest.CRASH))
        assertEquals("OutOfMemoryError", crashSummary("Version 2.0.1 · Nov 1, 9:41 PM · thread main\njava.lang.OutOfMemoryError: Failed to allocate\n\tat android.graphics.Bitmap.nativeCreate(Native Method)"))
        assertEquals("NullPointerException in the cameras", crashSummary("x\njava.lang.NullPointerException\n\tat com.woodruff.tvexplorer.nest.CameraStream.start(CameraStream.kt:10)"))
        assertEquals("A crash", crashSummary("nothing to read here"))
    }

    @Test fun whenACrashHappenedFromItsReport() {
        assertEquals(at(2026, 11, 1, 21, 41), crashTime(HealthTest.CRASH, now, zone))
        // Written without the year: December's crash read in January was last year's.
        val jan = at(2027, 1, 3, 9, 0)
        assertEquals(at(2026, 12, 30, 8, 5), crashTime("Version 2.0.1 · Dec 30, 8:05 AM · thread main\n", jan, zone))
        assertEquals(now, crashTime("no time in it", now, zone))
    }

    @Test fun fragmentsJoinIntoOneSentence() {
        fun item(words: String) = HealthItem("x", words, "", "none")
        assertEquals("Everything’s working.", sentenceOf(emptyList()))
        assertEquals("The last update didn’t install.", sentenceOf(listOf(item("the last update didn’t install"))))
        assertEquals("Google Drive’s sign-in ended, and the last update didn’t install.", sentenceOf(listOf(item("Google Drive’s sign-in ended"), item("the last update didn’t install"))))
        assertEquals("A, b, and c.", sentenceOf(listOf(item("a"), item("b"), item("c"))))
        assertEquals("the cameras’ sign-in ended", fragment("The cameras’ sign-in ended"))
        assertEquals("Woodruff crashed today", fragment("Woodruff crashed today"))
        assertEquals("Google Photos’ sign-in ended", fragment("Google Photos’ sign-in ended"))
        assertEquals("only 600 MB is free", fragment("Only 600 MB is free"))
    }
}
