package com.woodruff.tvexplorer.photos

import android.content.Context
import android.os.Environment
import android.util.Log
import com.woodruff.tvexplorer.cloud.GoogleAuth
import com.woodruff.tvexplorer.data.FileOperations
import com.woodruff.tvexplorer.screensaver.Appearance
import com.woodruff.tvexplorer.server.CompanionHub
import com.woodruff.tvexplorer.server.TransferService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Google Photos for the slideshow, through Google's Photos Picker (since 2025 apps can no longer
 * browse a whole library or list its albums). The TV shows a QR code, the owner picks photos on their phone in
 * Google's own picker, and the TV downloads them into Pictures/Google Photos and points the
 * slideshow there. Downloading matters: picked items can only be fetched for a short time.
 *
 * The picker has no list of albums, but searching an album's name in it shows that album, so an album made for
 * the TV is picked by searching its name and selecting its photos. A new pick replaces what earlier picks left on
 * the TV (or is added to it): the TV holds just that album, photos it already has aren't downloaded again, and
 * its storage doesn't fill up pick after pick.
 */
object GooglePhotos {
    enum class Step { NEED_GOOGLE, SIGN_IN, READY, PICKING, DOWNLOADING, DONE }

    data class State(
        val step: Step = Step.NEED_GOOGLE,
        val qrUrl: String? = null,
        val done: Int = 0,
        val total: Int = 0,
        val added: Int = 0,
        val videos: Int = 0,
        /** Picked items the TV already had: not downloaded again. */
        val kept: Int = 0,
        /** Earlier picks taken off the TV because this pick replaced them. */
        val removed: Int = 0,
        val failed: Int = 0,
        val error: String? = null,
    )

    /** What the downloads take up on the TV, for the window's storage line. */
    data class OnTv(val photos: Int = 0, val videos: Int = 0, val bytes: Long = 0) {
        val items: Int get() = photos + videos
    }

    private const val API = "https://photospicker.googleapis.com/v1/"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state
    private lateinit var app: Context
    private var job: Job? = null
    @Volatile private var sessionId: String? = null
    private val _onTv = MutableStateFlow(OnTv())
    val onTv: StateFlow<OnTv> = _onTv
    private val _replace = MutableStateFlow(true)
    /** True: the next pick replaces what earlier picks left on the TV; false: it's added to them. */
    val replace: StateFlow<Boolean> = _replace

    /** Downloads and "Remove all" take turns, so files can't be pulled from under a download. */
    private val files = Mutex()
    /** The file each picked item became, by Google's item id (an item keeps its id from one pick to the next). */
    private class Kept(val name: String, val video: Boolean)
    private val picked = HashMap<String, Kept>()
    private var pickedLoaded = false

    /** Picked photos (also the slideshow folder) and picked videos, on the TV's shared storage. */
    val folder: File get() = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "Google Photos")
    val videoFolder: File get() = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "Google Photos")

    fun init(c: Context) {
        app = c.applicationContext
        scope.launch {
            CompanionHub.googleCodes.collect { (code, attempt) ->
                if (attempt.grant != GoogleAuth.Grant.PHOTOS) return@collect
                try {
                    GoogleAuth.exchange(app, code, attempt)
                    startPicking() // signed in on the phone: go straight to choosing photos
                } catch (e: Exception) {
                    _state.update { it.copy(error = e.message ?: "Sign-in failed") }
                }
            }
        }
    }

    fun isConnected(c: Context) = GoogleAuth.isConnected(c, GoogleAuth.Grant.PHOTOS)

    /** Called when the Google Photos window opens: sign in if needed, else start a new pick. */
    fun open() {
        if (_state.value.step == Step.DOWNLOADING) return // reopening the window just shows progress
        _state.value = State()
        _replace.value = true
        scope.launch { files.withLock { withContext(Dispatchers.IO) { loadPicked(); countOnTv() } } }
        when {
            !GoogleAuth.isConfigured(app) -> _state.value = State(Step.NEED_GOOGLE)
            !isConnected(app) -> startSignIn()
            else -> startPicking()
        }
    }

    private fun startSignIn() {
        job?.cancel()
        job = scope.launch {
            _state.value = State(Step.SIGN_IN)
            TransferService.start(app)
            val server = TransferService.state.first { it.running || it.error != null }
            val base = server.base ?: return@launch _state.update { it.copy(error = server.error ?: "The phone connection couldn't start.") }
            val a = GoogleAuth.newAttempt(app, base, GoogleAuth.Grant.PHOTOS)
            CompanionHub.expect(a)
            _state.value = State(Step.SIGN_IN, qrUrl = a.authUrl)
        }
    }

    fun startPicking() {
        job?.cancel()
        job = scope.launch {
            try {
                _state.value = State(Step.READY)
                val session = withContext(Dispatchers.IO) { call("POST", "sessions", "{}") }
                val id = session.getString("id").also { sessionId = it }
                // "/autoclose" closes Google's picker on the phone as soon as the selection is done.
                _state.value = State(Step.PICKING, qrUrl = session.getString("pickerUri").trimEnd('/') + "/autoclose")
                val poll = seconds(session.optJSONObject("pollingConfig")?.optString("pollInterval"), 4.0)
                val deadline = System.currentTimeMillis() +
                    (seconds(session.optJSONObject("pollingConfig")?.optString("timeoutIn"), 1800.0) * 1000).toLong()
                while (true) {
                    delay((poll * 1000).toLong().coerceIn(2_000, 15_000))
                    val s = withContext(Dispatchers.IO) { call("GET", "sessions/$id", null) }
                    if (s.optBoolean("mediaItemsSet")) break
                    if (System.currentTimeMillis() > deadline) throw IOException("The picker timed out. Try again.")
                }
                download(id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("GooglePhotos", "pick failed", e)
                _state.update { it.copy(error = e.message ?: "Couldn't get photos") }
            } finally {
                endSession()
            }
        }
    }

    private suspend fun download(session: String) {
        data class Item(val id: String, val url: String, val name: String, val video: Boolean, val taken: Long?)
        val items = mutableListOf<Item>()
        var page: String? = null
        do {
            val q = "mediaItems?sessionId=${enc(session)}&pageSize=100" + (page?.let { "&pageToken=${enc(it)}" } ?: "")
            val r = withContext(Dispatchers.IO) { call("GET", q, null) }
            val arr = r.optJSONArray("mediaItems")
            if (arr != null) for (i in 0 until arr.length()) {
                val m = arr.getJSONObject(i)
                val f = m.optJSONObject("mediaFile") ?: continue
                val video = m.optString("type") == "VIDEO"
                val name = f.optString("filename").ifBlank { m.optString("id") + if (video) ".mp4" else ".jpg" }
                // Resized downloads lose their EXIF data, so keep Google's capture time for the date filters.
                val url = f.getString("baseUrl")
                items += Item(m.optString("id").ifBlank { url }, url, name, video, MediaDates.parseRfc3339(m.optString("createTime")))
            }
            page = r.optString("nextPageToken").ifBlank { null }
        } while (page != null)

        val replacing = _replace.value
        files.withLock {
            // Items the TV already has (same Google item, its file still here) aren't downloaded again.
            val todo = withContext(Dispatchers.IO) {
                loadPicked()
                synchronized(picked) { items.filter { item -> picked[item.id]?.let { fileOf(it).exists() } != true } }
            }
            val kept = items.size - todo.size
            _state.value = State(Step.DOWNLOADING, done = 0, total = todo.size, kept = kept)
            val screen = app.resources.displayMetrics
            val sized = photoSize(screen.widthPixels, screen.heightPixels)
            // Four downloads at a time: much faster than one by one, without starving video playback.
            val added = java.util.concurrent.atomic.AtomicInteger()
            val videos = java.util.concurrent.atomic.AtomicInteger()
            val failed = java.util.concurrent.atomic.AtomicInteger()
            val finished = java.util.concurrent.atomic.AtomicInteger()
            val gate = kotlinx.coroutines.sync.Semaphore(4)
            try {
                coroutineScope {
                    for (item in todo) launch(Dispatchers.IO) {
                        gate.withPermit {
                            val dir = (if (item.video) videoFolder else folder).apply { mkdirs() }
                            val target = synchronized(this@GooglePhotos) {
                                // Reserve the name so two parallel downloads can't pick the same one.
                                FileOperations.uniqueTarget(dir, if (item.video) cleanName(item.name) else jpegName(item.name))
                                    .also { it.createNewFile() }
                            }
                            val tmp = File(dir, ".${target.name}.part")
                            try {
                                // Photos at the size the TV draws them; videos as-is ("=dv").
                                val conn = open(if (item.video) "${item.url}=dv" else "${item.url}$sized", "GET")
                                if (conn.responseCode !in 200..299) throw IOException("HTTP ${conn.responseCode}")
                                conn.inputStream.use { input -> tmp.outputStream().use { input.copyTo(it, 256 * 1024) } }
                                conn.disconnect()
                                // rename() replaces the empty placeholder atomically, keeping the name reserved.
                                if (!tmp.renameTo(target)) throw IOException("Couldn't save ${target.name}")
                                item.taken?.let { MediaDates.record(app, target, it) }
                                if (item.video) videos.incrementAndGet() else added.incrementAndGet()
                                // Written down as it arrives (and saved now and then), so even an interrupted
                                // download is replaced by the next pick instead of staying on the TV for good.
                                val n = synchronized(picked) { picked[item.id] = Kept(target.name, item.video); picked.size }
                                if (n % 25 == 0) savePicked()
                            } catch (e: CancellationException) {
                                target.delete()
                                throw e
                            } catch (e: Exception) {
                                target.delete()
                                failed.incrementAndGet()
                                Log.w("GooglePhotos", "download failed: ${item.name}", e)
                            } finally {
                                tmp.delete()
                            }
                        }
                        val n = finished.incrementAndGet()
                        _state.update { it.copy(done = n, added = added.get(), videos = videos.get(), failed = failed.get()) }
                    }
                }
            } finally {
                withContext(NonCancellable + Dispatchers.IO) { savePicked(); MediaDates.save(app) }
            }
            val removed = withContext(Dispatchers.IO) {
                val (gone, inUse) = synchronized(picked) {
                    val gone = leftOver(picked.keys.toSet(), items.map { it.id }, replacing, failed.get()).mapNotNull { picked.remove(it) }
                    gone to picked.values.mapTo(HashSet()) { fileOf(it) }
                }
                // A file an earlier pick left can have been deleted on the TV and its name reused by this pick:
                // only files no remaining pick uses are deleted.
                val n = gone.map { fileOf(it) }.count { it !in inUse && it.delete() }
                if (gone.isNotEmpty()) savePicked()
                countOnTv()
                n
            }
            if (items.any { !it.video } && _onTv.value.photos > 0) Appearance.setSlideshowFolder(app, folder.path)
            _state.value = State(
                Step.DONE, done = todo.size, total = todo.size, added = added.get(), videos = videos.get(),
                kept = kept, removed = removed, failed = failed.get(),
            )
        }
    }

    /** Takes every downloaded photo and video off the TV (Google Photos itself isn't touched). */
    fun removeAll() {
        scope.launch {
            files.withLock {
                withContext(Dispatchers.IO) {
                    loadPicked()
                    for (dir in listOf(folder, videoFolder)) dir.listFiles()?.forEach { if (it.isFile) it.delete() }
                    synchronized(picked) { picked.clear() }
                    savePicked()
                    countOnTv()
                }
                // Nothing left to show there: the slideshow goes back to the usual photo folders.
                if (Appearance.slideshowFolder == folder.path) Appearance.setSlideshowFolder(app, null)
            }
        }
    }

    fun setReplace(on: Boolean) { _replace.value = on }

    /** Reads which files came from picks, before anything else is saved in their folders (IO thread). */
    fun ready() = loadPicked()

    /** [file] is no longer a pick's: deleted, or something else took its name (IO thread). */
    fun forget(file: File) {
        loadPicked()
        val dir = file.canonicalFile.parentFile
        val inPhotos = dir == folder.canonicalFile
        val inVideos = dir == videoFolder.canonicalFile
        val gone = synchronized(picked) { picked.entries.removeAll { (_, k) -> k.name == file.name && (if (k.video) inVideos else inPhotos) } }
        if (gone) savePicked()
        countOnTv()
    }

    private fun fileOf(k: Kept) = File(if (k.video) videoFolder else folder, k.name)

    private fun pickedFile() = File(app.filesDir, "google_photos_picked.json")

    /** Reads which files came from picks, once (IO thread). */
    private fun loadPicked() {
        synchronized(picked) {
            if (pickedLoaded) return
            pickedLoaded = true
            val f = pickedFile()
            if (f.exists()) {
                runCatching {
                    val o = JSONObject(f.readText())
                    for (id in o.keys()) o.getJSONObject(id).let { picked[id] = Kept(it.getString("f"), it.optBoolean("v")) }
                }
            } else {
                // Before this list was kept, only picks wrote into these two folders: what's there now is what the
                // next pick replaces.
                for ((dir, video) in listOf(folder to false, videoFolder to true)) dir.listFiles()?.forEach {
                    if (it.isFile && !it.name.startsWith(".")) picked["earlier:${if (video) "v" else "p"}:${it.name}"] = Kept(it.name, video)
                }
                savePicked()
            }
        }
    }

    private fun savePicked() {
        synchronized(picked) {
            runCatching {
                val o = JSONObject()
                for ((id, k) in picked) o.put(id, JSONObject().put("f", k.name).put("v", k.video))
                val f = pickedFile()
                val tmp = File(f.parentFile, f.name + ".tmp")
                tmp.writeText(o.toString())
                tmp.renameTo(f)
            }
        }
    }

    /** Counts what's in the two folders, for the storage line (IO thread). */
    private fun countOnTv() {
        var photos = 0
        var videos = 0
        var bytes = 0L
        for ((dir, video) in listOf(folder to false, videoFolder to true)) dir.listFiles()?.forEach {
            if (it.isFile && !it.name.startsWith(".")) {
                if (video) videos++ else photos++
                bytes += it.length()
            }
        }
        _onTv.value = OnTv(photos, videos, bytes)
    }

    /** Leaves the window: stops waiting for a pick and tells Google to drop the session. */
    fun cancel() {
        job?.cancel()
        scope.launch { endSession() }
    }

    fun signOut(done: () -> Unit = {}) {
        scope.launch {
            GoogleAuth.signOut(app, GoogleAuth.Grant.PHOTOS)
            _state.value = State()
            done()
        }
    }

    private suspend fun endSession() {
        val id = sessionId ?: return
        sessionId = null
        withContext(NonCancellable + Dispatchers.IO) { runCatching { call("DELETE", "sessions/$id", null) } }
    }

    /** Durations come as "5s" or "1800s". */
    private fun seconds(s: String?, default: Double) = s?.removeSuffix("s")?.toDoubleOrNull() ?: default

    private fun cleanName(name: String) = name.substringAfterLast('/').replace(Regex("[\\\\:*?\"<>|]"), "_").take(120).ifBlank { "video.mp4" }

    /** The download is always a JPEG at the requested size, whatever the original was (HEIC, PNG…). */
    private fun jpegName(name: String): String {
        val base = name.substringAfterLast('/').substringBeforeLast('.').ifBlank { "photo" }.take(80)
        return "$base.jpg"
    }

    private fun open(url: String, method: String): HttpURLConnection {
        val token = GoogleAuth.blockingToken(app, GoogleAuth.Grant.PHOTOS)
            ?: throw IOException("Google Photos sign-in expired. Connect again.")
        return (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15_000
            readTimeout = 60_000
            setRequestProperty("Authorization", "Bearer $token")
        }
    }

    private fun call(method: String, path: String, body: String?): JSONObject {
        val conn = open(API + path, method)
        try {
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(body.toByteArray()) }
            }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code in 200..299) return if (text.isBlank()) JSONObject() else JSONObject(text)
            val msg = runCatching { JSONObject(text).optJSONObject("error")?.optString("message") }.getOrNull().orEmpty()
            throw IOException(
                when (code) {
                    401 -> "Google Photos sign-in expired. Connect again."
                    403 -> "Google refused ($msg). Enable the Google Photos Picker API in Google Cloud."
                    else -> "Google Photos error $code${if (msg.isNotBlank()) ": $msg" else ""}"
                },
            )
        } finally {
            conn.disconnect()
        }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
}

/**
 * Photos are stored at the size the TV draws them: the slideshow decodes at 1920×1080, so a 4K copy only pays off
 * on the few boxes whose own screen is drawn in 4K. A 1080p copy takes about a quarter of the space.
 */
internal fun photoSize(widthPx: Int, heightPx: Int): String =
    if (maxOf(widthPx, heightPx) > 1920) "=w3840-h2160" else "=w1920-h1080"

/**
 * The earlier picks a finished pick takes off the TV: those not in it, when it replaces them. None when adding, when
 * nothing was picked, or when some picked items didn't arrive (then the old ones stay rather than leave gaps).
 */
internal fun leftOver(earlier: Set<String>, pick: List<String>, replacing: Boolean, failed: Int): Set<String> =
    if (!replacing || failed > 0 || pick.isEmpty()) emptySet() else earlier - pick.toSet()
