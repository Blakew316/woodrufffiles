package com.woodruff.tvexplorer.gallery

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.Looper
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.github.takahirom.roborazzi.captureRoboImage
import com.woodruff.tvexplorer.cloud.DriveFile
import com.woodruff.tvexplorer.cloud.DrivePlace
import com.woodruff.tvexplorer.cloud.DriveStatus
import com.woodruff.tvexplorer.cloud.EpisodeMeta
import com.woodruff.tvexplorer.cloud.FamilySync
import com.woodruff.tvexplorer.cloud.FolderCache
import com.woodruff.tvexplorer.cloud.FreshEpisode
import com.woodruff.tvexplorer.cloud.LibraryFilter
import com.woodruff.tvexplorer.cloud.MediaInfo
import com.woodruff.tvexplorer.cloud.NewEpisodes
import com.woodruff.tvexplorer.cloud.NewShow
import com.woodruff.tvexplorer.notes.Notes
import com.woodruff.tvexplorer.notify.NoticeSource
import com.woodruff.tvexplorer.notify.Notices
import com.woodruff.tvexplorer.nowplaying.Playing
import com.woodruff.tvexplorer.player.RecentStore
import com.woodruff.tvexplorer.player.Watched
import com.woodruff.tvexplorer.tmdb.Tmdb
import com.woodruff.tvexplorer.tvs.TvStatus
import com.woodruff.tvexplorer.ui.AppRoot
import com.woodruff.tvexplorer.ui.MainViewModel
import com.woodruff.tvexplorer.ui.Screen
import com.woodruff.tvexplorer.ui.components.ToolButton
import com.woodruff.tvexplorer.ui.components.TopTabs
import com.woodruff.tvexplorer.ui.icons.Sym
import com.woodruff.tvexplorer.ui.screens.EpisodeCard
import com.woodruff.tvexplorer.ui.screens.DashboardSamples
import com.woodruff.tvexplorer.ui.screens.LibraryHeader
import com.woodruff.tvexplorer.ui.screens.LibraryRows
import com.woodruff.tvexplorer.ui.screens.LocalPosterPainter
import com.woodruff.tvexplorer.ui.screens.PosterBackdrop
import com.woodruff.tvexplorer.ui.screens.TitlePage
import com.woodruff.tvexplorer.ui.screens.TitleSamples
import com.woodruff.tvexplorer.ui.screens.libraryFieldStatus
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
 * Episodes and what's new (the episodes spec), dark and light: a show's title page opened at its new
 * episode (real names and synopses, NEW badges, Play S3 E2 and the episode named under the buttons), the
 * same page for a show TMDB doesn't know (names from the files, no synopses), Home's Continue watching
 * with new episodes badged, and the TV Shows Overview with "New · S3 E2", "3 new episodes" and "New
 * season" in blue. Drawn with the real page, cards, posters and rows, over sample posters (test
 * resources) and drawn episode pictures.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-television-xhdpi")
class TitleGallery {
    @get:Rule val compose = createComposeRule()

    private val out = File(System.getProperty("gallery.dir") ?: "build/gallery").apply { mkdirs() }
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val now = System.currentTimeMillis()
    private val day = 24 * 60 * 60_000L

    @After fun reset() {
        FolderCache.galleryOffline = false
        TitleSamples.details = null
        DashboardSamples.added = null
        DashboardSamples.newShows = null
        DashboardSamples.tvs = null
        DashboardSamples.cameras = null
        Palette.light = false
    }

    /** "Backyard Science" with TMDB: Season 3 opened at its new episode, E1 watched, E2 and E3 new, E4 older. */
    @Test
    fun drawShowPage() {
        // The ✓ marked here stays on this TV (nothing is sent anywhere).
        FamilySync.setEnabled(app, false)
        val show = DriveFile(
            "bs", "Backyard Science (2019)", DriveFile.FOLDER_MIME, 0, 0, null, art("t_backyard_science"),
            MediaInfo(
                "Backyard Science", 2019, listOf("Family", "Documentary"), 8.1,
                "Two curious kids and their grandmother turn an ordinary backyard into a laboratory, one weekend experiment at a time.",
                picture("backdrop-garden", 1280, 720, ::garden), tmdbId = 4242, tv = true,
            ),
            created = now - 400 * day,
        )
        FolderCache.seed("bs", listOf(season("bs-s1", 1), season("bs-s2", 2), season("bs-s3", 3)))
        FolderCache.seed(
            "bs-s1",
            listOf(
                episode("b11", "Backyard.Science.S01E01.mkv", EpisodeMeta(1, 1, "Rain Gauges", "The first storm of spring fills six jars, and nobody agrees on the winner.", 23), 500 * day, ::rain),
                episode("b12", "Backyard.Science.S01E02.mkv", EpisodeMeta(1, 2, "Kitchen Volcano", "Baking soda, vinegar and a very patient lawn.", 24), 500 * day, ::volcano),
            ),
        )
        FolderCache.seed(
            "bs-s3",
            listOf(
                episode("b31", "Backyard.Science.S03E01.mkv", EpisodeMeta(3, 1, "Rain Gauges, Revisited", "A year later, the jars come back out — and this time the kids keep a proper log.", 24), 40 * day, ::rain),
                episode("b32", "Backyard.Science.S03E02.mkv", EpisodeMeta(3, 2, "Tide Pools", TIDE_POOLS, 24), 3 * day, ::tidePools),
                episode("b33", "Backyard.Science.S03E03.mkv", EpisodeMeta(3, 3, LONG_NAME, "A whole Saturday of counting wings, songs and one very bold squirrel.", 26), 2 * day, ::birds),
                episode("b34", "Backyard.Science.S03E04.mkv", EpisodeMeta(3, 4, "Night Sky", "Lying on the grass with a borrowed telescope, waiting for the moon to clear the fence.", 25), 41 * day, ::nightSky),
            ),
        )
        Watched.mark(app, "drive:b31")
        val fresh = listOf(
            FreshEpisode("b32", "Backyard.Science.S03E02.mkv", "bs-s3", "Season 3", now - 3 * day, 3, 2),
            FreshEpisode("b33", "Backyard.Science.S03E03.mkv", "bs-s3", "Season 3", now - 2 * day, 3, 3),
        )
        TitleSamples.details = Tmdb.Details(ageRating = "TV-G", cast = listOf("Ana Lima", "Ben Ito", "Cara Moss", "Dev Rao"))
        // As it opens (Play S3 E2 focused, the episodes just below), then on the new episode's card.
        drawPage("tv-title-show", show, focusEpisodeId = "b32", fresh = fresh, onCard = "Tide Pools", cardShot = "tv-title-show-episodes")
    }

    /** Opened at a new episode in a season this TV has never listed, while it can't reach Google: the season says so. */
    @Test
    fun drawOfflineSeason() {
        FamilySync.setEnabled(app, false)
        val show = DriveFile(
            "bo", "Backyard Science (2019)", DriveFile.FOLDER_MIME, 0, 0, null, art("t_backyard_science"),
            MediaInfo("Backyard Science", 2019, listOf("Family", "Documentary"), 8.1, "Two curious kids and their grandmother turn an ordinary backyard into a laboratory.",
                picture("backdrop-garden", 1280, 720, ::garden), tmdbId = 4242, tv = true),
            created = now - 400 * day,
        )
        FolderCache.seed("bo", listOf(season("bo-s1", 1), season("bo-s2", 2)))
        FolderCache.seed("bo-s1", listOf(episode("o11", "Backyard.Science.S01E01.mkv", EpisodeMeta(1, 1, "Rain Gauges", "Six jars.", 23), 500 * day, ::rain)))
        val fresh = listOf(FreshEpisode("o21", "Backyard.Science.S02E01.mkv", "bo-s2", "Season 2", now - 2 * day, 2, 1))
        // Google can't be reached: Season 2 was never listed here, Season 1 was (and shows as it was).
        FolderCache.galleryOffline = true
        drawPage("tv-title-show-offline", show, focusEpisodeId = "o21", fresh = fresh, onCard = null)
    }

    /** A season's folder in Streaming: the same cards over the poster backdrop, in the theme's text. */
    @Test
    fun drawSeasonFolder() {
        FamilySync.setEnabled(app, false)
        val eps = listOf(
            episode("f1", "Backyard.Science.S03E01.mkv", EpisodeMeta(3, 1, "Rain Gauges, Revisited", "A year later, the jars come back out — and this time the kids keep a proper log.", 24), 40 * day, ::rain),
            episode("f2", "Backyard.Science.S03E02.mkv", EpisodeMeta(3, 2, "Tide Pools", TIDE_POOLS, 24), 3 * day, ::tidePools),
            episode("f3", "Backyard.Science.S03E03.mkv", EpisodeMeta(3, 3, LONG_NAME, "A whole Saturday of counting wings, songs and one very bold squirrel.", 26), 2 * day, ::birds),
            episode("f4", "Backyard.Science.S03E04.mkv", EpisodeMeta(3, 4, "Night Sky", "Lying on the grass with a borrowed telescope, waiting for the moon to clear the fence.", 25), 41 * day, ::nightSky),
            episode("f5", "Backyard.Science.S03E05.720p.HDTV.mkv", null, 41 * day, ::volcano),
            episode("f6", "Backyard Science - S03E06 - Kitchen Science.mkv", null, 41 * day, null),
        )
        Watched.mark(app, "drive:f1")
        compose.setContent {
            TvExplorerTheme {
                CompositionLocalProvider(LocalPosterPainter provides ::decode) {
                    key(Palette.light) {
                        Box(Modifier.fillMaxSize().background(Palette.background).ambientBackdrop()) {
                            // As Streaming draws a show's folder: the focused episode's picture behind, dimmed.
                            PosterBackdrop(remember { mutableStateOf(posterArt(eps[1])) }, null)
                            Column(Modifier.fillMaxSize().padding(start = 40.dp, end = 40.dp, top = 18.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 6.dp)) {
                                    ToolButton(Sym.ArrowBack, null, {}, modifier = Modifier.padding(end = 14.dp))
                                    Text("Season 3", style = MaterialTheme.typography.headlineSmall, maxLines = 1, modifier = Modifier.padding(end = 18.dp).weight(1f))
                                    ToolButton(Sym.MoreHoriz, null, {}, modifier = Modifier.padding(start = 10.dp))
                                }
                                LazyVerticalGrid(
                                    columns = GridCells.Adaptive(260.dp),
                                    contentPadding = PaddingValues(top = 30.dp, bottom = 56.dp, start = 18.dp, end = 18.dp),
                                    horizontalArrangement = Arrangement.spacedBy(30.dp),
                                    verticalArrangement = Arrangement.spacedBy(30.dp),
                                ) {
                                    items(eps, key = { it.id }) { ep ->
                                        val watched = Watched.isWatched(app, "drive:${ep.id}")
                                        EpisodeCard(
                                            ep, null, progress = null, watched = watched,
                                            modifier = Modifier,
                                            fg = Palette.text, secondary = Palette.text.copy(alpha = 0.85f),
                                            isNew = NewEpisodes.isNew(ep.created, now, watched, null),
                                            onClick = {},
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        for (l in listOf(false, true)) {
            compose.runOnIdle { Palette.light = l }
            settle()
            compose.onNode(hasContentDescription("Tide Pools") and hasClickAction()).requestFocus()
            settle()
            capture("tv-tv-shows-season", l)
        }
    }

    /** The same show without TMDB: names from the files, no synopses; Season 1 with E2 part-watched (Resume S1 E2). */
    @Test
    fun drawPlainShowPage() {
        FamilySync.setEnabled(app, false)
        val show = DriveFile("bp", "Backyard Science (2019)", DriveFile.FOLDER_MIME, 0, 0, null, art("t_backyard_science"), created = now - 400 * day)
        FolderCache.seed("bp", listOf(season("bp-s1", 1), season("bp-s2", 2)))
        FolderCache.seed(
            "bp-s1",
            listOf(
                episode("p11", "Backyard.Science.S01E01.Rain.Gauges.1080p.WEB-DL.mkv", null, 500 * day, ::rain),
                episode("p12", "Backyard Science - S01E02 - Kitchen Volcano.mkv", null, 500 * day, ::volcano),
                episode("p13", "Backyard.Science.S01E03.720p.HDTV.x264.mkv", null, 500 * day, ::garden),
                episode("p14", "S01E04_Bugs Under the Porch.mkv", null, 500 * day, null),
            ),
        )
        Watched.mark(app, "drive:p11")
        RecentStore(app).save("drive:p12", "Backyard Science - S01E02 - Kitchen Volcano.mkv", 9 * 60_000L, 23 * 60_000L)
        // On the part-watched episode's card: the names the files give, no synopses.
        drawPage("tv-title-show-plain", show, focusEpisodeId = null, fresh = emptyList(), onCard = "Kitchen Volcano", cardOnly = true)
    }

    /** Home: a resume tile, the pause, then three shows with new episodes (NEW badges), the first focused. */
    @Test
    fun drawHome() {
        Notes.load(app)
        Notes.save(app, null, "Groceries", "Milk\nEggs\nCoffee beans\nBananas", pinned = true)
        Notices.load(app)
        Notices.post(app, NoticeSource.PHONE, "Dinner's ready", "Come downstairs when you can")
        Notices.markAllRead(app)
        Notices.dismissLatest()
        RecentStore(app).save("drive:sample-1", "Midnight Harbor (2024)", 2_710_000, 6_840_000)
        DashboardSamples.tvs = listOf(
            TvStatus("this", "Living Room", true, true, Playing("Midnight Harbor", null, "Woodruff Family", true, 2_710_000, 6_840_000)),
            TvStatus("den", "Den", true, false, Playing("Backyard Science", null, "YouTube", true, 410_000, 1_320_000)),
            TvStatus("kitchen", "Kitchen", false, false, null),
        )
        DashboardSamples.cameras = listOf("Front Door", "Backyard", "Garage", "Driveway")
        val shows = sampleShows()
        val films = listOf(
            DriveFile("m1", "Orbit (2021).mp4", "video/mp4", 0, 0, null, art("m_orbit"), created = now - 6 * day),
            DriveFile("m2", "Neon Alley (2024).mkv", "video/x-matroska", 0, 0, null, art("m_neon_alley"), created = now - 9 * day),
        )
        DashboardSamples.added = shows.take(3).map { DrivePlace.TvShows to it } + films.map { DrivePlace.Movies to it }
        DashboardSamples.newShows = sampleNews(shows)
        val vm = MainViewModel(app)
        compose.setContent {
            TvExplorerTheme {
                CompositionLocalProvider(LocalPosterPainter provides ::decode) {
                    Box(Modifier.fillMaxSize().background(Palette.background)) { AppRoot(vm) }
                }
            }
        }
        for (light in listOf(false, true)) {
            // Home is opened afresh in each theme.
            compose.runOnIdle { Palette.light = light; vm.show(Screen.SETTINGS) }
            compose.mainClock.advanceTimeBy(1_500)
            compose.runOnIdle { vm.show(Screen.HOME) }
            compose.mainClock.advanceTimeBy(1_500)
            settle()
            // On the resume tile: the tile, the pause, then the new episodes' posters with their badges.
            compose.onNode(hasText("Midnight Harbor", substring = true) and hasClickAction()).requestFocus()
            settle()
            capture("tv-home-new-episodes", light)
            // On the first of them (the row glides it to its leading edge): its name over the stronger scrim.
            compose.onNode(hasText("Backyard Science", substring = true) and hasClickAction()).requestFocus()
            settle()
            capture("tv-home-new-episodes-poster", light)
        }
    }

    /** The TV Shows Overview: the banner, then Recently added with what's new under three posters, in blue. */
    @Test
    fun drawShowsOverview() {
        val shows = sampleShows()
        val news = sampleNews(shows)
        compose.setContent {
            TvExplorerTheme {
                CompositionLocalProvider(LocalPosterPainter provides ::decode) {
                    key(Palette.light) {
                        Box(Modifier.fillMaxSize().background(Palette.background).ambientBackdrop()) {
                            PosterBackdrop(remember { mutableStateOf(posterArt(shows[0])) }, null)
                            Column(Modifier.fillMaxSize().padding(start = 40.dp, end = 40.dp, top = 18.dp)) {
                                LibraryHeader(
                                    "TV Shows", shows, LibraryFilter(overview = true), onChange = {}, onSearch = {},
                                    switcher = { TopTabs(listOf("Movies", "TV Shows"), selected = 1, onSelect = {}) },
                                    hint = librarySearchHint("TV Shows", DriveStatus.Online, true),
                                    status = libraryFieldStatus(DriveStatus.Online, true),
                                    cutAt = 0,
                                )
                                Box(Modifier.fillMaxSize()) {
                                    LibraryRows(
                                        // Its own place per theme: the focus and scroll aren't carried from one shot to the next.
                                        shows, null, placeKey = "tv-shows-rows-${Palette.light}", isShows = true, canTakeFocus = { false },
                                        onChoose = {}, onPlay = {}, onMenu = {}, onFocusArt = {}, marks = remember { mutableIntStateOf(0) },
                                        newShows = news,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        for (l in listOf(false, true)) {
            compose.runOnIdle { Palette.light = l }
            settle()
            // On the first poster of Recently added: its name over the stronger scrim, the row in view.
            compose.onNode(hasText("Backyard Science", substring = true) and hasClickAction()).requestFocus()
            settle()
            capture("tv-tv-shows-rows", l)
        }
    }

    /**
     * A title page as its window shows it (the page itself, without the window's glide), as it opens
     * ([name]), then with the focus on the card called [onCard] ([cardShot]; none when null); [cardOnly]:
     * only that.
     */
    private fun drawPage(
        name: String, show: DriveFile, focusEpisodeId: String?, fresh: List<FreshEpisode>,
        onCard: String?, cardShot: String = name, cardOnly: Boolean = false,
    ) {
        var theme by mutableStateOf(false)
        compose.setContent {
            TvExplorerTheme {
                CompositionLocalProvider(LocalPosterPainter provides ::decode) {
                    // A new page per theme: it opens as a page does, its Play focused.
                    key(theme) {
                        Box(Modifier.fillMaxSize().background(Color.Black)) {
                            TitlePage(
                                show, posterArt(show), null, emptyList(), isShow = true, artHeaders = { emptyMap() },
                                onPlay = {}, onPlayEpisode = { _, _, _ -> }, onPlayKey = { _, _ -> }, openLabel = "All folders",
                                onOpen = {}, onChoose = {}, focusEpisodeId = focusEpisodeId, fresh = fresh,
                            )
                        }
                    }
                }
            }
        }
        for (l in listOf(false, true)) {
            compose.runOnIdle { Palette.light = l; theme = l }
            settle()
            if (!cardOnly) capture(name, l)
            if (onCard == null) continue
            compose.onNode(hasContentDescription(onCard) and hasClickAction()).requestFocus()
            settle()
            capture(cardShot, l)
        }
    }

    /** The four sample shows: three older ones with new episodes, and Detective Rowe, added whole this week. */
    private fun sampleShows(): List<DriveFile> = listOf(
        show("bs", "Backyard Science", "t_backyard_science", 400, 8.1, listOf("Family", "Documentary"), "backdrop-garden", ::garden,
            "Two curious kids and their grandmother turn an ordinary backyard into a laboratory, one weekend experiment at a time."),
        show("kr", "Kitchen Rules", "t_kitchen_rules", 700, 7.4, listOf("Reality"), "backdrop-kitchen", ::kitchen,
            "Home cooks take over a restaurant kitchen for one service a week, and the regulars vote."),
        show("lh", "The Lighthouse", "t_the_lighthouse", 900, 7.9, listOf("Drama", "Mystery"), "backdrop-sea", ::tidePools,
            "A keeper's diary turns up in the lamp room, and the island's oldest families start to talk."),
        show("dr", "Detective Rowe", "t_detective_rowe", 5, 7.2, listOf("Crime", "Drama"), null, null,
            "A retired detective takes one last case in the town she swore she'd never go back to."),
    )

    private fun show(
        id: String, title: String, poster: String, ageDays: Int, rating: Double, genres: List<String>,
        backdrop: String?, scene: ((Canvas, Paint, Int, Int) -> Unit)?, overview: String,
    ) = DriveFile(
        id, title, DriveFile.FOLDER_MIME, 0, 0, null, art(poster),
        MediaInfo(title, null, genres, rating, overview, backdrop?.let { picture(it, 1280, 720, scene!!) }, tmdbId = id.hashCode() and 0xffff, tv = true),
        created = now - ageDays * day,
    )

    /** "New · S3 E2" for Backyard Science, "3 new episodes" for Kitchen Rules, "New season" for The Lighthouse. */
    private fun sampleNews(shows: List<DriveFile>): Map<String, NewShow> {
        fun e(id: String, s: Int, n: Int, daysAgo: Int) = FreshEpisode(id, "S${s}E$n.mkv", "$id-season", "Season $s", now - daysAgo * day, s, n)
        val (bs, kr, lh) = shows
        return mapOf(
            bs.id to NewShow(bs.id, listOf(e("b32", 3, 2, 1)), now - 1 * day, announced = true),
            kr.id to NewShow(kr.id, listOf(e("k39", 3, 9, 3), e("k41", 4, 1, 2), e("k42", 4, 2, 2)), now - 2 * day, announced = true),
            lh.id to NewShow(lh.id, listOf(e("l21", 2, 1, 4), e("l22", 2, 2, 4), e("l23", 2, 3, 4)), now - 4 * day, announced = true),
        )
    }

    private fun season(id: String, n: Int) = DriveFile(id, "Season $n", DriveFile.FOLDER_MIME, 0, 0, null, created = now - 400 * day)

    private fun episode(id: String, name: String, meta: EpisodeMeta?, ageMs: Long, scene: ((Canvas, Paint, Int, Int) -> Unit)?) = DriveFile(
        id, name, "video/x-matroska", 0, 0, null, scene?.let { picture("still-$id", 640, 360, it) }, created = now - ageMs, episode = meta,
    )

    private fun art(key: String): String {
        val dir = File(app.cacheDir, "posters").apply { mkdirs() }
        val f = File(dir, "$key.jpg")
        if (!f.exists()) javaClass.getResourceAsStream("/gallery/posters/$key.jpg")!!.use { input -> f.outputStream().use { input.copyTo(it) } }
        return "file://" + f.absolutePath
    }

    /** A drawn picture ([draw] onto [w]×[h]), saved once as a file the sample painter reads. */
    private fun picture(name: String, w: Int, h: Int, draw: (Canvas, Paint, Int, Int) -> Unit): String {
        val dir = File(app.cacheDir, "pictures").apply { mkdirs() }
        val f = File(dir, "$name.png")
        if (!f.exists()) {
            val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            draw(Canvas(b), Paint(Paint.ANTI_ALIAS_FLAG), w, h)
            f.outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
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

    // ---- Drawn pictures: simple scenes in the soft light of a TV still ----------------------------

    private fun Canvas.gradient(p: Paint, l: Float, t: Float, r: Float, b: Float, top: Int, bottom: Int) {
        p.shader = LinearGradient(0f, t, 0f, b, top, bottom, Shader.TileMode.CLAMP)
        drawRect(l, t, r, b, p)
        p.shader = null
    }

    /** A backyard garden by day: sky, a fence, a lawn and a bed of flowers. */
    private fun garden(c: Canvas, p: Paint, w: Int, h: Int) {
        c.gradient(p, 0f, 0f, w.toFloat(), h * 0.55f, 0xFF7FB2E5.toInt(), 0xFFCFE3F4.toInt())
        c.gradient(p, 0f, h * 0.55f, w.toFloat(), h.toFloat(), 0xFF6A9E4B.toInt(), 0xFF2F5726.toInt())
        p.color = 0xFF9C8466.toInt()
        c.drawRect(0f, h * 0.44f, w.toFloat(), h * 0.47f, p)
        for (x in 0 until w step (w / 24)) c.drawRect(x.toFloat(), h * 0.36f, x + w / 60f, h * 0.58f, p)
        val colors = intArrayOf(0xFFE86F68.toInt(), 0xFFF2C14E.toInt(), 0xFFB57EDC.toInt(), 0xFFF4F1DE.toInt())
        for (i in 0 until 40) {
            p.color = colors[i % colors.size]
            c.drawCircle(w * (0.05f + i * 0.023f), h * (0.7f + (i % 5) * 0.035f), h * 0.018f, p)
        }
        p.shader = RadialGradient(w * 0.8f, h * 0.1f, h * 0.5f, 0x66FFF5D6, 0x00FFF5D6, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p); p.shader = null
    }

    /** A rainy afternoon: grey-blue sky, streaks of rain, jars on a step. */
    private fun rain(c: Canvas, p: Paint, w: Int, h: Int) {
        c.gradient(p, 0f, 0f, w.toFloat(), h.toFloat(), 0xFF5F7489.toInt(), 0xFF2C3946.toInt())
        p.color = 0x33FFFFFF
        p.strokeWidth = 2f
        for (i in 0 until 80) {
            val x = (i * 37 % w).toFloat(); val y = (i * 53 % h).toFloat()
            c.drawLine(x, y, x - 8f, y + 26f, p)
        }
        p.color = 0xFF4A3B33.toInt(); c.drawRect(0f, h * 0.78f, w.toFloat(), h.toFloat(), p)
        for (i in 0 until 6) {
            p.color = 0x99CFE8F5.toInt()
            c.drawRoundRect(RectF(w * (0.18f + i * 0.11f), h * 0.6f, w * (0.24f + i * 0.11f), h * 0.79f), 8f, 8f, p)
            p.color = 0xCC6FB3E6.toInt()
            c.drawRect(w * (0.18f + i * 0.11f), h * (0.74f - i * 0.02f), w * (0.24f + i * 0.11f), h * 0.79f, p)
        }
    }

    /** A baking-soda volcano on the lawn, mid-eruption. */
    private fun volcano(c: Canvas, p: Paint, w: Int, h: Int) {
        c.gradient(p, 0f, 0f, w.toFloat(), h * 0.6f, 0xFF8EC5EE.toInt(), 0xFFE6F2FA.toInt())
        c.gradient(p, 0f, h * 0.6f, w.toFloat(), h.toFloat(), 0xFF79A85A.toInt(), 0xFF3C6A2E.toInt())
        val cone = Path().apply { moveTo(w * 0.3f, h * 0.85f); lineTo(w * 0.46f, h * 0.38f); lineTo(w * 0.54f, h * 0.38f); lineTo(w * 0.7f, h * 0.85f); close() }
        p.shader = LinearGradient(0f, h * 0.38f, 0f, h * 0.85f, 0xFF8B5A3C.toInt(), 0xFF5A3825.toInt(), Shader.TileMode.CLAMP)
        c.drawPath(cone, p); p.shader = null
        p.color = 0xFFF7F3EA.toInt()
        for (i in 0 until 9) c.drawCircle(w * (0.44f + i * 0.015f), h * (0.33f - (i % 3) * 0.05f), h * 0.05f, p)
        p.color = 0xFFF28F3B.toInt(); c.drawCircle(w * 0.5f, h * 0.36f, h * 0.035f, p)
    }

    /** Rock pools at low tide: sky, sea, rocks and a starfish. */
    private fun tidePools(c: Canvas, p: Paint, w: Int, h: Int) {
        c.gradient(p, 0f, 0f, w.toFloat(), h * 0.4f, 0xFF9CC8EA.toInt(), 0xFFE8F1F7.toInt())
        c.gradient(p, 0f, h * 0.4f, w.toFloat(), h * 0.58f, 0xFF2F7FB3.toInt(), 0xFF1F5E8A.toInt())
        c.gradient(p, 0f, h * 0.58f, w.toFloat(), h.toFloat(), 0xFF6C6158.toInt(), 0xFF3B342E.toInt())
        p.color = 0xFF3D8FB0.toInt()
        c.drawOval(RectF(w * 0.12f, h * 0.68f, w * 0.48f, h * 0.9f), p)
        c.drawOval(RectF(w * 0.56f, h * 0.72f, w * 0.88f, h * 0.94f), p)
        p.color = 0xFFE9824F.toInt()
        val star = Path()
        for (i in 0 until 10) {
            val r = if (i % 2 == 0) h * 0.06f else h * 0.025f
            val a = Math.toRadians(i * 36.0 - 90)
            val x = w * 0.3f + r * Math.cos(a).toFloat(); val y = h * 0.79f + r * Math.sin(a).toFloat()
            if (i == 0) star.moveTo(x, y) else star.lineTo(x, y)
        }
        star.close(); c.drawPath(star, p)
    }

    /** Birds in a garden tree: green leaves and a few silhouettes. */
    private fun birds(c: Canvas, p: Paint, w: Int, h: Int) {
        c.gradient(p, 0f, 0f, w.toFloat(), h.toFloat(), 0xFFB8DCC4.toInt(), 0xFF5E9470.toInt())
        p.color = 0xFF3F6E4A.toInt()
        for (i in 0 until 14) c.drawCircle(w * (0.1f + i * 0.065f), h * (0.25f + (i % 4) * 0.06f), h * 0.16f, p)
        p.color = 0xFF4A3426.toInt(); c.drawRect(w * 0.47f, h * 0.45f, w * 0.53f, h.toFloat(), p)
        p.color = 0xFF22313A.toInt()
        for (i in 0 until 5) c.drawOval(RectF(w * (0.2f + i * 0.14f), h * (0.12f + (i % 2) * 0.08f), w * (0.25f + i * 0.14f), h * (0.15f + (i % 2) * 0.08f)), p)
    }

    /** A night sky over the fence: deep blue, stars and a moon. */
    private fun nightSky(c: Canvas, p: Paint, w: Int, h: Int) {
        c.gradient(p, 0f, 0f, w.toFloat(), h.toFloat(), 0xFF0B1630.toInt(), 0xFF243B63.toInt())
        p.color = 0xCCFFFFFF.toInt()
        for (i in 0 until 60) c.drawCircle((i * 97 % w).toFloat(), (i * 61 % (h * 0.7f).toInt()).toFloat(), if (i % 7 == 0) 2.6f else 1.4f, p)
        p.color = 0xFFF4EBC8.toInt(); c.drawCircle(w * 0.78f, h * 0.22f, h * 0.08f, p)
        p.color = 0xFF151D2B.toInt(); c.drawRect(0f, h * 0.8f, w.toFloat(), h.toFloat(), p)
        for (x in 0 until w step (w / 20)) c.drawRect(x.toFloat(), h * 0.72f, x + w / 50f, h.toFloat(), p)
    }

    /** A warm kitchen: counter, pans and window light. */
    private fun kitchen(c: Canvas, p: Paint, w: Int, h: Int) {
        c.gradient(p, 0f, 0f, w.toFloat(), h.toFloat(), 0xFFE4C6A1.toInt(), 0xFF8C6646.toInt())
        p.color = 0xFFF6E7CF.toInt(); c.drawRect(w * 0.6f, h * 0.1f, w * 0.9f, h * 0.45f, p)
        p.color = 0xFF5B4030.toInt(); c.drawRect(0f, h * 0.62f, w.toFloat(), h * 0.68f, p)
        p.color = 0xFF3B2C24.toInt(); c.drawRect(0f, h * 0.68f, w.toFloat(), h.toFloat(), p)
        p.color = 0xFF2E2E30.toInt()
        c.drawOval(RectF(w * 0.12f, h * 0.52f, w * 0.32f, h * 0.64f), p)
        c.drawOval(RectF(w * 0.36f, h * 0.5f, w * 0.52f, h * 0.63f), p)
        p.shader = RadialGradient(w * 0.75f, h * 0.28f, h * 0.6f, 0x55FFF3D6, 0x00FFF3D6, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p); p.shader = null
    }

    private companion object {
        /** A 300-character synopsis, as long as one is kept. */
        val TIDE_POOLS = "At the lowest tide of the year, the family drives to the coast before dawn, to map the rock pools " +
            "behind the old stone pier. Grandma brings a notebook, the kids bring buckets, and everyone learns which crabs pinch, why " +
            "anemones close when touched, and how a starfish can grow back an arm it has lost."

        /** A 60-character name. */
        const val LONG_NAME = "The Great Backyard Bird Count and Other Very Long Adventures"
    }
}
