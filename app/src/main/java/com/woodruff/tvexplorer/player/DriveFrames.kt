package com.woodruff.tvexplorer.player

import android.media.MediaDataSource
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import java.io.IOException

/**
 * A Drive film for Android's frame reader (the scrub preview's MediaMetadataRetriever), read through
 * [source]: the player's own way to Drive, so the film's header and index come from the TV's copy of
 * its start (FilmStarts) and everything else in bounded pieces ([BoundedDriveSource], small ones:
 * [DrivePieces.FRAMES]).
 *
 * Given the film's address instead, Android's reader asked Google itself, for everything from each
 * spot it read to the end of the film (`Range: bytes=<spot>-`, or from the first byte no Range at
 * all): a request into the film at every frame of a scrub, and at every jump while it opened it.
 * Here its reads come from one open read for as long as they carry on from it; a little ahead
 * ([SKIP_BYTES]) is read through rather than asked for again, a little behind ([KEPT_BYTES]) comes
 * from memory, and anywhere else is a new read, starting small again.
 */
@OptIn(UnstableApi::class)
internal class DriveFrames(private val source: DataSource, private val uri: Uri) : MediaDataSource() {
    /** The film's size: [UNASKED] until the first read, -1 when Drive doesn't say. */
    private var size = UNASKED
    private var open = false
    private var closed = false
    /** Where the open read has got to. */
    private var at = 0L
    /** The last bytes read, up to [at]: [keptLength] of them, for a step back. */
    private val kept = ByteArray(KEPT_BYTES)
    private var keptLength = 0
    private val scratch = ByteArray(64 * 1024)

    @Synchronized
    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (size <= 0) return 0
        if (closed) return -1
        return guarded {
            val total = sizeNow()
            if (total >= 0 && position >= total) return@guarded -1
            var done = 0
            if (open && position < at && position >= at - keptLength) {
                val from = keptLength - (at - position).toInt()
                done = minOf(size, keptLength - from)
                System.arraycopy(kept, from, buffer, offset, done)
                if (done == size) return@guarded done
            }
            val want = position + done
            if (!open || want < at || want - at > SKIP_BYTES) reopen(want) else skip(want - at)
            while (done < size) {
                val n = source.read(buffer, offset + done, size - done)
                if (n == C.RESULT_END_OF_INPUT) break
                keep(buffer, offset + done, n)
                at += n
                done += n
            }
            if (done == 0) -1 else done
        }
    }

    @Synchronized
    override fun getSize(): Long = if (closed) -1 else guarded { sizeNow() }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        if (open) {
            open = false
            runCatching { source.close() }
        }
    }

    /** The size, opening the film at its first byte the first time (where the reader starts anyway). */
    private fun sizeNow(): Long {
        if (size == UNASKED) {
            size = -1
            reopen(0)
        }
        return size
    }

    private fun reopen(position: Long) {
        if (open) {
            open = false
            source.close()
        }
        keptLength = 0
        at = position
        val length = try {
            source.open(DataSpec.Builder().setUri(uri).setPosition(position).setFlags(FLAGS).build())
        } catch (e: IOException) {
            runCatching { source.close() }
            throw e
        }
        open = true
        if (size < 0 && length != C.LENGTH_UNSET.toLong()) size = position + length
    }

    /** Reads on [n] bytes (fewer at the file's end), keeping them for a step back. */
    private fun skip(n: Long) {
        var left = n
        while (left > 0) {
            val r = source.read(scratch, 0, minOf(scratch.size.toLong(), left).toInt())
            if (r == C.RESULT_END_OF_INPUT) return
            keep(scratch, 0, r)
            at += r
            left -= r
        }
    }

    private fun keep(b: ByteArray, off: Int, n: Int) {
        if (n >= KEPT_BYTES) {
            System.arraycopy(b, off + n - KEPT_BYTES, kept, 0, KEPT_BYTES)
            keptLength = KEPT_BYTES
            return
        }
        val over = keptLength + n - KEPT_BYTES
        if (over > 0) {
            System.arraycopy(kept, over, kept, 0, keptLength - over)
            keptLength -= over
        }
        System.arraycopy(b, off, kept, keptLength, n)
        keptLength += n
    }

    /** Android's reader takes an IOException as a read that failed; anything else is made one. */
    private inline fun <T> guarded(block: () -> T): T = try {
        block()
    } catch (e: IOException) {
        throw e
    } catch (e: Exception) {
        throw IOException(e)
    }

    companion object {
        /** Read through to a spot this far ahead, rather than asked for again. */
        const val SKIP_BYTES = 512L * 1024
        /** Kept of what was read, for a reader that steps back. */
        const val KEPT_BYTES = 256 * 1024
        private const val UNASKED = -2L
        /** As the player's loads are flagged (FilmStarts keeps what falls in a film's start and end). */
        private const val FLAGS = DataSpec.FLAG_DONT_CACHE_IF_LENGTH_UNKNOWN or DataSpec.FLAG_ALLOW_CACHE_FRAGMENTATION
    }
}
