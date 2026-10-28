package com.woodruff.tvexplorer.player

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import java.util.concurrent.Executors

/**
 * Netflix-style scrubbing previews: the frame at a point in the video, fetched on demand.
 *
 * Nothing is prepared ahead (that would mean downloading much of the film again); instead, when the
 * scrubbing pauses on a spot, one frame is read there (the nearest keyframe: fast, and close enough
 * for finding a scene). Frames are kept per 10 s slot, so going back over a stretch is instant.
 * All decoding happens on one background thread; results come back on the main thread.
 */
class ScrubPreview(
    /** Where to read the video from, and any headers (the Drive token), for a media key. Called off the main thread. */
    private val source: (key: String) -> Pair<String, Map<String, String>>?,
) {
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "scrub-preview").apply { priority = Thread.MIN_PRIORITY } }
    private val main = Handler(Looper.getMainLooper())
    private val cache = object : LruCache<String, Bitmap>(80) {}

    // Only touched on the worker thread.
    private var retriever: MediaMetadataRetriever? = null
    private var retrieverKey: String? = null
    private var failedKey: String? = null

    @Volatile private var wanted: Pair<String, Long>? = null
    private var pending: Runnable? = null

    private fun slot(key: String, ms: Long) = "$key@${ms / SLOT_MS}"

    /** A frame already fetched for this spot, if any (instant). */
    fun cached(key: String, positionMs: Long): Bitmap? = cache.get(slot(key, positionMs))

    /**
     * Fetch the frame near [positionMs] once the scrubbing has rested for a moment; [onFrame] runs
     * on the main thread, only if this is still the latest spot asked for.
     */
    fun request(key: String, positionMs: Long, onFrame: (Bitmap) -> Unit) {
        val target = key to positionMs / SLOT_MS * SLOT_MS + SLOT_MS / 2
        wanted = target
        pending?.let(main::removeCallbacks)
        val r = Runnable {
            worker.execute {
                if (wanted != target) return@execute
                val bmp = cache.get(slot(key, target.second)) ?: frame(key, target.second)?.also { cache.put(slot(key, target.second), it) }
                if (bmp != null) main.post { if (wanted == target) onFrame(bmp) }
            }
        }
        pending = r
        main.postDelayed(r, DEBOUNCE_MS)
    }

    /** Stop any work in flight (scrubbing ended). Keeps the cache. */
    fun cancel() {
        wanted = null
        pending?.let(main::removeCallbacks)
        pending = null
    }

    fun release() {
        cancel()
        worker.execute { runCatching { retriever?.release() }; retriever = null; retrieverKey = null }
        worker.shutdown()
        cache.evictAll()
    }

    private fun frame(key: String, ms: Long): Bitmap? {
        if (failedKey == key) return null // this file can't be read for frames (some MKVs): don't keep trying
        val r = retrieverFor(key) ?: return null
        return runCatching {
            if (Build.VERSION.SDK_INT >= 27) r.getScaledFrameAtTime(ms * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, WIDTH, HEIGHT)
            else r.getFrameAtTime(ms * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)?.let { Bitmap.createScaledBitmap(it, WIDTH, HEIGHT, true) }
        }.getOrNull()
    }

    private fun retrieverFor(key: String): MediaMetadataRetriever? {
        if (retrieverKey == key) return retriever
        runCatching { retriever?.release() }
        retriever = null; retrieverKey = null
        val (url, headers) = source(key) ?: return null
        return runCatching {
            MediaMetadataRetriever().apply {
                if (url.startsWith("http")) setDataSource(url, headers) else setDataSource(url)
            }
        }.onFailure { failedKey = key }.getOrNull()?.also { retriever = it; retrieverKey = key }
    }

    companion object {
        const val WIDTH = 320
        const val HEIGHT = 180
        private const val SLOT_MS = 10_000L
        private const val DEBOUNCE_MS = 160L
    }
}
