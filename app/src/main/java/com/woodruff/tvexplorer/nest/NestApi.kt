package com.woodruff.tvexplorer.nest

import android.content.Context
import com.woodruff.tvexplorer.cloud.GoogleAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** A Google Home / Nest camera or doorbell that can stream live. */
data class Camera(
    /** Full resource name: enterprises/{project}/devices/{id}. */
    val name: String,
    val label: String,
    val room: String?,
    val webRtc: Boolean,
    val doorbell: Boolean,
)

/** Answer from Google for one live view; it lasts about five minutes unless extended. */
data class LiveSession(val answerSdp: String, val mediaSessionId: String)

/**
 * Google's Smart Device Management API (Device Access). Newer cameras and doorbells stream over
 * WebRTC: we send an SDP offer, Google returns the answer, and the stream must be extended
 * before it expires.
 */
object NestApi {
    private const val BASE = "https://smartdevicemanagement.googleapis.com/v1/"
    private const val LIVE = "sdm.devices.traits.CameraLiveStream"

    suspend fun cameras(c: Context): List<Camera> = withContext(Dispatchers.IO) {
        val project = GoogleAuth.nestProject(c) ?: throw IOException("Enter your Device Access Project ID")
        val json = call(c, "GET", "enterprises/$project/devices", null)
        val devices = json.optJSONArray("devices") ?: return@withContext emptyList()
        (0 until devices.length()).mapNotNull { i ->
            val d = devices.getJSONObject(i)
            val traits = d.optJSONObject("traits") ?: return@mapNotNull null
            val live = traits.optJSONObject(LIVE) ?: return@mapNotNull null
            val protocols = live.optJSONArray("supportedProtocols")
            val webRtc = protocols != null && (0 until protocols.length()).any { protocols.optString(it) == "WEB_RTC" }
            val room = d.optJSONArray("parentRelations")?.optJSONObject(0)?.optString("displayName")?.ifBlank { null }
            val type = d.optString("type")
            val label = traits.optJSONObject("sdm.devices.traits.Info")?.optString("customName")?.ifBlank { null }
                ?: room?.let { "$it ${if (type.endsWith("DOORBELL")) "doorbell" else "camera"}" }
                ?: if (type.endsWith("DOORBELL")) "Doorbell" else "Camera"
            Camera(d.getString("name"), label, room, webRtc, type.endsWith("DOORBELL"))
        }.sortedWith(compareByDescending<Camera> { it.doorbell }.thenBy { it.label.lowercase() })
    }

    suspend fun startStream(c: Context, camera: Camera, offerSdp: String): LiveSession = withContext(Dispatchers.IO) {
        val r = command(c, camera, "GenerateWebRtcStream", JSONObject().put("offerSdp", offerSdp))
        LiveSession(r.getString("answerSdp"), r.getString("mediaSessionId"))
    }

    /** Returns the session id to use from now on (Google may issue a new one). */
    suspend fun extendStream(c: Context, camera: Camera, mediaSessionId: String): String = withContext(Dispatchers.IO) {
        command(c, camera, "ExtendWebRtcStream", JSONObject().put("mediaSessionId", mediaSessionId))
            .optString("mediaSessionId").ifBlank { mediaSessionId }
    }

    suspend fun stopStream(c: Context, camera: Camera, mediaSessionId: String) = withContext(Dispatchers.IO) {
        runCatching { command(c, camera, "StopWebRtcStream", JSONObject().put("mediaSessionId", mediaSessionId)) }
        Unit
    }

    private fun command(c: Context, camera: Camera, name: String, params: JSONObject): JSONObject {
        val body = JSONObject().put("command", "sdm.devices.commands.CameraLiveStream.$name").put("params", params)
        return call(c, "POST", "${camera.name}:executeCommand", body).optJSONObject("results") ?: JSONObject()
    }

    private fun call(c: Context, method: String, path: String, body: JSONObject?): JSONObject {
        val token = GoogleAuth.blockingToken(c, GoogleAuth.Grant.NEST)
            ?: throw IOException("Connect your cameras again (Google sign-in expired)")
        val conn = (URL(BASE + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15_000
            readTimeout = 20_000
            setRequestProperty("Authorization", "Bearer $token")
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
        }
        try {
            if (body != null) conn.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            val json = runCatching { JSONObject(text) }.getOrDefault(JSONObject())
            if (code in 200..299) return json
            val msg = json.optJSONObject("error")?.optString("message").orEmpty()
            throw IOException(
                when (code) {
                    401 -> "Google sign-in for cameras expired. Connect again."
                    403 -> "Google refused access ($msg). Check that the Smart Device Management API is enabled and the cameras were shared during sign-in."
                    404 -> "Camera or project not found. Check the Device Access Project ID."
                    429 -> throw RateLimited()
                    else -> "Camera error $code${if (msg.isNotBlank()) ": $msg" else ""}"
                },
            )
        } finally {
            conn.disconnect()
        }
    }
}

/** Google answered 429: too many camera requests for this account right now. */
class RateLimited : IOException("Google is limiting camera requests. Waiting, then trying again.")
