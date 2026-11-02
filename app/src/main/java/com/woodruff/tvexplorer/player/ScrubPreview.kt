package com.woodruff.tvexplorer.player

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Netflix-style scrubbing previews: the frame at a point in the video, fetched on demand.
 *
 * Nothing is prepared ahead (that would mean downloading much of the film again); instead one frame
 * is read at the spot being scrubbed to (the nearest keyframe: fast, and close enough for finding a
 * scene). Frames are kept per 10 s slot, so going back over a stretch is instant. All decoding
 * happens on one background thread, always for the latest spot asked for; results come back on the
 * main thread.
 *
 * It stays out of the way of the film: Android's reader fetches only a little past each frame
 * ([headersFor]), and once a scrub has ended ([cancel]) no new spot is read, so the seek the family
 * is waiting for has the connection to itself.
 */
class ScrubPreview(
    /** Where to read the video from, and any headers (the Drive token), for a media key. Called off the main thread. */
    private val source: (key: String) -> Pair<String, Map<String, String>>?,
) {
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "scrub-preview").apply { priority = Thread.MIN_PRIORITY } }
    private val main = Handler(Looper.getMainLooper())
    // Sized by memory, not by count: about 45 frames.
    private val cache = object : LruCache<String, Bitmap>(10 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    // Only touched on the worker thread.
    private var retriever: MediaMetadataRetriever? = null
    private var retrieverKey: String? = null
    private var failedKey: String? = null
    private var failedAt = 0L
    /** The last spot fetched (or tried) in this scrub. */
    @Volatile private var done: Pair<String, Long>? = null

    @Volatile private var wanted: Pair<String, Long>? = null
    @Volatile private var onFrame: ((Bitmap) -> Unit)? = null
    private val busy = AtomicBoolean(false)

    // Closed after a few idle seconds. Kept open until then so a quick second scrub doesn't read the
    // file's header and index all over again; it reads nothing more meanwhile (see headersFor).
    private val closeIdle = Runnable { submit { closeRetriever() } }

    private fun slot(key: String, ms: Long) = "$key@${ms / SLOT_MS}"

    /** A frame already fetched for this spot, if any (instant). */
    fun cached(key: String, positionMs: Long): Bitmap? = cache.get(slot(key, positionMs))

    /**
     * Fetch the frame near [positionMs]: at once when nothing is being read, otherwise as soon as
     * the frame being read is done (spots passed over in between are skipped). [onFrame] runs on the
     * main thread, only while this is still the latest spot asked for.
     *
     * (It used to wait for the scrubbing to rest; on a remote every press restarted that wait, and
     * the time bar ends a scrub a second after the last press — so frames rarely arrived in time.)
     */
    fun request(key: String, positionMs: Long, onFrame: (Bitmap) -> Unit) {
        main.removeCallbacks(closeIdle)
        this.onFrame = onFrame
        wanted = key to positionMs / SLOT_MS * SLOT_MS + SLOT_MS / 2
        pump()
    }

    /** Stop any work in flight (scrubbing ended). Keeps the cache. */
    fun cancel() {
        wanted = null
        onFrame = null
        done = null
    }

    fun release() {
        cancel()
        main.removeCallbacks(closeIdle)
        submit { closeRetriever() }
        worker.shutdown()
        cache.evictAll()
    }

    private fun submit(task: () -> Unit) {
        runCatching { worker.execute(task) } // after release(), nothing more runs
    }

    private fun pump() {
        if (busy.compareAndSet(false, true)) submit(::work)
    }

    private fun work() {
        try {
            while (true) {
                val target = wanted
                if (target == null || target == done) break
                done = target
                val (key, ms) = target
                val bmp = cache.get(slot(key, ms)) ?: frame(target)?.also { cache.put(slot(key, ms), it) }
                if (bmp != null) main.post { if (wanted == target) onFrame?.invoke(bmp) }
            }
        } finally {
            busy.set(false)
            // A spot asked for just as this finished: carry on with it. Otherwise close the reader soon.
            val w = wanted
            if (w != null && w != done) pump() else main.postDelayed(closeIdle, IDLE_CLOSE_MS)
        }
    }

    private fun closeRetriever() {
        runCatching { retriever?.release() }
        retriever = null
        retrieverKey = null
    }

    private fun frame(target: Pair<String, Long>): Bitmap? {
        val (key, ms) = target
        // This file couldn't be opened for frames (some MKVs, or the network): don't keep trying for a while.
        if (failedKey == key && System.currentTimeMillis() - failedAt < RETRY_FAILED_MS) return null
        val r = retrieverFor(key) ?: return null
        // Opening the file took a while (its header and index come from Drive first). If the scrub
        // has moved on or ended meanwhile, this spot is no longer wanted: reading it now would only
        // compete with the seek the scrub just made.
        if (wanted != target) return null
        return runCatching {
            if (Build.VERSION.SDK_INT >= 27) {
                // Scaled down by the decoder, keeping the picture's shape.
                r.getScaledFrameAtTime(ms * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, WIDTH, HEIGHT)
            } else {
                r.getFrameAtTime(ms * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)?.let { full ->
                    // Older TVs only give the full-size frame: shrink it (keeping its shape) and free the big one.
                    val scale = minOf(WIDTH.toFloat() / full.width, HEIGHT.toFloat() / full.height)
                    val w = (full.width * scale).toInt().coerceAtLeast(1)
                    val h = (full.height * scale).toInt().coerceAtLeast(1)
                    Bitmap.createScaledBitmap(full, w, h, true).also { if (it !== full) full.recycle() }
                }
            }
        }.getOrNull()
    }

    private fun retrieverFor(key: String): MediaMetadataRetriever? {
        if (retrieverKey == key && retriever != null) return retriever
        closeRetriever()
        val (url, headers) = source(key) ?: return null
        return runCatching {
            MediaMetadataRetriever().apply {
                if (isHttp(url)) setDataSource(url, headersFor(url, headers)) else setDataSource(url)
            }
        }.onFailure { failedKey = key; failedAt = System.currentTimeMillis() }
            .getOrNull()?.also { retriever = it; retrieverKey = key }
    }

    companion object {
        const val WIDTH = 320
        const val HEIGHT = 180
        private const val SLOT_MS = 10_000L
        private const val IDLE_CLOSE_MS = 5_000L
        private const val RETRY_FAILED_MS = 60_000L

        /**
         * Android's own reader behind MediaMetadataRetriever (NuCachedSource2) streams up to 20 MB
         * past every spot it reads, on a connection of its own, and goes on with it for seconds
         * after the last frame: just as the scrub ends and the player asks Drive for the new spot.
         * These ask it to read at most 2 MB ahead ("low-water / high-water KB / keep-alive s", no
         * keep-alive reads) and then hang up. Android takes them off before the request goes out; a
         * reader that doesn't know them sends them on, and the server ignores them.
         */
        internal val READ_AHEAD_LIMIT = mapOf(
            "x-cache-config" to "256/2048/0",
            "x-disconnect-at-highwatermark" to "1",
        )

        internal fun isHttp(url: String) = url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true)

        /** The headers a frame reader for [url] gets: [headers] (the Drive token), read-ahead capped for the network. */
        internal fun headersFor(url: String, headers: Map<String, String>): Map<String, String> =
            if (isHttp(url)) headers + READ_AHEAD_LIMIT else headers
    }
}
