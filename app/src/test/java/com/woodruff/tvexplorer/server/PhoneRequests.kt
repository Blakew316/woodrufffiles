package com.woodruff.tvexplorer.server

import fi.iki.elonen.NanoHTTPD
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.InputStream

/**
 * A request as NanoHTTPD hands it to the routes, for tests: its address, method, query parameters, headers and body
 * (JSON, or raw bytes; [length] can claim more than is sent, as a big upload's Content-Length does).
 */
internal fun phoneRequest(
    uri: String,
    method: NanoHTTPD.Method = NanoHTTPD.Method.GET,
    params: Map<String, String> = emptyMap(),
    body: JSONObject? = null,
    raw: ByteArray? = null,
    headers: Map<String, String> = emptyMap(),
    length: Long? = null,
): NanoHTTPD.IHTTPSession {
    val bytes = raw ?: body?.toString()?.toByteArray() ?: ByteArray(0)
    val all = mapOf("content-length" to (length ?: bytes.size.toLong()).toString(), "host" to "living-room.local:8080") + headers
    return object : NanoHTTPD.IHTTPSession {
        override fun execute() {}
        override fun getCookies(): NanoHTTPD.CookieHandler? = null
        override fun getHeaders(): Map<String, String> = all
        override fun getInputStream(): InputStream = ByteArrayInputStream(bytes)
        override fun getMethod() = method
        @Deprecated("") override fun getParms(): Map<String, String> = params
        override fun getParameters(): Map<String, List<String>> = params.mapValues { listOf(it.value) }
        override fun getQueryParameterString(): String = ""
        override fun getUri() = uri
        override fun parseBody(files: MutableMap<String, String>?) {}
        override fun getRemoteIpAddress() = "127.0.0.1"
        override fun getRemoteHostName() = "localhost"
    }
}

/** The routes' answer, a refusal in plain words becoming a 400 as [TransferServer] makes it. */
internal fun CompanionRoutes.answerFor(s: NanoHTTPD.IHTTPSession): NanoHTTPD.Response = try {
    handle(s) ?: NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.NOT_FOUND, "text/plain", "")
} catch (e: IllegalArgumentException) {
    NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.BAD_REQUEST, "text/plain", e.message)
}

internal fun NanoHTTPD.Response.bodyText(): String = data.bufferedReader().readText()
