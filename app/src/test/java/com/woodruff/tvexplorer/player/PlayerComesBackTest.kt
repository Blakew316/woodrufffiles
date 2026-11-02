package com.woodruff.tvexplorer.player

import android.app.Application
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.media3.ui.PlayerView
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
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
 * Pressing the TV's Home during a film and coming back from the app row (browsing-motion §3.4): the
 * film is still there, paused at its spot with the controls up, rather than playing on by itself.
 * Covered by something nobody chose (the TV's screensaver, standby), it comes back as it was.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-television-xhdpi")
class PlayerComesBackTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()

    /** The player opened on a film (a stand-in file: nothing plays on the JVM, the queue is what counts). */
    private fun open(): ActivityController<PlayerActivity> {
        val film = File(app.cacheDir, "Midnight Harbor (2024).mp4").apply { writeBytes(ByteArray(64)) }
        return Robolectric.buildActivity(PlayerActivity::class.java, PlayerActivity.intent(app, listOf(film.path), listOf("Midnight Harbor (2024)"), film.path)).setup()
    }

    private fun playerView(c: ActivityController<PlayerActivity>): PlayerView {
        fun find(v: View): PlayerView? = v as? PlayerView ?: (v as? ViewGroup)?.let { g -> (0 until g.childCount).firstNotNullOfOrNull { find(g.getChildAt(it)) } }
        return find(c.get().window.decorView)!!
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300))

    @Test
    fun leftForTheTvsHomeItComesBackPausedWithTheControlsUp() {
        val c = open()
        idle()
        assertTrue("a film chosen plays", playerView(c).player!!.playWhenReady)
        // The remote's Home: Android tells the player someone chose to leave, then it goes to the back.
        c.userLeaving()
        c.pause().stop()
        // Woodruff again from the TV's app row: its task as it was, the player on top.
        c.restart().resume()
        idle()
        val view = playerView(c)
        assertFalse("paused where it was", view.player!!.playWhenReady)
        assertTrue("the controls are up", view.isControllerFullyVisible)
    }

    @Test
    fun coveredByNothingAnyoneChoseItComesBackAsItWas() {
        val c = open()
        idle()
        // The TV going to standby: no one chose to leave.
        c.pause().stop()
        c.restart().resume()
        idle()
        assertTrue(playerView(c).player!!.playWhenReady)
    }

    @Test
    fun leftByChoiceSurvivesAndroidClosingWoodruffMeanwhile() {
        val c = open()
        idle()
        c.userLeaving()
        c.pause().stop()
        val saved = android.os.Bundle()
        c.saveInstanceState(saved)
        c.destroy()
        // Made again from what Android kept, as after Woodruff was closed to free memory.
        val again = Robolectric.buildActivity(PlayerActivity::class.java, c.intent).setup(saved)
        idle()
        assertFalse(playerView(again).player!!.playWhenReady)
    }
}
