package com.woodruff.tvexplorer.server

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import com.woodruff.tvexplorer.cloud.DriveApi
import com.woodruff.tvexplorer.data.FileTypes
import com.woodruff.tvexplorer.net.Http
import com.woodruff.tvexplorer.player.DataSourceBody
import com.woodruff.tvexplorer.player.DriveAsks
import com.woodruff.tvexplorer.player.MediaRef
import com.woodruff.tvexplorer.player.boundedDrive
import com.woodruff.tvexplorer.player.documentSize
import com.woodruff.tvexplorer.player.driveAuthorized
import fi.iki.elonen.NanoHTTPD.Response
import fi.iki.elonen.NanoHTTPD.newChunkedResponse
import fi.iki.elonen.NanoHTTPD.newFixedLengthResponse
import java.io.File
import java.io.FileInputStream
import java.io.IOException

/**
 * A film or episode played on the phone itself (the Woodruff app's "Play on iPhone"): the file, or the part of it the
 * phone's player asks for ("Range: bytes=…", so it can start anywhere and seek), from this TV's storage or, for a
 * Drive video, from Drive with this TV's sign-in.
 */
internal object PhoneStream {

    /** The bytes asked for in a "bytes=a-b", "bytes=a-" or "bytes=-n" header, within [length]; null when it asks for none of them. */
    fun parseRange(header: String?, length: Long): LongRange? {
        if (header == null || length <= 0) return null
        val spec = header.trim().removePrefix("bytes=").substringBefore(',').trim()
        val dash = spec.indexOf('-')
        if (!header.trim().startsWith("bytes=") || dash < 0) return null
        val a = spec.substring(0, dash).trim()
        val b = spec.substring(dash + 1).trim()
        return when {
            a.isEmpty() -> {
                val n = b.toLongOrNull() ?: return null
                if (n <= 0) null else maxOf(0, length - n)..(length - 1)
            }
            else -> {
                val start = a.toLongOrNull() ?: return null
                if (start >= length) return null
                val end = if (b.isEmpty()) length - 1 else (b.toLongOrNull() ?: return null).coerceAtMost(length - 1)
                if (end < start) null else start..end
            }
        }
    }

    fun file(f: File, range: String?): Response {
        val length = f.length()
        val mime = FileTypes.mimeOf(f.name)
        val r = parseRange(range, length)
        if (range != null && r == null) {
            return newFixedLengthResponse(Response.Status.RANGE_NOT_SATISFIABLE, "text/plain", "")
                .apply { addHeader("Content-Range", "bytes */$length") }
        }
        if (r == null) {
            return newFixedLengthResponse(Response.Status.OK, mime, FileInputStream(f), length).apply { addHeader("Accept-Ranges", "bytes") }
        }
        val input = FileInputStream(f).apply { channel.position(r.first) }
        val count = r.last - r.first + 1
        return newFixedLengthResponse(Response.Status.PARTIAL_CONTENT, mime, input, count).apply {
            addHeader("Accept-Ranges", "bytes")
            addHeader("Content-Range", "bytes ${r.first}-${r.last}/$length")
        }
    }

    /**
     * A Drive video, the part the phone asks for ("bytes=a-b", "bytes=a-" or all of it), answered as Drive would, with
     * this TV's sign-in ([token], asked afresh for each request). Google itself is asked in bounded pieces
     * ([boundedDrive]), however much the phone asks for: its player asks for everything from a spot to the end of the
     * film and hangs up as it seeks, and Google may count each of those as the rest of the film against its daily
     * download allowance. A phone that hangs up asks Google for nothing more. Any other Range ("bytes=-n", several) is
     * passed on as it is ([driveAsAsked]): it's bounded already, or Drive turns it down.
     */
    @OptIn(UnstableApi::class)
    fun drive(id: String, range: String?, token: () -> String, upstream: DataSource.Factory = OkHttpDataSource.Factory(Http.client)): Response {
        val asked = if (range == null) 0L to null else askedRange(range) ?: return driveAsAsked(id, range, token())
        val (start, last) = asked
        DriveAsks.onPhone(MediaRef.Drive(id).key)
        val source = boundedDrive(driveAuthorized(upstream, token)).createDataSource()
        val spec = DataSpec.Builder().setUri(Uri.parse(DriveApi.mediaUrl(id))).setPosition(start)
            .setLength(if (last == null) C.LENGTH_UNSET.toLong() else last - start + 1).build()
        val opened = try {
            source.open(spec)
        } catch (e: IOException) {
            runCatching { source.close() }
            val code = generateSequence<Throwable>(e) { it.cause }.take(10).filterIsInstance<HttpDataSource.InvalidResponseCodeException>().firstOrNull()?.responseCode
            throw if (code == null) e else answered(code)
        }
        val headers = source.responseHeaders
        val total = documentSize(headers)
        val type = headers.entries.firstOrNull { it.key?.equals("Content-Type", ignoreCase = true) == true }?.value?.firstOrNull()
            ?.takeUnless { it.startsWith("application/octet-stream") } ?: "video/mp4"
        val end = when {
            total != C.LENGTH_UNSET.toLong() -> minOf(last ?: (total - 1), total - 1)
            last != null -> last
            opened != C.LENGTH_UNSET.toLong() -> start + opened - 1
            else -> null
        }
        if (end != null && end < start) {
            runCatching { source.close() }
            throw IOException("Past the end of the video")
        }
        val body = DataSourceBody(source)
        val status = if (range != null) Response.Status.PARTIAL_CONTENT else Response.Status.OK
        val r = if (end != null) newFixedLengthResponse(status, type, body, end - start + 1) else newChunkedResponse(status, type, body)
        r.addHeader("Accept-Ranges", "bytes")
        if (range != null && end != null && total != C.LENGTH_UNSET.toLong()) r.addHeader("Content-Range", "bytes $start-$end/$total")
        return r
    }

    /**
     * Google Drive turned the phone's request down with [status]: 403 (one of its limits, its daily one among them, or
     * the file refused) or 429 (too many requests). The phone hears the same answer ([TransferServer] passes it on), not
     * 500, "the TV failed", which its player takes as worth asking again, and again, against the same limit.
     */
    internal class DriveRefused(val status: Int, message: String) : IOException(message)

    /** What the phone is told when Drive answered [code] instead of the video. Visible for tests. */
    internal fun answered(code: Int): IOException = when (code) {
        403, 429 -> DriveRefused(code, "Google Drive answered $code")
        416 -> IOException("Past the end of the video")
        else -> IOException("Google Drive answered $code")
    }

    /** The one range in [header] ("bytes=a-b", "bytes=a-": its first byte and its last, null for "to the end"); null for any other. */
    internal fun askedRange(header: String): Pair<Long, Long?>? {
        val m = Regex("""bytes=(\d+)-(\d*)""").matchEntire(header.trim()) ?: return null
        val start = m.groupValues[1].toLongOrNull() ?: return null
        val last = m.groupValues[2].takeIf { it.isNotEmpty() }?.let { it.toLongOrNull() ?: return null }
        if (last != null && last < start) return null
        return start to last
    }

    /** A Drive video passed through as Drive sends it, for a Range [askedRange] doesn't take (Drive answers ranges itself). */
    private fun driveAsAsked(id: String, range: String?, token: String): Response {
        val c = Http.open(DriveApi.mediaUrl(id))
        c.connectTimeout = 15_000
        c.readTimeout = 30_000
        c.setRequestProperty("Authorization", "Bearer $token")
        if (range != null) c.setRequestProperty("Range", range)
        val code = c.responseCode
        if (code != 200 && code != 206) {
            c.disconnect()
            throw answered(code)
        }
        val length = c.getHeaderField("Content-Length")?.toLongOrNull() ?: -1L
        val type = c.contentType?.takeUnless { it.startsWith("application/octet-stream") } ?: "video/mp4"
        val status = if (code == 206) Response.Status.PARTIAL_CONTENT else Response.Status.OK
        val r = if (length >= 0) newFixedLengthResponse(status, type, c.inputStream, length) else newChunkedResponse(status, type, c.inputStream)
        r.addHeader("Accept-Ranges", "bytes")
        c.getHeaderField("Content-Range")?.let { r.addHeader("Content-Range", it) }
        return r
    }
}
