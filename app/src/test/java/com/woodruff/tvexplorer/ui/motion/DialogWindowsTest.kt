package com.woodruff.tvexplorer.ui.motion

import android.os.Looper
import android.view.KeyEvent
import android.view.Window
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.woodruff.tvexplorer.ui.SearchKey
import com.woodruff.tvexplorer.ui.components.ActionMenu
import com.woodruff.tvexplorer.ui.components.ConfirmDialog
import com.woodruff.tvexplorer.ui.components.MenuAction
import com.woodruff.tvexplorer.ui.icons.Sym
import com.woodruff.tvexplorer.update.OnScreen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/**
 * Woodruff's pop-ups (title pages, menus, the filters, search) are windows of their own: each one is
 * followed while it's up, its keys get what the main window's get, and it's named for Settings → Smoothness.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class DialogWindowsTest {
    /** Stands in for the main window, whose pop-ups open Search with the remote's Search button. */
    class Main : ComponentActivity(), SearchKey.Host

    /** Stands in for a screen that isn't the main window (the player). */
    class Other : ComponentActivity()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    @After fun stopCollecting() = scope.cancel()

    private fun settle() = repeat(5) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100)) }

    @Test fun aPopUpIsFollowedWhileItsUpAndNamed() {
        val main = Robolectric.buildActivity(Main::class.java).setup().get()
        var open by mutableStateOf(true)
        main.setContent {
            if (open) Dialog(onDismissRequest = {}) {
                DialogWindowMotion(WindowMotion.Page, label = "Title page")
                Box(Modifier.size(80.dp).dpadGate())
            }
        }
        settle()
        val page = DialogWindows.of(main).single()
        assertEquals("Title page", DialogWindows.label(page))
        // Its keys go through the same hook as every Woodruff window's, with the main window's gate.
        assertTrue(page.callback is DialogWindows.Keys)
        assertTrue(page.callback is OnScreen.KeyCounter)

        open = false
        settle()
        assertTrue(DialogWindows.of(main).isEmpty())
    }

    @Test fun eachKindOfPopUpHasItsName() {
        val main = Robolectric.buildActivity(Main::class.java).setup().get()
        var which by mutableStateOf(0)
        main.setContent {
            when (which) {
                0 -> ActionMenu("Midnight Harbor", null, listOf(MenuAction("Play", Sym.Movie) {}), onDismiss = {})
                1 -> ConfirmDialog("Delete?", "Gone for good.", "Delete", onConfirm = {}, onDismiss = {})
                else -> Dialog(onDismissRequest = {}) {
                    DialogWindowMotion(WindowMotion.SideSheet)
                    Box(Modifier.size(80.dp).dpadGate())
                }
            }
        }
        settle()
        assertEquals("Menu", DialogWindows.label(DialogWindows.of(main).single()))
        which = 1
        settle()
        assertEquals(DialogWindows.POP_UP, DialogWindows.label(DialogWindows.of(main).single()))
        which = 2
        settle()
        assertEquals("Filters", DialogWindows.label(DialogWindows.of(main).single()))
    }

    @Test fun popUpsAreListedLowestFirstForTheirOwnScreen() {
        val main = Robolectric.buildActivity(Main::class.java).setup().get()
        val other = Robolectric.buildActivity(Other::class.java).setup().get()
        var menu by mutableStateOf(false)
        main.setContent {
            Dialog(onDismissRequest = {}) { DialogWindowMotion(WindowMotion.Page, label = "Title page"); Box(Modifier.size(80.dp).dpadGate()) }
            if (menu) ActionMenu("Midnight Harbor", null, listOf(MenuAction("Play", Sym.Movie) {}), onDismiss = {})
        }
        settle()
        menu = true
        settle()
        assertEquals(listOf("Title page", "Menu"), DialogWindows.of(main).map { DialogWindows.label(it) })
        assertTrue(DialogWindows.of(other).isEmpty())
    }

    @Test fun theSearchButtonInAMainWindowPopUpOpensSearch() {
        val main = Robolectric.buildActivity(Main::class.java).setup().get()
        main.setContent { Dialog(onDismissRequest = {}) { Box(Modifier.size(80.dp).dpadGate()) } }
        settle()
        val presses = collect()
        val page = DialogWindows.of(main).single()
        // Used up whole (press, repeat, release), so Android doesn't open its own assistant too; once a press.
        assertTrue(page.callback.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SEARCH)))
        assertTrue(page.callback.dispatchKeyEvent(KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SEARCH, 1)))
        assertTrue(page.callback.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_SEARCH)))
        settle()
        assertEquals(1, presses.size)
    }

    @Test fun anotherScreensPopUpLeavesTheSearchButtonToAndroid() {
        val other = Robolectric.buildActivity(Other::class.java).setup().get()
        other.setContent { Dialog(onDismissRequest = {}) { Box(Modifier.size(80.dp).dpadGate()) } }
        settle()
        val presses = collect()
        DialogWindows.of(other).single().callback.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SEARCH))
        settle()
        assertTrue(presses.isEmpty())
    }

    @Test fun keysReachThePopUpThroughItsWindowAndHeldArrowsAreGated() {
        val main = Robolectric.buildActivity(Main::class.java).setup().get()
        val keys = mutableListOf<Int>()
        main.setContent {
            Dialog(onDismissRequest = {}) {
                val focus = remember { FocusRequester() }
                Box(
                    Modifier.size(80.dp).dpadGate().focusRequester(focus).focusable()
                        .onKeyEvent { if (it.type == KeyEventType.KeyDown) keys += it.nativeKeyEvent.repeatCount; false },
                )
                LaunchedEffect(Unit) { focus.requestFocus() }
            }
        }
        settle()
        val window: Window = DialogWindows.of(main).single()
        val t = android.os.SystemClock.uptimeMillis()
        window.callback.dispatchKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_DOWN, 0))
        // A repeat of the same hold at once, before the move has been drawn: dropped, as in the main window.
        window.callback.dispatchKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_DOWN, 1))
        settle()
        assertEquals(listOf(0), keys)
        assertFalse(keys.contains(1))
    }

    /** Collects Search presses from now on. */
    private fun collect(): MutableList<Unit> {
        val got = mutableListOf<Unit>()
        scope.launch(start = CoroutineStart.UNDISPATCHED) { SearchKey.presses.collect { got += it } }
        return got
    }
}
