package com.woodruff.tvexplorer.cloud

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Folder listings (a show's seasons, a season's episodes, a film's own folder), so title pages
 * open complete: [peek] gives the last listing at once, [list] fetches it fresh and keeps it. Kept
 * in memory and as a small file each on disk (its own folder, apart from [ListingCache]), so
 * they're there even right after a restart.
 */
object FolderCache {
    /** The most recently used listings; the oldest drop out past [MEMORY]. Guarded by itself. */
    private val memory = object : LinkedHashMap<String, List<DriveFile>>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<DriveFile>>?): Boolean = size > MEMORY
    }

    /** Bumped by [clear]: a listing still loading as the account signs out isn't kept. */
    @Volatile private var generation = 0
    private val disk = Any()
    private var writes = 0

    /**
     * The last listing of [folderId]; null when it hasn't been listed. Reads a small file when it
     * isn't in memory (after a restart), so call it off the main thread unless [inMemory] had it.
     */
    fun peek(c: Context, folderId: String): List<DriveFile>? {
        inMemory(folderId)?.let { return it }
        val gen = generation
        val saved = synchronized(disk) { read(c, folderId) } ?: return null
        synchronized(memory) { if (gen == generation && folderId !in memory) memory[folderId] = saved }
        return saved
    }

    /** The last listing of [folderId] if it's in memory: no disk read, so fine on the main thread. */
    fun inMemory(folderId: String): List<DriveFile>? = synchronized(memory) { memory[folderId] }

    /** Lists [folder] from Drive with a current token and keeps it; null when it can't be listed. */
    suspend fun list(c: Context, token: String?, folder: DriveFile): List<DriveFile>? = try {
        val gen = generation
        val t = GoogleAuth.token(c) ?: token
        if (t == null) null else DriveApi.list(t, DrivePlace.Folder(folder.id, folder.name)).also { keep(c, folder.id, it, gen) }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    /** Signed out: forget every listing, in memory and on disk. */
    fun clear(c: Context) {
        synchronized(memory) {
            generation++
            memory.clear()
        }
        synchronized(disk) { runCatching { dir(c).deleteRecursively() } }
    }

    private suspend fun keep(c: Context, folderId: String, files: List<DriveFile>, gen: Int) {
        val changed = synchronized(memory) {
            if (gen != generation) return
            (memory.put(folderId, files) != files)
        }
        // Unchanged since it was last saved: no need to write it again.
        if (changed) withContext(Dispatchers.IO) {
            synchronized(disk) { if (gen == generation) write(c, folderId, files) }
        }
    }

    private fun dir(c: Context) = File(c.applicationContext.cacheDir, "folders")

    private fun read(c: Context, folderId: String): List<DriveFile>? = runCatching {
        val f = File(dir(c), fileName(folderId)).takeIf { it.exists() } ?: return null
        fromJson(f.readText())
    }.getOrNull()

    private fun write(c: Context, folderId: String, files: List<DriveFile>) {
        runCatching {
            val d = dir(c).apply { mkdirs() }
            val f = File(d, fileName(folderId))
            val tmp = File(d, f.name + ".tmp")
            tmp.writeText(toJson(files))
            tmp.renameTo(f)
            // Now and then, only the most recently listed are kept: it stays small.
            if (writes++ % 25 == 0) prune(d)
        }
    }

    private fun prune(d: File) {
        val saved = d.listFiles { f -> f.name.endsWith(".json") } ?: return
        if (saved.size <= DISK) return
        saved.sortedBy { it.lastModified() }.take(saved.size - DISK).forEach { it.delete() }
    }

    /** A folder id as a file name (Drive's ids are letters, digits, "-" and "_" already). Visible for tests. */
    internal fun fileName(folderId: String): String = folderId.replace(UNSAFE, "_") + ".json"

    /** A listing as it's saved: what Drive lists of each file. Visible for tests. */
    internal fun toJson(files: List<DriveFile>): String = JSONArray().apply {
        files.forEach { d ->
            put(
                JSONObject().put("id", d.id).put("n", d.name).put("m", d.mimeType).put("s", d.size)
                    .put("t", d.modified).put("c", d.created).put("th", d.thumbnailLink ?: ""),
            )
        }
    }.toString()

    /** Reads what [toJson] saved. Visible for tests. */
    internal fun fromJson(s: String): List<DriveFile> {
        val arr = JSONArray(s)
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            DriveFile(
                id = o.getString("id"), name = o.optString("n"), mimeType = o.optString("m"),
                size = o.optLong("s"), modified = o.optLong("t"), created = o.optLong("c"),
                thumbnailLink = o.optString("th").ifBlank { null },
            )
        }
    }

    private val UNSAFE = Regex("[^A-Za-z0-9_-]")
    private const val MEMORY = 80
    private const val DISK = 300
}
