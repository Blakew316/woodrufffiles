package com.woodruff.tvexplorer.notify

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import com.woodruff.tvexplorer.MainActivity
import com.woodruff.tvexplorer.R
import com.woodruff.tvexplorer.nowplaying.NowPlaying
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject

enum class NoticeSource { PHONE, MAIL, SYSTEM }

data class Notice(
    val id: Long,
    val source: NoticeSource,
    val title: String,
    val body: String,
    val at: Long,
    /** Optional link (e.g. a video URL sent from the phone) the notice can open. */
    val link: String? = null,
    val read: Boolean = false,
    /** The sound it came with (not kept across restarts: only its pop-up needs it). */
    val chime: Chime.Sound? = null,
) {
    /** How long its pop-up stays: a doorbell long enough to get up and look. */
    val showMs: Long get() = if (chime == Chime.Sound.DOORBELL) 25_000 else 7_000
}

/**
 * The app's notification center. Anything that wants the viewer's attention (a message from the
 * phone, new mail) goes through [post], which:
 *  1. keeps it in the history list (the Notifications screen, persisted),
 *  2. shows a popup over whatever is on screen, even other apps, when "display over other apps"
 *     is allowed ([NoticeOverlay]); Android TV launchers mostly hide normal notifications,
 *  3. also posts a regular Android notification for launchers that do show them.
 * Inside the app, [latest] drives an in-app banner.
 */
object Notices {
    private const val CHANNEL = "notices"
    private const val MAX = 100

    private val _items = MutableStateFlow<List<Notice>>(emptyList())
    val items: StateFlow<List<Notice>> = _items.asStateFlow()

    private val _latest = MutableStateFlow<Notice?>(null)
    val latest: StateFlow<Notice?> = _latest.asStateFlow()

    @Volatile private var loaded = false

    fun load(context: Context) {
        if (loaded) return
        loaded = true
        val arr = runCatching { JSONArray(prefs(context).getString("items", "[]")) }.getOrDefault(JSONArray())
        _items.value = (0 until arr.length()).mapNotNull { i ->
            arr.optJSONObject(i)?.let {
                Notice(
                    it.optLong("id"), runCatching { NoticeSource.valueOf(it.optString("src")) }.getOrDefault(NoticeSource.SYSTEM),
                    it.optString("title"), it.optString("body"), it.optLong("at"),
                    it.optString("link").ifEmpty { null }, it.optBoolean("read"),
                )
            }
        }
    }

    /** [chime]: also play that sound (announcements and the doorbell; never mail). */
    fun post(context: Context, source: NoticeSource, title: String, body: String, link: String? = null, chime: Chime.Sound? = null) {
        load(context)
        val sound = chime.takeIf { source != NoticeSource.MAIL }
        val n = Notice(System.nanoTime(), source, title.take(200), body.take(2000), System.currentTimeMillis(), link, chime = sound)
        _items.update { (listOf(n) + it).take(MAX) }
        _latest.value = n
        save(context)
        // Wherever the viewer is: in Woodruff, in its player or in another app.
        if (sound != null) Chime.play(context, sound)
        // Woodruff's own player shows the doorbell in its own card (with OK to look): not twice.
        val playerCard = sound == Chime.Sound.DOORBELL && NowPlaying.ownPlayer != null
        if (!AppVisibility.foreground && !playerCard) {
            if (Settings.canDrawOverlays(context)) NoticeOverlay.show(context, n) else systemNotification(context, n)
        }
    }

    private val shown = RecentIds(200)

    /**
     * False when the message with this [id] was already shown here (relayed twice, or by two TVs),
     * so it isn't shown again. A blank id is always new.
     */
    fun firstTime(id: String?): Boolean = shown.add(id)

    fun markAllRead(context: Context) {
        // Nothing unread (the usual case): don't rewrite every notice and the file for nothing.
        if (_items.value.none { !it.read }) return
        _items.update { l -> l.map { it.copy(read = true) } }; save(context)
    }
    fun clear(context: Context) { _items.value = emptyList(); save(context) }
    fun dismissLatest() { _latest.value = null }
    val unread: Int get() = _items.value.count { !it.read }

    /** Also [NoticeOverlay]'s fallback when the TV refuses its pop-up. */
    internal fun systemNotification(context: Context, n: Notice) {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Messages and mail", NotificationManager.IMPORTANCE_HIGH))
        }
        val open = PendingIntent.getActivity(
            context, 2, Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN, "notifications"),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notif = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notice)
            .setContentTitle(n.title)
            .setContentText(n.body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(n.body))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        runCatching { nm.notify((n.id % Int.MAX_VALUE).toInt(), notif) }
    }

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences("notices", Context.MODE_PRIVATE)

    private fun save(context: Context) {
        val arr = JSONArray()
        _items.value.forEach {
            arr.put(JSONObject().put("id", it.id).put("src", it.source.name).put("title", it.title).put("body", it.body)
                .put("at", it.at).put("link", it.link ?: "").put("read", it.read))
        }
        prefs(context).edit().putString("items", arr.toString()).apply()
    }
}

/** The last [max] ids seen, oldest forgotten first. */
internal class RecentIds(private val max: Int) {
    private val ids = LinkedHashSet<String>()

    /** True when [id] is new (now remembered); always true for a blank id. */
    @Synchronized
    fun add(id: String?): Boolean {
        if (id.isNullOrBlank()) return true
        if (!ids.add(id)) return false
        if (ids.size > max) ids.remove(ids.first())
        return true
    }
}

/** Whether a Woodruff Family screen is showing, so popups don't double up with the in-app banner. */
object AppVisibility {
    @Volatile var foreground = false
    /** Last remote button press in Woodruff (ms), for "nobody is using it right now". */
    @Volatile var lastInput = System.currentTimeMillis()
}
