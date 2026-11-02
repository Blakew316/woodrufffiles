package com.woodruff.tvexplorer.gallery

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import com.woodruff.tvexplorer.cloud.DriveFile
import com.woodruff.tvexplorer.cloud.Library
import com.woodruff.tvexplorer.cloud.LibraryFilter
import com.woodruff.tvexplorer.cloud.MediaInfo
import com.woodruff.tvexplorer.ui.LocalOpenSidebar
import com.woodruff.tvexplorer.ui.SmoothBringIntoView
import com.woodruff.tvexplorer.ui.components.TopTabs
import com.woodruff.tvexplorer.ui.motion.Pivot
import com.woodruff.tvexplorer.ui.motion.PivotScroll
import com.woodruff.tvexplorer.ui.screens.GridBackToTop
import com.woodruff.tvexplorer.ui.screens.LibraryHeader
import com.woodruff.tvexplorer.ui.screens.LibraryRows
import com.woodruff.tvexplorer.ui.screens.LocalPosterPainter
import com.woodruff.tvexplorer.ui.screens.PosterBackdrop
import com.woodruff.tvexplorer.ui.screens.PosterTile
import com.woodruff.tvexplorer.ui.screens.posterArt
import com.woodruff.tvexplorer.ui.theme.Palette
import com.woodruff.tvexplorer.ui.theme.TvExplorerTheme
import com.woodruff.tvexplorer.ui.theme.ambientBackdrop
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
 * Browsing feel (browsing-motion §8): where Back from deep in Streaming lands, drawn by doing it. The
 * Movies Overview back at its top with the banner's Play focused (the backdrop following the banner's
 * title), and the All grid back at its top with its first poster focused. Sample posters (test
 * resources) and drawn banner pictures; dark and light. Only runs with `-Pgallery`.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-television-xhdpi")
class BrowsingGallery {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val out = File(System.getProperty("gallery.dir") ?: "build/gallery").apply { mkdirs() }
    private val app = ApplicationProvider.getApplicationContext<Application>()

    @After fun theme() { Palette.light = false }

    /** The sample posters (test resources), each with its own title and a drawn banner picture. */
    private val posters = listOf(
        Triple("Midnight Harbor (2024)", "m_midnight_harbor", ::harbor), Triple("The Last Light (2023)", "m_last_light", ::sunset),
        Triple("Ironwood (2025)", "m_ironwood", ::forest), Triple("Neon Alley (2024)", "m_neon_alley", ::city),
        Triple("Summer at the Cabin (2020)", "m_summer_cabin", ::desert), Triple("Paper Planes (2022)", "m_paper_planes", null),
        Triple("Orbit (2021)", "m_orbit", null),
    )
    private val words = listOf("Quiet", "Silent", "Silver", "Velvet", "Winter", "Wild", "Still", "Sunday", "Tall", "Vast")
    private val things = listOf("River", "Garden", "Signal", "Coast", "Bridge", "Lantern", "Orchard", "Station", "Meadow")
    private val genres = listOf("Drama", "Comedy", "Thriller", "Family", "Sci-Fi", "Adventure", "Mystery", "Romance")

    /**
     * A Movies library big enough for a dozen rows: the seven sample posters (the newest, so they lead
     * Recently added, and first from A to Z), five of them featured with their banner pictures; the
     * rest title cards (no poster).
     */
    private fun library(): List<DriveFile> {
        val withArt = posters.mapIndexed { i, (name, key, scene) ->
            val parsed = Library.parsed("$name.mkv", folder = false)
            val info = MediaInfo(
                parsed.title, parsed.year, listOf(genres[i % genres.size], genres[(i + 3) % genres.size]), rating = 7.2 + i * 0.2,
                overview = if (scene != null) "A quiet town, a storm on the way and one long night that changes everything for the family who runs the harbor." else null,
                backdrop = scene?.let { picture("backdrop-$key", it) },
            )
            DriveFile("p$i", "$name.mkv", "video/x-matroska", 0, (1_000 - i) * 60_000L, null, art(key), info)
        }
        val cards = (0 until words.size * things.size).map { i ->
            val name = "${words[i % words.size]} ${things[i / words.size]} (${1996 + (i * 7) % 30}).mkv"
            val parsed = Library.parsed(name, folder = false)
            val info = MediaInfo(parsed.title, parsed.year, listOf(genres[i % genres.size], genres[(i + 3) % genres.size]), rating = 6.0 + (i * 13 % 25) / 10.0)
            DriveFile("c$i", name, "video/x-matroska", 0, i * 60_000L, null, null, info)
        }
        return withArt + cards
    }

    private fun back() = compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }

    private fun press(key: Key) = compose.onRoot().performKeyInput { pressKey(key) }

    @Test
    fun drawBackToTheTop() {
        val files = library()
        val grid = Library.apply(files, LibraryFilter())
        var shot by mutableStateOf("rows")
        compose.mainClock.autoAdvance = false
        compose.setContent {
            TvExplorerTheme {
                CompositionLocalProvider(
                    LocalPosterPainter provides ::decode,
                    LocalBringIntoViewSpec provides SmoothBringIntoView,
                    LocalOpenSidebar provides {},
                ) {
                    key(shot, Palette.light) {
                        // As DriveScreen lays out a library: the focused title's picture behind, dimmed;
                        // 40 dp sides, the header's row, then the rows or the grid.
                        val art = remember { mutableStateOf<String?>(null) }
                        Box(Modifier.fillMaxSize().background(Palette.background).ambientBackdrop()) {
                            PosterBackdrop(art, null)
                            Column(Modifier.fillMaxSize().padding(start = 40.dp, end = 40.dp, top = 18.dp)) {
                                LibraryHeader(
                                    "Movies", files, LibraryFilter(overview = shot == "rows"), onChange = {}, onSearch = {},
                                    switcher = { TopTabs(listOf("Movies", "TV Shows"), selected = 0, onSelect = {}) },
                                )
                                Box(Modifier.fillMaxSize()) {
                                    if (shot == "rows") LibraryRows(
                                        files, null, placeKey = "gallery-rows-${Palette.light}", isShows = false, canTakeFocus = { true },
                                        onChoose = {}, onPlay = {}, onMenu = {}, onFocusArt = { a -> if (a != null) art.value = a },
                                        marks = remember { mutableIntStateOf(0) },
                                    ) else {
                                        // The All grid as DriveScreen draws it, with its Back to the top.
                                        val state = rememberLazyGridState()
                                        val held = remember { mutableStateOf(false) }
                                        val first = remember { FocusRequester() }
                                        GridBackToTop(state, first, held)
                                        PivotScroll(state, Pivot(offset = 30.dp)) {
                                            LazyVerticalGrid(
                                                state = state, columns = GridCells.Adaptive(150.dp),
                                                modifier = Modifier.onFocusChanged { held.value = it.hasFocus },
                                                contentPadding = PaddingValues(top = 30.dp, bottom = 56.dp, start = 18.dp, end = 18.dp),
                                                horizontalArrangement = Arrangement.spacedBy(30.dp),
                                                verticalArrangement = Arrangement.spacedBy(30.dp),
                                            ) {
                                                itemsIndexed(grid, key = { _, f -> f.id }) { i, f ->
                                                    PosterTile(
                                                        f, null, onClick = {}, onLongClick = {}, label = Library.label(f),
                                                        modifier = Modifier.then(if (i == 0) Modifier.focusRequester(first) else Modifier)
                                                            .onFocusChanged { if (it.hasFocus) posterArt(f)?.let { a -> art.value = a } },
                                                    )
                                                }
                                            }
                                        }
                                        androidx.compose.runtime.LaunchedEffect(Unit) {
                                            repeat(10) { androidx.compose.runtime.withFrameNanos {}; if (runCatching { first.requestFocus() }.isSuccess) return@LaunchedEffect }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        for (light in listOf(false, true)) {
            // The Overview: down to the sixth row, then Back.
            compose.runOnIdle { Palette.light = light; shot = "rows" }
            settle(30)
            repeat(7) { press(Key.DirectionDown); settle(8) }
            back()
            settle(40)
            capture("tv-movies-back-to-top", light)

            // The All grid: down eight rows, then Back.
            compose.runOnIdle { shot = "grid" }
            settle(20)
            repeat(8) { press(Key.DirectionDown); settle(8) }
            back()
            settle(40)
            capture("tv-movies-grid-back-to-top", light)
        }
    }

    /** Frames, focus, glides, the rows worked out off the main thread, and the backdrop's settle and fade. */
    private fun settle(steps: Int) = repeat(steps) {
        Thread.sleep(40)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
        compose.mainClock.advanceTimeBy(100)
        compose.waitForIdle()
    }

    private fun capture(name: String, light: Boolean) {
        compose.onRoot().captureRoboImage(File(out, "$name-${if (light) "light" else "dark"}.png").path)
    }

    private fun art(key: String): String {
        val dir = File(app.cacheDir, "posters").apply { mkdirs() }
        val f = File(dir, "$key.jpg")
        if (!f.exists()) javaClass.getResourceAsStream("/gallery/posters/$key.jpg")!!.use { input -> f.outputStream().use { input.copyTo(it) } }
        return "file://" + f.absolutePath
    }

    private fun decode(url: String) = BitmapFactory.decodeFile(url.removePrefix("file://"))?.let { BitmapPainter(it.asImageBitmap()) }

    /** A drawn wide picture for the banner, saved as a file the posters' painter reads. */
    private fun picture(name: String, scene: (Canvas, Paint, Int, Int) -> Unit): String {
        val f = File(File(app.cacheDir, "posters").apply { mkdirs() }, "$name.png")
        if (!f.exists()) {
            val w = 1280; val h = 720
            val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            scene(Canvas(b), Paint(Paint.ANTI_ALIAS_FLAG), w, h)
            f.outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        return "file://" + f.absolutePath
    }

    private fun harbor(c: Canvas, p: Paint, w: Int, h: Int) {
        p.shader = LinearGradient(0f, 0f, 0f, h * 0.6f, intArrayOf(0xFF050B1C.toInt(), 0xFF14284A.toInt(), 0xFF2C4C78.toInt()), null, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w.toFloat(), h * 0.6f, p)
        p.shader = RadialGradient(w * 0.72f, h * 0.24f, h * 0.09f, 0xFFF4F1E1.toInt(), 0xFFE6E0C8.toInt(), Shader.TileMode.CLAMP)
        c.drawCircle(w * 0.72f, h * 0.24f, h * 0.08f, p)
        p.shader = LinearGradient(0f, h * 0.6f, 0f, h.toFloat(), intArrayOf(0xFF1B3358.toInt(), 0xFF070E1E.toInt()), null, Shader.TileMode.CLAMP)
        c.drawRect(0f, h * 0.6f, w.toFloat(), h.toFloat(), p)
        p.shader = null
        p.color = 0x55F4F1E1
        for (k in 0 until 9) c.drawRect(w * 0.69f - k * 6f, h * (0.63f + k * 0.035f), w * 0.75f + k * 6f, h * (0.635f + k * 0.035f), p)
        p.color = 0xFF0A1426.toInt()
        c.drawRect(w * 0.4f, h * 0.52f, w * 0.58f, h * 0.6f, p)
        c.drawRect(w * 0.47f, h * 0.36f, w * 0.48f, h * 0.52f, p)
    }

    private fun sunset(c: Canvas, p: Paint, w: Int, h: Int) {
        p.shader = LinearGradient(0f, 0f, 0f, h * 0.62f, intArrayOf(0xFF2B1E4A.toInt(), 0xFFB2496A.toInt(), 0xFFF2A65A.toInt()), null, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w.toFloat(), h * 0.62f, p)
        p.shader = RadialGradient(w * 0.68f, h * 0.6f, h * 0.24f, 0xFFFFE2A8.toInt(), 0x00FFFFFF, Shader.TileMode.CLAMP)
        c.drawCircle(w * 0.68f, h * 0.6f, h * 0.24f, p)
        p.shader = LinearGradient(0f, h * 0.62f, 0f, h.toFloat(), intArrayOf(0xFF6B3A5C.toInt(), 0xFF1B1530.toInt()), null, Shader.TileMode.CLAMP)
        c.drawRect(0f, h * 0.62f, w.toFloat(), h.toFloat(), p)
        p.shader = null
    }

    private fun forest(c: Canvas, p: Paint, w: Int, h: Int) {
        p.shader = LinearGradient(0f, 0f, 0f, h.toFloat(), intArrayOf(0xFF9CC3B2.toInt(), 0xFF2F5A4A.toInt(), 0xFF0F241D.toInt()), null, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
        p.shader = null
        for (i in 0 until 14) {
            val x = w * (0.35f + i * 0.05f)
            p.color = if (i % 2 == 0) 0xFF173A2E.toInt() else 0xFF1F4A3A.toInt()
            val path = android.graphics.Path().apply { moveTo(x, h * 0.25f + (i % 3) * 30f); lineTo(x - 70f, h.toFloat()); lineTo(x + 70f, h.toFloat()); close() }
            c.drawPath(path, p)
        }
    }

    private fun city(c: Canvas, p: Paint, w: Int, h: Int) {
        p.shader = LinearGradient(0f, 0f, 0f, h.toFloat(), intArrayOf(0xFF0B1630.toInt(), 0xFF22305C.toInt(), 0xFF3E2C5E.toInt()), null, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
        p.shader = null
        for (i in 0 until 18) {
            val left = w * 0.3f + i * 52f
            val top = h * (0.35f + (i * 37 % 30) / 100f)
            p.color = 0xFF121A33.toInt(); c.drawRect(left, top, left + 46f, h.toFloat(), p)
            p.color = 0xAAFFD58A.toInt()
            for (y in (top + 14).toInt() until h step 28) for (x in (left + 8).toInt() until (left + 40).toInt() step 14) if ((x + y) % 3 == 0) c.drawRect(x.toFloat(), y.toFloat(), x + 6f, y + 9f, p)
        }
    }

    private fun desert(c: Canvas, p: Paint, w: Int, h: Int) {
        p.shader = LinearGradient(0f, 0f, 0f, h * 0.55f, intArrayOf(0xFF7DB4E0.toInt(), 0xFFE9D3B0.toInt()), null, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w.toFloat(), h * 0.55f, p)
        p.shader = LinearGradient(0f, h * 0.55f, 0f, h.toFloat(), intArrayOf(0xFFD39B5E.toInt(), 0xFF8A5A33.toInt()), null, Shader.TileMode.CLAMP)
        c.drawRect(0f, h * 0.55f, w.toFloat(), h.toFloat(), p)
        p.shader = null
        p.color = 0xFFB97A45.toInt()
        c.drawOval(w * 0.45f, h * 0.45f, w * 1.1f, h * 0.75f, p)
    }

    private fun snow(c: Canvas, p: Paint, w: Int, h: Int) {
        p.shader = LinearGradient(0f, 0f, 0f, h.toFloat(), intArrayOf(0xFF8FA9C4.toInt(), 0xFFDCE6EF.toInt(), 0xFFF5F8FB.toInt()), null, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
        p.shader = null
        p.color = 0xFF5F7790.toInt()
        val path = android.graphics.Path().apply { moveTo(w * 0.4f, h * 0.7f); lineTo(w * 0.62f, h * 0.22f); lineTo(w * 0.84f, h * 0.7f); close() }
        c.drawPath(path, p)
        p.color = 0xFFFFFFFF.toInt()
        val cap = android.graphics.Path().apply { moveTo(w * 0.555f, h * 0.36f); lineTo(w * 0.62f, h * 0.22f); lineTo(w * 0.685f, h * 0.36f); close() }
        c.drawPath(cap, p)
    }
}
