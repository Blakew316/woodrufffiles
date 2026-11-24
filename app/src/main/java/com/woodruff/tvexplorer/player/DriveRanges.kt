package com.woodruff.tvexplorer.player

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.HttpUtil
import androidx.media3.datasource.TransferListener
import java.io.IOException
import java.io.InputStream

/**
 * How much of a Drive film Google is asked for at a time: [first] bytes at a new spot, twice as many
 * each time the read carries straight on, up to [most].
 */
internal data class DrivePieces(val first: Long, val most: Long) {
    /** The piece after one of [previous] bytes (0: the first at a new spot). */
    fun next(previous: Long): Long = if (previous <= 0) first else minOf(previous * 2, most)

    companion object {
        /**
         * The player's (and the phone's, through this TV). 8 MB at a new spot is a second of the
         * heaviest Blu-ray remux (64 Mb/s) and several of anything else, so a start or a seek is
         * usually one request (the player starts at 1 s loaded); and it's all that's asked for when
         * the player only peeks there (a film's header, its index, a seek it leaves at once). Doubling
         * reaches 64 MB in three pieces: 7 s of that remux, a minute of a 1080p film, so playing
         * straight on costs a request every 7 s at worst, which the 50 s loaded ahead hides. 64 MB
         * is also the Apple apps' largest piece.
         */
        val FILMS = DrivePieces(8L * MB, 64L * MB)

        /**
         * The scrub preview's: a frame is one keyframe (rarely over 2 MB, even in 4K) and nothing
         * after it is wanted. Android's own reader used to stream 2 MB past each frame too.
         */
        val FRAMES = DrivePieces(2L * MB, 8L * MB)

        private const val MB = 1024L * 1024
    }
}

/**
 * [upstream] for the player, with every request for a Drive film ([carriesDriveToken]) made in
 * bounded pieces ([BoundedDriveSource]); anything else passes straight through. [placesKept]: how
 * many places the player jumps away from stay open ([BoundedDriveSource.kept]), each on a request
 * of its own from [upstream]; 0 keeps none.
 */
@OptIn(UnstableApi::class)
internal fun boundedDrive(
    upstream: DataSource.Factory,
    pieces: DrivePieces = DrivePieces.FILMS,
    asks: DriveAskSink = DriveAsks,
    placesKept: Int = 0,
): DataSource.Factory =
    DataSource.Factory { BoundedDriveSource(upstream.createDataSource(), pieces, asks, more = upstream::createDataSource, placesKept = placesKept) }

/**
 * Google Drive asked for its films in bounded pieces, never "from here to the end".
 *
 * Media3 opens a film with no length ("everything from this byte on"), so each start, seek, change
 * of sound track and try again went to Google as `Range: bytes=<spot>-` (or, from the first byte,
 * no Range at all: the whole file), and was hung up on after a few megabytes. Google doesn't say
 * how it counts its daily download allowance for a file; counted as what was asked for, every one
 * of those was the rest of a 50-80 GB film, and a few seeks used it up for the whole family
 * ("downloadQuotaExceeded"). Here each request to Google names its end: [DrivePieces.first] bytes
 * at a new spot, then twice as many each time the read carries straight on, up to
 * [DrivePieces.most]. The next piece is asked for only when the player reads past the last one,
 * never ahead of it, so a player that stops reading (its buffer full, a seek) asks for nothing more.
 *
 * The player sees what [upstream] alone would have shown it: [open] returns the length asked for, or
 * else the rest of the file from where it starts (the size in Google's first answer, its
 * Content-Range), or else C.LENGTH_UNSET; [read] goes on from piece to piece up to that end, or the
 * file's. [getResponseHeaders] are the answer to the piece being read (each Content-Range names the
 * whole file's size, which is what FilmStarts checks); [getUri] is where that piece came from.
 * Errors come up as they are, so the player's tries again and its words for Google's refusals work
 * as before (a new open starts again with a small piece, from where the player got to).
 *
 * Passed straight through: anything not on Drive, and a request bounded already within the first
 * piece (FilmStarts filling a gap in a kept start, a subtitle file read again). A Drive answer that
 * ignores the range (no Content-Range) is taken as Drive sends it, the whole request in one, as
 * before. Each request and what it brought is counted for the TV's diagnostics ([asks]).
 *
 * The places the player jumps away from stay open ([kept]): see there.
 */
@OptIn(UnstableApi::class)
internal class BoundedDriveSource(
    first: DataSource,
    private val pieces: DrivePieces = DrivePieces.FILMS,
    private val asks: DriveAskSink = DriveAsks,
    /** More of the same way to Drive, one for each place kept open ([kept]); null keeps none. */
    private val more: (() -> DataSource)? = null,
    /** How many places jumped away from stay open at most ([kept]); 0 keeps none. */
    private val placesKept: Int = 0,
    /** How far past a kept place a read may start and still carry on from it ([SKIP_AT_MOST]). */
    private val skipAtMost: Long = SKIP_AT_MOST,
    /** How long a kept place waits for the player to come back ([KEPT_FOR_MS]). */
    private val keptForMs: Long = KEPT_FOR_MS,
    /** Milliseconds, for how long a kept place waits: the device's own clock but in the tests. */
    private val clock: () -> Long = { android.os.SystemClock.elapsedRealtime() },
) : DataSource {
    /** The way to Drive the read open now goes through (the kept places each have their own). */
    private var upstream: DataSource = first
    /** One not in use, for the next read, so keeping places doesn't make a new one each time. */
    private var spare: DataSource? = null
    private val listeners = ArrayList<TransferListener>()

    /**
     * The places the player last jumped away from, each still open: Drive's answer there part-read.
     *
     * Some films keep their sound far from their picture in the file, and the player reads them by
     * jumping between the two. Each jump closed the request it left and opened a new one where it
     * landed, and every new request waits on Google before its first byte: PAW Patrol - The Mighty
     * Movie asked Google 647 times in three minutes for 5.3 GB, got 493 MB, and the film stopped to
     * wait all the while. Now a place jumped away from stays open, and when the player comes back to
     * it (or a little after it, [skipAtMost], the bytes between read past) its answer carries on:
     * no new request, no wait. Each is let go of when nothing comes back to it within [keptForMs],
     * or when more than [placesKept] are kept (the oldest). Oldest first; guarded by itself (the
     * timer that lets them go runs on a thread of its own, [LetGo]).
     *
     * Each kept place is a request of its own, so the way to Drive should give each its own
     * connection: on a shared HTTP/2 one, what Google sends to a place kept waiting fills the
     * connection's window and holds up the read that's going on (PlayerActivity's filmClient).
     */
    private val kept = ArrayList<Kept>()

    /** A place kept open: [source] holds Drive's answer for [key], read as far as [at], its bytes coming up to [pieceEnd]. */
    private class Kept(
        val source: DataSource,
        val key: String,
        val at: Long,
        val pieceEnd: Long,
        val nominal: Long,
        val size: Long,
        val since: Long,
    )

    /** The request as the player made it; null while closed. */
    private var spec: DataSpec? = null
    /** A Drive file: counted ([asks]). */
    private var drive = false
    /** Passed straight through ([upstream] holds the whole request). */
    private var through = false
    /** The film's key ("drive:…") for the counts; null for a subtitle file and anything else. */
    private var key: String? = null
    /** Where the next byte comes from, in the file's own offsets. */
    private var at = 0L
    /** Where this read ends (exclusive): the request's end, or the file's once known; [NO_LENGTH] while neither is. */
    private var end = NO_LENGTH
    /** The whole file's size, once an answer said it. */
    private var size = NO_LENGTH
    /**
     * The piece open now: its bytes come up to [pieceEnd] (exclusive; a kept place carried on may
     * go past this read's [end]), [pieceRead] bytes read of it so far.
     */
    private var pieceOpen = false
    private var pieceEnd = 0L
    private var pieceRead = 0L
    /** The length of the last piece asked for, before it was cut to the file's end: the next is twice it. */
    private var nominal = 0L
    /** The piece open now is a kept place carried on ([kept]): if Google let go of it meanwhile, it's asked for afresh. */
    private var carried = false
    /** The piece open now failed (its request, or a read): it's closed, never kept. */
    private var broken = false

    override fun addTransferListener(transferListener: TransferListener) {
        listeners += transferListener
        upstream.addTransferListener(transferListener)
        spare?.addTransferListener(transferListener)
        synchronized(kept) { kept.map { it.source } }.forEach { it.addTransferListener(transferListener) }
    }

    override fun open(dataSpec: DataSpec): Long {
        spec = dataSpec
        broken = false
        carried = false
        val uri = dataSpec.uri
        drive = carriesDriveToken(uri.scheme, uri.host, uri.path)
        key = if (drive) driveFileKey(uri) else null
        through = !drive || (dataSpec.length != NO_LENGTH && dataSpec.length <= pieces.first)
        if (!drive) return upstream.open(dataSpec)
        if (through) return passOn(dataSpec)
        at = dataSpec.position
        end = if (dataSpec.length == NO_LENGTH) NO_LENGTH else dataSpec.position + dataSpec.length
        size = NO_LENGTH
        nominal = 0
        // Back at a place jumped away from: its answer carries on, nothing asked of Google.
        if (!carryOn()) {
            openPiece()
            if (size == NO_LENGTH && contentRange(upstream.responseHeaders) == null) {
                // Drive ignored the range and sent the whole file: take the request as asked, in one.
                closePiece()
                through = true
                return passOn(dataSpec)
            }
        }
        return when {
            dataSpec.length != NO_LENGTH -> dataSpec.length
            size != NO_LENGTH -> maxOf(0L, size - dataSpec.position)
            else -> NO_LENGTH
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (through) return upstream.read(buffer, offset, length).also { if (it > 0) pieceRead += it }
        while (true) {
            if (end != NO_LENGTH && at >= end) return C.RESULT_END_OF_INPUT
            if (pieceOpen && at >= pieceEnd) closePiece()
            if (!pieceOpen) {
                openPiece()
                continue // its answer may say the file ends here
            }
            val want = minOf(length.toLong(), pieceEnd - at, if (end != NO_LENGTH) end - at else Long.MAX_VALUE).toInt()
            val n = try {
                upstream.read(buffer, offset, want)
            } catch (e: IOException) {
                // (The player letting go of this load interrupts it: that's no reason to ask Google again.)
                if (!carried || Thread.currentThread().isInterrupted) { broken = true; throw e }
                dropCarried() // Google let go of the kept place meanwhile: asked for afresh, from here
                continue
            }
            if (n == C.RESULT_END_OF_INPUT) {
                if (carried) { dropCarried(); continue }
                // The piece ended early: at the file's end (nothing came, or Drive never said its
                // size), else a connection that ended cleanly mid-piece, carried on from here.
                val nothing = pieceRead == 0L
                closePiece()
                if (nothing || size == NO_LENGTH) { end = at; return C.RESULT_END_OF_INPUT }
                continue
            }
            at += n
            pieceRead += n
            return n
        }
    }

    override fun getUri(): Uri? = if (spec == null) null else upstream.uri ?: spec?.uri

    override fun getResponseHeaders(): Map<String, List<String>> = if (spec == null) emptyMap() else upstream.responseHeaders

    override fun close() {
        if (spec == null) return
        // Left part-way through a piece with more of it coming: kept open for the player's way back.
        if (pieceOpen && !through && !broken && keep()) {
            spec = null
            pieceOpen = false
            pieceRead = 0
            carried = false
            return
        }
        val held = pieceOpen || through
        if (held && drive) asks.received(key, pieceRead)
        spec = null
        pieceOpen = false
        through = false
        carried = false
        pieceRead = 0
        if (held) upstream.close()
    }

    /**
     * Keeps the piece open now as a place jumped away from ([kept]), and takes another way to Drive
     * for the next read. False (nothing kept) when it can't be: no places kept here, a read whose
     * film or size isn't known, or nothing more of this piece coming.
     */
    private fun keep(): Boolean {
        val make = more ?: return false
        val k = key ?: return false
        if (placesKept <= 0 || size == NO_LENGTH || at >= pieceEnd) return false
        val next = spare ?: runCatching { make() }.getOrNull()?.also { s -> listeners.forEach(s::addTransferListener) } ?: return false
        spare = null
        asks.received(k, pieceRead)
        val place = Kept(upstream, k, at, pieceEnd, nominal, size, clock())
        val out = synchronized(kept) {
            kept += place
            if (kept.size > placesKept) kept.removeAt(0) else null
        }
        upstream = next
        out?.let(::letGo)
        LetGo.after(keptForMs) { expire(place) }
        return true
    }

    /**
     * The read opened now starts at a place kept ([kept]), or a little after one: that answer
     * carries on from here, the bytes between read past. True when it does. False when no place kept
     * fits, and the read asks Google as usual; or when the read starts past the end of the kept
     * place's piece: that piece is read to its end (its connection free for the next), and the read
     * asks for the next piece of that place, as big as it had grown, as if it had read straight on.
     */
    private fun carryOn(): Boolean {
        val k = key ?: return false
        val position = at
        val now = clock()
        val place = synchronized(kept) {
            val i = kept.indices
                .filter { val p = kept[it]; p.key == k && position >= p.at && position - p.at <= skipAtMost && position < p.size && now - p.since <= keptForMs }
                .minByOrNull { position - kept[it].at } ?: return false
            kept.removeAt(i)
        }
        // The bytes between where it was left and where this read starts (or where its piece ends): read past.
        val skip = minOf(position, place.pieceEnd) - place.at
        if (skip > 0) {
            val scratch = ByteArray(minOf(skip, SKIP_READ).toInt())
            var left = skip
            try {
                while (left > 0) {
                    val n = place.source.read(scratch, 0, minOf(left, scratch.size.toLong()).toInt())
                    if (n == C.RESULT_END_OF_INPUT) { asks.received(k, skip - left); letGo(place); return false }
                    left -= n
                }
            } catch (e: IOException) {
                asks.received(k, skip - left)
                letGo(place)
                return false
            }
        }
        asks.received(k, maxOf(0L, skip))
        // This read's way to Drive (not open) waits for the next; the kept place's is the one read now.
        spare = upstream
        upstream = place.source
        size = place.size
        end = if (end == NO_LENGTH) size else minOf(end, size)
        nominal = place.nominal
        if (position >= place.pieceEnd) {
            // Its piece read through: closed, and the next one asked for on the same connection.
            runCatching { upstream.close() }
            return false
        }
        pieceEnd = place.pieceEnd
        pieceRead = 0
        pieceOpen = true
        carried = true
        return true
    }

    /** The kept place carried on now went (Google let it go meanwhile): closed, and the read asks afresh from [at], a small piece first. */
    private fun dropCarried() {
        carried = false
        pieceOpen = false
        asks.received(key, pieceRead)
        pieceRead = 0
        runCatching { upstream.close() }
        nominal = 0
    }

    /** [place] let go of, if it's still kept: nothing came back to it in time. */
    private fun expire(place: Kept) {
        val gone = synchronized(kept) { kept.remove(place) }
        if (gone) letGo(place)
    }

    /** Closes a kept place's answer (what was read of it was counted as it was kept). */
    private fun letGo(place: Kept) {
        runCatching { place.source.close() }
    }

    /** The whole request to [upstream] as it is (counted as one). */
    private fun passOn(dataSpec: DataSpec): Long {
        pieceRead = 0
        val length = try {
            upstream.open(dataSpec)
        } catch (e: IOException) {
            asks.requested(key, dataSpec.length.takeIf { it != NO_LENGTH } ?: 0L)
            refused(e)
            throw e
        }
        val total = documentSize(upstream.responseHeaders)
        val asked = when {
            dataSpec.length != NO_LENGTH && total != NO_LENGTH -> minOf(dataSpec.length, maxOf(0L, total - dataSpec.position))
            dataSpec.length != NO_LENGTH -> dataSpec.length
            total != NO_LENGTH -> maxOf(0L, total - dataSpec.position)
            else -> maxOf(0L, length)
        }
        asks.requested(key, asked)
        return length
    }

    /** Asks for the next piece, from [at]: the first at a new spot, then twice the last, never past [end]. */
    private fun openPiece() {
        val s = checkNotNull(spec)
        nominal = pieces.next(nominal)
        val length = if (end == NO_LENGTH) nominal else minOf(nominal, end - at)
        pieceEnd = at + length
        pieceRead = 0
        pieceOpen = true
        carried = false
        try {
            upstream.open(s.subrange(at - s.position, length))
        } catch (e: IOException) {
            broken = true
            asks.requested(key, length)
            refused(e)
            throw e
        }
        if (size == NO_LENGTH) {
            size = documentSize(upstream.responseHeaders)
            if (size != NO_LENGTH) end = if (end == NO_LENGTH) size else minOf(end, size)
        }
        if (end != NO_LENGTH) pieceEnd = minOf(pieceEnd, maxOf(at, end))
        asks.requested(key, pieceEnd - at)
    }

    private fun closePiece() {
        if (!pieceOpen) return
        pieceOpen = false
        carried = false
        asks.received(key, pieceRead)
        pieceRead = 0
        upstream.close()
    }

    /** Google turning a request down over one of its limits: said in the TV's diagnostics. */
    private fun refused(e: IOException) {
        val http = generateSequence<Throwable>(e) { it.cause }.take(10)
            .filterIsInstance<HttpDataSource.InvalidResponseCodeException>().firstOrNull()?.responseCode ?: return
        val reason = if (http == 403 || http == 429) PlaybackErrors.reasonOf(e) else null
        if (DriveAsks.isQuotaRefusal(http, reason)) asks.refused(key, http, reason)
    }
}

/**
 * How far past a kept place a read may start and still carry on from it ([BoundedDriveSource.kept]):
 * the bytes between are read past, most of them already sent while it waited. A new request instead
 * waits on Google's first byte, about as long as this takes to come at a home connection's speed.
 */
internal const val SKIP_AT_MOST = 4L * 1024 * 1024

/** How much is read at a time when reading past ([SKIP_AT_MOST]). */
private const val SKIP_READ = 64L * 1024

/** How long a place jumped away from stays open with nothing coming back to it. */
internal const val KEPT_FOR_MS = 15_000L

/**
 * How many places the player keeps open ([BoundedDriveSource.kept]): the picture's and a few sound
 * tracks' (Media3 reads every track in the file, chosen or not).
 */
internal const val PLACES_KEPT = 4

/** Lets go of kept places that nothing came back to ([BoundedDriveSource.kept]), off the player's threads. */
internal object LetGo {
    private val timer by lazy {
        java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "drive-kept-places").apply { isDaemon = true } }
    }

    fun after(ms: Long, then: () -> Unit) {
        runCatching { timer.schedule({ runCatching(then) }, ms, java.util.concurrent.TimeUnit.MILLISECONDS) }
    }
}

/** The whole file's size in an answer's Content-Range ("bytes 0-99/12345", or a 416's with no bytes); [NO_LENGTH] when it doesn't say. */
@OptIn(UnstableApi::class)
internal fun documentSize(headers: Map<String, List<String>>): Long =
    contentRange(headers)?.let(HttpUtil::getDocumentSize) ?: NO_LENGTH

private fun contentRange(headers: Map<String, List<String>>): String? =
    headers.entries.firstOrNull { it.key?.equals("Content-Range", ignoreCase = true) == true }?.value?.firstOrNull()

/**
 * A request's bytes as an InputStream, for answers passed on to the phone ([com.woodruff.tvexplorer.server.PhoneStream]):
 * [source] is open already; closing the stream closes it, so a phone that hangs up asks Google for nothing more.
 */
@OptIn(UnstableApi::class)
internal class DataSourceBody(private val source: DataSource) : InputStream() {
    private var done = false
    private val one = ByteArray(1)

    override fun read(): Int = if (read(one, 0, 1) <= 0) -1 else one[0].toInt() and 0xFF

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (done) return -1
        if (len == 0) return 0
        val n = source.read(b, off, len)
        if (n == C.RESULT_END_OF_INPUT) { done = true; return -1 }
        return n
    }

    override fun close() {
        done = true
        source.close()
    }
}

/** Media3's "length not known" ([C.LENGTH_UNSET]) as the Long that lengths are. */
private const val NO_LENGTH = C.LENGTH_UNSET.toLong()
