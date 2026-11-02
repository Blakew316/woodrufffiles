package com.woodruff.tvexplorer.ui.screens

import android.content.Context
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.woodruff.tvexplorer.update.Updater.InstallFacts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.Calendar

/**
 * Settings → Updates → This copy: a few words in the row that fit its value column ("Updated Nov 2",
 * never a sentence cut inside a time), the whole story in its panel, dates and times as the TV writes them.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ThisCopyTest {
    private val day: (Long) -> String = { "day$it" }
    private val at: (Long) -> String = { "time$it" }

    @Test fun theRowSaysWhatChangedLastInAFewWords() {
        assertEquals("Updated day900000", thisCopyValue(InstallFacts(1_000, 900_000, "Woodruff itself", null), day))
        assertEquals("Fresh install day1000", thisCopyValue(InstallFacts(1_000, 1_000, "Downloader", null), day))
        // Within a minute of the install: the same install, not an update.
        assertEquals("Fresh install day1000", thisCopyValue(InstallFacts(1_000, 50_000, "Downloader", null), day))
        // Android didn't say when: nothing rather than a guess (the panel says who installed it).
        assertNull(thisCopyValue(InstallFacts(0, 0, "adb or a file app", null), day))
        // Short enough for the 200 dp value column with a date in it ("Fresh install Oct 13, 2025").
        assertTrue(thisCopyValue(InstallFacts(1_000, 1_000, "Downloader", null)) { "Oct 13, 2025" }!!.length <= 26)
    }

    @Test fun thePanelTellsTheWholeStory() {
        assertEquals(
            "Installed fresh time1000 · updated time900000 by Woodruff itself",
            thisCopyStory(InstallFacts(1_000, 900_000, "Woodruff itself", null), at),
        )
        assertEquals("Installed fresh time1000 by Downloader", thisCopyStory(InstallFacts(1_000, 1_000, "Downloader", null), at))
        assertEquals("Installed by adb or a file app", thisCopyStory(InstallFacts(0, 0, "adb or a file app", null), at))
        assertEquals(
            "Installed fresh time1000 by Downloader\nSign-ins lost time5000: the TV’s key store lost their key",
            thisCopyStory(InstallFacts(1_000, 1_000, "Downloader", 5_000), at),
        )
    }

    @Test fun timesAreWrittenAsTheTvWritesThem() {
        val c = ApplicationProvider.getApplicationContext<Context>()
        val ms = Calendar.getInstance().apply { set(2026, Calendar.NOVEMBER, 2, 15, 12, 0) }.timeInMillis
        fun spaced(s: String) = s.replace(' ', ' ').replace(' ', ' ')
        Settings.System.putString(c.contentResolver, Settings.System.TIME_12_24, "24")
        assertTrue(tvDayTime(c, ms), spaced(tvDayTime(c, ms)).endsWith(" at 15:12"))
        Settings.System.putString(c.contentResolver, Settings.System.TIME_12_24, "12")
        assertTrue(tvDayTime(c, ms), spaced(tvDayTime(c, ms)).endsWith(" at 3:12 PM"))
        assertTrue(tvDay(c, ms), tvDay(c, ms).startsWith("Nov 2"))
    }
}
