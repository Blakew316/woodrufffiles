package com.woodruff.tvexplorer.player

import android.net.Uri
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.Consumer
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.extractor.text.CuesWithTiming
import androidx.media3.extractor.text.DefaultSubtitleParserFactory
import androidx.media3.extractor.text.SubtitleParser
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * Subtitle files read safely. Media3 reads a film's subtitle files alongside the film, and by itself
 * a subtitle file it can't read (deleted from Drive since it was found, a dropped connection, a file
 * that isn't really WebVTT) fails the whole film. Here such a file only leaves the film without
 * those subtitles. And subtitle files saved in Windows' Western encoding (as many .srt files are)
 * keep their accents: Media3 reads only UTF-8 or UTF-16, which turned "Español" into "Espa�ol".
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

    /** [upstream], with subtitle files ([mark]) that can't be read coming back empty instead of failing. */
    fun dataSourceFactory(upstream: DataSource.Factory): DataSource.Factory = DataSource.Factory { SafeSidecarSource(upstream.createDataSource()) }

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
     * reads as empty, and one cut short mid-read ends where it was cut. Everything else passes
     * straight through.
     */
    private class SafeSidecarSource(private val inner: DataSource) : DataSource {
        private var sidecar = false
        private var empty = false
        private var spec: DataSpec? = null

        override fun addTransferListener(transferListener: TransferListener) = inner.addTransferListener(transferListener)

        override fun open(dataSpec: DataSpec): Long {
            spec = dataSpec
            sidecar = isMarked(dataSpec.uri)
            empty = false
            if (!sidecar) return inner.open(dataSpec)
            return try {
                inner.open(dataSpec)
            } catch (e: IOException) {
                Log.w(TAG, "Couldn't read a subtitle file: ${e.javaClass.simpleName}")
                runCatching { inner.close() }
                empty = true
                0
            }
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (empty) return C.RESULT_END_OF_INPUT
            if (!sidecar) return inner.read(buffer, offset, length)
            return try {
                inner.read(buffer, offset, length)
            } catch (e: IOException) {
                Log.w(TAG, "A subtitle file was cut short: ${e.javaClass.simpleName}")
                empty = true
                C.RESULT_END_OF_INPUT
            }
        }

        override fun getUri(): Uri? = if (empty) spec?.uri else inner.uri

        override fun getResponseHeaders(): Map<String, List<String>> = if (empty) emptyMap() else inner.responseHeaders

        override fun close() {
            try {
                // A subtitle file's trouble closing is no more the film's than its trouble reading.
                if (sidecar) runCatching { inner.close() } else inner.close()
            } finally {
                spec = null
                empty = false
                sidecar = false
            }
        }
    }
}
