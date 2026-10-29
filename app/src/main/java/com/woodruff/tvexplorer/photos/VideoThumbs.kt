package com.woodruff.tvexplorer.photos

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.os.Build
import coil.ImageLoader
import coil.decode.DataSource
import coil.decode.ImageSource
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.fetch.SourceResult
import coil.request.ImageRequest
import coil.request.Options
import com.woodruff.tvexplorer.data.FileKind
import com.woodruff.tvexplorer.data.FileTypes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okio.Path.Companion.toOkioPath
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt

/**
 * Video tiles' pictures, kept on disk: a small JPEG of each video's first frame (the one its tile
 * always showed), made once and reused, also after a restart. Before, every visit opened each video
 * again and decoded a frame: hundreds of ms a tile for a 4K phone video, two at a time.
 *
 * Keyed by the video's path, size and modified time (an edited video gets a new one), kept in the
 * app's cache folder, and trimmed past [MAX_BYTES], the longest unseen first. Local files only:
 * nothing here goes on the network. When no frame can be read, the tile loads the video itself, as
 * before.
 */
object VideoThumbs {
    private const val DIR = "video_thumbs"
    private const val MAX_BYTES = 50L * 1024 * 1024
    private const val KEEP_BYTES = 40L * 1024 * 1024
    private const val DAY_MS = 24 * 3_600_000L
    // A bit more than the widest video tile (the file grid's, about 280 dp), so they stay sharp.
    private const val BOX_W_DP = 320
    private const val BOX_H_DP = 180

    // A few at a time: each one opens a video decoder, and a TV has only a few. Before Android 8.1
    // the frame only comes full size (33 MB for 4K), so there one at a time.
    private val gate by lazy { Semaphore(if (Build.VERSION.SDK_INT >= 27) 2 else 1) }
    /** Videos no thumbnail could be made for in this run: straight to the old way, not tried twice. */
    private val failed = ConcurrentHashMap.newKeySet<String>()
    /** Bytes in the folder: counted on the first new thumbnail, then kept up to date. */
    private var total = -1L

    /** A tile's image request for a local [video]: its saved thumbnail, else the video itself as before. */
    fun request(c: Context, video: File): ImageRequest =
        ImageRequest.Builder(c).data(video).fetcherFactory(Thumbs).build()

    /**
     * [video]'s saved thumbnail, made now when there's none yet; null when no frame can be read from
     * it. About the size of a tile, to be decoded by Coil like any small photo.
     */
    suspend fun thumbnail(c: Context, video: File): File? = withContext(Dispatchers.IO) {
        val length = video.length()
        if (length <= 0L) return@withContext null
        val density = c.resources.displayMetrics.density
        val boxW = (BOX_W_DP * density).roundToInt()
        val boxH = (BOX_H_DP * density).roundToInt()
        val name = fileName(video.absolutePath, length, video.lastModified(), boxW, boxH)
        if (name in failed) return@withContext null
        val dir = File(c.cacheDir, DIR)
        val thumb = File(dir, name)
        if (thumb.length() > 0) return@withContext thumb.also(::touch)
        gate.withPermit {
            // Another tile of the same video may have made it while this one waited.
            if (thumb.length() > 0) thumb else make(video, dir, thumb, boxW, boxH)
        }
    }

    /** Reads [video]'s frame and saves it as [thumb]; null (and not tried again this run) when it can't. */
    private fun make(video: File, dir: File, thumb: File, boxW: Int, boxH: Int): File? {
        val frame = runCatching { frame(video.absolutePath, boxW, boxH) }.getOrNull()
        if (frame == null) {
            failed += thumb.name
            return null
        }
        var tmp: File? = null
        try {
            dir.mkdirs()
            // Written aside and renamed into place, so a thumbnail is never read half-written.
            tmp = File.createTempFile(thumb.name, ".tmp", dir)
            val saved = tmp.outputStream().use { frame.compress(Bitmap.CompressFormat.JPEG, 85, it) } && tmp.renameTo(thumb)
            if (!saved) {
                failed += thumb.name
                return null
            }
            added(dir, thumb.length())
            return thumb
        } catch (e: Exception) {
            failed += thumb.name
            return null
        } finally {
            tmp?.delete()
            frame.recycle()
        }
    }

    /** The first frame, as Coil showed it (its nearest keyframe), scaled to just cover the tile box. */
    private fun frame(path: String, boxW: Int, boxH: Int): Bitmap? {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(path)
            fun meta(key: Int) = r.extractMetadata(key)?.toIntOrNull() ?: 0
            val size = frameSize(
                meta(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH), meta(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT),
                meta(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION), boxW, boxH,
            )
            if (size != null && Build.VERSION.SDK_INT >= 27) {
                // Scaled by the decoder: the full-size frame is never made.
                return r.getScaledFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, size.first, size.second)
            }
            // Older TVs only give the full-size frame (already upright): shrink it and free the big one.
            val full = r.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: return null
            val (w, h) = frameSize(full.width, full.height, 0, boxW, boxH) ?: return full
            if (w == full.width && h == full.height) return full
            return Bitmap.createScaledBitmap(full, w, h, true).also { if (it !== full) full.recycle() }
        } finally {
            runCatching { if (Build.VERSION.SDK_INT >= 29) r.close() else r.release() }
        }
    }

    /** Marks [thumb] as seen today, so trimming takes the ones not seen for longest first. */
    private fun touch(thumb: File) {
        val now = System.currentTimeMillis()
        if (now - thumb.lastModified() > DAY_MS) thumb.setLastModified(now)
    }

    /** Counts a new thumbnail in; past [MAX_BYTES] the longest unseen go, down to [KEEP_BYTES]. */
    @Synchronized
    private fun added(dir: File, bytes: Long) {
        // The first count already includes the new one.
        total = if (total < 0) dir.listFiles().orEmpty().sumOf { it.length() } else total + bytes
        if (total <= MAX_BYTES) return
        val entries = dir.listFiles().orEmpty().map { Entry(it.name, it.length(), it.lastModified()) }
        val gone = toTrim(entries, MAX_BYTES, KEEP_BYTES).toSet()
        gone.forEach { File(dir, it).delete() }
        total = entries.filter { it.name !in gone }.sumOf { it.bytes }
    }

    /** Hands videos to [ThumbFetcher]; anything else goes on to Coil's own fetchers. */
    private object Thumbs : Fetcher.Factory<File> {
        override fun create(data: File, options: Options, imageLoader: ImageLoader): Fetcher? =
            if (FileTypes.kindOf(data.name) == FileKind.VIDEO) ThumbFetcher(data, options.context) else null
    }

    private class ThumbFetcher(private val video: File, private val context: Context) : Fetcher {
        override suspend fun fetch(): FetchResult? {
            val thumb = try {
                VideoThumbs.thumbnail(context, video)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            // Null hands the request on to Coil's file fetcher and video decoder: the old way.
            return thumb?.let { SourceResult(ImageSource(file = it.toOkioPath()), "image/jpeg", DataSource.DISK) }
        }
    }

    // ---- Pure parts (unit tested) ---------------------------------------------------------------

    /** A file in the thumbnail folder. */
    internal data class Entry(val name: String, val bytes: Long, val modified: Long)

    /**
     * The size to read a [width]×[height] video's frame at: just covering [boxW]×[boxH] (tiles crop
     * to fill, portrait videos included), never larger than the video. Null when the size is unknown.
     */
    internal fun frameSize(width: Int, height: Int, rotation: Int, boxW: Int, boxH: Int): Pair<Int, Int>? {
        if (width <= 0 || height <= 0) return null
        // Phones record portrait video sideways, with a rotation to show it upright.
        val turned = rotation.mod(180) == 90
        val w = if (turned) height else width
        val h = if (turned) width else height
        val scale = maxOf(boxW.toDouble() / w, boxH.toDouble() / h).coerceAtMost(1.0)
        return (w * scale).roundToInt().coerceAtLeast(1) to (h * scale).roundToInt().coerceAtLeast(1)
    }

    /** The thumbnail's file name: a new one whenever the video (or the tile size) changes. */
    internal fun fileName(path: String, length: Long, modified: Long, boxW: Int, boxH: Int): String {
        val digest = MessageDigest.getInstance("SHA-1").digest("$path|$length|$modified|${boxW}x$boxH".toByteArray())
        return digest.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') } + ".jpg"
    }

    /** Names to delete once the folder is past [max]: the oldest (least recently seen) first, down to [keep]. */
    internal fun toTrim(entries: List<Entry>, max: Long, keep: Long): List<String> {
        var left = entries.sumOf { it.bytes }
        if (left <= max) return emptyList()
        val out = ArrayList<String>()
        for (e in entries.sortedWith(compareBy<Entry>({ it.modified }, { it.name }))) {
            if (left <= keep) break
            out += e.name
            left -= e.bytes
        }
        return out
    }
}
