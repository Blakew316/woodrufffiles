package com.woodruff.tvexplorer.player

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSink
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.FileDataSource
import androidx.media3.datasource.HttpUtil
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSink
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheKeyFactory
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.datasource.cache.ContentMetadataMutations
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * The first and last few megabytes of each film played from Google Drive, kept on the TV so that
 * Continue watching starts it sooner.
 *
 * Starting a film part-way takes three requests to Drive, one after the other: its header (from its
 * first byte), then its index of where each moment is (an MKV's Cues, an MP4's moov, usually near
 * the end of the file), then the spot to carry on from. Each one waits on Drive's own time per
 * request. The header and the index are the same bytes every time, so they're kept here as they're
 * read: whatever is read of a film's first [HEAD_BYTES] and its last [TAIL_BYTES], nothing in
 * between. The next start of the same film reads them from the TV, and asks Drive only for the spot:
 * one request instead of three. At most [MAX_BYTES] in all, the films played longest ago making way
 * (Media3's SimpleCache, in the app's cache folder, which Android may also clear when it needs room).
 *
 * A film replaced in Drive (Manage versions keeps its id) must never start from the old one's index.
 * Every answer Drive still sends says how big the whole file is now; when that isn't the size its
 * kept start came from, the kept start is thrown away and the film starts again from Drive alone
 * ([StaleStartException]). Films small enough to be kept whole ([MIN_FILE_BYTES]) aren't kept at all:
 * they start quickly anyway, and a film read entirely from the TV would never be checked.
 *
 * Only Google Drive's films: a USB drive is quick to read, and a link from the phone could change
 * without a sign. Anything that goes wrong with the TV's copy (no room left, a file Android cleared)
 * only means the film is read from Drive, as before; it never stops the film.
 */
@OptIn(UnstableApi::class)
internal object FilmStarts {
    const val HEAD_BYTES = 4L * 1024 * 1024
    const val TAIL_BYTES = 4L * 1024 * 1024
    const val MIN_FILE_BYTES = 32L * 1024 * 1024
    const val MAX_BYTES = 96L * 1024 * 1024
    private const val FOLDER = "film-starts"
    private const val TAG = "FilmStarts"

    @Volatile private var cache: Cache? = null
    @Volatile private var unusable = false

    /**
     * Drive's answers stopped saying how big the file is, so kept starts can't be checked: none is
     * used again until Woodruff starts afresh.
     */
    @Volatile private var uncheckable = false

    /** When each film's start was last read from the TV's copy (elapsedRealtime), by its key. */
    private val copyReads = ConcurrentHashMap<String, Long>()

    /**
     * The TV's copy, opened the first time it's asked for: never on the main thread (it reads its
     * index from disk). Null where it can't be used.
     */
    fun cache(c: Context): Cache? {
        cache?.let { return it }
        if (unusable || uncheckable) return null
        synchronized(this) {
            cache?.let { return it }
            if (unusable) return null
            val app = c.applicationContext
            return runCatching {
                SimpleCache(File(app.cacheDir, FOLDER), LeastRecentlyUsedCacheEvictor(MAX_BYTES), StandaloneDatabaseProvider(app))
            }.onFailure {
                unusable = true
                Log.w(TAG, "Can't keep films' starts on this TV", it)
            }.getOrNull()?.also { cache = it }
        }
    }

    /**
     * Opens the TV's copy ahead of the first film (on a background thread), so the film's first
     * request doesn't wait for it to read its index.
     */
    fun warm(c: Context) {
        val opened = cache(c) as? SimpleCache ?: return
        runCatching { opened.checkInitialization() }.onFailure { unusable = true; cache = null }
    }

    /** The player's sources: Drive's films through the TV's copy of their start, everything else straight to [upstream]. */
    fun dataSourceFactory(c: Context, upstream: DataSource.Factory): DataSource.Factory {
        val app = c.applicationContext
        return DataSource.Factory { DriveStartsDataSource(upstream) { usable(app) } }
    }

    /** The cache, if it opened well and its starts can be checked. On the loader's thread: may wait for it to open. */
    private fun usable(c: Context): Cache? {
        if (uncheckable) return null
        val opened = cache(c) ?: return null
        if (opened is SimpleCache && runCatching { opened.checkInitialization() }.isFailure) {
            unusable = true
            return null
        }
        return opened
    }

    /** Whether [key]'s start was read from the TV's copy at or after [sinceMs] (elapsedRealtime). */
    fun readFromCopy(key: String, sinceMs: Long = 0): Boolean = (copyReads[key] ?: -1) >= sinceMs

    /** Throws away [key]'s kept start (a film that couldn't be read from it). Disk: off the main thread. */
    fun forget(key: String) {
        copyReads.remove(key)
        val opened = cache ?: return
        runCatching { opened.removeResource(key) }
    }

    /** Whether a playback error was a kept start found out of date ([StaleStartException]). */
    fun isStale(error: Throwable): Boolean = generateSequence(error) { it.cause }.take(10).any { it is StaleStartException }

    internal fun noteReadFromCopy(key: String) {
        copyReads[key] = SystemClock.elapsedRealtime()
    }

    internal fun noteUncheckable() {
        if (!uncheckable) Log.w(TAG, "Google Drive didn't say how big a film is: not keeping films' starts this time")
        uncheckable = true
    }

    /**
     * A Drive film through [cache]: what's kept is read from the TV, the rest from [upstream], and
     * whatever falls within the film's first and last bytes ([windows]) is kept as it's read.
     * [onReadFromCopy] is told each time the TV's copy was used; [onUncheckable] when Drive's answer
     * didn't say the file's size.
     */
    internal fun cachedSource(
        cache: Cache,
        upstream: DataSource,
        windows: StartWindows = StartWindows(),
        onReadFromCopy: () -> Unit = {},
        onUncheckable: () -> Unit = ::noteUncheckable,
    ): DataSource = CacheDataSource.Factory()
        .setCache(cache)
        .setUpstreamDataSourceFactory { SizeCheckedDataSource(upstream, cache, onUncheckable) }
        .setCacheReadDataSourceFactory(FileDataSource.Factory())
        .setCacheWriteDataSinkFactory { WindowSink(cache, windows, CacheDataSink(cache, CacheDataSink.DEFAULT_FRAGMENT_SIZE)) }
        .setCacheKeyFactory(KEYS)
        .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        .setEventListener(object : CacheDataSource.EventListener {
            override fun onCachedBytesRead(cacheSizeBytes: Long, cachedBytesRead: Long) = onReadFromCopy()
            override fun onCacheIgnored(reason: Int) = Unit
        })
        .createDataSource()

    /** A Drive film's bytes are kept under its player key ("drive:…"), whatever its address looks like. */
    private val KEYS = CacheKeyFactory { spec -> driveFileKey(spec.uri) ?: CacheKeyFactory.DEFAULT.buildCacheKey(spec) }
}

/** Which parts of a film are kept: its first [headBytes] and last [tailBytes]; none of a film under [minFileBytes]. */
internal data class StartWindows(
    val headBytes: Long = FilmStarts.HEAD_BYTES,
    val tailBytes: Long = FilmStarts.TAIL_BYTES,
    val minFileBytes: Long = FilmStarts.MIN_FILE_BYTES,
) {
    /** The byte ranges kept of a file of [size] bytes, in order (start, end exclusive). */
    fun of(size: Long): List<LongRange> =
        if (size < minFileBytes || size < headBytes + tailBytes) emptyList()
        else listOf(0L until headBytes, (size - tailBytes) until size)
}

/**
 * The player's key ("drive:<id>") for the address the player streams a Drive file from
 * (DriveApi.mediaUrl), or null for any other address.
 */
internal fun driveFileKey(uri: Uri): String? {
    if (!carriesDriveToken(uri.scheme, uri.host, uri.path)) return null
    val path = uri.pathSegments
    if (path.size != 4 || path[0] != "drive" || path[1] != "v3" || path[2] != "files" || path[3].isBlank()) return null
    if (runCatching { uri.getQueryParameter("alt") }.getOrNull() != "media") return null
    return MediaRef.Drive(path[3]).key
}

/**
 * How big the whole file is, from the answer to a request starting at [position] (asked for
 * [requestedLength] bytes, the source said [resolvedLength] would come): the total in its
 * Content-Range ("bytes 1000-1999/123456"); with no Content-Range, the answer is the whole file, so
 * its Content-Length; null when neither says.
 */
internal fun wholeSize(headers: Map<String, List<String>>, position: Long, requestedLength: Long, resolvedLength: Long): Long? {
    header(headers, "Content-Range")?.let { range -> return HttpUtil.getDocumentSize(range).takeIf { it != NO_LENGTH } }
    header(headers, "Content-Length")?.toLongOrNull()?.let { return it }
    return if (requestedLength == NO_LENGTH && resolvedLength != NO_LENGTH) position + resolvedLength else null
}

/** Media3's "length not known" ([C.LENGTH_UNSET]) as the Long that lengths are. */
private const val NO_LENGTH = C.LENGTH_UNSET.toLong()

private fun header(headers: Map<String, List<String>>, name: String): String? =
    headers.entries.firstOrNull { it.key?.equals(name, ignoreCase = true) == true }?.value?.firstOrNull()

/**
 * A film's start kept on the TV came from another version of the file ([FilmStarts]): it has been
 * thrown away, and the film must start again from Drive. The player does that once, by itself.
 */
internal class StaleStartException : IOException("The start of this film kept on the TV was out of date")

/**
 * Media3's own retry rules, except that a film whose kept start was out of date
 * ([StaleStartException]) isn't tried again from where it was (its parser has read the old index):
 * it stops at once, for the player to start it again from Drive.
 */
@OptIn(UnstableApi::class)
internal class FilmStartsErrorPolicy : DefaultLoadErrorHandlingPolicy() {
    override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long =
        if (FilmStarts.isStale(loadErrorInfo.exception)) C.TIME_UNSET else super.getRetryDelayMsFor(loadErrorInfo)
}

/**
 * Sends each request where it belongs, chosen afresh at every open: a Drive film through the TV's
 * copy of its start ([FilmStarts.cachedSource], once [cacheFor] has one), anything else (a link from
 * the phone) straight to [upstream].
 */
@OptIn(UnstableApi::class)
internal class DriveStartsDataSource(
    private val upstream: DataSource.Factory,
    private val windows: StartWindows = StartWindows(),
    private val cacheFor: () -> Cache?,
) : DataSource {
    private val plain = upstream.createDataSource()
    private var copied: DataSource? = null
    private var current: DataSource? = null
    private var currentKey: String? = null
    private val listeners = ArrayList<TransferListener>()

    override fun addTransferListener(transferListener: TransferListener) {
        listeners += transferListener
        plain.addTransferListener(transferListener)
        copied?.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        val key = driveFileKey(dataSpec.uri)
        val source = key?.let { copied() } ?: plain
        current = source
        currentKey = key
        return source.open(dataSpec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = checkNotNull(current).read(buffer, offset, length)

    override fun getUri(): Uri? = current?.uri

    override fun getResponseHeaders(): Map<String, List<String>> = current?.responseHeaders ?: emptyMap()

    override fun close() {
        try {
            current?.close()
        } finally {
            current = null
            currentKey = null
        }
    }

    /** The source through the TV's copy, made the first time a Drive film is opened. */
    private fun copied(): DataSource? {
        copied?.let { return it }
        val cache = cacheFor() ?: return null
        return FilmStarts.cachedSource(cache, upstream.createDataSource(), windows, onReadFromCopy = { currentKey?.let(FilmStarts::noteReadFromCopy) })
            .also { source -> listeners.forEach(source::addTransferListener); copied = source }
    }
}

/**
 * Drive, checked against the TV's copy as each answer comes: the answer says how big the whole file
 * is now, and a start kept from a file of another size is thrown away ([StaleStartException]). The
 * size of a film with nothing kept yet is noted, for [WindowSink] and for later checks.
 */
@OptIn(UnstableApi::class)
private class SizeCheckedDataSource(
    private val upstream: DataSource,
    private val cache: Cache,
    private val onUncheckable: () -> Unit,
) : DataSource {
    override fun addTransferListener(transferListener: TransferListener) = upstream.addTransferListener(transferListener)

    override fun open(dataSpec: DataSpec): Long {
        val length = upstream.open(dataSpec)
        val key = dataSpec.key ?: return length
        val size = wholeSize(upstream.responseHeaders, dataSpec.position, dataSpec.length, length)
        // The cache's record of the film's size. If the cache can't even be read, nothing is checked
        // and nothing will be kept (WindowSink needs that record): the film simply comes from Drive.
        val kept = runCatching { ContentMetadata.getContentLength(cache.getContentMetadata(key)) }.getOrNull() ?: return length
        when {
            size == null && kept == NO_LENGTH -> Unit // nothing kept, and nothing will be
            size == null -> { onUncheckable(); stale(key, null) }
            kept == NO_LENGTH -> runCatching { cache.applyContentMetadataMutations(key, sizeIs(size)) }
            kept != size -> stale(key, size)
        }
        return length
    }

    /** Throws [key]'s kept start away, notes the file's size as it is now (if known), and stops this read. */
    private fun stale(key: String, size: Long?): Nothing {
        Log.i("FilmStarts", "$key changed in Google Drive: starting it again without its kept start")
        runCatching { cache.removeResource(key) }
        runCatching { cache.applyContentMetadataMutations(key, if (size != null) sizeIs(size) else ContentMetadataMutations().remove(ContentMetadata.KEY_CONTENT_LENGTH)) }
        runCatching { upstream.close() }
        throw StaleStartException()
    }

    private fun sizeIs(size: Long) = ContentMetadataMutations().also { ContentMetadataMutations.setContentLength(it, size) }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = upstream.read(buffer, offset, length)

    override fun getUri(): Uri? = upstream.uri

    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders

    override fun close() = upstream.close()
}

/**
 * Keeps only a film's start and end of what's read from Drive ([StartWindows]): the bytes that fall
 * in them go on to [inner] (a cache file each), the rest is let go. The film's size is the cache's
 * record of it (noted by [SizeCheckedDataSource] as the answer arrived); without one, nothing is
 * kept. It never fails a read: if the copy can't be written (no room left), it stops keeping this
 * stretch and the film plays on from Drive.
 */
@OptIn(UnstableApi::class)
internal class WindowSink(private val cache: Cache, private val windows: StartWindows, private val inner: DataSink) : DataSink {
    private var spec: DataSpec? = null
    private var kept: List<LongRange> = emptyList()
    private var position = 0L
    private var writing: LongRange? = null
    private var broken = false

    override fun open(dataSpec: DataSpec) {
        spec = dataSpec
        position = dataSpec.position
        writing = null
        broken = false
        val size = dataSpec.key?.let { key -> runCatching { ContentMetadata.getContentLength(cache.getContentMetadata(key)) }.getOrNull() }
        kept = if (size == null || size == NO_LENGTH) emptyList() else windows.of(size)
    }

    override fun write(buffer: ByteArray, offset: Int, length: Int) {
        var done = 0
        while (done < length && !broken) {
            val at = position + done
            val left = length - done
            val window = kept.firstOrNull { at in it }
            if (window == null) {
                stopWriting()
                val next = kept.filter { it.first > at }.minOfOrNull { it.first }
                done += if (next == null) left else minOf(left.toLong(), next - at).toInt()
                continue
            }
            if (writing != window && !startWriting(window, at)) break
            val n = minOf(left.toLong(), window.last + 1 - at).toInt()
            try {
                inner.write(buffer, offset + done, n)
            } catch (e: Exception) {
                fail(e)
                break
            }
            done += n
            if (at + n > window.last) stopWriting()
        }
        position += length
    }

    override fun close() {
        stopWriting()
        spec = null
    }

    /** Starts keeping [window] from [at] (where the read has got to in it). False if the copy can't be written. */
    private fun startWriting(window: LongRange, at: Long): Boolean {
        stopWriting()
        val s = spec ?: return false
        return try {
            inner.open(s.subrange(at - s.position, window.last + 1 - at))
            writing = window
            true
        } catch (e: Exception) {
            fail(e)
            false
        }
    }

    private fun stopWriting() {
        if (writing == null) return
        writing = null
        try {
            inner.close()
        } catch (e: Exception) {
            fail(e)
        }
    }

    private fun fail(e: Exception) {
        if (!broken) Log.w("FilmStarts", "Can't keep this film's start on the TV", e)
        broken = true
        writing = null
        runCatching { inner.close() }
    }
}
