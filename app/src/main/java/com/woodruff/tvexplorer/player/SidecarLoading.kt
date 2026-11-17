package com.woodruff.tvexplorer.player

import android.net.Uri
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.Consumer
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.extractor.text.CuesWithTiming
import androidx.media3.extractor.text.DefaultSubtitleParserFactory
import androidx.media3.extractor.text.SubtitleParser
import java.io.ByteArrayOutputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * Subtitle files read safely. Media3 reads a film's subtitle files alongside the film, and by itself
 * a subtitle file it can't read (deleted from Drive since it was found, a dropped connection, a file
 * that isn't really WebVTT) fails the whole film. Here such a file only leaves the film without
 * those subtitles, after a few more tries when the trouble may pass (a dropped connection, Drive
 * busy). And subtitle files saved in Windows' Western encoding (as many .srt files are) keep their
 * accents: Media3 reads only UTF-8 or UTF-16, which turned "Español" into "Espa�ol".
 */
@OptIn(UnstableApi::class)
internal object SidecarLoading {
    private const val TAG = "Subtitles"

    /**
     * Marks a subtitle file's address as a sidecar's (in its fragment, which no request carries:
     * OkHttp leaves it off, files are opened by their path), so the player's sources know it.
     */
    fun mark(uri: Uri): Uri = uri.buildUpon().fragment(MARK).build()

    /** Whether [uri] is a subtitle file's ([mark]). */
    fun isMarked(uri: Uri?): Boolean = uri?.fragment == MARK

    private const val MARK = "woodruff-sidecar"

    /**
     * [upstream], with subtitle files ([mark]) that can't be read coming back empty instead of failing,
     * once [RETRY_PAUSES_MS] more tries haven't helped where they might ([worthTryingAgain]). [pause]
     * waits between tries (false: the film is closing, so stop). A subtitle file read in full is kept in
     * memory for as long as this factory (the player's) is in use ([KeptSidecars]): Media3 reads each
     * one again from its start at every rewind past what it still holds (more than 12 s, to before the
     * first line, past the last) and at every try again after a stall, which was a request to Drive
     * for each of a film's subtitle files every time.
     */
    fun dataSourceFactory(upstream: DataSource.Factory, pause: (Long) -> Boolean = ::sleep): DataSource.Factory {
        val kept = KeptSidecars()
        return DataSource.Factory { SafeSidecarSource(upstream.createDataSource(), pause, kept) }
    }

    /**
     * The subtitle files read in full, by address (the player's: as long as it plays). At most
     * [MAX_FILE_BYTES] each (a subtitle file is text: a few hundred KB at most) and [MAX_BYTES] in all,
     * the ones read longest ago making way. Shared by the player's loading threads.
     */
    internal class KeptSidecars(private val maxBytes: Int = MAX_BYTES, private val maxFileBytes: Int = MAX_FILE_BYTES) {
        private val files = LinkedHashMap<String, ByteArray>(8, 0.75f, true)
        private var bytes = 0

        @Synchronized fun get(uri: Uri): ByteArray? = files[uri.toString()]

        /** Whether a file of [size] bytes so far may still be kept. */
        fun fits(size: Int): Boolean = size <= maxFileBytes

        @Synchronized fun put(uri: Uri, data: ByteArray) {
            if (!fits(data.size)) return
            files.remove(uri.toString())?.let { bytes -= it.size }
            files[uri.toString()] = data
            bytes += data.size
            val oldest = files.entries.iterator()
            while (bytes > maxBytes && oldest.hasNext()) {
                val e = oldest.next()
                bytes -= e.value.size
                oldest.remove()
            }
        }
    }

    const val MAX_FILE_BYTES = 4 * 1024 * 1024
    const val MAX_BYTES = 16 * 1024 * 1024

    /**
     * The pauses before each further try at a subtitle file that failed in a way that may pass: a
     * moment's network trouble at a film's start no longer leaves its subtitles empty for the whole
     * film. Taken as the file is read, never while it opens, so the film doesn't wait for them.
     */
    val RETRY_PAUSES_MS = longArrayOf(1_000, 2_000, 4_000)

    /**
     * Whether trying a subtitle file again may work where [e] failed: a dropped or timed-out
     * connection, a name that didn't resolve, no Google token just now, Drive busy (429, 5xx) or
     * asking to try again (408). Not a file that's gone, refused or not allowed (other answers, a file
     * not found), nor the film closing (the loading thread interrupted): those read as empty at once.
     */
    fun worthTryingAgain(e: IOException): Boolean {
        val causes = generateSequence<Throwable>(e) { it.cause }.take(10).toList()
        val http = causes.firstNotNullOfOrNull { it as? HttpDataSource.InvalidResponseCodeException }?.responseCode
        if (http != null) return http == 408 || http == 429 || http >= 500
        if (causes.any { it is FileNotFoundException || (it is InterruptedIOException && it !is SocketTimeoutException) }) return false
        return causes.none { it is DataSourceException && it.reason in NOT_AGAIN }
    }

    /** What another try can't change: the file isn't there, can't be read here, or isn't what was asked for. */
    private val NOT_AGAIN = setOf(
        PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
        PlaybackException.ERROR_CODE_IO_NO_PERMISSION,
        PlaybackException.ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED,
        PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE,
        PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE,
    )

    /** Waits [ms] on the loading thread; false when it's interrupted (the film closing, its loads let go). */
    private fun sleep(ms: Long): Boolean = try {
        Thread.sleep(ms)
        true
    } catch (e: InterruptedException) {
        Thread.currentThread().interrupt()
        false
    }

    /**
     * How the player reads subtitles (in files beside the film, or inside it): Media3's own parsers,
     * given text in UTF-8 ([readable]), and never failing the film over a subtitle that doesn't parse.
     */
    fun parserFactory(inner: SubtitleParser.Factory = DefaultSubtitleParserFactory()): SubtitleParser.Factory = SafeParserFactory(inner)

    /**
     * Subtitle text as UTF-8: as it is when it's UTF-8 already, or starts with a byte-order mark
     * (UTF-8 or UTF-16, which Media3 reads itself); otherwise it was saved in Windows' Western
     * encoding (Windows-1252, the usual one for .srt files in English, Spanish, French, German and
     * the like), and is turned into UTF-8. Visible for tests.
     */
    fun readable(data: ByteArray, offset: Int, length: Int): ByteArray? {
        if (length <= 0 || hasByteOrderMark(data, offset, length)) return null
        val utf8 = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        try {
            utf8.decode(ByteBuffer.wrap(data, offset, length))
            return null
        } catch (e: CharacterCodingException) {
            // Not UTF-8: read below as Windows-1252.
        }
        val text = String(data, offset, length, WESTERN)
        return text.toByteArray(Charsets.UTF_8)
    }

    private fun hasByteOrderMark(d: ByteArray, o: Int, n: Int): Boolean =
        (n >= 3 && d[o] == 0xEF.toByte() && d[o + 1] == 0xBB.toByte() && d[o + 2] == 0xBF.toByte()) ||
            (n >= 2 && ((d[o] == 0xFE.toByte() && d[o + 1] == 0xFF.toByte()) || (d[o] == 0xFF.toByte() && d[o + 1] == 0xFE.toByte())))

    /** Windows' Western encoding, or Latin-1 (its near twin) on a TV that somehow lacks it. */
    private val WESTERN: Charset by lazy { runCatching { Charset.forName("windows-1252") }.getOrDefault(Charsets.ISO_8859_1) }

    /** Formats written as text, in an encoding of their own choosing (the rest are pictures, or say theirs). */
    private val TEXT_FORMATS = setOf(MimeTypes.APPLICATION_SUBRIP, MimeTypes.TEXT_SSA, MimeTypes.TEXT_VTT)

    private class SafeParserFactory(private val inner: SubtitleParser.Factory) : SubtitleParser.Factory {
        override fun supportsFormat(format: Format): Boolean = inner.supportsFormat(format)
        override fun getCueReplacementBehavior(format: Format): Int = inner.getCueReplacementBehavior(format)
        override fun create(format: Format): SubtitleParser = SafeParser(inner.create(format), format.sampleMimeType in TEXT_FORMATS)
    }

    private class SafeParser(private val inner: SubtitleParser, private val text: Boolean) : SubtitleParser {
        override fun parse(data: ByteArray, offset: Int, length: Int, outputOptions: SubtitleParser.OutputOptions, output: Consumer<CuesWithTiming>) {
            try {
                val fixed = if (text) readable(data, offset, length) else null
                if (fixed != null) inner.parse(fixed, 0, fixed.size, outputOptions, output)
                else inner.parse(data, offset, length, outputOptions, output)
            } catch (e: Exception) {
                // A subtitle that doesn't parse (a .vtt without its header, a damaged file) shows nothing.
                Log.w(TAG, "Couldn't read these subtitles", e)
            }
        }

        override fun reset() = inner.reset()
        override fun getCueReplacementBehavior(): Int = inner.cueReplacementBehavior
    }

    /**
     * A source that reads subtitle files ([mark]) like any other, except that one it can't open
     * reads as empty, and one cut short mid-read ends where it was cut, unless trying again may help
     * ([worthTryingAgain]): then it's tried again ([RETRY_PAUSES_MS]), from where it got to. A first
     * try that fails opens as a file of unknown length, so the film starts at once; the tries come as
     * it's read, on its own loading thread. One read in full is kept ([kept]) and read from memory
     * from then on. Everything else passes straight through.
     */
    private class SafeSidecarSource(private val inner: DataSource, private val pause: (Long) -> Boolean, private val kept: KeptSidecars) : DataSource {
        private var sidecar = false
        private var empty = false
        /** Not open: the last try failed, and the next is due as it's read ([reconnect]). */
        private var pending = false
        private var spec: DataSpec? = null
        /** Bytes read so far: a try after a dropped connection carries on from there. */
        private var done = 0L
        /** Further tries made (each after its pause). */
        private var tries = 0
        /** Read from memory instead ([KeptSidecars]): the whole file, read from [memoryAt] up to [memoryEnd]. */
        private var memory: ByteArray? = null
        private var memoryAt = 0
        private var memoryEnd = 0
        /** The file as it's read from its first byte, kept once it has all come; null when it won't be. */
        private var collected: ByteArrayOutputStream? = null

        override fun addTransferListener(transferListener: TransferListener) = inner.addTransferListener(transferListener)

        override fun open(dataSpec: DataSpec): Long {
            spec = dataSpec
            sidecar = isMarked(dataSpec.uri)
            empty = false
            pending = false
            done = 0
            tries = 0
            memory = null
            collected = null
            if (!sidecar) return inner.open(dataSpec)
            // Read in full before: from memory, nothing asked of Drive.
            kept.get(dataSpec.uri)?.let { return fromMemory(it, dataSpec) }
            // Read from its start to its end, it's kept as it comes in.
            if (dataSpec.position == 0L && dataSpec.length == C.LENGTH_UNSET.toLong()) collected = ByteArrayOutputStream()
            return try {
                inner.open(dataSpec)
            } catch (e: IOException) {
                runCatching { inner.close() }
                if (worthTryingAgain(e)) {
                    Log.w(TAG, "Couldn't read a subtitle file, trying again: ${e.javaClass.simpleName}")
                    pending = true
                    C.LENGTH_UNSET.toLong()
                } else {
                    Log.w(TAG, "Couldn't read a subtitle file: ${e.javaClass.simpleName}")
                    empty = true
                    collected = null
                    0
                }
            }
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            memory?.let { whole ->
                if (memoryAt >= memoryEnd) return C.RESULT_END_OF_INPUT
                val n = minOf(length, memoryEnd - memoryAt)
                System.arraycopy(whole, memoryAt, buffer, offset, n)
                memoryAt += n
                return n
            }
            if (empty) return C.RESULT_END_OF_INPUT
            if (!sidecar) return inner.read(buffer, offset, length)
            while (true) {
                if (pending && !reconnect()) return C.RESULT_END_OF_INPUT
                try {
                    val n = inner.read(buffer, offset, length)
                    if (n > 0) {
                        done += n
                        collect(buffer, offset, n)
                    } else if (n == C.RESULT_END_OF_INPUT) keepWhole()
                    return n
                } catch (e: IOException) {
                    runCatching { inner.close() }
                    if (!worthTryingAgain(e) || tries >= RETRY_PAUSES_MS.size) {
                        Log.w(TAG, "A subtitle file was cut short: ${e.javaClass.simpleName}")
                        empty = true
                        collected = null // cut short: not the whole file
                        return C.RESULT_END_OF_INPUT
                    }
                    Log.w(TAG, "A subtitle file was cut short, trying again: ${e.javaClass.simpleName}")
                    pending = true
                }
            }
        }

        /** [whole] (kept in memory) as the answer to [dataSpec]: the part it asks for. */
        private fun fromMemory(whole: ByteArray, dataSpec: DataSpec): Long {
            memory = whole
            memoryAt = dataSpec.position.coerceIn(0L, whole.size.toLong()).toInt()
            memoryEnd = if (dataSpec.length == C.LENGTH_UNSET.toLong()) whole.size
            else minOf(whole.size.toLong(), memoryAt + dataSpec.length).toInt()
            return (memoryEnd - memoryAt).toLong()
        }

        /** [n] more bytes of the file, for keeping it ([collected]); a file too big to keep stops being collected. */
        private fun collect(b: ByteArray, off: Int, n: Int) {
            val c = collected ?: return
            if (!kept.fits(c.size() + n)) { collected = null; return }
            c.write(b, off, n)
        }

        /** The whole file has come: kept for the next time it's read. */
        private fun keepWhole() {
            val c = collected ?: return
            collected = null
            spec?.let { kept.put(it.uri, c.toByteArray()) }
        }

        /**
         * The next tries, each after its pause, from where reading got to: true once one opens; false
         * when none did, or the film is closing ([pause] said so), and it reads as ending here.
         */
        private fun reconnect(): Boolean {
            val at = spec
            while (at != null && tries < RETRY_PAUSES_MS.size) {
                if (!pause(RETRY_PAUSES_MS[tries++])) break
                try {
                    inner.open(if (done > 0) at.subrange(done) else at)
                    pending = false
                    return true
                } catch (e: IOException) {
                    runCatching { inner.close() }
                    if (!worthTryingAgain(e)) break
                }
            }
            Log.w(TAG, "Couldn't read a subtitle file: it stays empty for this start")
            pending = false
            empty = true
            collected = null
            return false
        }

        override fun getUri(): Uri? = if (empty || pending || memory != null) spec?.uri else inner.uri

        override fun getResponseHeaders(): Map<String, List<String>> = if (empty || pending || memory != null) emptyMap() else inner.responseHeaders

        override fun close() {
            try {
                // A subtitle file's trouble closing is no more the film's than its trouble reading.
                // One read from memory never opened anything.
                if (memory == null) { if (sidecar) runCatching { inner.close() } else inner.close() }
            } finally {
                spec = null
                empty = false
                pending = false
                sidecar = false
                done = 0
                tries = 0
                memory = null
                collected = null
            }
        }
    }
}
