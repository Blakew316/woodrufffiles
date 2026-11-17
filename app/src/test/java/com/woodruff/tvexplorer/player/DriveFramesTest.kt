package com.woodruff.tvexplorer.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.woodruff.tvexplorer.cloud.DriveApi
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.random.Random

/**
 * The scrub preview's reader of a Drive film (DriveFrames): Android's frame reader gets its bytes
 * through the player's way to Drive, and its reads cost as few requests as they can: one open read
 * for reads that carry on, a step back from memory, a step ahead read through, a jump a new read
 * that starts small (through BoundedDriveSource, the pieces stay bounded).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class DriveFramesTest {
    private val uri = Uri.parse(DriveApi.mediaUrl("abc123"))
    private val film = Random(3).nextBytes(4 * 1024 * 1024)
    private val drive = Opens(film)
    private val frames = DriveFrames(BoundedDriveSource(drive, DrivePieces(first = 64 * 1024L, most = 256 * 1024L), Ignore), uri)

    private fun at(position: Long, size: Int): ByteArray {
        val out = ByteArray(size)
        var done = 0
        while (done < size) {
            val n = frames.readAt(position + done, out, done, size - done)
            if (n < 0) break
            done += n
        }
        return out.copyOf(done)
    }

    @Test fun itsSizeIsTheFilesAndReadsThatCarryOnAreOneRead() {
        assertEquals(film.size.toLong(), frames.size)
        assertArrayEquals(film.copyOfRange(0, 2048), at(0, 2048))
        assertArrayEquals(film.copyOfRange(2048, 100_000), at(2048, 100_000 - 2048))
        // One read opened (its pieces bounded), from the first byte.
        assertEquals(listOf(0L), drive.opens)
        assertTrue(drive.specs.all { it.length != C.LENGTH_UNSET.toLong() && it.length <= 256 * 1024 })
    }

    @Test fun aStepBackComesFromMemoryAndAStepAheadIsReadThrough() {
        at(0, 300_000)
        // Back 100 KB, inside what's kept.
        assertArrayEquals(film.copyOfRange(200_000, 210_000), at(200_000, 10_000))
        // Ahead 300 KB: read through.
        assertArrayEquals(film.copyOfRange(510_000, 512_000), at(510_000, 2_000))
        assertEquals(listOf(0L), drive.opens)
    }

    @Test fun aJumpIsANewReadStartingSmall() {
        at(0, 4096)
        val near = film.size - 50_000
        assertArrayEquals(film.copyOfRange(near, near + 4096), at(near.toLong(), 4096))
        assertArrayEquals(film.copyOfRange(1_000_000, 1_004_096), at(1_000_000, 4096))
        assertEquals(listOf(0L, near.toLong(), 1_000_000L), drive.opens)
        // Each new read's first piece is the smallest (the one near the end, cut to the file's end).
        assertEquals(64 * 1024L, drive.specs.last().length)
    }

    @Test fun pastTheEndIsTheEnd() {
        assertEquals(-1, frames.readAt(film.size.toLong(), ByteArray(10), 0, 10))
        assertArrayEquals(film.copyOfRange(film.size - 10, film.size), at(film.size - 10L, 100))
        frames.close()
        assertEquals(-1, frames.readAt(0, ByteArray(10), 0, 10))
    }

    private object Ignore : DriveAskSink {
        override fun requested(key: String?, bytes: Long) = Unit
        override fun received(key: String?, bytes: Long) = Unit
        override fun refused(key: String?, http: Int, reason: String?) = Unit
    }

    /** Drive as OkHttpDataSource answers ranges (Content-Range with the size); [opens]: where each new read began. */
    private class Opens(val film: ByteArray) : DataSource {
        val specs = ArrayList<DataSpec>()
        val opens = ArrayList<Long>()
        private var lastEnd = -1L
        private var position = 0L
        private var end = 0L
        private var open: DataSpec? = null

        override fun open(dataSpec: DataSpec): Long {
            specs += dataSpec
            // A piece that carries straight on from the last is the same read.
            if (dataSpec.position != lastEnd) opens += dataSpec.position
            open = dataSpec
            position = dataSpec.position
            end = if (dataSpec.length == C.LENGTH_UNSET.toLong()) film.size.toLong() else minOf(film.size.toLong(), dataSpec.position + dataSpec.length)
            lastEnd = end
            return if (dataSpec.length != C.LENGTH_UNSET.toLong()) dataSpec.length else end - position
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (position >= end) return C.RESULT_END_OF_INPUT
            val n = minOf(length.toLong(), end - position).toInt()
            System.arraycopy(film, position.toInt(), buffer, offset, n)
            position += n
            return n
        }

        override fun getUri(): Uri? = open?.uri
        override fun getResponseHeaders(): Map<String, List<String>> =
            open?.let { mapOf("Content-Range" to listOf("bytes ${it.position}-${end - 1}/${film.size}")) } ?: emptyMap()
        override fun close() { open = null }
        override fun addTransferListener(transferListener: TransferListener) = Unit
    }
}
