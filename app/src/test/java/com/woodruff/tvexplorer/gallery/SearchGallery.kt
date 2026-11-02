package com.woodruff.tvexplorer.gallery

import android.app.Application
import android.graphics.BitmapFactory
import android.os.Looper
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.requestFocus
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import com.woodruff.tvexplorer.cloud.DriveFile
import com.woodruff.tvexplorer.cloud.DrivePlace
import com.woodruff.tvexplorer.cloud.FreshEpisode
import com.woodruff.tvexplorer.cloud.MediaInfo
import com.woodruff.tvexplorer.cloud.NewShow
import com.woodruff.tvexplorer.ui.screens.LocalPosterPainter
import com.woodruff.tvexplorer.ui.screens.SearchBlock
import com.woodruff.tvexplorer.ui.screens.SearchFrom
import com.woodruff.tvexplorer.ui.screens.SearchRequest
import com.woodruff.tvexplorer.ui.screens.SearchScreen
import com.woodruff.tvexplorer.ui.theme.Palette
import com.woodruff.tvexplorer.ui.theme.TvExplorerTheme
import kotlinx.coroutines.flow.emptyFlow
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.Duration

/**
 * Search (the search spec), dark and light: the empty page with Recently added, a query found in both
 * libraries, no matches, the offline line over the saved library, the libraries still loading, and the pages
 * for Google Drive not set up, a sign-in that ended and keys Google won't take. The real page (its keyboard,
 * field and grid) over sample posters (test resources), drawn without its window, as the title pages are.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-television-xhdpi")
class SearchGallery {
    @get:Rule val compose = createComposeRule()

    private val out = File(System.getProperty("gallery.dir") ?: "build/gallery").apply { mkdirs() }
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val now = System.currentTimeMillis()
    private val day = 24 * 60 * 60_000L

    @After fun reset() { Palette.light = false }

    private val movies by lazy {
        listOf(
            film("m1", "Midnight Harbor", 2024, 7.8, "m_midnight_harbor", 2),
            film("m2", "The Last Light", 2023, 7.1, "m_last_light", 9),
            film("m3", "Paper Planes", 2022, 7.0, "m_paper_planes", 40),
            film("m4", "Ironwood", 2025, 7.4, "m_ironwood", 5),
            film("m5", "Orbit", 2021, 7.6, "m_orbit", 6),
            film("m6", "Summer at the Cabin", 2020, 6.6, "m_summer_cabin", 60),
            film("m7", "Neon Alley", 2024, 6.9, "m_neon_alley", 12),
        )
    }
    private val shows by lazy {
        listOf(
            show("t1", "Kitchen Rules", 2018, 7.4, "t_kitchen_rules", 400),
            show("t2", "The Lighthouse", 2019, 7.9, "t_the_lighthouse", 300),
            show("t3", "Backyard Science", 2019, 8.1, "t_backyard_science", 500),
            show("t4", "Detective Rowe", 2023, 7.2, null, 3),
        )
    }
    private val libraries: Map<DrivePlace, List<DriveFile>> by lazy { mapOf(DrivePlace.Movies to movies, DrivePlace.TvShows to shows) }

    /** Recently added, newest first: Backyard Science back at the front with a new episode. */
    private val recent: List<Pair<DrivePlace, DriveFile>> by lazy {
        listOf(
            DrivePlace.TvShows to shows[2], DrivePlace.Movies to movies[0], DrivePlace.TvShows to shows[3],
            DrivePlace.Movies to movies[3], DrivePlace.Movies to movies[4], DrivePlace.Movies to movies[1],
        )
    }
    private val news by lazy {
        mapOf("t3" to NewShow("t3", listOf(FreshEpisode("b32", "Backyard.Science.S03E02.mkv", "s3", "Season 3", now - day, 3, 2)), now - day, announced = true))
    }

    @Test fun drawEmpty() = draw("tv-search", query = "")

    @Test fun drawResults() = draw("tv-search-results", query = "li", focus = "The Lighthouse (2019)")

    @Test fun drawNoMatches() = draw("tv-search-no-matches", query = "zzz")

    @Test fun drawOffline() = draw("tv-search-offline", query = "the", offline = "Offline · searching your saved library", down = true)

    @Test fun drawLoading() = draw("tv-search-loading", query = "", listed = false)

    @Test fun drawSetUp() = draw("tv-search-setup", query = "", block = SearchBlock.NEEDS_DRIVE)

    @Test fun drawSignInAgain() = draw("tv-search-signin-again", query = "", block = SearchBlock.SIGN_IN_ENDED)

    @Test fun drawKeys() = draw("tv-search-keys", query = "", block = SearchBlock.KEYS)

    /**
     * Search with [query] typed as it opens, in both themes: the focus on "a" (or on the result called
     * [focus]); [offline]: the offline line; [listed]: the libraries are listed; [block]: Google Drive isn't ready.
     */
    private fun draw(
        name: String, query: String, focus: String? = null, offline: String? = null, down: Boolean = false,
        listed: Boolean = true, block: SearchBlock = SearchBlock.NONE,
    ) {
        var theme by mutableStateOf(false)
        compose.setContent {
            TvExplorerTheme {
                CompositionLocalProvider(LocalPosterPainter provides ::decode) {
                    // A new page per theme: it opens afresh, its focus on "a".
                    key(theme) {
                        Box(Modifier.fillMaxSize().background(Palette.background)) {
                            SearchScreen(
                                request = SearchRequest(SearchFrom.MENU, null, listen = false, id = 1), block = block,
                                libraries = { if (listed) libraries else emptyMap() }, listings = 1,
                                recent = if (listed) recent else emptyList(), news = news, offline = offline, down = down,
                                token = null, voice = true, covered = false, marks = remember { mutableIntStateOf(0) },
                                onListen = {}, heard = emptyFlow(), listenAgain = emptyFlow(),
                                onOpen = {}, onMenu = {}, onPlay = {}, onFix = {},
                                initialQuery = query,
                            )
                        }
                    }
                }
            }
        }
        for (l in listOf(false, true)) {
            compose.runOnIdle { Palette.light = l; theme = l }
            settle()
            if (focus != null) {
                compose.onNode(hasText(focus) and hasClickAction()).requestFocus()
                settle()
            }
            compose.onRoot().captureRoboImage(File(out, "$name-${if (l) "light" else "dark"}.png").path)
        }
    }

    private fun film(id: String, title: String, year: Int, rating: Double, poster: String?, ageDays: Int) = DriveFile(
        id, "$title ($year).mkv", "video/x-matroska", 0, 0, null, poster?.let(::art), MediaInfo(title, year, rating = rating),
        created = now - ageDays * day,
    )

    private fun show(id: String, title: String, year: Int, rating: Double, poster: String?, ageDays: Int) = DriveFile(
        id, title, DriveFile.FOLDER_MIME, 0, 0, null, poster?.let(::art), MediaInfo(title, year, rating = rating, tv = true),
        created = now - ageDays * day,
    )

    private fun art(key: String): String {
        val dir = File(app.cacheDir, "posters").apply { mkdirs() }
        val f = File(dir, "$key.jpg")
        if (!f.exists()) javaClass.getResourceAsStream("/gallery/posters/$key.jpg")!!.use { input -> f.outputStream().use { input.copyTo(it) } }
        return "file://" + f.absolutePath
    }

    private fun decode(url: String) = BitmapFactory.decodeFile(url.removePrefix("file://"))?.let { BitmapPainter(it.asImageBitmap()) }

    /** Let recomposition, the matching (off the main thread), focus and frames run. */
    private fun settle() {
        repeat(25) {
            Thread.sleep(60)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
            compose.mainClock.advanceTimeBy(100)
            compose.waitForIdle()
        }
    }
}
