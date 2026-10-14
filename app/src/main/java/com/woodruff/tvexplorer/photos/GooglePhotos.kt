package com.woodruff.tvexplorer.photos

import android.content.Context
import android.os.Environment
import android.util.Log
import com.woodruff.tvexplorer.cloud.GoogleAuth
import com.woodruff.tvexplorer.data.FileOperations
import com.woodruff.tvexplorer.screensaver.Appearance
import com.woodruff.tvexplorer.server.CompanionHub
import com.woodruff.tvexplorer.server.TransferService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Google Photos for the slideshow, through Google's Photos Picker (since 2025 apps can no longer
 * browse a whole library). The TV shows a QR code, the owner picks photos on their phone in
 * Google's own picker, and the TV downloads them into Pictures/Google Photos and points the
 * slideshow there. Downloading matters: picked items can only be fetched for a short time.
 */
object GooglePhotos {
    enum class Step { NEED_GOOGLE, SIGN_IN, READY, PICKING, DOWNLOADING, DONE }

    data class State(
        val step: Step = Step.NEED_GOOGLE,
        val qrUrl: String? = null,
        val done: Int = 0,
        val total: Int = 0,
        val added: Int = 0,
        val error: String? = null,
    )

    private const val API = "https://photospicker.googleapis.com/v1/"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state
    private lateinit var app: Context
    private var job: Job? = null
    @Volatile private var sessionId: String? = null

    val folder: File get() = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "Google Photos")

    fun init(c: Context) {
        app = c.applicationContext
        scope.launch {
            CompanionHub.googleCodes.collect { (code, attempt) ->
                if (attempt.grant != GoogleAuth.Grant.PHOTOS) return@collect
                try {
                    GoogleAuth.exchange(app, code, attempt)
                    startPicking() // signed in on the phone: go straight to choosing photos
                } catch (e: Exception) {
                    _state.update { it.copy(error = e.message ?: "Sign-in failed") }
                }
            }
        }
    }

    fun isConnected(c: Context) = GoogleAuth.isConnected(c, GoogleAuth.Grant.PHOTOS)

    /** Called when the Google Photos window opens: sign in if needed, else start a new pick. */
    fun open() {
        _state.value = State()
        when {
            !GoogleAuth.isConfigured(app) -> _state.value = State(Step.NEED_GOOGLE)
            !isConnected(app) -> startSignIn()
            else -> startPicking()
        }
    }

    private fun startSignIn() {
        job?.cancel()
        job = scope.launch {
            _state.value = State(Step.SIGN_IN)
            TransferService.start(app)
            val server = TransferService.state.first { it.running || it.error != null }
            val base = server.base ?: return@launch _state.update { it.copy(error = server.error ?: "The phone connection couldn't start.") }
            val a = GoogleAuth.newAttempt(app, base, GoogleAuth.Grant.PHOTOS)
            CompanionHub.expect(a)
            _state.value = State(Step.SIGN_IN, qrUrl = a.authUrl)
        }
    }

    fun startPicking() {
        job?.cancel()
        job = scope.launch {
            try {
                _state.value = State(Step.READY)
                val session = withContext(Dispatchers.IO) { call("POST", "sessions", "{}") }
                val id = session.getString("id").also { sessionId = it }
                // "/autoclose" closes Google's picker on the phone as soon as the selection is done.
                _state.value = State(Step.PICKING, qrUrl = session.getString("pickerUri").trimEnd('/') + "/autoclose")
                val poll = seconds(session.optJSONObject("pollingConfig")?.optString("pollInterval"), 4.0)
                val deadline = System.currentTimeMillis() +
                    (seconds(session.optJSONObject("pollingConfig")?.optString("timeoutIn"), 1800.0) * 1000).toLong()
                while (true) {
                    delay((poll * 1000).toLong().coerceIn(2_000, 15_000))
                    val s = withContext(Dispatchers.IO) { call("GET", "sessions/$id", null) }
                    if (s.optBoolean("mediaItemsSet")) break
                    if (System.currentTimeMillis() > deadline) throw IOException("The picker timed out. Try again.")
                }
                download(id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("GooglePhotos", "pick failed", e)
                _state.update { it.copy(error = e.message ?: "Couldn't get photos") }
            } finally {
                endSession()
            }
        }
    }

    private suspend fun download(session: String) {
        val items = mutableListOf<Pair<String, String>>() // baseUrl to file name
        var page: String? = null
        do {
            val q = "mediaItems?sessionId=${enc(session)}&pageSize=100" + (page?.let { "&pageToken=${enc(it)}" } ?: "")
            val r = withContext(Dispatchers.IO) { call("GET", q, null) }
            val arr = r.optJSONArray("mediaItems")
            if (arr != null) for (i in 0 until arr.length()) {
                val m = arr.getJSONObject(i)
                if (m.optString("type") == "VIDEO") continue // the slideshow shows photos
                val f = m.optJSONObject("mediaFile") ?: continue
                val name = f.optString("filename").ifBlank { m.optString("id") + ".jpg" }
                items += f.getString("baseUrl") to name
            }
            page = r.optString("nextPageToken").ifBlank { null }
        } while (page != null)

        _state.value = State(Step.DOWNLOADING, done = 0, total = items.size)
        val dir = folder.apply { mkdirs() }
        var added = 0
        items.forEachIndexed { i, (url, name) ->
            withContext(Dispatchers.IO) {
                val target = FileOperations.uniqueTarget(dir, jpegName(name))
                val tmp = File(dir, ".${target.name}.part")
                try {
                    // Sized for a TV: sharp at 4K, small enough to keep storage and decoding light.
                    val conn = open("$url=w3840-h2160", "GET")
                    if (conn.responseCode !in 200..299) throw IOException("HTTP ${conn.responseCode}")
                    conn.inputStream.use { input -> tmp.outputStream().use { input.copyTo(it) } }
                    conn.disconnect()
                    if (tmp.renameTo(target)) added++
                } catch (e: Exception) {
                    Log.w("GooglePhotos", "download failed: $name", e)
                } finally {
                    tmp.delete()
                }
            }
            _state.update { it.copy(done = i + 1, added = added) }
        }
        Appearance.setSlideshowFolder(app, dir.path)
        _state.value = State(Step.DONE, done = items.size, total = items.size, added = added)
    }

    /** Leaves the window: stops waiting for a pick and tells Google to drop the session. */
    fun cancel() {
        job?.cancel()
        scope.launch { endSession() }
    }

    fun signOut(done: () -> Unit = {}) {
        scope.launch {
            GoogleAuth.signOut(app, GoogleAuth.Grant.PHOTOS)
            _state.value = State()
            done()
        }
    }

    private suspend fun endSession() {
        val id = sessionId ?: return
        sessionId = null
        withContext(NonCancellable + Dispatchers.IO) { runCatching { call("DELETE", "sessions/$id", null) } }
    }

    /** Durations come as "5s" or "1800s". */
    private fun seconds(s: String?, default: Double) = s?.removeSuffix("s")?.toDoubleOrNull() ?: default

    /** The download is always a JPEG at the requested size, whatever the original was (HEIC, PNG…). */
    private fun jpegName(name: String): String {
        val base = name.substringAfterLast('/').substringBeforeLast('.').ifBlank { "photo" }.take(80)
        return "$base.jpg"
    }

    private fun open(url: String, method: String): HttpURLConnection {
        val token = GoogleAuth.blockingToken(app, GoogleAuth.Grant.PHOTOS)
            ?: throw IOException("Google Photos sign-in expired. Connect again.")
        return (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15_000
            readTimeout = 60_000
            setRequestProperty("Authorization", "Bearer $token")
        }
    }

    private fun call(method: String, path: String, body: String?): JSONObject {
        val conn = open(API + path, method)
        try {
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(body.toByteArray()) }
            }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code in 200..299) return if (text.isBlank()) JSONObject() else JSONObject(text)
            val msg = runCatching { JSONObject(text).optJSONObject("error")?.optString("message") }.getOrNull().orEmpty()
            throw IOException(
                when (code) {
                    401 -> "Google Photos sign-in expired. Connect again."
                    403 -> "Google refused ($msg). Enable the Google Photos Picker API in Google Cloud."
                    else -> "Google Photos error $code${if (msg.isNotBlank()) ": $msg" else ""}"
                },
            )
        } finally {
            conn.disconnect()
        }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
}
