package com.woodruff.tvexplorer.ui.screens

import android.app.Application
import android.os.Looper
import android.view.KeyEvent
import android.view.Window
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.woodruff.tvexplorer.SourceScan
import com.woodruff.tvexplorer.cloud.DriveFile
import com.woodruff.tvexplorer.cloud.DrivePlace
import com.woodruff.tvexplorer.cloud.FamilySync
import com.woodruff.tvexplorer.cloud.MediaInfo
import com.woodruff.tvexplorer.ui.AppRoot
import com.woodruff.tvexplorer.ui.MainViewModel
import com.woodruff.tvexplorer.ui.SearchKey
import com.woodruff.tvexplorer.ui.motion.DialogWindows
import com.woodruff.tvexplorer.ui.theme.Palette
import com.woodruff.tvexplorer.ui.theme.TvExplorerTheme
import com.woodruff.tvexplorer.util.FrameStats
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import java.time.Duration

/**
 * The remote's Search button from anywhere in the main window: its pop-ups (a title page here) hand the
 * press straight to Search through their windows (SearchKey), so Search opens once, over the page, and
 * the same button with Search up asks it to listen again instead of stacking a second Search. Nothing in
 * the screens handles the button themselves any more: one place, the window, takes it.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-television-xhdpi")
class SearchKeyEverywhereTest {
    /** Stands in for the main window (MainActivity), whose pop-ups hand the Search button to Search. */
    class Main : ComponentActivity(), SearchKey.Host

    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val film = DriveFile("m1", "Midnight Harbor (2024).mkv", "video/x-matroska", 0, 0, null, info = MediaInfo("Midnight Harbor", 2024))

    private var host: ActivityController<Main>? = null

    @After fun closeEverything() {
        scope.cancel()
        SearchPages.close()
        TitlePages.close()
        settle()
        // Gone with its screens: an AppRoot left up would go on answering the Search button (and opening its
        // own Search and title pages) in every test after this one.
        host?.pause()?.stop()?.destroy()
        settle()
        DashboardSamples.tvs = null
        DashboardSamples.cameras = null
        if (host != null) {
            SearchKey.take(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SEARCH))
            settle()
            assertFalse("nothing of this test's Woodruff is left answering the Search button", SearchPages.isOpen)
            SearchPages.close()
        }
    }

    private fun settle() = repeat(8) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100)) }

    /** Woodruff's screens in a main window, Home on show. */
    private fun main(): Main {
        FamilySync.setEnabled(app, false)
        DashboardSamples.tvs = emptyList()
        DashboardSamples.cameras = emptyList()
        val vm = MainViewModel(app)
        val main = Robolectric.buildActivity(Main::class.java).setup().also { host = it }.get()
        main.setContent { TvExplorerTheme { Box(Modifier.fillMaxSize().background(Palette.background)) { AppRoot(vm) } } }
        settle()
        return main
    }

    /** One press of the remote's Search button (press, a repeat, release), as Android hands it to [window]. */
    private fun search(window: Window) {
        window.callback.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SEARCH))
        window.callback.dispatchKeyEvent(KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SEARCH, 1))
        window.callback.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_SEARCH))
        settle()
    }

    @Test fun searchFromATitlePageOpensSearchExactlyOnceAndAgainListensAgain() {
        val main = main()
        val opened = mutableListOf<Long>()
        var listenAgain = 0
        scope.launch(start = CoroutineStart.UNDISPATCHED) { SearchPages.request.collect { r -> if (r != null && r.id !in opened) opened += r.id } }
        scope.launch(start = CoroutineStart.UNDISPATCHED) { SearchPages.listenAgain.collect { listenAgain++ } }

        TitlePages.open(DrivePlace.Movies, film)
        settle()
        val page = DialogWindows.of(main).single()
        assertEquals("Title page", DialogWindows.label(page))

        // The button in the title page's window: Search, once, from the remote (it listens at once when the
        // TV can), over the page, which stays underneath for Back.
        search(page)
        assertEquals(1, opened.size)
        val request = SearchPages.request.value
        assertNotNull(request)
        assertEquals(SearchFrom.REMOTE, request!!.from)
        assertTrue(request.listen)
        assertEquals(0, listenAgain)
        assertNotNull(TitlePages.request.value)
        val windows = DialogWindows.of(main)
        assertEquals(2, windows.size)
        assertEquals(page, windows.first())
        // Settings → Smoothness: Search's frames as "Search"; the screen under it keeps its own name.
        assertEquals("Search", DialogWindows.label(windows.last()))
        assertEquals("Home", FrameStats.screen)

        // Again, now in Search's own window: it listens again; no second Search over the first.
        search(windows.last())
        assertEquals(1, opened.size)
        assertEquals(request, SearchPages.request.value)
        assertEquals(1, listenAgain)
        assertEquals(2, DialogWindows.of(main).size)
    }

    @Test fun onlyTheWindowsHandTheButtonOnNoScreenCatchesItItself() {
        // Where the remote's Search (or voice) button is looked at in the app: the one hook every window of the
        // main window shares (SearchKey), and the recovery screen, which answers it itself. A screen catching it
        // as well would open Search a second time, or not at all where the window took it first.
        val seen = SourceScan.matches(Regex("""KEYCODE_(SEARCH|VOICE_ASSIST)\b""")).map { it.substringBefore(':') }.toSet()
        assertEquals(setOf("ui/SearchKey.kt", "update/RecoveryView.kt"), seen)
    }
}
