package com.woodruff.tvexplorer.nest

import com.woodruff.tvexplorer.net.Http
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection

/**
 * Google Cloud Pub/Sub's REST API, as the doorbell uses it: reading a subscription's topic, making
 * and deleting a TV's own subscription, and pulling and acknowledging messages. Blocking: for the
 * doorbell's background thread. [token] is the cameras' sign-in (it asks for Pub/Sub access).
 */
internal object PubSub {
    private const val BASE = "https://pubsub.googleapis.com/v1/"

    /** Google answered [code] (403, 404, 409…); [status] is its reason ("PERMISSION_DENIED"), for the log. */
    class Answer(val code: Int, val status: String) : IOException("Pub/Sub answered $code $status")

    /**
     * Messages waiting on [subscription] (up to 10; Google holds the request a while when there are none).
     * [opened]: the connection it waits on, so it can be cut short (disconnected) from another thread.
     */
    fun pull(token: String, subscription: String, opened: (HttpURLConnection) -> Unit = {}): JSONArray =
        call(token, "POST", "$subscription:pull", JSONObject().put("maxMessages", 10), readTimeoutMs = 70_000, opened = opened)
            .optJSONArray("receivedMessages") ?: JSONArray()

    /** Tells Google the messages [ackIds] arrived, so they aren't delivered again. */
    fun acknowledge(token: String, subscription: String, ackIds: JSONArray) {
        call(token, "POST", "$subscription:acknowledge", JSONObject().put("ackIds", ackIds))
    }

    /** The subscription [name] as Google describes it (its "topic", among others). */
    fun subscription(token: String, name: String): JSONObject = call(token, "GET", name, null)

    /** Makes the subscription [name] as [body] says. */
    fun create(token: String, name: String, body: JSONObject): JSONObject = call(token, "PUT", name, body)

    /** Deletes the subscription [name]. */
    fun delete(token: String, name: String) {
        call(token, "DELETE", name, null)
    }

    private fun call(
        token: String, method: String, path: String, body: JSONObject?, readTimeoutMs: Int = 20_000, opened: (HttpURLConnection) -> Unit = {},
    ): JSONObject {
        val conn = Http.open(BASE + path).apply {
            requestMethod = method
            connectTimeout = 15_000
            readTimeout = readTimeoutMs
            setRequestProperty("Authorization", "Bearer $token")
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
        }
        opened(conn)
        try {
            if (body != null) conn.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            val json = runCatching { JSONObject(text) }.getOrDefault(JSONObject())
            if (code in 200..299) return json
            throw Answer(code, json.optJSONObject("error")?.optString("status").orEmpty())
        } finally {
            conn.disconnect()
        }
    }
}
