package com.woodruff.tvexplorer.nowplaying

import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.service.notification.NotificationListenerService
import android.os.SystemClock
import org.json.JSONObject

/** What a TV is playing right now. */
data class Playing(
    val title: String,
    val subtitle: String?,
    val app: String,
    val playing: Boolean,
    val positionMs: Long,
    val durationMs: Long,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("title", title).put("subtitle", subtitle ?: "").put("app", app)
        .put("playing", playing).put("position", positionMs).put("duration", durationMs)

    companion object {
        fun fromJson(o: JSONObject?): Playing? = o?.takeIf { it.optString("title").isNotBlank() }?.let {
            Playing(it.optString("title"), it.optString("subtitle").ifBlank { null }, it.optString("app"),
                it.optBoolean("playing"), it.optLong("position"), it.optLong("duration"))
        }
    }
}

/**
 * Now playing on this TV: Woodruff Family's own player first; otherwise whatever app is playing
 * media (Netflix, YouTube, Plex…), read from Android's media sessions. Reading other apps' sessions
 * needs "Notification access" for [MediaWatcher], granted once in Settings (or with adb).
 */
object NowPlaying {
    /** Updated by the built-in player every couple of seconds; null when it's closed. */
    @Volatile var ownPlayer: Playing? = null

    fun current(c: Context): Playing? = ownPlayer ?: fromSessions(c)

    fun canSeeOtherApps(c: Context): Boolean = try {
        sessions(c); true
    } catch (e: Exception) {
        false // SecurityException until Notification access is granted
    }

    private fun sessions(c: Context): List<MediaController> {
        val msm = c.getSystemService(MediaSessionManager::class.java) ?: return emptyList()
        return msm.getActiveSessions(ComponentName(c, MediaWatcher::class.java))
    }

    private fun fromSessions(c: Context): Playing? = try {
        val all = sessions(c)
        val ctl = all.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: all.firstOrNull()
        ctl?.let { m ->
            val meta = m.metadata
            val title = meta?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
                ?: meta?.getString(MediaMetadata.METADATA_KEY_TITLE)
            val subtitle = meta?.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)
                ?: meta?.getString(MediaMetadata.METADATA_KEY_ARTIST)
            val app = runCatching {
                c.packageManager.getApplicationLabel(c.packageManager.getApplicationInfo(m.packageName, 0)).toString()
            }.getOrDefault(m.packageName)
            val state = m.playbackState
            val playing = state?.state == PlaybackState.STATE_PLAYING
            // Sessions report the position at their last update; move it forward to now.
            val pos = state?.let { s ->
                if (playing && s.lastPositionUpdateTime > 0) {
                    s.position + ((SystemClock.elapsedRealtime() - s.lastPositionUpdateTime) * s.playbackSpeed).toLong()
                } else s.position
            } ?: 0L
            Playing(
                title = title?.takeIf { it.isNotBlank() } ?: app,
                subtitle = subtitle?.takeIf { it.isNotBlank() },
                app = app,
                playing = playing,
                positionMs = pos.coerceAtLeast(0),
                durationMs = meta?.getLong(MediaMetadata.METADATA_KEY_DURATION)?.coerceAtLeast(0) ?: 0,
            )
        }
    } catch (e: SecurityException) {
        null // notification access not granted: only Woodruff's own player is visible
    } catch (e: Exception) {
        null
    }
}

/** Exists only so Android can grant access to other apps' media sessions (Notification access). */
class MediaWatcher : NotificationListenerService()
