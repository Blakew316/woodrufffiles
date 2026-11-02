package com.woodruff.tvexplorer.ui.screens

import android.app.Application
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.woodruff.tvexplorer.photos.GooglePhotos
import com.woodruff.tvexplorer.ui.LocalOpenSidebar
import com.woodruff.tvexplorer.ui.Location
import com.woodruff.tvexplorer.ui.MainViewModel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.time.Duration

/**
 * Family Memories (browsing-motion §3.3): at the top of its photos, Back from far down glides to the
 * top with the first photo focused, and the next Back opens the side menu; inside a sub-folder, Back
 * still goes up a folder (README's remote table) and never glides.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-television-xhdpi")
class MemoriesBackTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val top = GooglePhotos.folder

    @After fun clean() { top.deleteRecursively() }

    /** [n] photos in [dir], "Beach 000" the newest (Family Memories shows newest first). */
    private fun photos(dir: File, n: Int, name: String) {
        dir.mkdirs()
        val now = System.currentTimeMillis()
        for (i in 0 until n) File(dir, "%s %03d.jpg".format(name, i)).apply { writeBytes(ByteArray(8)); setLastModified(now - i * 3_600_000L) }
    }

    private fun back() = compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }

    private fun settle(steps: Int = 20) = repeat(steps) {
        Thread.sleep(20)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50))
        compose.mainClock.advanceTimeBy(50)
        compose.waitForIdle()
    }

    @Test
    fun backFarDownThePhotosGoesToTheTopThenTheSideMenu() {
        photos(top, 60, "Beach")
        var sideMenu = 0
        val vm = MainViewModel(app)
        vm.openRoot(top)
        compose.mainClock.autoAdvance = false
        compose.setContent { CompositionLocalProvider(LocalOpenSidebar provides { sideMenu++ }) { BrowserScreen(vm) } }
        settle(30)
        compose.onNode(hasText("Beach 000", substring = true)).assertIsFocused()
        repeat(10) { compose.onRoot().performKeyInput { pressKey(Key.DirectionDown) }; settle(6) }

        back()
        settle(40)
        compose.onNode(hasText("Beach 000", substring = true)).assertIsFocused()
        assertEquals(0, sideMenu)

        back()
        settle()
        assertEquals(1, sideMenu)
    }

    @Test
    fun insideASubFolderBackStillGoesUpAFolder() {
        val trip = File(top, "Trip")
        photos(trip, 60, "Lake")
        var sideMenu = 0
        val vm = MainViewModel(app)
        vm.openRoot(top)
        compose.mainClock.autoAdvance = false
        compose.setContent { CompositionLocalProvider(LocalOpenSidebar provides { sideMenu++ }) { BrowserScreen(vm) } }
        settle(20)
        compose.runOnIdle { vm.openFolder(trip) }
        settle(30)
        repeat(10) { compose.onRoot().performKeyInput { pressKey(Key.DirectionDown) }; settle(6) }

        back()
        settle(20)
        val at = vm.browser.value.location
        assertTrue("went up to the top of Family Memories: $at", at is Location.Folder && at.dir == top)
        assertEquals(0, sideMenu)
    }
}
