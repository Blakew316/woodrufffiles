package com.woodruff.tvexplorer.player

import android.app.Application
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.media3.common.Player
import androidx.media3.ui.PlayerView
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import java.io.File
import java.time.Duration

/**
 * A film left paused in the player (the TV's Home, then the app row the next day) and the family's
 * progress on the other TVs: it comes back where the family has got to with it, and opening it and
 * leaving again never stamps this TV's old spot over that progress on every TV, nor brings back a
 * film finished elsewhere. The other TVs' progress is merged in as FamilySync merges it.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-television-xhdpi")
class PlayerFamilySpotTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    /** Every player opened, closed after each test: one left started would hear the next test's changes. */
    private val opened = ArrayList<ActivityController<PlayerActivity>>()

    @After fun closeThePlayers() {
        opened.asReversed().forEach { it.close() }
    }

    /** A stand-in film of its own for each test (nothing plays on the JVM: its queue and spot are what count). */
    private fun film(name: String): File =
        File(File(app.cacheDir, "family-spot-$name").apply { mkdirs() }, "Midnight Harbor (2024).mp4").apply { writeBytes(ByteArray(64)) }

    private fun open(film: File): ActivityController<PlayerActivity> =
        Robolectric.buildActivity(PlayerActivity::class.java, PlayerActivity.intent(app, listOf(film.path), listOf("Midnight Harbor (2024)"), film.path))
            .setup().also(opened::add)

    private fun player(c: ActivityController<PlayerActivity>): Player {
        fun find(v: View): PlayerView? = v as? PlayerView ?: (v as? ViewGroup)?.let { g -> (0 until g.childCount).firstNotNullOfOrNull { find(g.getChildAt(it)) } }
        return find(c.get().window.decorView)!!.player!!
    }

    /** Time passes (the TV's clock with it). */
    private fun idle() = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300))

    private fun entry(key: String): RecentItem? = RecentStore(app).all().firstOrNull { it.key == key }

    /** The other TVs' progress merged in, as FamilySync does: [item] in place of this TV's entry, or [finished] taken out. */
    private fun fromTheOtherTvs(item: RecentItem? = null, finished: String? = null) {
        RecentStore(app).syncWith { items, gone ->
            (listOfNotNull(item) + items.filter { it.key != item?.key && it.key != finished }) to
                (gone + listOfNotNull(finished?.let { it to System.currentTimeMillis() }))
        }
    }

    /** Paused at 30:00 in this room and the remote's Home pressed: kept, with the player on top of Woodruff. */
    private fun leftAtHalfAnHour(film: File): ActivityController<PlayerActivity> {
        val c = open(film)
        idle()
        player(c).seekTo(HALF_HOUR)
        c.userLeaving()
        c.pause().stop()
        assertEquals(HALF_HOUR, entry(film.path)?.positionMs)
        idle()
        return c
    }

    private fun bedroom(key: String) = RecentItem(key, "Midnight Harbor (2024)", HOUR, 2 * HOUR, System.currentTimeMillis())

    @Test
    fun openedFromTheAppRowAndLeftAgainItKeepsNothing() {
        val film = film("as-it-was")
        val c = leftAtHalfAnHour(film)
        val kept = entry(film.path)
        // Next day, from the app row: paused at 30:00. Back, Back: out again without playing it.
        c.restart().resume()
        idle()
        assertEquals(HALF_HOUR, player(c).currentPosition)
        c.pause().stop()
        assertEquals("nothing saved again as the family's newest", kept, entry(film.path))
    }

    @Test
    fun watchedOnInAnotherRoomItComesBackWhereTheFamilyGotToAndLeavesThatAlone() {
        val film = film("watched-on")
        val c = leftAtHalfAnHour(film)
        // That evening the bedroom TV watches it on from 30:00 to 60:00.
        val theirs = bedroom(film.path)
        fromTheOtherTvs(theirs)
        idle()
        c.restart().resume()
        idle()
        val p = player(c)
        assertEquals("where the family got to", HOUR, p.currentPosition)
        assertFalse("still paused, as left", p.playWhenReady)
        c.pause().stop()
        // Every TV's Continue watching still offers it at 60:00, not this room's 30:00.
        assertEquals(theirs, entry(film.path))
    }

    @Test
    fun finishedInAnotherRoomItComesBackPausedAndStaysFinished() {
        val film = film("finished")
        val c = open(film)
        idle()
        player(c).seekTo(HALF_HOUR)
        // The TV put to standby mid-film: nobody chose to leave, so it would come back playing.
        c.pause().stop()
        idle()
        // Watched to the end on the bedroom TV meanwhile.
        fromTheOtherTvs(finished = film.path)
        idle()
        c.restart().resume()
        idle()
        val p = player(c)
        assertEquals("at its spot", HALF_HOUR, p.currentPosition)
        assertFalse("not playing on by itself", p.playWhenReady)
        c.pause().stop()
        assertNull("not back in Continue watching", entry(film.path))
        assertTrue("still finished on every TV", film.path in RecentStore(app).removals())
    }

    @Test
    fun theFamilysProgressComingInJustAfterItCameBackMovesItThere() {
        val film = film("catch-up")
        val c = leftAtHalfAnHour(film)
        c.restart().resume()
        idle()
        assertEquals(HALF_HOUR, player(c).currentPosition)
        // The other TVs' progress, fetched as it came back, arrives a moment later.
        val theirs = bedroom(film.path)
        fromTheOtherTvs(theirs)
        idle()
        assertEquals(HOUR, player(c).currentPosition)
        c.pause().stop()
        assertEquals(theirs, entry(film.path))
    }

    @Test
    fun butOnceSomeoneMovedItHereItStaysWhereTheyPutItAndThatIsKept() {
        val film = film("moved-here")
        val c = leftAtHalfAnHour(film)
        c.restart().resume()
        idle()
        // Scrubbed on a little, still paused.
        player(c).seekTo(HALF_HOUR + 100_000)
        fromTheOtherTvs(bedroom(film.path))
        idle()
        assertEquals(HALF_HOUR + 100_000, player(c).currentPosition)
        c.pause().stop()
        // Their choice, the newest now.
        assertEquals(HALF_HOUR + 100_000, entry(film.path)?.positionMs)
    }

    @Test
    fun madeAgainAfterAndroidClosedWoodruffItComesBackWhereTheFamilyGotTo() {
        val film = film("made-again")
        val c = open(film)
        idle()
        player(c).seekTo(HALF_HOUR)
        c.userLeaving()
        c.pause().stop()
        val saved = android.os.Bundle()
        c.saveInstanceState(saved)
        c.destroy()
        idle()
        val theirs = bedroom(film.path)
        fromTheOtherTvs(theirs)
        idle()
        val again = Robolectric.buildActivity(PlayerActivity::class.java, c.intent).setup(saved).also(opened::add)
        idle()
        assertEquals(HOUR, player(again).currentPosition)
        assertFalse(player(again).playWhenReady)
        again.pause().stop()
        assertEquals(theirs, entry(film.path))
    }

    private companion object {
        const val HALF_HOUR = 1_800_000L
        const val HOUR = 3_600_000L
    }
}
