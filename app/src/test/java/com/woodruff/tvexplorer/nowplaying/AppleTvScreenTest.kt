package com.woodruff.tvexplorer.nowplaying

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Woodruff on the Apple TV saying it's on the Apple TV's screen (/api/appletv): how long each word lasts, and when
 * this TV takes its screen to be on the Apple TV: on the input named for it, or while the Apple TV says so and no
 * app of the TV's own is playing.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class AppleTvScreenTest {
    private val input = Playing("HDMI 1", null, "TV", true, 0, 0)
    private val named = Playing("Apple TV HDMI 4", null, "TV", true, 0, 0)
    private val netflix = Playing("Stranger Things", "S4, E1", "Netflix", true, 600_000, 3_000_000)

    // Both are the process's own: each test starts with nothing said and nothing of Woodruff's playing.
    @Before fun nothingSaidYet() { AppleTvScreen.said(false); NowPlaying.ownPlayer = null }
    @After fun forget() { AppleTvScreen.said(false); NowPlaying.ownPlayer = null }

    @Test fun aWordLastsAsLongAsItSays() {
        assertFalse(AppleTvScreen.onScreen(1_000))
        AppleTvScreen.said(true, 25, now = 1_000)
        assertTrue(AppleTvScreen.onScreen(1_000))
        assertTrue(AppleTvScreen.onScreen(25_999))
        assertFalse(AppleTvScreen.onScreen(26_000))
        // Said again 10 s later, as the Apple TV does while it's on screen: 25 s from then.
        AppleTvScreen.said(true, 25, now = 11_000)
        assertTrue(AppleTvScreen.onScreen(35_999))
        assertFalse(AppleTvScreen.onScreen(36_000))
    }

    @Test fun leavingCountsAtOnce() {
        AppleTvScreen.said(true, 25, now = 1_000)
        AppleTvScreen.said(false, now = 2_000)
        assertFalse(AppleTvScreen.onScreen(2_000))
    }

    @Test fun aWordLastsFiveToSixtySeconds() {
        AppleTvScreen.said(true, 1, now = 0)
        assertTrue(AppleTvScreen.onScreen(4_999))
        assertFalse(AppleTvScreen.onScreen(5_000))
        AppleTvScreen.said(true, -30, now = 0)
        assertTrue(AppleTvScreen.onScreen(4_999))
        assertFalse(AppleTvScreen.onScreen(5_000))
        AppleTvScreen.said(true, 3_600, now = 0)
        assertTrue(AppleTvScreen.onScreen(59_999))
        assertFalse(AppleTvScreen.onScreen(60_000))
        // One that doesn't say how long: 25 s.
        AppleTvScreen.said(true, now = 0)
        assertTrue(AppleTvScreen.onScreen(24_999))
        assertFalse(AppleTvScreen.onScreen(25_000))
    }

    @Test fun theAppleTvsInputCountsByItself() {
        assertTrue(showsAppleTv(named, appleTvSays = false))
        assertTrue(showsAppleTv(named, appleTvSays = true))
    }

    @Test fun theAppleTvsWordCountsWhileNoAppOfTheTvsOwnPlays() {
        // Nothing seen (no Notification access, or nothing playing): the Apple TV is taken at its word.
        assertTrue(showsAppleTv(null, appleTvSays = true))
        // An input not named for the Apple TV is still an input, maybe the Apple TV's.
        assertTrue(showsAppleTv(input, appleTvSays = true))
        assertTrue(showsAppleTv(Playing("Input", null, "Live TV", true, 0, 0), appleTvSays = true))
        // An app that played earlier, paused now.
        assertTrue(showsAppleTv(netflix.copy(playing = false), appleTvSays = true))
    }

    @Test fun anAppOfTheTvsOwnPlayingIsTheTvGoneFromTheAppleTv() {
        assertFalse(showsAppleTv(netflix, appleTvSays = true))
        assertFalse(showsAppleTv(Playing("Lofi Radio", null, "YouTube", true, 0, 0), appleTvSays = true))
        assertTrue(otherAppPlaying(netflix))
        assertFalse(otherAppPlaying(input))
        assertFalse(otherAppPlaying(named))
        assertFalse(otherAppPlaying(netflix.copy(playing = false)))
        assertFalse(otherAppPlaying(null))
    }

    @Test fun aWordRunOutCountsForNothing() {
        assertFalse(showsAppleTv(null, appleTvSays = false))
        assertFalse(showsAppleTv(input, appleTvSays = false))
        assertFalse(showsAppleTv(netflix, appleTvSays = false))
    }

    @Test fun theTvGoesByTheAppleTvsWordUntilItRunsOut() {
        val c = ApplicationProvider.getApplicationContext<Context>()
        // Robolectric's media sessions list nothing, as on a TV without Notification access.
        assertFalse(NowPlaying.onAppleTv(c, now = 1_000))
        AppleTvScreen.said(true, 25, now = 1_000)
        assertTrue(NowPlaying.onAppleTv(c, now = 2_000))
        assertFalse(NowPlaying.onAppleTv(c, now = 26_000))
        // Woodruff's own player open on this TV: not the Apple TV, whatever it says.
        AppleTvScreen.said(true, 25, now = 30_000)
        NowPlaying.ownPlayer = Playing("Midnight Harbor", null, "Woodruff Family", true, 2_710_000, 6_840_000, key = "drive:m1")
        assertFalse(NowPlaying.onAppleTv(c, now = 31_000))
    }
}
