package com.woodruff.tvexplorer.gallery

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
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
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import com.woodruff.tvexplorer.calendar.CalendarState
import com.woodruff.tvexplorer.cloud.DriveApi
import com.woodruff.tvexplorer.cloud.DriveFile
import com.woodruff.tvexplorer.cloud.DrivePlace
import com.woodruff.tvexplorer.cloud.DriveState
import com.woodruff.tvexplorer.cloud.DriveStatus
import com.woodruff.tvexplorer.cloud.GoogleAuth
import com.woodruff.tvexplorer.cloud.Library
import com.woodruff.tvexplorer.cloud.LibraryFilter
import com.woodruff.tvexplorer.cloud.SignInStep
import com.woodruff.tvexplorer.nest.Camera
import com.woodruff.tvexplorer.nest.CameraStills
import com.woodruff.tvexplorer.nest.NestState
import com.woodruff.tvexplorer.nest.NestStep
import com.woodruff.tvexplorer.notes.Notes
import com.woodruff.tvexplorer.notify.AppVisibility
import com.woodruff.tvexplorer.notify.NoticeSource
import com.woodruff.tvexplorer.notify.Notices
import com.woodruff.tvexplorer.notify.SignInNotice
import com.woodruff.tvexplorer.nowplaying.Playing
import com.woodruff.tvexplorer.player.RecentStore
import com.woodruff.tvexplorer.tvs.TvStatus
import com.woodruff.tvexplorer.ui.AppRoot
import com.woodruff.tvexplorer.ui.MainViewModel
import com.woodruff.tvexplorer.ui.Screen
import com.woodruff.tvexplorer.ui.components.TopTabs
import com.woodruff.tvexplorer.ui.screens.ConnectPanel
import com.woodruff.tvexplorer.ui.screens.DashboardSamples
import com.woodruff.tvexplorer.ui.screens.LibraryHeader
import com.woodruff.tvexplorer.ui.screens.LibraryPagePanel
import com.woodruff.tvexplorer.ui.screens.LibraryRows
import com.woodruff.tvexplorer.ui.screens.LocalPosterPainter
import com.woodruff.tvexplorer.ui.screens.PosterBackdrop
import com.woodruff.tvexplorer.ui.screens.PosterTile
import com.woodruff.tvexplorer.ui.screens.SettingsAt
import com.woodruff.tvexplorer.ui.screens.SettingsSamples
import com.woodruff.tvexplorer.ui.screens.openSettingsAt
import com.woodruff.tvexplorer.ui.screens.libraryFieldStatus
import com.woodruff.tvexplorer.ui.screens.libraryPage
import com.woodruff.tvexplorer.ui.screens.libraryPageSubject
import com.woodruff.tvexplorer.ui.screens.librarySearchHint
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
 * The offline states of the offline-states spec, dark and light: Streaming over its saved library
 * with the state in the search field (offline, can't reach Google, back online), the calm page when
 * nothing is saved (and while it tries again), keys Google refuses, the sign-in panel when Google
 * ended the sign-in, Home and the Command Center with the cameras offline, the quiet notice of an
 * ended sign-in, and a library too big to list. Drawn with the real header, page, posters, backdrop
 * and screens, over sample posters (test resources) and drawn camera pictures.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-television-xhdpi")
class OfflineGallery {
    @get:Rule val compose = createComposeRule()

    private val out = File(System.getProperty("gallery.dir") ?: "build/gallery").apply { mkdirs() }
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val t0 = 1_800_000_000_000L

    private val movies = listOf(
        "Midnight Harbor (2024).mkv" to "m_midnight_harbor", "The Last Light (2023).mp4" to "m_last_light",
        "Paper Planes (2022).mkv" to "m_paper_planes", "Ironwood (2025).mkv" to "m_ironwood",
        "Orbit (2021).mp4" to "m_orbit", "Summer at the Cabin (2020).mkv" to "m_summer_cabin",
        "Neon Alley (2024).mkv" to "m_neon_alley",
    )
    private val shows = listOf(
        "Kitchen Rules" to "t_kitchen_rules", "The Lighthouse" to "t_the_lighthouse",
        "Backyard Science" to "t_backyard_science", "Detective Rowe" to "t_detective_rowe",
    )

    /** A Streaming shot: which library, what's saved, the connection's state, and how it's laid out. */
    private data class Shot(
        val name: String, val shows: Boolean, val saved: Boolean, val status: DriveStatus,
        val overview: Boolean = true, val cutAt: Int = 0, val focusField: Boolean = false,
    )

    @After fun reset() {
        SettingsSamples.signIns = null
        DashboardSamples.camerasOffline = false
        DashboardSamples.calendar = null
        DashboardSamples.nest = null
        AppVisibility.foreground = false
        Palette.light = false
    }

    @Test
    fun drawStreaming() {
        val movieFiles = movies.mapIndexed { i, (name, key) ->
            DriveFile("m$i", name, "video/x-matroska", 0, i.toLong(), null, art(key), Library.parsed(name, folder = false))
        }
        val showFiles = shows.mapIndexed { i, (name, key) -> DriveFile("t$i", name, DriveFile.FOLDER_MIME, 0, i.toLong(), null, art(key), Library.parsed(name, folder = true)) }
        val said = DriveStatus.Offline(since = t0, google = false, shown = true)
        val unreachable = DriveStatus.Offline(since = t0, google = true, shown = true)
        val shots = listOf(
            Shot("movies-offline", shows = false, saved = true, status = said),
            // The search field focused (white): the state in its dark words at 70 %.
            Shot("movies-cant-reach", shows = false, saved = true, status = unreachable, focusField = true),
            Shot("tv-shows-back-online", shows = true, saved = true, status = DriveStatus.BackOnline(t0)),
            Shot("movies-offline-empty", shows = false, saved = false, status = said),
            Shot("tv-shows-trying", shows = true, saved = false, status = DriveStatus.Reconnecting(unreachable)),
            Shot("movies-keys", shows = false, saved = true, status = DriveStatus.Refused("x")),
            // Twice over, so the grid runs on past the bottom of the screen as a big library's does.
            Shot("movies-truncated", shows = false, saved = true, status = DriveStatus.Online, overview = false, cutAt = DriveApi.LIBRARY_LIMIT),
        )
        var shot by mutableStateOf(shots.first())
        compose.setContent {
            TvExplorerTheme {
                CompositionLocalProvider(LocalPosterPainter provides { url -> decode(url) }) {
                    // A new composition per shot: each opens as a screen does (and its page takes the focus).
                    key(shot, Palette.light) {
                        val s = shot
                        val title = if (s.shows) "TV Shows" else "Movies"
                        val files = when {
                            !s.saved -> emptyList()
                            s.shows -> showFiles
                            s.cutAt > 0 -> (movieFiles + movieFiles.map { it.copy(id = it.id + "b") })
                            else -> movieFiles
                        }
                        val hasFiles = files.isNotEmpty()
                        Box(Modifier.fillMaxSize().background(Palette.background).ambientBackdrop()) {
                            // As Streaming does: the focused title's picture behind, dimmed (none behind a page).
                            if (hasFiles && libraryPage(s.status, hasFiles) == null) {
                                PosterBackdrop(remember { mutableStateOf(posterArt(files[if (s.shows) 1 else 0])) }, null)
                            }
                            // As DriveScreen lays out a library: 40 dp sides, the header's row, then the content.
                            Column(Modifier.fillMaxSize().padding(start = 40.dp, end = 40.dp, top = 18.dp)) {
                                LibraryHeader(
                                    title, files, LibraryFilter(overview = s.overview), onChange = {}, onSearch = {},
                                    switcher = { TopTabs(listOf("Movies", "TV Shows"), selected = if (s.shows) 1 else 0, onSelect = {}) },
                                    hint = librarySearchHint(title, s.status, hasFiles),
                                    status = libraryFieldStatus(s.status, hasFiles),
                                    cutAt = s.cutAt,
                                )
                                Box(Modifier.fillMaxSize()) {
                                    val page = libraryPage(s.status, hasFiles)
                                    when {
                                        page != null -> LibraryPagePanel(
                                            page, libraryPageSubject(if (s.shows) DrivePlace.TvShows else DrivePlace.Movies, top = true),
                                            onTryAgain = {}, onOpenKeys = {}, takeFocus = { true },
                                        )
                                        s.overview -> LibraryRows(
                                            files, null, placeKey = s.name, isShows = s.shows, canTakeFocus = { false },
                                            onChoose = {}, onPlay = {}, onFocusArt = {}, marks = remember { mutableIntStateOf(0) },
                                        )
                                        else -> LazyVerticalGrid(
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
                }
            }
        }
        for (light in listOf(false, true)) {
            for (s in shots) {
                compose.runOnIdle { Palette.light = light; shot = s }
                settle()
                if (s.focusField) {
                    compose.onNode(hasText(librarySearchHint("Movies", s.status, true), substring = true) and hasClickAction(), useUnmergedTree = false).requestFocus()
                    settle()
                }
                capture("tv-${s.name}", light)
            }
        }
    }

    /** Google ended the Drive sign-in: the panel asks to sign in again (never for a network failure). */
    @Test
    fun drawSignInAgain() {
        val state = DriveState(step = SignInStep.SIGN_IN, ended = true, qrUrl = "https://accounts.google.com/o/oauth2/v2/auth?client_id=sample")
        compose.setContent {
            TvExplorerTheme { Box(Modifier.fillMaxSize().background(Palette.background).ambientBackdrop()) { ConnectPanel(state, onReset = {}) } }
        }
        for (light in listOf(false, true)) {
            compose.runOnIdle { Palette.light = light }
            settle()
            capture("tv-drive-signin-again", light)
        }
    }

    @Test
    fun drawDashboard() {
        seed()
        val vm = MainViewModel(app)
        compose.setContent {
            TvExplorerTheme { Box(Modifier.fillMaxSize().background(Palette.background)) { AppRoot(vm) } }
        }
        for (light in listOf(false, true)) {
            // The samples aren't state: Home is opened afresh to read them.
            compose.runOnIdle { Palette.light = light; seedNotices(); seedOffline(); vm.show(Screen.SETTINGS) }
            compose.mainClock.advanceTimeBy(1_500)
            compose.runOnIdle { vm.show(Screen.HOME) }
            compose.mainClock.advanceTimeBy(1_500)
            settle()
            capture("tv-home-offline", light)

            compose.runOnIdle { vm.show(Screen.CAMERAS) }
            compose.mainClock.advanceTimeBy(1_500)
            settle()
            capture("tv-command-center-offline", light)

            // Google ended the Drive sign-in while Woodruff is on screen: its quiet notice, over Home.
            compose.runOnIdle {
                DashboardSamples.camerasOffline = false
                DashboardSamples.calendar = null
                vm.show(Screen.HOME)
            }
            compose.mainClock.advanceTimeBy(1_500)
            settle()
            compose.runOnIdle {
                seedNotices()
                AppVisibility.foreground = true
                SignInNotice.ended(app, GoogleAuth.Grant.DRIVE, t0)
            }
            compose.mainClock.advanceTimeBy(1_000)
            settle()
            capture("tv-home-signin-notice", light)
            compose.runOnIdle { Notices.dismissLatest(); AppVisibility.foreground = false }
            compose.mainClock.advanceTimeBy(1_000)
        }
    }

    /**
     * Settings → Accounts after Google ended this TV's sign-ins (the weekly limit of a consent screen
     * in Testing ends them all at once): "Sign-in ended" in amber, the focused row's in its dark words.
     */
    @Test
    fun drawAccounts() {
        SettingsSamples.signIns = GoogleAuth.Grant.entries.associateWith { GoogleAuth.SignIn.ENDED }
        val vm = MainViewModel(app)
        // Opened straight at Accounts, as Home's calendar row opens it.
        openSettingsAt(vm, SettingsAt.ACCOUNTS)
        compose.setContent {
            TvExplorerTheme { Box(Modifier.fillMaxSize().background(Palette.background)) { AppRoot(vm) } }
        }
        compose.mainClock.advanceTimeBy(1_500)
        for (light in listOf(false, true)) {
            compose.runOnIdle { Palette.light = light }
            settle()
            capture("tv-settings-accounts-ended", light)
        }
    }

    /** Home and the Command Center as the gallery's other shots have them, with the cameras and calendar offline. */
    private fun seed() {
        Notes.load(app)
        Notes.save(app, null, "Groceries", "Milk\nEggs\nCoffee beans\nBananas", pinned = true)
        seedNotices()
        RecentStore(app).apply {
            save("drive:sample-3", "Paper Planes (2022)", 3_000_000, 5_600_000)
            save("drive:sample-2", "Backyard Science · S2 E4", 410_000, 1_320_000)
            save("drive:sample-1", "Midnight Harbor (2024)", 2_710_000, 6_840_000)
        }
        DashboardSamples.tvs = listOf(
            TvStatus("this", "Living Room", true, true, Playing("Midnight Harbor", null, "Woodruff Family", true, 2_710_000, 6_840_000)),
            TvStatus("den", "Den", true, false, Playing("Backyard Science", null, "YouTube", true, 410_000, 1_320_000)),
            TvStatus("kitchen", "Kitchen", false, false, null),
        )
        val names = listOf("Front Door", "Backyard", "Garage", "Driveway")
        DashboardSamples.cameras = names
        val pictures = cameraPictures()
        names.forEachIndexed { i, n -> CameraStills.put(n, pictures[i]) }
        val cams = names.mapIndexed { i, n -> Camera("enterprises/sample/devices/$i", n, null, webRtc = true, doorbell = i == 0) }
        // The Command Center's cameras: the first two seen this run (their last pictures), the others not.
        cams.take(2).forEachIndexed { i, c -> CameraStills.put(c.name, pictures[i]) }
        DashboardSamples.nest = NestState(step = NestStep.CONNECTED, cameras = cams, offline = true)
        seedOffline()
    }

    /** Two read notices, and no sign-in ending told of yet. */
    private fun seedNotices() {
        Notices.load(app)
        Notices.clear(app)
        Notices.prefs(app).edit().clear().commit()
        Notices.post(app, NoticeSource.PHONE, "Dinner's ready", "Come downstairs when you can")
        Notices.post(app, NoticeSource.MAIL, "Your order has shipped", "Arriving Thursday")
        Notices.markAllRead(app)
        Notices.dismissLatest()
    }

    private fun seedOffline() {
        DashboardSamples.camerasOffline = true
        DashboardSamples.calendar = CalendarState(connected = true, loaded = true, offline = true, noNetwork = true)
    }

    /** Drawn camera pictures (no real cameras here), one per sample camera, in the soft light of a TV camera's view. */
    private fun cameraPictures(): List<Bitmap> = listOf(frontDoor(), backyard(), garage(), driveway())

    private fun picture(draw: Canvas.(Paint) -> Unit): Bitmap {
        val b = Bitmap.createBitmap(480, 270, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        c.draw(p)
        // A camera lens's darker corners.
        p.shader = android.graphics.RadialGradient(240f, 135f, 300f, intArrayOf(0x00000000, 0x00000000, 0x66000000), floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, 480f, 270f, p)
        p.shader = null
        return b
    }

    private fun Canvas.gradient(p: Paint, l: Float, t: Float, r: Float, b: Float, top: Int, bottom: Int) {
        p.shader = LinearGradient(0f, t, 0f, b, top, bottom, Shader.TileMode.CLAMP)
        drawRect(l, t, r, b, p)
        p.shader = null
    }

    /** A porch at dusk: siding, a door under a warm light, a mat. */
    private fun frontDoor() = picture { p ->
        gradient(p, 0f, 0f, 480f, 270f, 0xFF3B4A5E.toInt(), 0xFF2A3341.toInt())
        p.color = 0x14FFFFFF
        for (y in 12 until 200 step 14) drawRect(0f, y.toFloat(), 480f, y + 1.5f, p) // siding
        gradient(p, 0f, 200f, 480f, 270f, 0xFF5A4D42.toInt(), 0xFF2E2722.toInt()) // porch boards
        p.shader = android.graphics.RadialGradient(240f, 70f, 170f, 0x66FFD58A, 0x00FFD58A, Shader.TileMode.CLAMP)
        drawRect(0f, 0f, 480f, 270f, p); p.shader = null // the porch light's glow
        p.color = 0xFF1B2230.toInt(); drawRoundRect(RectF(196f, 70f, 284f, 202f), 4f, 4f, p) // the door
        p.color = 0xFF27364A.toInt(); drawRoundRect(RectF(204f, 80f, 276f, 132f), 3f, 3f, p) // its panel
        p.color = 0xFFE8C67A.toInt(); drawCircle(270f, 142f, 3.5f, p) // the handle
        p.color = 0xFFFFE3A8.toInt(); drawRoundRect(RectF(232f, 44f, 248f, 60f), 3f, 3f, p) // the light
        p.color = 0xFF6E5A47.toInt(); drawRoundRect(RectF(190f, 206f, 290f, 222f), 3f, 3f, p) // the mat
    }

    /** A lawn by day: sky, a fence, a tree. */
    private fun backyard() = picture { p ->
        gradient(p, 0f, 0f, 480f, 150f, 0xFF6E9FD6.toInt(), 0xFFB9D3EC.toInt())
        gradient(p, 0f, 150f, 480f, 270f, 0xFF5E8F45.toInt(), 0xFF2F5426.toInt())
        p.color = 0xFF8C7A62.toInt()
        drawRect(0f, 128f, 480f, 134f, p) // the fence's rail
        for (x in 0 until 480 step 26) drawRect(x.toFloat(), 112f, x + 10f, 158f, p)
        p.color = 0xFF3E2E22.toInt(); drawRect(352f, 96f, 364f, 176f, p) // a trunk
        p.color = 0xFF3F6B33.toInt(); drawCircle(358f, 78f, 50f, p); drawCircle(330f, 98f, 34f, p); drawCircle(388f, 100f, 32f, p)
    }

    /** A garage inside: a door of panels, concrete. */
    private fun garage() = picture { p ->
        gradient(p, 0f, 0f, 480f, 190f, 0xFF4A505A.toInt(), 0xFF3A3F47.toInt())
        gradient(p, 0f, 190f, 480f, 270f, 0xFF6C6F73.toInt(), 0xFF45484C.toInt())
        p.color = 0xFFC9C6BE.toInt(); drawRect(110f, 40f, 370f, 192f, p) // the door
        p.color = 0x22000000
        for (y in 72 until 192 step 30) drawRect(110f, y.toFloat(), 370f, y + 2f, p) // its panels
        p.color = 0xFF2B2E33.toInt(); drawRect(102f, 34f, 378f, 40f, p)
        p.shader = android.graphics.RadialGradient(240f, 20f, 200f, 0x40FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP)
        drawRect(0f, 0f, 480f, 270f, p); p.shader = null // a ceiling light
    }

    /** A driveway: sky, asphalt to the street, a parked car. */
    private fun driveway() = picture { p ->
        gradient(p, 0f, 0f, 480f, 120f, 0xFF8FB3DD.toInt(), 0xFFD8E4F0.toInt())
        gradient(p, 0f, 120f, 480f, 270f, 0xFF4F7A3E.toInt(), 0xFF35562B.toInt())
        val road = android.graphics.Path().apply { moveTo(200f, 120f); lineTo(280f, 120f); lineTo(430f, 270f); lineTo(50f, 270f); close() }
        p.shader = LinearGradient(0f, 120f, 0f, 270f, 0xFF6A6D72.toInt(), 0xFF3C3F43.toInt(), Shader.TileMode.CLAMP)
        drawPath(road, p); p.shader = null
        p.color = 0xFF2C3E57.toInt(); drawRoundRect(RectF(170f, 150f, 310f, 214f), 18f, 18f, p) // a car
        p.color = 0xFF9DB4CF.toInt(); drawRoundRect(RectF(196f, 156f, 284f, 180f), 8f, 8f, p) // its windows
        p.color = 0xFF151A21.toInt(); drawCircle(196f, 214f, 12f, p); drawCircle(284f, 214f, 12f, p)
    }

    private fun art(key: String): String {
        val dir = File(app.cacheDir, "posters").apply { mkdirs() }
        val f = File(dir, "$key.jpg")
        if (!f.exists()) javaClass.getResourceAsStream("/gallery/posters/$key.jpg")!!.use { input -> f.outputStream().use { input.copyTo(it) } }
        return "file://" + f.absolutePath
    }

    private fun decode(url: String) = BitmapFactory.decodeFile(url.removePrefix("file://"))?.let { BitmapPainter(it.asImageBitmap()) }

    private fun capture(name: String, light: Boolean) {
        compose.onRoot().captureRoboImage(File(out, "$name-${if (light) "light" else "dark"}.png").path)
    }

    /** Let recomposition, focus, fades and frames run after each change. */
    private fun settle() {
        repeat(25) {
            Thread.sleep(60)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
            compose.mainClock.advanceTimeBy(100)
            compose.waitForIdle()
        }
    }
}
