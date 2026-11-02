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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import com.woodruff.tvexplorer.cloud.DriveFile
import com.woodruff.tvexplorer.cloud.DriveStatus
import com.woodruff.tvexplorer.cloud.Library
import com.woodruff.tvexplorer.cloud.LibraryFilter
import com.woodruff.tvexplorer.ui.components.OfflinePage
import com.woodruff.tvexplorer.ui.components.OfflineWords
import com.woodruff.tvexplorer.ui.components.TopTabs
import com.woodruff.tvexplorer.ui.screens.LibraryHeader
import com.woodruff.tvexplorer.ui.screens.LocalPosterPainter
import com.woodruff.tvexplorer.ui.screens.PosterTile
import com.woodruff.tvexplorer.ui.screens.driveHeaderStatus
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
 * Streaming while Google can't be reached (a TV woken before its Wi-Fi is back): the saved library
 * with the header's quiet "Offline" note, and the calm page shown when nothing is saved yet. Drawn
 * with the real header, posters and page over sample posters (test resources), as PosterGallery does.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-television-xhdpi")
class OfflineGallery {
    @get:Rule val compose = createComposeRule()

    private val movies = listOf(
        "Midnight Harbor (2024).mkv" to "m_midnight_harbor", "The Last Light (2023).mp4" to "m_last_light",
        "Paper Planes (2022).mkv" to "m_paper_planes", "Ironwood (2025).mkv" to "m_ironwood",
        "Orbit (2021).mp4" to "m_orbit", "Summer at the Cabin (2020).mkv" to "m_summer_cabin",
        "Neon Alley (2024).mkv" to "m_neon_alley",
    )

    private val shows = listOf(
        "Kitchen Rules" to "t_kitchen_rules", "The Lighthouse" to "t_the_lighthouse",
        "Backyard Science" to "t_backyard_science",
    )

    /** One shot: which library, whether anything is saved, and whether the TV has a network again. */
    private data class Shot(val name: String, val shows: Boolean, val saved: Boolean, val networkUp: Boolean)

    @Test
    fun drawOffline() {
        val out = File(System.getProperty("gallery.dir") ?: "build/gallery").apply { mkdirs() }
        val dir = File(ApplicationProvider.getApplicationContext<android.content.Context>().cacheDir, "posters").apply { mkdirs() }
        fun art(key: String): String {
            val f = File(dir, "$key.jpg")
            if (!f.exists()) javaClass.getResourceAsStream("/gallery/posters/$key.jpg")!!.use { input -> f.outputStream().use { input.copyTo(it) } }
            return "file://" + f.absolutePath
        }
        val movieFiles = movies.mapIndexed { i, (name, key) ->
            DriveFile("m$i", name, "video/x-matroska", 0, i.toLong(), null, art(key), Library.parsed(name, folder = false))
        }
        val showFiles = shows.mapIndexed { i, (name, key) -> DriveFile("t$i", name, DriveFile.FOLDER_MIME, 0, 0, null, art(key)) }

        var shot by mutableStateOf(Shot("movies-offline", shows = false, saved = true, networkUp = false))
        compose.setContent {
            TvExplorerTheme {
                CompositionLocalProvider(LocalPosterPainter provides { url ->
                    BitmapFactory.decodeFile(url.removePrefix("file://"))?.let { BitmapPainter(it.asImageBitmap()) }
                }) {
                    Box(Modifier.fillMaxSize().background(Palette.background).ambientBackdrop()) {
                        val title = if (shot.shows) "TV Shows" else "Movies"
                        val files = if (!shot.saved) emptyList() else if (shot.shows) showFiles else movieFiles
                        // As DriveScreen lays out a library: 40 dp sides, the header's row, then the posters.
                        Column(Modifier.fillMaxSize().padding(start = 40.dp, end = 40.dp, top = 18.dp)) {
                            LibraryHeader(
                                title, files, LibraryFilter(overview = true), onChange = {}, onSearch = {},
                                switcher = { TopTabs(listOf("Movies", "TV Shows"), selected = if (shot.shows) 1 else 0, onSelect = {}) },
                                status = driveHeaderStatus(DriveStatus.Offline, shot.networkUp, hasFiles = files.isNotEmpty(), library = true),
                            )
                            if (files.isNotEmpty()) LazyVerticalGrid(
                                columns = GridCells.Adaptive(150.dp),
                                contentPadding = PaddingValues(top = 30.dp, bottom = 56.dp, start = 18.dp, end = 18.dp),
                                horizontalArrangement = Arrangement.spacedBy(30.dp),
                                verticalArrangement = Arrangement.spacedBy(30.dp),
                            ) {
                                items(Library.apply(files, LibraryFilter()), key = { it.id }) { f ->
                                    PosterTile(f, null, onClick = {}, onLongClick = {}, modifier = Modifier, label = Library.label(f))
                                }
                            } else OfflinePage(
                                OfflineWords.title(shot.networkUp, "Google Drive"),
                                OfflineWords.body(shot.networkUp, "Your library", "Google Drive"),
                                onAction = {},
                            )
                        }
                    }
                }
            }
        }
        val shots = listOf(
            Shot("movies-offline", shows = false, saved = true, networkUp = false),
            // The Wi-Fi back, Google moments away: TV Shows (the longer search hint) beside the longer words.
            Shot("tv-shows-reconnecting", shows = true, saved = true, networkUp = true),
            Shot("drive-offline", shows = false, saved = false, networkUp = false),
        )
        for (light in listOf(false, true)) {
            for (s in shots) {
                compose.runOnIdle { Palette.light = light; shot = s }
                settle()
                compose.onRoot().captureRoboImage(File(out, "tv-${s.name}-${if (light) "light" else "dark"}.png").path)
            }
        }
        compose.runOnIdle { Palette.light = false }
    }

    /** Let recomposition, the note's fade and frames run after each page switch. */
    private fun settle() {
        repeat(25) {
            Thread.sleep(80)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
            compose.mainClock.advanceTimeBy(100)
            compose.waitForIdle()
        }
    }
}
