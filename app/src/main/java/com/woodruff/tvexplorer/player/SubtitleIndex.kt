package com.woodruff.tvexplorer.player

import android.content.Context
import android.net.Uri
import android.util.Log
import com.woodruff.tvexplorer.cloud.DriveApi
import com.woodruff.tvexplorer.cloud.DriveFile
import com.woodruff.tvexplorer.data.FileKind
import com.woodruff.tvexplorer.data.FileTypes
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * The subtitle files beside the family's Google Drive videos ([Sidecar]), learned from the folder
 * listings Woodruff reads anyway (a library, a show's seasons, a film's own folder, the player's own
 * look at a season), so a film starts with its subtitles attached, and no extra request to Drive.
 * Kept on the TV too, for the videos that have some, so Continue watching has them right after a
 * restart. A video whose folder this TV has never listed starts without them; they're there from the
 * next time it's listed (its title page opened, its season browsed).
 */
internal object SubtitleIndex {
    private const val FILE = "subtitles.json"
    private const val TAG = "SubtitleIndex"

    /** Videos with subtitle files kept, at most: far more than a family's films that come with them. */
    private const val MAX = 5_000

    /** Each video's subtitle files by its media key ("drive:<id>"), the most recently learned last. Guarded by this. */
    private val known = object : LinkedHashMap<String, List<Sidecar>>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<Sidecar>>?): Boolean = size > MAX
    }

    /** Videos found with none before the saved copy was read: what's saved for them is out of date. Guarded by this. */
    private val noneBeforeLoad = HashSet<String>()

    /** Learned since the last save. Guarded by this. */
    private var changed = false
    private var saveQueued = false

    @Volatile private var app: Context? = null

    /** What was saved has been read (or thrown away, signing out): what it knows now is all it knows. */
    @Volatile var loaded = false
        private set

    private val disk = Any()

    /**
     * Learning and saving happen here, one after another, off whatever thread the listing arrived on.
     * Replaceable in tests.
     */
    internal var worker: Executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "subtitle-index").apply { isDaemon = true; priority = Thread.NORM_PRIORITY - 1 }
    }

    /** Lets it read and save its copy on the TV: any context will do (the application's is kept). */
    fun attach(c: Context) {
        if (app == null) app = c.applicationContext
        if (synchronized(this) { changed }) saveSoon()
    }

    /** [key]'s subtitle files, as last learned; null when it has none, or none are known. In memory: any thread. */
    fun known(key: String): List<Sidecar>? = synchronized(this) { known[key] }

    /**
     * Learns one Drive folder's [files] (its listing), in the background: any thread may call it (a
     * title page's listing arrives on the main thread). [c] lets it save.
     */
    fun learn(files: List<DriveFile>, c: Context? = null) {
        c?.let(::attach)
        if (files.none(::isVideo)) return
        worker.execute { runCatching { learnNow(files) }.onFailure { Log.w(TAG, "Couldn't learn a folder's subtitles", it) } }
    }

    /** [learn], now, on the calling (background) thread: for the player, which uses what it learns at once. */
    fun learnNow(files: List<DriveFile>) {
        val found = inFolder(files)
        if (found.isEmpty()) return
        val save = synchronized(this) {
            for ((key, subs) in found) {
                if (subs.isEmpty()) {
                    if (known.remove(key) != null) changed = true
                    if (!loaded) noneBeforeLoad += key
                } else if (known.put(key, subs) != subs) changed = true
            }
            changed
        }
        if (save) saveSoon()
    }

    /**
     * Each video's subtitle files among one folder's [files] (an empty list for a video with none),
     * by its media key. A film's own folder is one video and no other folders: every subtitle in it
     * is that film's ([Sidecars.pair]). Visible for tests.
     */
    fun inFolder(files: List<DriveFile>): Map<String, List<Sidecar>> {
        val videos = files.filter(::isVideo)
        if (videos.isEmpty()) return emptyMap()
        val subtitles = files.filter { !it.isFolder && !it.name.startsWith("._") && FileTypes.subtitleMime(it.name) != null }
        val ownFolder = videos.size == 1 && files.none { it.isFolder && !Sidecars.isSubsFolder(it.name) }
        val pairs = Sidecars.pair(videos, { it.name }, subtitles, { it.name }, ownFolder)
        return videos.withIndex().associate { (i, video) ->
            MediaRef.Drive(video.id).key to pairs[i].map { m ->
                Sidecar(DriveApi.mediaUrl(m.file.id), FileTypes.subtitleMime(m.file.name)!!, m.name.language, m.name.forced, m.name.sdh)
            }
        }
    }

    /** A video in a listing: never a subtitle file that Drive happens to call a video (VobSub's .sub), nor a Mac's "._" shadow file. */
    private fun isVideo(f: DriveFile): Boolean = !f.isFolder && f.kind == FileKind.VIDEO && !FileTypes.isSidecar(f.name) && !f.name.startsWith("._")

    /**
     * Reads what was saved, once per run (the disk: off the main thread). What was learned since
     * Woodruff started is newer, so it wins.
     */
    fun load(c: Context) {
        attach(c)
        readSaved(c.applicationContext)
        if (synchronized(this) { changed }) saveSoon()
    }

    private fun readSaved(c: Context) {
        if (loaded) return
        synchronized(disk) {
            if (loaded) return
            val saved = runCatching { read(File(c.cacheDir, FILE)) }.getOrDefault(emptyMap())
            synchronized(this) {
                for ((key, subs) in saved) if (key !in known && key !in noneBeforeLoad) known[key] = subs
                noneBeforeLoad.clear()
                loaded = true
            }
        }
    }

    /** Signed out of Drive: another account's files mean nothing to the next one. */
    fun forgetDrive() {
        synchronized(this) {
            known.clear()
            noneBeforeLoad.clear()
            loaded = true
            changed = true
        }
        saveSoon()
    }

    /** Saves what it knows, in a moment (once, however many folders are learned meanwhile). */
    private fun saveSoon() {
        val c = app ?: return
        synchronized(this) {
            if (saveQueued) return
            saveQueued = true
        }
        worker.execute {
            synchronized(this) { saveQueued = false }
            runCatching {
                // Never over the saved copy with only part of it.
                readSaved(c)
                val snapshot = synchronized(this) {
                    changed = false
                    known.filterValues { it.isNotEmpty() }
                }
                synchronized(disk) { write(File(c.cacheDir, FILE), snapshot) }
            }.onFailure { Log.w(TAG, "Couldn't save the subtitles found", it) }
        }
    }

    /** What [write] saved. Visible for tests. */
    internal fun read(file: File): Map<String, List<Sidecar>> {
        if (!file.exists()) return emptyMap()
        val o = JSONObject(file.readText())
        val out = LinkedHashMap<String, List<Sidecar>>()
        for (key in o.keys()) {
            val arr = o.optJSONArray(key) ?: continue
            out[key] = (0 until arr.length()).mapNotNull { i ->
                val s = arr.optJSONObject(i) ?: return@mapNotNull null
                val uri = s.optString("u").ifEmpty { null } ?: return@mapNotNull null
                val mime = s.optString("m").ifEmpty { null } ?: return@mapNotNull null
                Sidecar(uri, mime, s.optString("l").ifEmpty { null }, s.optBoolean("f"), s.optBoolean("s"))
            }
        }
        return out
    }

    /** Saves [all] to [file], whole or not at all. Visible for tests. */
    internal fun write(file: File, all: Map<String, List<Sidecar>>) {
        val o = JSONObject()
        for ((key, subs) in all) {
            o.put(key, JSONArray().apply {
                subs.forEach { s ->
                    put(JSONObject().put("u", s.uri).put("m", s.mime).apply {
                        s.language?.let { put("l", it) }
                        if (s.forced) put("f", true)
                        if (s.sdh) put("s", true)
                    })
                }
            })
        }
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        tmp.writeText(o.toString())
        if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
    }

    /** For tests: back to a fresh start. */
    internal fun resetForTest() {
        synchronized(this) {
            known.clear()
            noneBeforeLoad.clear()
            changed = false
            saveQueued = false
            loaded = false
        }
        app = null
    }
}

/**
 * The subtitle files beside videos on a USB drive or the TV's own storage: the same names as on Drive
 * ([Sidecars]), and in a "Subs" folder beside them, as many downloads come (Subs/2_English.srt, or
 * Subs/<the video's name>/2_English.srt). Read from the drive as a film starts (off the main thread);
 * nothing is kept, since drives come and go.
 */
internal object LocalSidecars {
    /** Each video's subtitle files in folder [dir], by its path; videos with none are left out. */
    fun inFolder(dir: File): Map<String, List<Sidecar>> {
        val entries = dir.listFiles()?.filter { !it.name.startsWith(".") } ?: return emptyMap()
        val files = entries.filter { it.isFile }.sortedBy { it.name.lowercase() }
        val folders = entries.filter { it.isDirectory }
        val videos = files.filter { FileTypes.kindOf(it.name) == FileKind.VIDEO && !FileTypes.isSidecar(it.name) }
        if (videos.isEmpty()) return emptyMap()
        val ownFolder = videos.size == 1 && folders.none { !Sidecars.isSubsFolder(it.name) }
        val found = List(videos.size) { ArrayList<Sidecar>() }
        // Beside the videos, then in a Subs folder (by name, or all of it in a film's own folder).
        Sidecars.pair(videos, { it.name }, subtitlesIn(files), { it.name }, ownFolder).forEachIndexed { i, matches -> matches.forEach { found[i] += sidecar(it) } }
        val subs = folders.firstOrNull { Sidecars.isSubsFolder(it.name) }
        val inSubs = subs?.listFiles()?.filter { !it.name.startsWith(".") }.orEmpty()
        Sidecars.pair(videos, { it.name }, subtitlesIn(inSubs.filter { it.isFile }.sortedBy { it.name.lowercase() }), { it.name }, ownFolder)
            .forEachIndexed { i, matches -> matches.forEach { found[i] += sidecar(it) } }
        // A folder in Subs named after a video holds that video's.
        for (folder in inSubs.filter { it.isDirectory }) {
            val v = videos.indexOfFirst { Sidecars.namedAfter(folder.name, it.name) }.takeIf { it >= 0 } ?: continue
            val inside = folder.listFiles()?.filter { it.isFile && !it.name.startsWith(".") }?.sortedBy { it.name.lowercase() }.orEmpty()
            for (f in subtitlesIn(inside)) found[v] += sidecar(SidecarMatch(f, Sidecars.inVideosFolder(videos[v].name, f.name)))
        }
        return videos.indices.filter { found[it].isNotEmpty() }.associate { videos[it].absolutePath to found[it].toList() }
    }

    private fun subtitlesIn(files: List<File>) = files.filter { FileTypes.subtitleMime(it.name) != null }

    private fun sidecar(m: SidecarMatch<File>) =
        Sidecar(Uri.fromFile(m.file).toString(), FileTypes.subtitleMime(m.file.name)!!, m.name.language, m.name.forced, m.name.sdh)
}
