package com.woodruff.tvexplorer.nowplaying

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Woodruff on an Apple TV saying it's on the Apple TV's screen (/api/appletv): how long each word lasts and whose it
 * is, and when this TV takes its screen to be on the Apple TV: on the input named for it, or while an Apple TV says so
 * and this TV shows the input it showed as the word began, or nothing it can tell by. Never while it shows its home
 * screen, an app of its own (playing, or paused since), or another input.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class AppleTvScreenTest {
    private val hdmi2 = Playing("HDMI 2", null, "TV", true, 0, 0)
    private val hdmi3 = Playing("HDMI 3", null, "TV", true, 0, 0)
    private val named = Playing("Apple TV HDMI 4", null, "TV", true, 0, 0)
    private val liveTv = Playing("BBC One", null, "Live TV", true, 0, 0)
    private val netflix = Playing("Stranger Things", "S4, E1", "Netflix", true, 600_000, 3_000_000)
    private val youTube = Playing("Lofi Radio", null, "YouTube", true, 0, 0)

    // The process's own: each test starts with nothing said, no input known and nothing of Woodruff's playing.
    @Before fun nothingSaidYet() { AppleTvScreen.forget(); NowPlaying.ownPlayer = null }
    @After fun forget() { AppleTvScreen.forget(); NowPlaying.ownPlayer = null }

    /** The words that hold once an Apple TV's word began at [at] with this TV showing [shows] (null: it can't see). */
    private fun began(shows: List<Playing>?, at: Long = 0L, from: String = "living-room-apple-tv"): List<AppleTvScreen.Word> {
        AppleTvScreen.said(true, 25, now = at, from = from) { shows }
        return AppleTvScreen.words(at)
    }

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

    @Test fun eachAppleTvsWordIsItsOwn() {
        AppleTvScreen.said(true, 25, now = 0, from = "apple-tv-1")
        AppleTvScreen.said(true, 25, now = 1_000, from = "apple-tv-2")
        // One Apple TV's "off" never cancels another's "on".
        AppleTvScreen.said(false, now = 2_000, from = "apple-tv-1")
        assertTrue(AppleTvScreen.onScreen(2_000))
        assertEquals(1, AppleTvScreen.words(2_000).size)
        AppleTvScreen.said(false, now = 3_000, from = "apple-tv-2")
        assertFalse(AppleTvScreen.onScreen(3_000))
        // An Apple TV on an older Woodruff doesn't say who it is: it's one sender of its own.
        AppleTvScreen.said(true, 25, now = 4_000)
        AppleTvScreen.said(false, now = 5_000, from = "apple-tv-1")
        assertTrue(AppleTvScreen.onScreen(5_000))
        AppleTvScreen.said(false, now = 6_000, from = "")
        assertFalse(AppleTvScreen.onScreen(6_000))
    }

    @Test fun eachAppleTvsNewestWordCounts() {
        AppleTvScreen.said(true, 25, now = 0, from = "apple-tv-1")
        AppleTvScreen.said(false, now = 1_000, from = "apple-tv-1")
        assertFalse(AppleTvScreen.onScreen(1_000))
        AppleTvScreen.said(true, 25, now = 2_000, from = "apple-tv-1")
        assertTrue(AppleTvScreen.onScreen(2_000))
        // Each one's word lasts as it said: the other's running out doesn't end it.
        AppleTvScreen.said(true, 5, now = 2_000, from = "apple-tv-2")
        assertTrue(AppleTvScreen.onScreen(10_000))
        assertEquals(1, AppleTvScreen.words(10_000).size)
    }

    @Test fun theInputNamedForTheAppleTvCountsByItself() {
        assertTrue(showsAppleTv(listOf(named), emptyList()))
        assertTrue(showsAppleTv(listOf(named), began(null)))
        // Left behind (no longer playing), it's no longer on screen.
        assertFalse(showsAppleTv(listOf(named.copy(playing = false)), emptyList()))
    }

    @Test fun withoutAWordNothingIsHeld() {
        for (shows in listOf(null, emptyList(), listOf(hdmi2), listOf(netflix), listOf(netflix.copy(playing = false)))) {
            assertFalse(showsAppleTv(shows, emptyList()))
        }
    }

    @Test fun aTvThatCantTellWhatsOnItsScreenTakesTheWord() {
        // No Notification access, or no app nor input says anything here.
        assertTrue(showsAppleTv(null, began(null)))
        assertTrue(showsAppleTv(emptyList(), began(emptyList())))
        assertTrue(showsAppleTv(null, began(listOf(hdmi2))))
    }

    @Test fun theInputTheTvShowedAsTheWordBeganIsTheAppleTvs() {
        val w = began(listOf(hdmi2))
        assertTrue(showsAppleTv(listOf(hdmi2), w))
        // Another input (a games console), Live TV, or an app of the TV's own: not the Apple TV.
        assertFalse(showsAppleTv(listOf(hdmi3), w))
        assertFalse(showsAppleTv(listOf(liveTv), w))
        assertFalse(showsAppleTv(listOf(netflix), w))
        assertFalse(showsAppleTv(listOf(youTube, hdmi2.copy(playing = false)), w))
    }

    @Test fun leavingTheInputItBeganOnIsTheTvGoneFromTheAppleTv() {
        val w = began(listOf(hdmi2, netflix.copy(playing = false)))
        // The TV's home screen or its settings: nothing plays, the input is left behind.
        assertFalse(showsAppleTv(listOf(hdmi2.copy(playing = false)), w))
        assertFalse(showsAppleTv(listOf(netflix.copy(playing = false), hdmi2.copy(playing = false)), w))
        // Back on it.
        assertTrue(showsAppleTv(listOf(hdmi2, netflix.copy(playing = false)), w))
    }

    @Test fun whereTheAppleTvsInputNamesNothingAnInputPlayingIsAnother() {
        // The Sonys: on the Apple TV's input nothing says so, only an app paused earlier in the day is there.
        val earlier = netflix.copy(playing = false)
        val w = began(listOf(earlier))
        assertTrue(showsAppleTv(listOf(earlier), w))
        assertTrue(showsAppleTv(emptyList(), w))
        // Switched to Live TV, or to an input that does say so: not the Apple TV.
        assertFalse(showsAppleTv(listOf(liveTv, earlier), w))
        assertFalse(showsAppleTv(listOf(hdmi3, earlier), w))
    }

    @Test fun anAppOfTheTvsOwnPlayingOrPausedSinceIsTheTvGoneToIt() {
        val earlier = netflix.copy(playing = false)
        val w = began(listOf(earlier))
        assertFalse(showsAppleTv(listOf(netflix), w))
        assertFalse(showsAppleTv(listOf(youTube, earlier), w))
        // Paused again after playing a while since the word began: it's been in front.
        assertFalse(showsAppleTv(listOf(earlier.copy(positionMs = earlier.positionMs + 5 * 60_000)), w))
        // An app opened and paused since: new.
        assertFalse(showsAppleTv(listOf(youTube.copy(playing = false), earlier), w))
        // The one from before, as it was: nothing to go by but the word.
        assertTrue(showsAppleTv(listOf(earlier.copy(positionMs = earlier.positionMs + 2_000)), w))
    }

    @Test fun anAppPausedByTheSwitchToTheAppleTvIsntNews() {
        // HDMI-CEC switches the TV as the Apple TV wakes: Netflix was still playing as the word began, and paused a moment later.
        val w = began(listOf(netflix))
        assertTrue(showsAppleTv(listOf(netflix.copy(playing = false, positionMs = netflix.positionMs + 3_000)), w))
    }

    @Test fun whatTheTvShowsSettlesOverTheFirstSecondsOfAWord() {
        // The TV still on Netflix as the word begins; 10 s later on HDMI 2, as the next word sees.
        AppleTvScreen.said(true, 25, now = 0) { listOf(netflix) }
        AppleTvScreen.said(true, 25, now = 10_000) { listOf(hdmi2, netflix.copy(playing = false)) }
        assertEquals(hdmi2, AppleTvScreen.words(10_000).single().input)
        AppleTvScreen.said(true, 25, now = 20_000) { listOf(hdmi2) }
        // Past the first 30 s what the TV shows no longer moves it: a games console later is another input.
        AppleTvScreen.said(true, 25, now = 30_000) { listOf(hdmi3) }
        AppleTvScreen.said(true, 25, now = 40_000) { listOf(hdmi3) }
        val w = AppleTvScreen.words(40_000)
        assertEquals(hdmi2, w.single().input)
        assertFalse(showsAppleTv(listOf(hdmi3), w))
        assertTrue(showsAppleTv(listOf(hdmi2), w))
    }

    @Test fun onAnAppAsTheWordBeganTheFirstInputPlayingIsTakenForTheAppleTvs() {
        AppleTvScreen.said(true, 25, now = 0) { listOf(netflix) }
        AppleTvScreen.said(true, 25, now = 20_000) { listOf(netflix) }
        var w = AppleTvScreen.words(20_000)
        assertTrue(w.single().onApp)
        // Not known yet: an input playing may be the Apple TV's.
        assertTrue(showsAppleTv(listOf(hdmi2), w))
        AppleTvScreen.said(true, 25, now = 30_000) { listOf(netflix) }
        AppleTvScreen.said(true, 25, now = 40_000) { listOf(netflix.copy(playing = false)) }
        assertTrue(AppleTvScreen.words(40_000).single().onApp)
        // Later, the TV comes to HDMI 2: that's the Apple TV's from then on.
        AppleTvScreen.said(true, 25, now = 50_000) { listOf(hdmi2) }
        AppleTvScreen.said(true, 25, now = 60_000) { listOf(hdmi3) }
        w = AppleTvScreen.words(60_000)
        assertEquals(hdmi2, w.single().input)
        assertFalse(showsAppleTv(listOf(hdmi3), w))
        assertTrue(showsAppleTv(listOf(hdmi2), w))
    }

    @Test fun anInputNamedForAnAppleTvRulesOutTheOthers() {
        // This TV has shown its input named for the Apple TV: another input playing isn't the Apple TV's.
        AppleTvScreen.noteNamed(listOf(named.copy(playing = false)))
        val w = began(listOf(netflix))
        assertEquals(named.copy(playing = false), AppleTvScreen.namedInput)
        assertFalse(showsAppleTv(listOf(hdmi2), w, AppleTvScreen.namedInput))
        assertTrue(showsAppleTv(listOf(named), w, AppleTvScreen.namedInput))
    }

    @Test fun theTvGoesByTheAppleTvsWordUntilItRunsOut() {
        val c = ApplicationProvider.getApplicationContext<Context>()
        // Robolectric's media sessions list nothing, as on a TV that can't tell what's on its screen.
        assertFalse(NowPlaying.onAppleTv(c, now = 1_000))
        NowPlaying.appleTvSaid(c, true, 25, from = "apple-tv-1", now = 1_000)
        assertTrue(NowPlaying.onAppleTv(c, now = 2_000))
        assertFalse(NowPlaying.onAppleTv(c, now = 26_000))
        // Woodruff's own player open on this TV: not the Apple TV, whatever it says.
        NowPlaying.appleTvSaid(c, true, 25, from = "apple-tv-1", now = 30_000)
        NowPlaying.ownPlayer = Playing("Midnight Harbor", null, "Woodruff Family", true, 2_710_000, 6_840_000, key = "drive:m1")
        assertFalse(NowPlaying.onAppleTv(c, now = 31_000))
    }

    @Test fun theTvSaysWhetherItsOnAnInputAndWhetherItsNamedForAnAppleTv() {
        assertEquals(null to null, screenReport(null))
        assertEquals(null to null, screenReport(emptyList()))
        assertEquals(true to false, screenReport(listOf(hdmi2)))
        assertEquals(true to true, screenReport(listOf(named)))
        assertEquals(false to false, screenReport(listOf(netflix)))
        assertEquals(false to false, screenReport(listOf(hdmi2.copy(playing = false))))
        val c = ApplicationProvider.getApplicationContext<Context>()
        assertEquals(null to null, NowPlaying.appleTvReport(c))
        NowPlaying.ownPlayer = Playing("Midnight Harbor", null, "Woodruff Family", true, 2_710_000, 6_840_000, key = "drive:m1")
        assertEquals(false to false, NowPlaying.appleTvReport(c))
    }

    @Test fun aWordRunOutLeavesNothingNoted() {
        began(listOf(hdmi2), at = 0)
        assertTrue(AppleTvScreen.words(30_000).isEmpty())
        // The next word begins afresh, with what the TV shows then.
        AppleTvScreen.said(true, 25, now = 30_000) { listOf(hdmi3) }
        assertEquals(hdmi3, AppleTvScreen.words(30_000).single().input)
        assertNull(AppleTvScreen.words(30_000).single().before.firstOrNull { it == hdmi2 })
    }
}
