package com.woodruff.tvexplorer.gallery

import android.graphics.BitmapFactory
import android.os.Looper
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.github.takahirom.roborazzi.captureRoboImage
import com.woodruff.tvexplorer.cloud.DriveFile
import com.woodruff.tvexplorer.cloud.Library
import com.woodruff.tvexplorer.cloud.LibraryFilter
import com.woodruff.tvexplorer.cloud.DriveStatus
import com.woodruff.tvexplorer.ui.components.TopTabs
import com.woodruff.tvexplorer.ui.screens.LibraryHeader
import com.woodruff.tvexplorer.ui.screens.LocalPosterPainter
import com.woodruff.tvexplorer.ui.screens.PosterBackdrop
import com.woodruff.tvexplorer.ui.screens.PosterTile
import com.woodruff.tvexplorer.ui.screens.libraryFieldStatus
import com.woodruff.tvexplorer.ui.screens.librarySearchHint
import com.woodruff.tvexplorer.ui.screens.posterArt
import com.woodruff.tvexplorer.ui.theme.ambientBackdrop
import com.woodruff.tvexplorer.ui.theme.Palette
import com.woodruff.tvexplorer.ui.theme.TvExplorerTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.Duration

/**
 * Movies and TV Shows poster grids for the UI gallery, laid out as Streaming lays out a library's grid
 * (Filter › All): Movies / TV Shows across the top with the search field and Filter, the grid's own
 * spacing, and the first poster focused with its picture dimmed behind the screen. CI has no Drive or
 * TMDB access, so these use made-up sample posters (test resources) through the real PosterTile;
 * "Detective Rowe" has no art on purpose, to show the title-card fallback.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-television-xhdpi")
class PosterGallery {
    @get:Rule val compose = createComposeRule()

    private val movies = listOf(
        "Midnight Harbor (2024).mkv" to "m_midnight_harbor", "The Last Light (2023).mp4" to "m_last_light",
        "Paper Planes (2022).mkv" to "m_paper_planes", "Ironwood (2025).mkv" to "m_ironwood",
        "Orbit (2021).mp4" to "m_orbit", "Summer at the Cabin (2020).mkv" to "m_summer_cabin",
        "Neon Alley (2024).mkv" to "m_neon_alley",
    )
    private val shows = listOf(
        "Kitchen Rules" to "t_kitchen_rules", "The Lighthouse" to "t_the_lighthouse",
        "Backyard Science" to "t_backyard_science", "Detective Rowe" to null,
    )

    @Test
    fun drawPosters() {
        val out = File(System.getProperty("gallery.dir") ?: "build/gallery").apply { mkdirs() }
        val dir = File(ApplicationProvider.getApplicationContext<android.content.Context>().cacheDir, "posters").apply { mkdirs() }
        fun art(key: String?): String? = key?.let { k ->
            val f = File(dir, "$k.jpg")
            if (!f.exists()) javaClass.getResourceAsStream("/gallery/posters/$k.jpg")!!.use { input -> f.outputStream().use { input.copyTo(it) } }
            "file://" + f.absolutePath
        }
        // Sample TMDB details, so the filter bar shows its genre row as it would with a TMDB key.
        val genres = listOf(listOf("Thriller", "Drama"), listOf("Drama"), listOf("Family", "Comedy"), listOf("Drama", "Western"),
            listOf("Sci-Fi", "Adventure"), listOf("Family", "Comedy"), listOf("Sci-Fi", "Thriller"))
        val movieFiles = movies.mapIndexed { i, (name, key) ->
            val parsed = Library.parsed(name, folder = false)
            DriveFile("m$i", name, "video/x-matroska", 0, i.toLong(), null, art(key), parsed.copy(genres = genres[i], rating = 6.5 + i * 0.3))
        }
        val showFiles = shows.mapIndexed { i, (name, key) -> DriveFile("t$i", name, DriveFile.FOLDER_MIME, 0, 0, null, art(key)) }

        var page by mutableStateOf("Movies" to movieFiles)
        compose.setContent {
            TvExplorerTheme {
              CompositionLocalProvider(LocalPosterPainter provides { url ->
                  BitmapFactory.decodeFile(url.removePrefix("file://"))?.let { BitmapPainter(it.asImageBitmap()) }
              }) {
                key(page.first, Palette.light) {
                    // As DriveScreen draws a library: the focused poster's picture behind, dimmed, over the
                    // backdrop; 40 dp sides and 18 dp down, the header's row, then the grid.
                    val shown = remember { Library.apply(page.second, LibraryFilter()) }
                    val art = remember { mutableStateOf(posterArt(shown.first())) }
                    Box(Modifier.fillMaxSize().background(Palette.background).ambientBackdrop()) {
                        PosterBackdrop(art, null)
                        Column(Modifier.fillMaxSize().padding(start = 40.dp, end = 40.dp, top = 18.dp)) {
                            LibraryHeader(
                                page.first, page.second, LibraryFilter(), onChange = {}, onSearch = {},
                                switcher = { TopTabs(listOf("Movies", "TV Shows"), selected = if (page.first == "Movies") 0 else 1, onSelect = {}) },
                                hint = librarySearchHint(page.first, DriveStatus.Online, true),
                                status = libraryFieldStatus(DriveStatus.Online, true),
                            )
                            LazyVerticalGrid(
                                columns = GridCells.Adaptive(150.dp),
                                contentPadding = PaddingValues(top = 30.dp, bottom = 56.dp, start = 18.dp, end = 18.dp),
                                horizontalArrangement = Arrangement.spacedBy(30.dp),
                                verticalArrangement = Arrangement.spacedBy(30.dp),
                            ) {
                                items(shown, key = { it.id }) { f ->
                                    PosterTile(f, null, onClick = {}, onLongClick = {}, modifier = Modifier, label = Library.label(f))
                                }
                            }
                        }
                    }
                }
              }
            }
        }
        for (light in listOf(false, true)) {
            for ((name, files) in listOf("movies" to movieFiles, "tv-shows" to showFiles)) {
                compose.runOnIdle { Palette.light = light; page = (if (name == "movies") "Movies" else "TV Shows") to files }
                settle()
                // The grid opens on its first poster, as Streaming's does.
                val first = Library.info(Library.apply(files, LibraryFilter()).first()).title
                compose.onNode(hasText(first, substring = true) and hasClickAction()).requestFocus()
                settle()
                // Streaming's header: Movies / TV Shows across the top, not a big title.
                check(compose.onAllNodesWithText("TV Shows").fetchSemanticsNodes().isNotEmpty()) { "the Movies / TV Shows tabs are missing" }
                compose.onRoot().captureRoboImage(File(out, "tv-$name-${if (light) "light" else "dark"}.png").path)
            }
        }
    }

    /** Let recomposition and frames run after each page switch. */
    private fun settle() {
        repeat(25) {
            Thread.sleep(80)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
            compose.mainClock.advanceTimeBy(100)
            compose.waitForIdle()
        }
    }
}
