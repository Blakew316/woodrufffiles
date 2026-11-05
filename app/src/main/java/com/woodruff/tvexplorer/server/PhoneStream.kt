package com.woodruff.tvexplorer.server

import com.woodruff.tvexplorer.cloud.DriveApi
import com.woodruff.tvexplorer.data.FileTypes
import fi.iki.elonen.NanoHTTPD.Response
import fi.iki.elonen.NanoHTTPD.newChunkedResponse
import fi.iki.elonen.NanoHTTPD.newFixedLengthResponse
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

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

    /** A Drive video, passed through as Drive sends it (Drive answers ranges itself). */
    fun drive(id: String, range: String?, token: String): Response {
        val c = URL(DriveApi.mediaUrl(id)).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000
        c.readTimeout = 30_000
        c.setRequestProperty("Authorization", "Bearer $token")
        if (range != null) c.setRequestProperty("Range", range)
        val code = c.responseCode
        if (code != 200 && code != 206) {
            c.disconnect()
            throw IOException(if (code == 416) "Past the end of the video" else "Google Drive answered $code")
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
