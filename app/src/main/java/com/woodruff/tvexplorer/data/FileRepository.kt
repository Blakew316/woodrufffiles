package com.woodruff.tvexplorer.data

import android.content.Context
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.coroutineContext

/** Read-only queries: directory listings, library categories and search. */
class FileRepository(private val context: Context) {

    suspend fun list(dir: File, showHidden: Boolean, sort: SortOrder): List<FileItem> =
        withContext(Dispatchers.IO) {
            val children = dir.listFiles() ?: throw SecurityException("Can't read ${dir.absolutePath}")
            sorted(
                children.asSequence()
                    .filter { showHidden || !it.name.startsWith(".") }
                    .map(FileItem::from)
                    .toList(),
                sort,
            )
        }

    /**
     * All files of a [kind], newest first. Uses MediaStore for speed; it indexes every mounted
     * volume including USB drives, so a manual walk is only needed for what MediaStore misses.
     */
    suspend fun category(kind: FileKind, showHidden: Boolean, limit: Int = 2000): List<FileItem> =
        withContext(Dispatchers.IO) {
            val exts = FileTypes.extensionsFor(kind)
            if (exts.isEmpty()) return@withContext emptyList()
            val uri = MediaStore.Files.getContentUri("external")
            val selection = exts.joinToString(" OR ") { "${MediaStore.Files.FileColumns.DISPLAY_NAME} LIKE ?" }
            val args = exts.map { "%.$it" }.toTypedArray()
            val out = ArrayList<FileItem>()
            try {
                context.contentResolver.query(
                    uri,
                    @Suppress("DEPRECATION") arrayOf(MediaStore.Files.FileColumns.DATA),
                    selection, args,
                    "${MediaStore.Files.FileColumns.DATE_MODIFIED} DESC",
                )?.use { c ->
                    while (c.moveToNext() && out.size < limit) {
                        ensureActive()
                        val path = c.getString(0) ?: continue
                        val f = File(path)
                        if (!f.isFile) continue
                        if (!showHidden && path.contains("/.")) continue
                        out += FileItem.from(f)
                    }
                }
            } catch (e: SecurityException) {
                // MediaStore unavailable without permission; caller shows the permission screen.
            }
            out
        }

    /** Breadth-first name search under [root]; stops at [limit] hits. */
    suspend fun search(root: File, query: String, showHidden: Boolean, limit: Int = 500): List<FileItem> =
        withContext(Dispatchers.IO) {
            val needle = query.trim().lowercase()
            if (needle.isEmpty()) return@withContext emptyList()
            val out = ArrayList<FileItem>()
            val queue = ArrayDeque<File>().apply { add(root) }
            while (queue.isNotEmpty() && out.size < limit) {
                coroutineContext.ensureActive()
                val dir = queue.removeFirst()
                val kids = dir.listFiles() ?: continue
                for (f in kids) {
                    if (!showHidden && f.name.startsWith(".")) continue
                    if (f.name.lowercase().contains(needle)) out += FileItem.from(f)
                    // Android/data and obb are huge and almost never what a TV user is looking for.
                    if (f.isDirectory && f.name != "Android") queue.add(f)
                }
            }
            out
        }

    /** Recursive size of a folder, for the Details dialog. */
    suspend fun folderSize(dir: File): Pair<Long, Int> = withContext(Dispatchers.IO) {
        var bytes = 0L
        var files = 0
        dir.walkTopDown().forEach {
            coroutineContext.ensureActive()
            if (it.isFile) { bytes += it.length(); files++ }
        }
        bytes to files
    }

    companion object {
        fun sorted(items: List<FileItem>, sort: SortOrder): List<FileItem> {
            val byField: Comparator<FileItem> = when (sort.field) {
                SortField.NAME -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.name }
                SortField.DATE -> compareBy { it.lastModified }
                SortField.SIZE -> compareBy { it.size }
                SortField.TYPE -> compareBy<FileItem> { FileTypes.extension(it.name) }
                    .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }
            }
            val ordered = if (sort.ascending) byField else byField.reversed()
            // Folders always come first regardless of direction.
            return items.sortedWith(compareBy<FileItem> { !it.isDirectory }.then(ordered))
        }
    }
}
