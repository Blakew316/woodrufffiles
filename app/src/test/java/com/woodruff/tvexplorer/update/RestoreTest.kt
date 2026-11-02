package com.woodruff.tvexplorer.update

import android.app.Application
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.woodruff.tvexplorer.MainActivity
import com.woodruff.tvexplorer.cloud.DrivePlace
import com.woodruff.tvexplorer.cloud.DriveState
import com.woodruff.tvexplorer.cloud.DriveViewModel
import com.woodruff.tvexplorer.cloud.LibraryFilter
import com.woodruff.tvexplorer.cloud.LibrarySort
import com.woodruff.tvexplorer.player.ImageViewerActivity
import com.woodruff.tvexplorer.player.PlayerActivity
import com.woodruff.tvexplorer.screensaver.SlideshowActivity
import com.woodruff.tvexplorer.ui.Location
import com.woodruff.tvexplorer.ui.MainViewModel
import com.woodruff.tvexplorer.ui.Screen
import com.woodruff.tvexplorer.update.Restore.App
import com.woodruff.tvexplorer.update.Restore.Film
import com.woodruff.tvexplorer.update.Restore.Photo
import com.woodruff.tvexplorer.update.Restore.Slideshow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

/** What an update puts back, how it's saved, and the activities that bring it back ([Restore]). */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class RestoreTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val tvFilter = LibraryFilter(sort = LibrarySort.RECENT, genre = "Comedy", decade = 1990, overview = false, unwatched = true)
    private val streaming = App(Screen.DRIVE, "tv", tvFilter)

    @After fun noCameraLeftAsked() { com.woodruff.tvexplorer.ui.screens.openCameraAt = null }

    @Test fun everyRouteSurvivesBeingSavedAndReadBack() {
        val routes = listOf(
            App(Screen.HOME),
            streaming,
            App(Screen.CAMERAS, camera = "enterprises/x/devices/front", cameraAt = 2),
            App(Screen.BROWSER, folder = "/storage/emulated/0/Pictures/Family"),
            App(Screen.HOME, say = Restore.VIDEO_GONE),
            Film(listOf("drive:a", "drive:b", "/mnt/usb/Home Movies/Beach.mp4"), listOf("A", "B", "Beach"), 1, 3_725_000, under = streaming),
            Film(listOf("drive:solo"), listOf(""), 0, 0),
            Photo(listOf("/p/1.jpg", "drive:2"), listOf("One", "Two"), 1, slideshow = true, under = App(Screen.BROWSER, folder = "/p")),
            Slideshow(memories = true, under = App(Screen.HOME)),
            Slideshow(memories = false),
        )
        for (r in routes) assertEquals(r, Restore.decode(Restore.encode(r)))
    }

    @Test fun whetherItWasPlayingIsntSavedItAlwaysComesBackPaused() {
        val playing = Film(listOf("drive:a"), listOf("A"), 0, 10_000, playing = true)
        assertEquals(playing.copy(playing = false), Restore.decode(Restore.encode(playing)))
    }

    @Test fun anythingElseReadsAsNothing() {
        assertNull(Restore.decode(null))
        assertNull(Restore.decode(""))
        assertNull(Restore.decode("not json"))
        assertNull(Restore.decode("""{"v":99,"kind":"app","screen":"HOME"}"""))
        assertNull(Restore.decode("""{"v":1,"kind":"film","keys":[]}"""))
        assertNull(Restore.decode("""{"v":1,"kind":"something new"}"""))
        // A screen this version doesn't know: Home.
        assertEquals(App(Screen.HOME), Restore.decode("""{"v":1,"kind":"app","screen":"HOLODECK"}"""))
    }

    @Test fun aLongQueueKeepsTheFilmAndWhatsAroundIt() {
        val keys = List(600) { "drive:$it" }
        val titles = List(600) { "Episode $it" }
        val middle = Restore.film(keys, titles, 300, 42_000, playing = false)!!
        assertEquals(Restore.QUEUE_KEPT, middle.keys.size)
        assertEquals("drive:300", middle.keys[middle.index])
        assertEquals("Episode 300", middle.titles[middle.index])
        assertEquals(42_000, middle.positionMs)
        val first = Restore.film(keys, titles, 3, 0, playing = false)!!
        assertEquals("drive:3", first.keys[first.index])
        assertEquals("drive:0", first.keys[0])
        val last = Restore.film(keys, titles, 599, 0, playing = false)!!
        assertEquals("drive:599", last.keys[last.index])
        assertEquals("drive:599", last.keys.last())
        // Titles missing for some: blank, still lined up.
        val short = Restore.film(listOf("a", "b"), listOf("A"), 1, 0, playing = true)!!
        assertEquals(listOf("A", ""), short.titles)
        assertNull(Restore.film(emptyList(), emptyList(), 0, 0, playing = false))
    }

    @Test fun theMainWindowSaysItsScreen() {
        val drive = DriveState(stack = listOf(DrivePlace.TvShows, DrivePlace.Folder("show", "A Show")), filters = mapOf("TV Shows" to tvFilter, "Movies" to LibraryFilter()))
        // Inside a show: the library, at its top, with its filter.
        assertEquals(streaming, Restore.app(Screen.DRIVE, drive, null, null))
        assertEquals(App(Screen.DRIVE, "mydrive"), Restore.app(Screen.DRIVE, DriveState(), null, null))
        assertEquals(App(Screen.CAMERAS, camera = "cam-2", cameraAt = 1), Restore.app(Screen.CAMERAS, null, null, "cam-2" to 1))
        assertEquals(App(Screen.CAMERAS), Restore.app(Screen.CAMERAS, null, null, null))
        assertEquals(App(Screen.BROWSER, folder = "/x/y"), Restore.app(Screen.BROWSER, null, Location.Folder(File("/x/y")), null))
        // Files showing a search or a kind of file: Home (only folders are put back).
        assertEquals(App(Screen.HOME), Restore.app(Screen.BROWSER, null, Location.Search(File("/x"), "beach"), null))
        assertEquals(App(Screen.SETTINGS), Restore.app(Screen.SETTINGS, null, null, null))
    }

    @Test fun whatsGoneIsntPutBack() {
        val film = Film(listOf("drive:a", "drive:gone"), listOf("A", "Gone"), 1, 5_000, under = streaming)
        // The film was deleted: the screen it was started from, saying so.
        assertEquals(streaming.copy(say = Restore.VIDEO_GONE), Restore.checked(film) { it == "drive:gone" })
        assertEquals(App(Screen.HOME, say = Restore.VIDEO_GONE), Restore.checked(film.copy(under = null)) { it == "drive:gone" })
        assertEquals(film, Restore.checked(film) { false })

        // The photo was deleted: its folder.
        val photo = Photo(listOf("/p/Family/1.jpg"), listOf("1"), 0, slideshow = false, under = App(Screen.HOME))
        assertEquals(App(Screen.BROWSER, folder = "/p/Family"), Restore.checked(photo) { it == "/p/Family/1.jpg" })
        // ...and its folder too (the USB drive was unplugged): the screen under it.
        assertEquals(App(Screen.HOME), Restore.checked(photo) { true })

        // A folder that's gone: Home, under a film too.
        val folder = App(Screen.BROWSER, folder = "/mnt/usb")
        assertEquals(App(Screen.HOME), Restore.checked(folder) { it == "/mnt/usb" })
        assertEquals(Slideshow(false, App(Screen.HOME)), Restore.checked(Slideshow(false, folder)) { it == "/mnt/usb" })
    }

    @Test fun onlyWhatsOnTopAppearsTheScreenItCameFromWaitsUnderIt() {
        val film = Film(listOf("drive:a", "drive:b"), listOf("A", "B"), 1, 3_725_000, under = streaming)
        val (main, player) = Restore.intents(app, film)
        assertEquals(MainActivity::class.java.name, main.component?.className)
        assertEquals(Restore.OPEN, main.getStringExtra(MainActivity.EXTRA_OPEN))
        assertEquals(streaming, Restore.decode(main.getStringExtra(Restore.EXTRA_ROUTE)))
        assertTrue(main.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        assertEquals(PlayerActivity::class.java.name, player.component?.className)
        assertTrue(player.getBooleanExtra(Restore.EXTRA_PAUSED, false))
        assertEquals(3_725_000L, player.getLongExtra("position", -1))
        assertEquals("drive:b", player.getStringExtra("start"))
        assertEquals(arrayListOf("drive:a", "drive:b"), player.getStringArrayListExtra("keys"))
        // In Woodruff's task, on top of the main window, not a task of its own.
        assertEquals(0, player.flags and Intent.FLAG_ACTIVITY_NEW_TASK)

        // A film started from the phone with no Woodruff under it: only the player, in its own task.
        val alone = Restore.intents(app, film.copy(under = null)).single()
        assertEquals(PlayerActivity::class.java.name, alone.component?.className)
        assertTrue(alone.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)

        val photo = Restore.intents(app, Photo(listOf("/a.jpg", "/b.jpg"), listOf("A", "B"), 1, slideshow = true, under = App(Screen.BROWSER, folder = "/"))).last()
        assertEquals(ImageViewerActivity::class.java.name, photo.component?.className)
        assertEquals("/b.jpg", photo.getStringExtra("start"))
        assertTrue(photo.getBooleanExtra(Restore.EXTRA_SLIDESHOW, false))

        val slideshow = Restore.intents(app, Slideshow(memories = true, under = App(Screen.HOME))).last()
        assertEquals(SlideshowActivity::class.java.name, slideshow.component?.className)
        assertTrue(slideshow.getBooleanExtra("memories", false))

        val home = Restore.intents(app, App(Screen.SETTINGS)).single()
        assertEquals(App(Screen.SETTINGS), Restore.decode(home.getStringExtra(Restore.EXTRA_ROUTE)))
    }

    /** The activities started since the last look, in the order they were started (Robolectric hands back the latest first). */
    private fun started(): List<Intent> = generateSequence { shadowOf(app).nextStartedActivity }.toList().asReversed()

    @Test fun reopeningStartsThemInOneGo() {
        val beach = File(app.filesDir, "Beach.mp4").apply { writeText("not really a film") }
        Restore.reopen(app, Film(listOf(beach.path), listOf("Beach"), 0, 61_000, under = App(Screen.HOME))) { Restore.isGone(app, it) }
        assertEquals(listOf(MainActivity::class.java.name, PlayerActivity::class.java.name), started().map { it.component?.className })

        // Taken off the TV since: the screen it came from, saying so.
        beach.delete()
        Restore.reopen(app, Film(listOf(beach.path), listOf("Beach"), 0, 61_000, under = App(Screen.HOME))) { Restore.isGone(app, it) }
        val only = started().single()
        assertEquals(MainActivity::class.java.name, only.component?.className)
        assertEquals(App(Screen.HOME, say = Restore.VIDEO_GONE), Restore.decode(only.getStringExtra(Restore.EXTRA_ROUTE)))
    }

    @Test fun theMainWindowOpensAtTheScreenPutBack() {
        val vm = MainViewModel(app)
        val drive = DriveViewModel(app)
        Restore.apply(streaming, vm) { drive }
        assertEquals(Screen.DRIVE, vm.screen.value)
        assertEquals(DrivePlace.TvShows, drive.state.value.stack.single())
        assertEquals(tvFilter, drive.state.value.filter)

        Restore.apply(App(Screen.CAMERAS, camera = "not listed yet", cameraAt = 2), vm) { drive }
        assertEquals(Screen.CAMERAS, vm.screen.value)
        assertEquals(2, com.woodruff.tvexplorer.ui.screens.openCameraAt)

        val folder = File(app.filesDir, "Family").apply { mkdirs() }
        Restore.apply(App(Screen.BROWSER, folder = folder.path), vm) { drive }
        assertEquals(Screen.BROWSER, vm.screen.value)
        assertEquals(Location.Folder(folder), vm.browser.value.location)

        Restore.apply(App(Screen.HOME, say = Restore.VIDEO_GONE), vm) { drive }
        assertEquals(Screen.HOME, vm.screen.value)
        assertEquals(Restore.VIDEO_GONE, vm.message.value)
    }

    @Test fun aLibraryNameStaysTheSame() {
        for (place in listOf(DrivePlace.Movies, DrivePlace.TvShows, DrivePlace.MyDrive, DrivePlace.SharedWithMe, DrivePlace.Videos())) {
            assertEquals(place, Restore.placeOf(Restore.libraryOf(place)))
        }
        assertNull(Restore.libraryOf(DrivePlace.Folder("some-folder", "Somewhere")))
        assertNull(Restore.placeOf(null))
    }
}
