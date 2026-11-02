package com.woodruff.tvexplorer.update

import android.content.Context
import android.content.Intent
import android.util.Log
import com.woodruff.tvexplorer.MainActivity
import com.woodruff.tvexplorer.cloud.DrivePlace
import com.woodruff.tvexplorer.cloud.DriveState
import com.woodruff.tvexplorer.cloud.DriveViewModel
import com.woodruff.tvexplorer.cloud.GoogleAuth
import com.woodruff.tvexplorer.cloud.LibraryFilter
import com.woodruff.tvexplorer.cloud.LibrarySort
import com.woodruff.tvexplorer.player.ImageViewerActivity
import com.woodruff.tvexplorer.player.MediaRef
import com.woodruff.tvexplorer.player.PlayerActivity
import com.woodruff.tvexplorer.screensaver.SlideshowActivity
import com.woodruff.tvexplorer.ui.Location
import com.woodruff.tvexplorer.ui.MainViewModel
import com.woodruff.tvexplorer.ui.Screen
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

/**
 * Puts back what Woodruff was showing when an update restarted it, so an update is invisible: the
 * slideshow running again, the camera wall, the photo on screen, the paused film at its spot, or
 * the screen that was up (Streaming in the same library with the same filter). Title pages, menus
 * and other pop-ups aren't put back: the screen under them is.
 *
 * Before an install starts, [OnScreen] asks the screen on top what it is ([Restorable]) and the
 * updater saves that [Route]. Once the new version is in, [com.woodruff.tvexplorer.server.BootReceiver]
 * reopens it ([reopen]): the screen it was opened from goes underneath (created only when Back
 * reaches it) and only the top one appears, so nothing else flashes first. The film comes back
 * paused, controls up. If what was showing has gone meanwhile (the film was deleted, the USB drive
 * unplugged), the screen it was opened from opens instead and says so.
 */
object Restore {
    /** [MainActivity.EXTRA_OPEN] for a screen put back, which [EXTRA_ROUTE] describes. */
    const val OPEN = "restore"
    const val EXTRA_ROUTE = "com.woodruff.tvexplorer.restore.ROUTE"
    /** The player starts paused (at the position it's given) instead of playing. */
    const val EXTRA_PAUSED = "com.woodruff.tvexplorer.restore.PAUSED"
    /** The photo viewer starts with its slideshow running. */
    const val EXTRA_SLIDESHOW = "com.woodruff.tvexplorer.restore.SLIDESHOW"

    /** What the screen the film was started from says when the film can't come back. */
    const val VIDEO_GONE = "Woodruff updated while you were away. That video isn’t available right now."

    /** At most this many of a film's queue come back, around the film itself (the player can hold 600). */
    internal const val QUEUE_KEPT = 200

    /** How long the restore waits to hear whether a film is still in Google Drive before putting it back anyway. */
    private const val CHECK_WAIT_MS = 5_000L
    private const val VERSION = 1

    /** A screen of Woodruff's that can say what it shows, so it can be put back ([OnScreen] asks it, on the main thread). */
    interface Restorable {
        /** What to put back if an update restarted Woodruff now; null: nothing (it's on its way out). */
        fun restoreRoute(): Route?
    }

    /** What to put back. */
    sealed interface Route

    /**
     * A screen of the main window, at its top: [library] and [filter] for Streaming (and the
     * Google Drive view), the camera full screen in the Command Center ([camera], at [cameraAt] in
     * the list), the folder in Files or Family Memories ([folder]). [say]: a message for when it
     * opens (only ever set as it's put back).
     */
    data class App(
        val screen: Screen,
        val library: String? = null,
        val filter: LibraryFilter? = null,
        val camera: String? = null,
        val cameraAt: Int = -1,
        val folder: String? = null,
        val say: String? = null,
    ) : Route

    /**
     * A film (or song) in Woodruff's player: its queue, the one playing and where. It comes back
     * paused there. [playing]: it was playing a moment ago (never saved: only for whether it's in
     * use). [under]: the screen it was started from, for Back.
     */
    data class Film(
        val keys: List<String>,
        val titles: List<String>,
        val index: Int,
        val positionMs: Long,
        val playing: Boolean = false,
        val under: App? = null,
    ) : Route

    /** A photo in the photo viewer, among [paths], with its slideshow running or not. */
    data class Photo(
        val paths: List<String>,
        val titles: List<String>,
        val index: Int,
        val slideshow: Boolean,
        val under: App? = null,
    ) : Route

    /** Woodruff's own slideshow (only "On this day" photos when [memories]). */
    data class Slideshow(val memories: Boolean, val under: App? = null) : Route

    // ---- What each screen says ---------------------------------------------------------------------

    /**
     * The player's [keys] and [titles] with [index] the one playing: kept to [QUEUE_KEPT] around it,
     * so the saved route stays small however long the folder it filled the queue from.
     */
    fun film(keys: List<String>, titles: List<String>, index: Int, positionMs: Long, playing: Boolean): Film? =
        kept(keys, titles, index)?.let { (k, t, i) -> Film(k, t, i, positionMs.coerceAtLeast(0), playing) }

    /** The photo viewer's [paths] (and their [titles]) with [index] the one on screen. */
    fun photo(paths: List<String>, titles: List<String>, index: Int, slideshow: Boolean): Photo? =
        kept(paths, titles, index)?.let { (p, t, i) -> Photo(p, t, i, slideshow) }

    /** At most [QUEUE_KEPT] of [items] around [index], their [titles] lined up (blank where missing), and where [index] lands. */
    private fun kept(items: List<String>, titles: List<String>, index: Int): Triple<List<String>, List<String>, Int>? {
        if (items.isEmpty()) return null
        val i = index.coerceIn(0, items.size - 1)
        val from = (i - QUEUE_KEPT / 2).coerceIn(0, (items.size - QUEUE_KEPT).coerceAtLeast(0))
        val to = (from + QUEUE_KEPT).coerceAtMost(items.size)
        return Triple(items.subList(from, to).toList(), (from until to).map { titles.getOrNull(it).orEmpty() }, i - from)
    }

    /**
     * The main window's screen: Streaming's library and its filter, the Command Center's camera full
     * screen ([camera]: its name and place), the folder Files is in. Pages and menus over a screen
     * aren't part of it.
     */
    fun app(screen: Screen, drive: DriveState?, browser: Location?, camera: Pair<String, Int>?): App = when (screen) {
        Screen.DRIVE -> {
            val root = drive?.stack?.firstOrNull()
            val library = root?.let(::libraryOf)
            App(Screen.DRIVE, library, filter = if (library == MOVIES || library == TV) drive.filters[root.title] else null)
        }
        Screen.CAMERAS -> App(Screen.CAMERAS, camera = camera?.first, cameraAt = camera?.second ?: -1)
        Screen.BROWSER -> (browser as? Location.Folder)?.let { App(Screen.BROWSER, folder = it.dir.path) } ?: App(Screen.HOME)
        else -> App(screen)
    }

    private const val MOVIES = "movies"
    private const val TV = "tv"

    /** Streaming's libraries and the Google Drive views, by a name that stays the same between versions. */
    internal fun libraryOf(place: DrivePlace): String? = when (place) {
        DrivePlace.Movies -> MOVIES
        DrivePlace.TvShows -> TV
        DrivePlace.MyDrive -> "mydrive"
        DrivePlace.SharedWithMe -> "shared"
        is DrivePlace.Videos -> "videos"
        else -> null
    }

    internal fun placeOf(library: String?): DrivePlace? = when (library) {
        MOVIES -> DrivePlace.Movies
        TV -> DrivePlace.TvShows
        "mydrive" -> DrivePlace.MyDrive
        "shared" -> DrivePlace.SharedWithMe
        "videos" -> DrivePlace.Videos()
        else -> null
    }

    // ---- Saving and reading a route --------------------------------------------------------------

    /** [route] as a small JSON string, saved before the install and handed to the new version. */
    fun encode(route: Route): String = json(route).put("v", VERSION).toString()

    /** A route [encode] made; null for anything else (another version's, damaged). */
    fun decode(text: String?): Route? = runCatching {
        val o = JSONObject(text ?: return null)
        if (o.optInt("v") != VERSION) return null
        route(o)
    }.getOrNull()

    private fun json(route: Route): JSONObject = when (route) {
        is App -> JSONObject().put("kind", "app").put("screen", route.screen.name).apply {
            route.library?.let { put("library", it) }
            route.filter?.let { put("filter", filterJson(it)) }
            route.camera?.let { put("camera", it) }
            if (route.cameraAt >= 0) put("cameraAt", route.cameraAt)
            route.folder?.let { put("folder", it) }
            route.say?.let { put("say", it) }
        }
        is Film -> JSONObject().put("kind", "film").put("keys", JSONArray(route.keys)).put("titles", JSONArray(route.titles))
            .put("index", route.index).put("position", route.positionMs).under(route.under)
        is Photo -> JSONObject().put("kind", "photo").put("paths", JSONArray(route.paths)).put("titles", JSONArray(route.titles))
            .put("index", route.index).put("slideshow", route.slideshow).under(route.under)
        is Slideshow -> JSONObject().put("kind", "slideshow").put("memories", route.memories).under(route.under)
    }

    private fun JSONObject.under(app: App?): JSONObject = apply { app?.let { put("under", json(it)) } }

    private fun route(o: JSONObject): Route? = when (o.optString("kind")) {
        "app" -> App(
            screen = runCatching { Screen.valueOf(o.getString("screen")) }.getOrDefault(Screen.HOME),
            library = o.optString("library").ifEmpty { null },
            filter = o.optJSONObject("filter")?.let(::filterOf),
            camera = o.optString("camera").ifEmpty { null },
            cameraAt = o.optInt("cameraAt", -1),
            folder = o.optString("folder").ifEmpty { null },
            say = o.optString("say").ifEmpty { null },
        )
        "film" -> strings(o.optJSONArray("keys")).takeIf { it.isNotEmpty() }?.let { keys ->
            Film(keys, strings(o.optJSONArray("titles")), o.optInt("index").coerceIn(0, keys.size - 1), o.optLong("position").coerceAtLeast(0), under = underOf(o))
        }
        "photo" -> strings(o.optJSONArray("paths")).takeIf { it.isNotEmpty() }?.let { paths ->
            Photo(paths, strings(o.optJSONArray("titles")), o.optInt("index").coerceIn(0, paths.size - 1), o.optBoolean("slideshow"), underOf(o))
        }
        "slideshow" -> Slideshow(o.optBoolean("memories"), underOf(o))
        else -> null
    }

    private fun underOf(o: JSONObject): App? = o.optJSONObject("under")?.let(::route) as? App

    private fun strings(a: JSONArray?): List<String> = if (a == null) emptyList() else List(a.length()) { a.optString(it) }

    private fun filterJson(f: LibraryFilter) = JSONObject().put("sort", f.sort.name).put("query", f.query)
        .put("overview", f.overview).put("unwatched", f.unwatched)
        .apply { f.genre?.let { put("genre", it) }; f.decade?.let { put("decade", it) } }

    private fun filterOf(o: JSONObject) = LibraryFilter(
        sort = runCatching { LibrarySort.valueOf(o.getString("sort")) }.getOrDefault(LibrarySort.TITLE),
        genre = o.optString("genre").ifEmpty { null },
        decade = if (o.has("decade")) o.optInt("decade") else null,
        query = o.optString("query"),
        overview = o.optBoolean("overview"),
        unwatched = o.optBoolean("unwatched"),
    )

    // ---- Putting it back -------------------------------------------------------------------------

    /**
     * Reopens [route] after an update (BootReceiver, on a background thread: it may ask Google Drive
     * whether a film is still there). Android lets Woodruff do this from the background when it may
     * draw over other apps, which the pop-up notifications already ask for; otherwise the TV stays
     * where it is, as before.
     */
    fun reopen(c: Context, route: Route, gone: (String) -> Boolean = { isGone(c, it) }) {
        val intents = intents(c, checked(route, gone))
        Log.i("Restore", "reopening ${describe(route)}")
        runCatching { c.startActivities(intents.toTypedArray()) }.onFailure { Log.w("Restore", "couldn't reopen after the update", it) }
    }

    /** "film", "app DRIVE"… for the log (no names: the log never says what someone was watching). */
    private fun describe(route: Route): String = when (route) {
        is App -> "app ${route.screen}"
        is Film -> "film" + (route.under?.let { " over ${it.screen}" } ?: "")
        is Photo -> "photo" + (route.under?.let { " over ${it.screen}" } ?: "")
        is Slideshow -> "slideshow" + (route.under?.let { " over ${it.screen}" } ?: "")
    }

    /**
     * [route], or what to open instead where its film, photo or folder has gone ([gone] answers for
     * a media key or a folder path): the screen the film was started from, saying why; the gone
     * photo's folder; Home for a folder that's gone. Visible for tests.
     */
    internal fun checked(route: Route, gone: (String) -> Boolean): Route = when (route) {
        is App -> checkedApp(route, gone)
        is Film -> {
            val under = route.under?.let { checkedApp(it, gone) }
            if (gone(route.keys[route.index])) (under ?: App(Screen.HOME)).copy(say = VIDEO_GONE) else route.copy(under = under)
        }
        is Photo -> {
            val under = route.under?.let { checkedApp(it, gone) }
            val path = route.paths[route.index]
            if (!gone(path)) route.copy(under = under)
            else (MediaRef.parse(path) as? MediaRef.Local)?.let { File(it.path).parent }?.takeIf { !gone(it) }
                ?.let { App(Screen.BROWSER, folder = it) } ?: under ?: App(Screen.HOME)
        }
        is Slideshow -> route.copy(under = route.under?.let { checkedApp(it, gone) })
    }

    private fun checkedApp(app: App, gone: (String) -> Boolean): App =
        if (app.screen == Screen.BROWSER && (app.folder == null || gone(app.folder))) App(Screen.HOME) else app

    /**
     * The activities that put [route] back, bottom first: the main window at its screen (when the
     * route has one), then the player, the photo viewer or the slideshow over it. Only the first
     * starts a task (Woodruff's); the others go on top of it. Visible for tests.
     */
    internal fun intents(c: Context, route: Route): List<Intent> {
        val under = when (route) {
            is App -> route
            is Film -> route.under
            is Photo -> route.under
            is Slideshow -> route.under
        }
        val top = when (route) {
            is App -> null
            is Film -> PlayerActivity.intent(c, route.keys, route.titles, route.keys[route.index], route.positionMs).putExtra(EXTRA_PAUSED, true)
            is Photo -> ImageViewerActivity.intent(c, route.paths, route.paths[route.index], route.titles).putExtra(EXTRA_SLIDESHOW, route.slideshow)
            is Slideshow -> SlideshowActivity.intent(c, route.memories)
        }
        return listOfNotNull(under?.let { mainIntent(c, it) }, top).mapIndexed { i, intent ->
            if (i == 0) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) else intent.setFlags(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK.inv())
        }
    }

    /** The main window, opening at [app]. */
    fun mainIntent(c: Context, app: App): Intent =
        Intent(c, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN, OPEN).putExtra(EXTRA_ROUTE, encode(app))

    /**
     * Opens [app] in the main window (MainActivity, before its first frame): the screen, Streaming's
     * library and filter, the camera full screen, the folder; and its message, if it has one.
     */
    fun apply(app: App, vm: MainViewModel, drive: () -> DriveViewModel) {
        when (app.screen) {
            Screen.DRIVE -> {
                val d = drive()
                d.openRoot(placeOf(app.library) ?: DrivePlace.Movies)
                app.filter?.let { d.setFilter(it) }
                vm.show(Screen.DRIVE)
            }
            Screen.CAMERAS -> {
                // By name when the cameras are listed already (the list could have changed), else where it was.
                val cams = com.woodruff.tvexplorer.nest.Nest.state.value.cameras
                val at = cams.indexOfFirst { it.name == app.camera }.takeIf { it >= 0 } ?: app.cameraAt
                if (at >= 0) com.woodruff.tvexplorer.ui.screens.openCameraAt = at
                vm.show(Screen.CAMERAS)
            }
            Screen.BROWSER -> app.folder?.let { vm.openRoot(File(it)) } ?: vm.show(Screen.HOME)
            else -> vm.show(app.screen)
        }
        app.say?.let { vm.say(it) }
    }

    // ---- Has it gone? --------------------------------------------------------------------------------

    /**
     * Whether the film or photo [key] (a media key, or a folder path) has gone: a file on the TV or a
     * USB drive that isn't there any more, or a Google Drive file deleted or in the bin. When Google
     * can't be asked in time it counts as there: the player says so itself if it really isn't.
     */
    fun isGone(c: Context, key: String): Boolean = when (val ref = MediaRef.parse(key)) {
        is MediaRef.Local -> !File(ref.path).exists()
        is MediaRef.Drive -> driveGone(c, ref.id)
        is MediaRef.Remote -> false
    }

    private fun driveGone(c: Context, id: String): Boolean {
        val ask = FutureTask { askDrive(c, id) }
        Thread(ask, "restore-check").apply { isDaemon = true }.start()
        return runCatching { ask.get(CHECK_WAIT_MS, TimeUnit.MILLISECONDS) }.getOrNull() == true
    }

    private fun askDrive(c: Context, id: String): Boolean {
        val token = GoogleAuth.blockingToken(c) ?: return false
        val url = "https://www.googleapis.com/drive/v3/files/${URLEncoder.encode(id, "UTF-8")}?fields=trashed&supportsAllDrives=true"
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 4_000; readTimeout = 4_000
            setRequestProperty("Authorization", "Bearer $token")
        }
        return try {
            when (conn.responseCode) {
                404 -> true
                in 200..299 -> JSONObject(conn.inputStream.bufferedReader().use { it.readText() }).optBoolean("trashed")
                else -> false
            }
        } finally {
            conn.disconnect()
        }
    }
}
