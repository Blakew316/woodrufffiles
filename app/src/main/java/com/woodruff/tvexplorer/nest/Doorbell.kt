package com.woodruff.tvexplorer.nest

import android.content.Context
import android.util.Base64
import android.util.Log
import com.woodruff.tvexplorer.cloud.GoogleAuth
import com.woodruff.tvexplorer.notify.NoticeSource
import com.woodruff.tvexplorer.notify.Notices
import com.woodruff.tvexplorer.tvs.TvLinks
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Doorbell alerts. Google only announces a doorbell press as an event on a Cloud Pub/Sub topic
 * (there is nothing to poll on the camera itself), so one TV, the one with the subscription set,
 * pulls that subscription. On a ring it pops up a notice (over other apps too), opens the doorbell
 * full screen when Woodruff is on screen, and tells every linked TV to do the same.
 *
 * Only one TV should have the subscription: Pub/Sub hands each message to one puller.
 */
object Doorbell {
    private const val PREFS = "doorbell"
    private const val EVENT = "sdm.devices.events.DoorbellChime.Chime"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    private val _rings = MutableSharedFlow<String>(extraBufferCapacity = 4)
    /** Camera resource names that just rang, for the app to show full screen. */
    val rings: SharedFlow<String> = _rings

    private val _status = MutableStateFlow<String?>(null)
    /** Why alerts aren't working, when they aren't (shown in Settings). */
    val status: StateFlow<String?> = _status

    fun subscription(c: Context): String? =
        c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("subscription", null)?.takeIf { it.isNotBlank() }

    fun setSubscription(c: Context, value: String?) {
        val clean = value?.trim().orEmpty()
        require(clean.isEmpty() || Regex("^projects/[^/]+/subscriptions/[^/]+$").matches(clean)) {
            "Enter the full subscription name: projects/your-project/subscriptions/your-subscription"
        }
        c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("subscription", clean).apply()
        _status.value = null
        start(c)
    }

    /** A doorbell rang: here, and (when [relay]) on every linked TV. */
    fun ring(c: Context, camera: String, label: String, relay: Boolean) {
        val app = c.applicationContext
        Notices.post(app, NoticeSource.SYSTEM, "Someone's at the door", label.ifBlank { "Doorbell" })
        _rings.tryEmit(camera)
        if (relay) scope.launch { TvLinks.forEachLinked(app) { TvLinks.doorbell(it, camera, label) } }
    }

    /** Starts (or restarts) watching for rings; called when the phone-connection service starts. */
    fun start(c: Context) {
        val app = c.applicationContext
        job?.cancel()
        job = scope.launch {
            while (isActive) {
                val sub = subscription(app)
                if (sub == null) { delay(60_000); continue }
                val wait = runCatching { pullOnce(app, sub) }.getOrElse { e ->
                    Log.w("Doorbell", "pull failed: ${e.message}")
                    _status.value = e.message
                    if (e is ForbiddenException) 5 * 60_000L else 30_000L
                }
                delay(wait)
            }
        }
    }

    private class ForbiddenException(msg: String) : IOException(msg)

    /** One pull: handles any rings, acknowledges everything. Returns how long to wait before the next. */
    private fun pullOnce(c: Context, sub: String): Long {
        val token = GoogleAuth.blockingToken(c, GoogleAuth.Grant.NEST)
            ?: throw IOException("Connect your cameras first")
        val res = post(token, "https://pubsub.googleapis.com/v1/$sub:pull", JSONObject().put("maxMessages", 10))
        val messages = res.optJSONArray("receivedMessages") ?: JSONArray()
        _status.value = null
        if (messages.length() == 0) return 3_000
        val acks = JSONArray()
        for (i in 0 until messages.length()) {
            val m = messages.getJSONObject(i)
            acks.put(m.optString("ackId"))
            val data = m.optJSONObject("message")?.optString("data").orEmpty()
            val event = runCatching { JSONObject(String(Base64.decode(data, Base64.DEFAULT))) }.getOrNull() ?: continue
            val update = event.optJSONObject("resourceUpdate") ?: continue
            if (update.optJSONObject("events")?.has(EVENT) != true) continue
            // A backlog from while the TV was off isn't news any more.
            val at = runCatching {
                java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US)
                    .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
                    .parse(event.optString("timestamp").take(19))?.time
            }.getOrNull() ?: System.currentTimeMillis()
            if (System.currentTimeMillis() - at > 90_000) continue
            val camera = update.optString("name")
            val label = Nest.state.value.cameras.firstOrNull { it.name == camera }?.label ?: "Doorbell"
            ring(c, camera, label, relay = true)
        }
        if (acks.length() > 0) post(token, "https://pubsub.googleapis.com/v1/$sub:acknowledge", JSONObject().put("ackIds", acks))
        return 0
    }

    private fun post(token: String, url: String, body: JSONObject): JSONObject {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 70_000
            doOutput = true
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Content-Type", "application/json")
        }
        try {
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code in 200..299) return runCatching { JSONObject(text) }.getOrDefault(JSONObject())
            throw when (code) {
                401, 403 -> ForbiddenException("Google refused the doorbell subscription. Connect the cameras again (to allow Pub/Sub) and check the subscription name.")
                404 -> IOException("Subscription not found. Check its name.")
                else -> IOException("Pub/Sub error $code")
            }
        } finally {
            conn.disconnect()
        }
    }
}
