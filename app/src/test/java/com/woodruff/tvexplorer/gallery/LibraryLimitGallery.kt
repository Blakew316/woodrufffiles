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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import com.woodruff.tvexplorer.cloud.DriveApi
import com.woodruff.tvexplorer.cloud.DriveFile
import com.woodruff.tvexplorer.cloud.Library
import com.woodruff.tvexplorer.cloud.LibraryFilter
import com.woodruff.tvexplorer.ui.components.TopTabs
import com.woodruff.tvexplorer.ui.screens.LibraryHeader
import com.woodruff.tvexplorer.ui.screens.LocalPosterPainter
import com.woodruff.tvexplorer.ui.screens.PosterTile
import com.woodruff.tvexplorer.ui.theme.Palette
import com.woodruff.tvexplorer.ui.theme.TvExplorerTheme
import com.woodruff.tvexplorer.ui.theme.ambientBackdrop
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.Duration

/**
 * A library too big to list in full: the quiet line under the header saying only the first 20,000
 * files show, and how to have them all show, over the grid. Drawn with the real header and posters
 * over sample posters (test resources), as OfflineGallery does.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-television-xhdpi")
class LibraryLimitGallery {
    @get:Rule val compose = createComposeRule()

    private val movies = listOf(
        "Midnight Harbor (2024).mkv" to "m_midnight_harbor", "The Last Light (2023).mp4" to "m_last_light",
        "Paper Planes (2022).mkv" to "m_paper_planes", "Ironwood (2025).mkv" to "m_ironwood",
        "Orbit (2021).mp4" to "m_orbit", "Summer at the Cabin (2020).mkv" to "m_summer_cabin",
        "Neon Alley (2024).mkv" to "m_neon_alley",
    )

    @Test
    fun drawLimit() {
        val out = File(System.getProperty("gallery.dir") ?: "build/gallery").apply { mkdirs() }
        val dir = File(ApplicationProvider.getApplicationContext<android.content.Context>().cacheDir, "posters").apply { mkdirs() }
        fun art(key: String): String {
            val f = File(dir, "$key.jpg")
            if (!f.exists()) javaClass.getResourceAsStream("/gallery/posters/$key.jpg")!!.use { input -> f.outputStream().use { input.copyTo(it) } }
            return "file://" + f.absolutePath
        }
        // Twice over, so the grid runs on past the bottom of the screen as a big library's does.
        val files = (movies + movies).mapIndexed { i, (name, key) ->
            DriveFile("m$i", name, "video/x-matroska", 0, i.toLong(), null, art(key), Library.parsed(name, folder = false))
        }

        compose.setContent {
            TvExplorerTheme {
                CompositionLocalProvider(LocalPosterPainter provides { url ->
                    BitmapFactory.decodeFile(url.removePrefix("file://"))?.let { BitmapPainter(it.asImageBitmap()) }
                }) {
                    Box(Modifier.fillMaxSize().background(Palette.background).ambientBackdrop()) {
                        // As DriveScreen lays out a library: 40 dp sides, the header (with the line under it), then the posters.
                        Column(Modifier.fillMaxSize().padding(start = 40.dp, end = 40.dp, top = 18.dp)) {
                            LibraryHeader(
                                "Movies", files, LibraryFilter(), onChange = {}, onSearch = {},
                                switcher = { TopTabs(listOf("Movies", "TV Shows"), selected = 0, onSelect = {}) },
                                cutAt = DriveApi.LIBRARY_LIMIT,
                            )
                            LazyVerticalGrid(
                                columns = GridCells.Adaptive(150.dp),
                                contentPadding = PaddingValues(top = 30.dp, bottom = 56.dp, start = 18.dp, end = 18.dp),
                                horizontalArrangement = Arrangement.spacedBy(30.dp),
                                verticalArrangement = Arrangement.spacedBy(30.dp),
                            ) {
                                items(files, key = { it.id }) { f ->
                                    PosterTile(f, null, onClick = {}, onLongClick = {}, modifier = Modifier, label = Library.label(f))
                                }
                            }
                        }
                    }
                }
            }
        }
        for (dark in listOf(true, false)) {
            compose.runOnIdle { Palette.light = !dark }
            settle()
            compose.onRoot().captureRoboImage(File(out, "tv-movies-truncated-${if (dark) "dark" else "light"}.png").path)
        }
        compose.runOnIdle { Palette.light = false }
    }

    /** Let recomposition and frames run after each switch. */
    private fun settle() {
        repeat(20) {
            Thread.sleep(60)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
            compose.mainClock.advanceTimeBy(100)
            compose.waitForIdle()
        }
    }
}
