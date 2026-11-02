package com.woodruff.tvexplorer.ui.screens

import com.woodruff.tvexplorer.player.DoorbellActivity
import com.woodruff.tvexplorer.player.autoCloses
import com.woodruff.tvexplorer.player.backPhrase
import com.woodruff.tvexplorer.ui.Screen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/** The words over a camera full screen (doorbell.md §3.1). */
class CameraCaptionTest {
    private val sevenFortyTwo = 1_793_043_720_000L // 19:42 UTC

    private fun clock(pattern: String) = SimpleDateFormat(pattern, Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }

    @Test fun theEyebrowSaysWhenItRangInTheTvsOwnFormat() {
        assertEquals("RANG AT 7:42 PM", rangAt(sevenFortyTwo, clock("h:mm a")))
        assertEquals("RANG AT 19:42", rangAt(sevenFortyTwo, clock("HH:mm")))
        // Languages that write "pm" in lower case: the eyebrow is upper case all the same.
        val lower = clock("h:mm a").apply { dateFormatSymbols = dateFormatSymbols.apply { amPmStrings = arrayOf("am", "pm") } }
        assertEquals("RANG AT 7:42 PM", rangAt(sevenFortyTwo, lower))
    }

    @Test fun theHintOffersLeftAndRightOnlyWithCamerasToSwitchTo() {
        assertEquals("◀ ▶ switch camera  ·  Back to the slideshow", cameraHint("Back to the slideshow", 3))
        assertEquals("Back to the slideshow", cameraHint("Back to the slideshow", 1))
        assertEquals("Back for all cameras", cameraHint("Back for all cameras", 0))
    }

    @Test fun backSaysWhereItGoes() {
        assertEquals("Back to keep watching", backPhrase(DoorbellActivity.Origin.FILM, null))
        assertEquals("Back to the slideshow", backPhrase(DoorbellActivity.Origin.SLIDESHOW, null))
        assertEquals("Back to close", backPhrase(DoorbellActivity.Origin.SCREENSAVER, null))
        assertEquals("Back to Home", backPhrase(DoorbellActivity.Origin.SCREEN, "Home"))
        assertEquals("Back to Woodruff", backPhrase(DoorbellActivity.Origin.SCREEN, null))
    }

    @Test fun itClosesByItselfAnywhereButOverAFilm() {
        assertFalse(autoCloses(DoorbellActivity.Origin.FILM))
        assertTrue(autoCloses(DoorbellActivity.Origin.SLIDESHOW))
        assertTrue(autoCloses(DoorbellActivity.Origin.SCREENSAVER))
        assertTrue(autoCloses(DoorbellActivity.Origin.SCREEN))
    }

    @Test fun eachScreenIsNamedAsTheMenuAndItsTitleSay() {
        assertEquals("Home", screenName(Screen.HOME, null, false))
        assertEquals("Movies", screenName(Screen.DRIVE, "Movies", false))
        assertEquals("TV Shows", screenName(Screen.DRIVE, "TV Shows", false))
        assertEquals("Streaming", screenName(Screen.DRIVE, null, false))
        assertEquals("Family Memories", screenName(Screen.BROWSER, null, true))
        assertEquals("Files", screenName(Screen.BROWSER, null, false))
        assertEquals("Settings", screenName(Screen.SETTINGS, null, false))
        assertEquals("Notifications", screenName(Screen.NOTIFICATIONS, null, false))
        assertEquals("Mail", screenName(Screen.MAIL, null, false))
        assertEquals("Notes", screenName(Screen.NOTES, null, false))
        assertEquals("Apps", screenName(Screen.APPS, null, false))
        // Every screen has a name that reads after "Back to".
        Screen.entries.forEach { assertTrue(screenName(it, null, false).isNotBlank()) }
    }
}
