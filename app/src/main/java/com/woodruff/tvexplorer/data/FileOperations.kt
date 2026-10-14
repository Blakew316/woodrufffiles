package com.woodruff.tvexplorer.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.coroutines.coroutineContext

/** Progress for long-running operations; [totalBytes] is 0 when unknown. */
data class OpProgress(val label: String, val doneBytes: Long, val totalBytes: Long, val currentName: String)

/**
 * Mutating file operations. Everything runs on [Dispatchers.IO] and checks for cancellation
 * between buffers, so cancelling the calling coroutine stops a multi-gigabyte copy promptly.
 * Name clashes are never overwritten: the new file gets a " (1)" style suffix instead.
 */
object FileOperations {
    private const val BUFFER = 256 * 1024

    suspend fun copy(
        sources: List<File>,
        destDir: File,
        move: Boolean,
        onProgress: (OpProgress) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val label = if (move) "Moving" else "Copying"
        for (src in sources) {
            if (destDir.canonicalPath.startsWith(src.canonicalPath + File.separator) ||
                destDir.canonicalPath == src.canonicalPath
            ) throw IOException("Can't ${label.lowercase()} a folder into itself")
        }
        // Same-volume moves are an instant rename; only fall back to copy+delete across volumes.
        val remaining = if (move) sources.filterNot { src ->
            src.parentFile?.canonicalPath == destDir.canonicalPath ||
                src.renameTo(uniqueTarget(destDir, src.name))
        } else sources
        if (remaining.isEmpty()) return@withContext

        val total = remaining.sumOf(::sizeOf)
        var done = 0L
        val buf = ByteArray(BUFFER)

        suspend fun copyOne(src: File, dest: File) {
            coroutineContext.ensureActive()
            if (src.isDirectory) {
                if (!dest.mkdirs() && !dest.isDirectory) throw IOException("Can't create ${dest.name}")
                src.listFiles()?.forEach { copyOne(it, File(dest, it.name)) }
                return
            }
            FileInputStream(src).use { input ->
                FileOutputStream(dest).use { out ->
                    while (true) {
                        coroutineContext.ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        onProgress(OpProgress(label, done, total, src.name))
                    }
                    out.fd.sync()
                }
            }
            dest.setLastModified(src.lastModified())
        }

        for (src in remaining) {
            val dest = uniqueTarget(destDir, src.name)
            try {
                copyOne(src, dest)
            } catch (t: Throwable) {
                dest.deleteRecursively() // never leave a half-written copy behind
                throw t
            }
            if (move && !src.deleteRecursively()) throw IOException("Copied but couldn't remove ${src.name}")
        }
    }

    suspend fun delete(targets: List<File>, onProgress: (OpProgress) -> Unit) = withContext(Dispatchers.IO) {
        targets.forEachIndexed { i, f ->
            ensureActive()
            onProgress(OpProgress("Deleting", i.toLong(), targets.size.toLong(), f.name))
            if (!f.deleteRecursively()) throw IOException("Couldn't delete ${f.name}")
        }
    }

    suspend fun rename(file: File, newName: String): File = withContext(Dispatchers.IO) {
        validateName(newName)
        val target = File(file.parentFile, newName)
        if (target.exists()) throw IOException("\"$newName\" already exists")
        if (!file.renameTo(target)) throw IOException("Rename failed")
        target
    }

    suspend fun createFolder(parent: File, name: String): File = withContext(Dispatchers.IO) {
        validateName(name)
        val dir = File(parent, name)
        if (dir.exists()) throw IOException("\"$name\" already exists")
        if (!dir.mkdirs()) throw IOException("Couldn't create folder")
        dir
    }

    suspend fun zip(sources: List<File>, zipFile: File, onProgress: (OpProgress) -> Unit) =
        withContext(Dispatchers.IO) {
            val total = sources.sumOf(::sizeOf)
            var done = 0L
            val buf = ByteArray(BUFFER)
            try {
                ZipOutputStream(FileOutputStream(zipFile).buffered()).use { zos ->
                    for (src in sources) {
                        val base = src.parentFile ?: continue
                        src.walkTopDown().forEach { f ->
                            ensureActive()
                            val rel = f.relativeTo(base).invariantSeparatorsPath
                            if (f.isDirectory) {
                                zos.putNextEntry(ZipEntry("$rel/")); zos.closeEntry()
                            } else {
                                zos.putNextEntry(ZipEntry(rel).apply { time = f.lastModified() })
                                FileInputStream(f).use { input ->
                                    while (true) {
                                        ensureActive()
                                        val n = input.read(buf)
                                        if (n < 0) break
                                        zos.write(buf, 0, n)
                                        done += n
                                        onProgress(OpProgress("Compressing", done, total, f.name))
                                    }
                                }
                                zos.closeEntry()
                            }
                        }
                    }
                }
            } catch (t: Throwable) {
                zipFile.delete()
                throw t
            }
        }

    /** Extracts into [destDir], rejecting entries that would escape it ("zip slip"). */
    suspend fun unzip(zipFile: File, destDir: File, onProgress: (OpProgress) -> Unit) =
        withContext(Dispatchers.IO) {
            val root = destDir.canonicalFile
            if (!root.mkdirs() && !root.isDirectory) throw IOException("Can't create ${root.name}")
            val total = zipFile.length()
            val counting = CountingInputStream(FileInputStream(zipFile).buffered())
            val buf = ByteArray(BUFFER)
            ZipInputStream(counting).use { zis ->
                while (true) {
                    ensureActive()
                    val entry = zis.nextEntry ?: break
                    val out = File(root, entry.name).canonicalFile
                    if (out != root && !out.path.startsWith(root.path + File.separator)) {
                        throw IOException("Blocked unsafe path in archive: ${entry.name}")
                    }
                    if (entry.isDirectory) { out.mkdirs(); continue }
                    out.parentFile?.mkdirs()
                    FileOutputStream(out).use { fos ->
                        while (true) {
                            ensureActive()
                            val n = zis.read(buf)
                            if (n < 0) break
                            fos.write(buf, 0, n)
                            onProgress(OpProgress("Extracting", counting.count, total, out.name))
                        }
                    }
                    if (entry.time > 0) out.setLastModified(entry.time)
                }
            }
        }

    /** "name.ext" -> "name (1).ext" until it doesn't collide. */
    fun uniqueTarget(dir: File, name: String): File {
        var f = File(dir, name)
        if (!f.exists()) return f
        val dot = name.lastIndexOf('.').takeIf { it > 0 } ?: name.length
        val stem = name.substring(0, dot)
        val ext = name.substring(dot)
        var i = 1
        while (f.exists()) f = File(dir, "$stem (${i++})$ext")
        return f
    }

    fun validateName(name: String) {
        if (name.isBlank() || name == "." || name == ".." || name.contains('/') || name.contains('\u0000')) {
            throw IOException("Invalid name")
        }
    }

    private fun sizeOf(f: File): Long = if (f.isDirectory) f.walkTopDown().filter { it.isFile }.sumOf { it.length() } else f.length()

    private class CountingInputStream(private val inner: java.io.InputStream) : java.io.FilterInputStream(inner) {
        var count = 0L; private set
        override fun read(): Int = super.read().also { if (it >= 0) count++ }
        override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) count += it }
    }
}
