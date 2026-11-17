package com.woodruff.tvexplorer.ui.screens

import android.app.Application
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.woodruff.tvexplorer.photos.GooglePhotos
import com.woodruff.tvexplorer.ui.MainViewModel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.time.Duration

/**
 * Family Memories: choosing Select keeps the photos as they were (newest first, the same filter) with the focus on
 * the photo that was chosen, and Select all takes what's on screen, never the photos a filter or search hides.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-television-xhdpi")
class MemoriesSelectTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val top = GooglePhotos.folder

    @After fun clean() { top.deleteRecursively() }

    /** [n] photos, "Photo 000" the oldest: newest first is the opposite of A to Z. */
    private fun photos(n: Int) {
        top.mkdirs()
        val now = System.currentTimeMillis()
        for (i in 0 until n) File(top, "Photo %03d.jpg".format(i)).apply { writeBytes(ByteArray(8)); setLastModified(now - (n - i) * 3_600_000L) }
    }

    private fun settle(steps: Int = 20) = repeat(steps) {
        Thread.sleep(20)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50))
        compose.mainClock.advanceTimeBy(50)
        compose.waitForIdle()
    }

    /** The words of what has the focus. */
    private fun focused(): String {
        val node = compose.onAllNodes(isFocused()).fetchSemanticsNodes().singleOrNull()
        assertNotNull("one thing has the focus", node)
        return node!!.config.getOrNull(SemanticsProperties.Text)?.joinToString(" ") { it.text }.orEmpty()
    }

    @Test fun choosingSelectKeepsTheOrderAndTheFocusOnThePhoto() {
        photos(40)
        val vm = MainViewModel(app)
        vm.openRoot(top)
        compose.mainClock.autoAdvance = false
        compose.setContent { BrowserScreen(vm) }
        settle(30)
        // Newest first.
        assertTrue(focused(), focused().contains("Photo 039"))
        repeat(2) { compose.onRoot().performKeyInput { pressKey(Key.DirectionDown) }; settle(6) }
        val on = focused()
        assertTrue(on, on.contains("Photo"))
        val name = Regex("Photo \\d{3}").find(on)!!.value

        // Select, from its menu.
        compose.runOnIdle { vm.toggleSelected(vm.browser.value.items.first { it.name.startsWith(name) }) }
        settle(20)
        assertTrue("still on $name: ${focused()}", focused().contains(name))
        assertEquals(0, compose.onAllNodes(hasText("Loading…")).fetchSemanticsNodes().size)
        // Still newest first: the oldest isn't on screen at the top.
        assertEquals(0, compose.onAllNodes(hasText("Photo 000", substring = true)).fetchSemanticsNodes().size)

        // And leaving the selection keeps it all too.
        compose.runOnIdle { vm.clearSelection() }
        settle(20)
        assertTrue("still on $name: ${focused()}", focused().contains(name))
        assertEquals(0, compose.onAllNodes(hasText("Loading…")).fetchSemanticsNodes().size)
    }

    @Test fun selectAllTakesWhatsOnScreenOnly() {
        photos(6)
        val vm = MainViewModel(app)
        vm.openRoot(top)
        repeat(20) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50)); Thread.sleep(10) }
        val items = vm.browser.value.items
        assertEquals(6, items.size)
        // Filtered to two of them (a year, a search): those two, not the four it hides.
        vm.selectAll(items.take(2))
        assertEquals(items.take(2).map { it.path }.toSet(), vm.browser.value.selected)
        // Nothing that isn't in the folder (any more) is taken.
        vm.selectAll(items.take(1) + items[0].copy(file = File(top, "Gone.jpg")))
        assertEquals(setOf(items[0].path), vm.browser.value.selected)
    }
}
