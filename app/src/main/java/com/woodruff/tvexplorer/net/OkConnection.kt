package com.woodruff.tvexplorer.net

import okhttp3.Call
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.ByteArrayOutputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.ProtocolException
import java.net.URL
import java.util.concurrent.TimeUnit

/**
 * A connection read and written the way Android's own HttpURLConnection is (request properties, an
 * output stream, the answer's code, its input or error stream), sent through [client] ([Http.open]).
 *
 * Android's own tries a host's addresses in the order Android gives them, each for the whole
 * connect timeout: on a home network whose IPv6 doesn't get out, a listing, a camera or a poster
 * waited on every IPv6 address before the first IPv4 one (see [Ipv4First]). The calls that used it
 * keep their code; only where the request goes through changes.
 *
 * It behaves as Android's does where those calls can tell: the request is sent when the answer is
 * first asked for; GET becomes POST once a body is written; a body with no type is a form;
 * redirects are followed unless turned off, never between http and https, and never taking
 * Authorization to another host; an answer of 400 or more is read from the error stream. The body
 * is kept in memory until it's sent, so this is for small ones.
 */
internal class OkConnection(url: URL, private val client: OkHttpClient) : HttpURLConnection(url) {
    private val asked = Headers.Builder()
    private var body: ByteArrayOutputStream? = null
    @Volatile private var call: Call? = null
    @Volatile private var answer: Response? = null
    private var failed: IOException? = null

    private val begun get() = answer != null || failed != null

    override fun setRequestMethod(method: String) {
        if (begun) throw ProtocolException("Can't reset method: already connected")
        if (method !in METHODS) throw ProtocolException("Invalid HTTP method: $method")
        this.method = method
    }

    override fun setRequestProperty(key: String, value: String) {
        check(!begun) { "Already connected" }
        asked[key] = value
    }

    override fun addRequestProperty(key: String, value: String) {
        check(!begun) { "Already connected" }
        asked.add(key, value)
    }

    override fun getRequestProperty(key: String): String? = asked[key]

    override fun getRequestProperties(): Map<String, List<String>> {
        check(!begun) { "Already connected" }
        return fields(asked.build())
    }

    override fun getOutputStream(): OutputStream {
        if (!doOutput) throw ProtocolException("cannot write to a URLConnection if doOutput=false - call setDoOutput(true)")
        if (begun) throw ProtocolException("cannot write request body after response has been read")
        if (method == "GET") method = "POST"
        return body ?: ByteArrayOutputStream().also { body = it }
    }

    /** Nothing is sent before the answer is asked for: a body may still be written after this. */
    override fun connect() {}

    override fun disconnect() {
        val got = answer
        if (got != null) got.close() else call?.cancel()
    }

    override fun usingProxy(): Boolean = false

    override fun getResponseCode(): Int = answer().code

    override fun getResponseMessage(): String = answer().message

    override fun getInputStream(): InputStream {
        val got = answer()
        if (got.code >= HTTP_BAD_REQUEST) throw FileNotFoundException(url.toString())
        return got.body!!.byteStream()
    }

    override fun getErrorStream(): InputStream? = answer?.takeIf { it.code >= HTTP_BAD_REQUEST }?.body?.byteStream()

    override fun getHeaderField(name: String?): String? = name?.let { answered()?.header(it) }

    override fun getHeaderField(n: Int): String? = answered()?.headers?.let { if (n in 0 until it.size) it.value(n) else null }

    override fun getHeaderFieldKey(n: Int): String? = answered()?.headers?.let { if (n in 0 until it.size) it.name(n) else null }

    override fun getHeaderFields(): Map<String, List<String>> = answered()?.headers?.let { fields(it) } ?: emptyMap()

    /** The answer, or null when there is none to read (as Android's header readers say it). */
    private fun answered(): Response? = try { answer() } catch (e: IOException) { null }

    /** The answer: the request is sent the first time this is asked, and what went wrong is told every time. */
    @Synchronized
    private fun answer(): Response {
        answer?.let { return it }
        failed?.let { throw it }
        connected = true
        try {
            val to = url.toString().toHttpUrlOrNull() ?: throw IOException("Not a web address")
            val written = body?.toByteArray()
            val sent = when {
                method == "GET" || method == "HEAD" -> null
                written != null -> written
                // OkHttp wants a body for these, even an empty one (Android's sends "Content-Length: 0").
                method in WITH_BODY -> ByteArray(0)
                else -> null
            }
            if (sent != null && asked["Content-Type"] == null) asked["Content-Type"] = "application/x-www-form-urlencoded"
            // The type goes as a header the caller set, so the body itself carries none.
            val request = Request.Builder().url(to).headers(asked.build()).method(method, sent?.toRequestBody(null)).build()
            val one = client.newBuilder()
                .connectTimeout(connectTimeout.toLong(), TimeUnit.MILLISECONDS)
                .readTimeout(readTimeout.toLong(), TimeUnit.MILLISECONDS)
                .followRedirects(instanceFollowRedirects)
                .followSslRedirects(false)
                .build()
            val made = one.newCall(request)
            call = made
            return made.execute().also { answer = it }
        } catch (e: IOException) {
            failed = e
            throw e
        } catch (e: IllegalArgumentException) {
            // A header or address OkHttp won't send: to the caller, a request that failed.
            throw IOException(e.message ?: "The request couldn’t be made", e).also { failed = it }
        }
    }

    private fun fields(headers: Headers): Map<String, List<String>> = headers.names().associateWith { headers.values(it) }

    private companion object {
        val METHODS = setOf("GET", "POST", "HEAD", "OPTIONS", "PUT", "DELETE", "TRACE", "PATCH")
        val WITH_BODY = setOf("POST", "PUT", "PATCH")
    }
}
