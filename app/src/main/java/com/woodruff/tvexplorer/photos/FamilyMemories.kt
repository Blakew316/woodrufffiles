package com.woodruff.tvexplorer.photos

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import com.woodruff.tvexplorer.data.FileKind
import com.woodruff.tvexplorer.data.FileOperations
import com.woodruff.tvexplorer.data.FileTypes
import com.woodruff.tvexplorer.screensaver.Appearance
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.IOException
import java.util.concurrent.Semaphore
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Family Memories as the phone sees it: the photos and videos in the two folders the TV's Family Memories shows
 * (Google Photos picks download there too), newest first, small pictures of them for the phone's grid, and what
 * the phone adds and deletes. A photo the phone adds keeps when it was taken (the phone reads that before it
 * shrinks the photo to the TV's size, which drops it), and a pick that replaces earlier picks never takes one
 * the phone added.
 */
object FamilyMemories {
    data class Item(val file: File, val video: Boolean, val taken: Long)

    /** The two sizes the phone asks for: its grid's tiles, and the photo viewer's full screen. */
    enum class Size { GRID, VIEW }

    private val _changes = MutableStateFlow(0)
    /** Goes up each time the phone adds or deletes something, so Family Memories on the TV lists it again. */
    val changes: StateFlow<Int> = _changes

    val photos: File get() = GooglePhotos.folder
    val videos: File get() = GooglePhotos.videoFolder

    private const val THUMB_DIR = "memory_thumbs"
    private const val MAX_BYTES = 60L * 1024 * 1024
    private const val KEEP_BYTES = 45L * 1024 * 1024
    // Grid tiles: the shorter side at least this (a third of an iPhone's width at 3x is ~400 px).
    private const val GRID_PX = 420
    // The viewer: the longer side at most this (an iPhone screen's height in pixels is ~2,600).
    private const val VIEW_PX = 2048

    /** Two pictures made at a time: each decodes a photo, and a video may be playing. */
    private val gate = Semaphore(2)
    /** Bytes in the thumbnail folder: counted on the first new one, then kept up to date. */
    private var total = -1L

    /** What's in Family Memories, newest first (when each was taken, else when it was saved). Reads files: IO thread. */
    fun list(c: Context): List<Item> {
        val files = listOf(photos, videos).flatMap { dir ->
            dir.listFiles().orEmpty().filter { it.isFile && !it.name.startsWith(".") && it.length() > 0 && kindOf(it.name) != null }
        }
        val dates = MediaDates.of(c, files)
        return files.map { Item(it, kindOf(it.name) == FileKind.VIDEO, dates[it.absolutePath] ?: it.lastModified()) }
            .sortedWith(compareByDescending<Item> { it.taken }.thenBy { it.file.name })
    }

    /** One file as an [Item] (IO thread). */
    fun item(c: Context, f: File): Item =
        Item(f, kindOf(f.name) == FileKind.VIDEO, MediaDates.of(c, listOf(f))[f.absolutePath] ?: f.lastModified())

    /** [path] when it's a photo or video in Family Memories; anything else is refused. */
    fun find(path: String): File {
        val f = File(path).canonicalFile
        val dir = f.parentFile
        require(dir != null && (dir == photos.canonicalFile || dir == videos.canonicalFile) && f.isFile && kindOf(f.name) != null) {
            "That isn't in Family Memories"
        }
        return f
    }

    /**
     * Where a photo or video called [name] goes (photos and videos each in their own folder), under a name nothing
     * else has; the empty file keeps that name taken until the upload replaces it.
     */
    fun target(name: String): File {
        val clean = name.substringAfterLast('/').substringAfterLast('\\').trim()
        FileOperations.validateName(clean)
        require(!clean.startsWith(".")) { "Invalid name" }
        val kind = kindOf(clean) ?: throw IllegalArgumentException("Only photos and videos go in Family Memories")
        val dir = (if (kind == FileKind.VIDEO) videos else photos).apply { mkdirs() }
        // Picks write their list of files before any upload lands, so a pick never mistakes an upload for one of its own.
        GooglePhotos.ready()
        return synchronized(this) {
            FileOperations.uniqueTarget(dir, clean).also { if (!it.createNewFile()) throw IOException("Couldn't save $clean") }
        }
    }

    /** [file] came in from the phone, taken at [taken] (ms) when the phone could tell. */
    fun added(c: Context, file: File, taken: Long?) {
        // Whatever had this name before (deleted since) leaves nothing behind: not its date, and not a pick's claim
        // on the name, which would take this file with it when a pick replaces it.
        MediaDates.forget(c, file)
        if (taken != null && taken > 0) MediaDates.record(c, file, taken)
        MediaDates.save(c)
        GooglePhotos.forget(file)
        _changes.update { it + 1 }
    }

    /** [file] was deleted from the phone: picks, dates, the TV's background and its Family Memories let go of it. */
    fun deleted(c: Context, file: File) {
        GooglePhotos.forget(file)
        MediaDates.forget(c, file)
        MediaDates.save(c)
        if (Appearance.background == file.path) runCatching { Appearance.setBackground(c, null) }
        _changes.update { it + 1 }
    }

    /** The size photos are kept at: what the TV draws, as Google Photos picks are. */
    fun fit(c: Context): Pair<Int, Int> {
        val m = c.resources.displayMetrics
        return if (max(m.widthPixels, m.heightPixels) > 1920) 3840 to 2160 else 1920 to 1080
    }

    /**
     * A JPEG of [f] at [size], made once and kept (by path, length and time; the longest unseen go first past
     * [MAX_BYTES]); a photo already small enough for the viewer is itself. Null when no picture can be made.
     */
    fun picture(c: Context, f: File, size: Size): File? {
        if (kindOf(f.name) == FileKind.VIDEO) return runBlocking { VideoThumbs.thumbnail(c, f) }
        val px = if (size == Size.GRID) GRID_PX else VIEW_PX
        val dir = File(c.cacheDir, THUMB_DIR)
        val out = File(dir, VideoThumbs.fileName(f.absolutePath, f.length(), f.lastModified(), px, if (size == Size.GRID) 0 else 1))
        if (out.length() > 0) return out.also(::touch)
        gate.acquire()
        try {
            if (out.length() > 0) return out
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(f.path, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            val turn = orientation(f)
            val upright = if (turn.swapsSides) bounds.outHeight to bounds.outWidth else bounds.outWidth to bounds.outHeight
            val (w, h) = scaled(upright.first, upright.second, size, px)
            // Already right for the viewer as it is: the photo itself (no second JPEG pass).
            if (size == Size.VIEW && w == upright.first && turn.isNone && bounds.outMimeType == "image/jpeg") return f
            // A grid tile decodes at least its own size (sharp); the viewer a little under it if that halves the memory.
            val want = if (size == Size.GRID) min(w, h) else (min(w, h) * 0.7).toInt()
            val sample = sampleFor(min(bounds.outWidth, bounds.outHeight), want)
            val decoded = BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
            val sideW = if (turn.swapsSides) decoded.height else decoded.width
            val m = turn.matrix(decoded.width, decoded.height)
            val k = min(1f, w.toFloat() / sideW)
            m.postScale(k, k)
            val picture = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, m, true)
            if (picture !== decoded) decoded.recycle()
            dir.mkdirs()
            val tmp = File.createTempFile(out.name, ".tmp", dir)
            try {
                val saved = tmp.outputStream().use { picture.compress(Bitmap.CompressFormat.JPEG, if (size == Size.GRID) 80 else 88, it) } && tmp.renameTo(out)
                if (!saved) return null
            } finally {
                tmp.delete()
                picture.recycle()
            }
            counted(dir, out.length())
            return out
        } catch (e: OutOfMemoryError) {
            return null
        } finally {
            gate.release()
        }
    }

    // ---- Pure parts (unit tested) ---------------------------------------------------------------

    /** Photo or video, by name; null for anything else. */
    internal fun kindOf(name: String): FileKind? = FileTypes.kindOf(name).takeIf { it == FileKind.IMAGE || it == FileKind.VIDEO }

    /**
     * An upright [w]×[h] photo's size as a [size] picture: a grid tile's shorter side [px] (never larger than
     * the photo), the viewer's longer side at most [px].
     */
    internal fun scaled(w: Int, h: Int, size: Size, px: Int): Pair<Int, Int> {
        val k = if (size == Size.GRID) min(1.0, px.toDouble() / min(w, h)) else min(1.0, px.toDouble() / max(w, h))
        return max(1, (w * k).roundToInt()) to max(1, (h * k).roundToInt())
    }

    /** The largest power-of-two shrink that still decodes the shorter side at least [want] (from [have]). */
    internal fun sampleFor(have: Int, want: Int): Int {
        var s = 1
        while (have / (s * 2) >= want) s *= 2
        return s
    }

    /** A photo's EXIF orientation: how to turn and flip it upright. */
    internal enum class Turn(val degrees: Float, val flip: Boolean) {
        // In EXIF's order: 5 (transpose) is flipped then turned 270°, 7 (transverse) flipped then turned 90°.
        NONE(0f, false), FLIP(0f, true), R180(180f, false), FLIP180(180f, true),
        FLIP270(270f, true), R90(90f, false), FLIP90(90f, true), R270(270f, false);

        val isNone get() = this == NONE
        val swapsSides get() = degrees == 90f || degrees == 270f

        fun matrix(w: Int, h: Int): Matrix = Matrix().apply {
            if (flip) postScale(-1f, 1f, w / 2f, h / 2f)
            if (degrees != 0f) {
                postRotate(degrees)
                // Back into view: rotating about the corner moves the picture off its own top-left.
                when (degrees) {
                    90f -> postTranslate(h.toFloat(), 0f)
                    180f -> postTranslate(w.toFloat(), h.toFloat())
                    270f -> postTranslate(0f, w.toFloat())
                }
            }
        }

        companion object {
            /** EXIF orientation 1–8 (anything else: as it is). */
            fun of(exif: Int): Turn = entries.getOrNull(exif - 1) ?: NONE
        }
    }

    private fun orientation(f: File): Turn = runCatching {
        Turn.of(ExifInterface(f.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL))
    }.getOrDefault(Turn.NONE)

    private fun touch(f: File) {
        val now = System.currentTimeMillis()
        if (now - f.lastModified() > 24 * 3_600_000L) f.setLastModified(now)
    }

    /** Counts a new picture in; past [MAX_BYTES] the longest unseen go, down to [KEEP_BYTES]. */
    @Synchronized
    private fun counted(dir: File, bytes: Long) {
        total = if (total < 0) dir.listFiles().orEmpty().sumOf { it.length() } else total + bytes
        if (total <= MAX_BYTES) return
        val entries = dir.listFiles().orEmpty().map { VideoThumbs.Entry(it.name, it.length(), it.lastModified()) }
        val gone = VideoThumbs.toTrim(entries, MAX_BYTES, KEEP_BYTES).toSet()
        gone.forEach { File(dir, it).delete() }
        total = entries.filter { it.name !in gone }.sumOf { it.bytes }
    }
}
