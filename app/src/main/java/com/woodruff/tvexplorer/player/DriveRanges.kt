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
 * bounded pieces ([BoundedDriveSource]); anything else passes straight through.
 */
@OptIn(UnstableApi::class)
internal fun boundedDrive(upstream: DataSource.Factory, pieces: DrivePieces = DrivePieces.FILMS, asks: DriveAskSink = DriveAsks): DataSource.Factory =
    DataSource.Factory { BoundedDriveSource(upstream.createDataSource(), pieces, asks) }

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
 */
@OptIn(UnstableApi::class)
internal class BoundedDriveSource(
    private val upstream: DataSource,
    private val pieces: DrivePieces = DrivePieces.FILMS,
    private val asks: DriveAskSink = DriveAsks,
) : DataSource {
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
    /** The piece open now: up to [pieceEnd] (exclusive), [pieceRead] bytes read of it so far. */
    private var pieceOpen = false
    private var pieceEnd = 0L
    private var pieceRead = 0L
    /** The length of the last piece asked for, before it was cut to the file's end: the next is twice it. */
    private var nominal = 0L

    override fun addTransferListener(transferListener: TransferListener) = upstream.addTransferListener(transferListener)

    override fun open(dataSpec: DataSpec): Long {
        spec = dataSpec
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
        openPiece()
        if (size == NO_LENGTH && contentRange(upstream.responseHeaders) == null) {
            // Drive ignored the range and sent the whole file: take the request as asked, in one.
            closePiece()
            through = true
            return passOn(dataSpec)
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
            val n = upstream.read(buffer, offset, minOf(length.toLong(), pieceEnd - at).toInt())
            if (n == C.RESULT_END_OF_INPUT) {
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
        val held = pieceOpen || through
        if (held && drive) asks.received(key, pieceRead)
        spec = null
        pieceOpen = false
        through = false
        pieceRead = 0
        if (held) upstream.close()
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
        try {
            upstream.open(s.subrange(at - s.position, length))
        } catch (e: IOException) {
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
