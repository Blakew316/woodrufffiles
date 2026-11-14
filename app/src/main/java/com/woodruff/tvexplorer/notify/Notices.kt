package com.woodruff.tvexplorer.notify

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import com.woodruff.tvexplorer.R
import com.woodruff.tvexplorer.nest.AlertPictures
import com.woodruff.tvexplorer.nest.CameraAlert
import com.woodruff.tvexplorer.nest.Shelved
import com.woodruff.tvexplorer.nest.pictureFits
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
    /** The camera alert it tells of, which its picture from the family's Mac is matched to (not kept across restarts). */
    val alert: CameraAlert? = null,
    /** That picture, on its card once it came (decoded and sized already); never on the Notifications list. */
    val picture: Bitmap? = null,
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

    /**
     * [chime]: also play that sound (announcements and the doorbell; never mail). [quiet]: calm,
     * non-urgent news (a sign-in that ended): kept unread here (Notifications, Home's At a glance) and
     * shown in Woodruff's own banner only while Woodruff's main screen is in front; never over its
     * player, slideshow or photo viewer, never over other apps, never as a system notification, never
     * with a sound. [popUp] false: kept and chimed, but no card anywhere (a doorbell ring whose camera
     * opens instead: the camera is the notice). [alert]: the camera alert it tells of, whose card shows
     * the family's Mac's picture of it ([AlertPictures]). Returns the notice, for [popUp] later.
     */
    fun post(
        context: Context, source: NoticeSource, title: String, body: String, link: String? = null, chime: Chime.Sound? = null,
        quiet: Boolean = false, popUp: Boolean = true, alert: CameraAlert? = null,
    ): Notice {
        load(context)
        val sound = chime.takeIf { source != NoticeSource.MAIL && !quiet }
        val n = Notice(System.nanoTime(), source, title.take(200), body.take(2000), System.currentTimeMillis(), link, chime = sound, alert = alert)
        _items.update { (listOf(n) + it).take(MAX) }
        save(context)
        // The TV's screen is on the Apple TV (one of its inputs): Woodruff on the Apple TV shows what
        // happens there itself, so this TV draws and chimes nothing over it. Kept, unread, like quiet
        // news. What someone sends this TV from a phone still shows.
        val elsewhere = source != NoticeSource.PHONE && !AppVisibility.foreground && overAppleTv(context)
        if (quiet || elsewhere) {
            // Woodruff's own banner while its main screen is in front; otherwise stored, unread, for
            // when someone next looks: nothing pops up now.
            if (popUp && AppVisibility.foreground) _latest.value = n
            return n
        }
        // Wherever the viewer is: in Woodruff, in its player or in another app.
        if (sound != null) Chime.play(context, sound)
        if (popUp) popUp(context, n)
        return n
    }

    /**
     * [n]'s card, where the viewer is: Woodruff's own banner, or over other apps (else an Android
     * notification). Also a doorbell ring's card when its camera couldn't open after all. A camera
     * alert's card shows its picture when that came first; one that comes later is added to it
     * ([pictureCame]).
     */
    fun popUp(context: Context, n: Notice) {
        // (With the picture's arrival on another thread, in one step: either the card finds it here, or it finds the card.)
        val card = synchronized(cards) { withPicture(n).also { _latest.value = it } }
        // Woodruff's own player shows the doorbell in its own card (with OK to look): not twice.
        val playerCard = card.chime == Chime.Sound.DOORBELL && NowPlaying.ownPlayer != null
        if (!AppVisibility.foreground && !playerCard) {
            if (Settings.canDrawOverlays(context)) NoticeOverlay.show(context, card) else systemNotification(context, card)
        }
    }

    /** Held while a card takes its picture, or a picture its card ([popUp], [systemNotification], [pictureCame]). */
    private val cards = Any()

    /** Camera alerts shown as Android notifications that are still waiting for their picture (the last few). */
    private val trayCards = ArrayList<Notice>()

    /** [n] with its camera alert's picture, when that came already. */
    internal fun withPicture(n: Notice): Notice {
        val alert = n.alert ?: return n
        if (n.picture != null) return n
        return AlertPictures.forAlert(alert)?.let { n.copy(picture = it) } ?: n
    }

    /** Whether [n]'s card takes the picture [p]: one for its camera alert, when it has none yet. */
    private fun takes(n: Notice, p: Shelved<Bitmap>): Boolean = n.picture == null && n.alert?.let { pictureFits(p.alert, it) } == true

    /**
     * A camera alert's picture came from the family's Mac ([AlertPictures]): the card up for that alert
     * takes it in place, in Woodruff, over other apps or as the TV's notification. It's the same card:
     * no second chime, and its time on screen still counts from when it came.
     */
    internal fun pictureCame(context: Context, p: Shelved<Bitmap>) {
        val app = context.applicationContext
        synchronized(cards) {
            _latest.value?.let { n -> if (takes(n, p)) _latest.value = n.copy(picture = p.picture) }
            trayCards.firstOrNull { takes(it, p) }?.let { n ->
                trayCards.remove(n)
                notify(app, n.copy(picture = p.picture), again = true)
            }
        }
        NoticeOverlay.picture(p)
    }

    /** Whether the TV's screen is on the Apple TV's input ([NowPlaying.onAppleTv]); replaced in tests. */
    internal var overAppleTv: (Context) -> Boolean = { NowPlaying.onAppleTv(it) }

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

    /**
     * Also [NoticeOverlay]'s fallback when the TV refuses its pop-up. A camera alert's picture shows in
     * it when it came; one that comes later is put in the same notification, without a second sound.
     */
    internal fun systemNotification(context: Context, n: Notice) {
        synchronized(cards) {
            val shown = withPicture(n)
            if (shown.alert != null && shown.picture == null) {
                trayCards += shown
                while (trayCards.size > 4) trayCards.removeAt(0)
            }
            notify(context, shown, again = false)
        }
    }

    /**
     * [n] as an Android notification; [again]: the one already posted for it, now with its picture (it
     * doesn't alert again, and one dismissed meanwhile stays dismissed).
     */
    private fun notify(context: Context, n: Notice, again: Boolean) {
        val nm = context.getSystemService(NotificationManager::class.java)
        val id = (n.id % Int.MAX_VALUE).toInt()
        if (again && runCatching { nm.activeNotifications.none { it.id == id } }.getOrDefault(true)) return
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Messages and mail", NotificationManager.IMPORTANCE_HIGH))
        }
        // The open main window, at Notifications (whatever was over it closes), never a second one.
        val open = PendingIntent.getActivity(
            context, 2, com.woodruff.tvexplorer.MainIntents.open(context, "notifications"),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val picture = n.picture
        val notif = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notice)
            .setContentTitle(n.title)
            .setContentText(n.body)
            .setStyle(
                if (picture != null) NotificationCompat.BigPictureStyle().bigPicture(picture).setSummaryText(n.body)
                else NotificationCompat.BigTextStyle().bigText(n.body),
            )
            .setContentIntent(open)
            .setAutoCancel(true)
            .setOnlyAlertOnce(again)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        runCatching { nm.notify(id, notif) }
    }

    /** The notices' own settings file (also where [SignInNotice] remembers which endings it told of). */
    internal fun prefs(c: Context) = c.applicationContext.getSharedPreferences("notices", Context.MODE_PRIVATE)

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
