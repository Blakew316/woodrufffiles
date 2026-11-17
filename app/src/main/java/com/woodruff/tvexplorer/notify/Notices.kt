package com.woodruff.tvexplorer.notify

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationCompat
import com.woodruff.tvexplorer.R
import com.woodruff.tvexplorer.nest.AlertPictures
import com.woodruff.tvexplorer.nest.CameraAlert
import com.woodruff.tvexplorer.nest.Doorbell
import com.woodruff.tvexplorer.nest.Shelved
import com.woodruff.tvexplorer.nest.pictureFits
import com.woodruff.tvexplorer.nowplaying.NowPlaying
import com.woodruff.tvexplorer.remote.TvScreen
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
    /** Held while the kept notices are read in, and while they're written out. */
    private val disk = Any()

    /**
     * The kept notices, read in once. A notice posted meanwhile on another thread waits for them (it was lost when
     * they were read in over it), and one posted before stays ahead of them.
     */
    fun load(context: Context) {
        if (loaded) return
        synchronized(disk) {
            if (loaded) return
            val arr = runCatching { JSONArray(prefs(context).getString("items", "[]")) }.getOrDefault(JSONArray())
            val kept = (0 until arr.length()).mapNotNull { i ->
                arr.optJSONObject(i)?.let {
                    Notice(
                        it.optLong("id"), runCatching { NoticeSource.valueOf(it.optString("src")) }.getOrDefault(NoticeSource.SYSTEM),
                        it.optString("title"), it.optString("body"), it.optLong("at"),
                        it.optString("link").ifEmpty { null }, it.optBoolean("read"),
                    )
                }
            }
            _items.update { now -> (now + kept.filter { k -> now.none { it.id == k.id } }).take(MAX) }
            loaded = true
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
     * Whatever can't be shown now ([mayShow]) is kept, unread, with nothing drawn or chimed.
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
        if (!mayShow(context, n)) return n
        if (quiet) {
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
     * Whether [n] may be drawn and chimed now; if not, it's only kept, unread, for when someone next looks:
     *  - not while the TV's screen is off: nobody would see it, and a card left on a dark screen used the news up
     *    (a doorbell ring is announced when the screen comes on, if that's soon: [Doorbell]);
     *  - not a camera alert or mail while the TV's screen is on the Apple TV (its input, or Woodruff on the Apple TV
     *    says so): Woodruff on the Apple TV shows those itself ([heldForAppleTv]). Announcements and what someone
     *    sends still show;
     *  - nothing but another ring over a doorbell's card that's still up (Woodruff's own, the one over other apps, or
     *    the player's over a film), which it would cover or cut short.
     * A doorbell ring's card that comes late asks again.
     */
    internal fun mayShow(context: Context, n: Notice): Boolean {
        if (!screenOn(context)) return false
        if (heldForAppleTv(n) && onAppleTvNow(context)) return false
        if (n.chime != Chime.Sound.DOORBELL && doorbellCardUp()) return false
        return true
    }

    /** What Woodruff on the Apple TV shows itself, so this TV doesn't over it ([mayShow]): the cameras' alerts and the doorbell (as the README lists them), and mail. */
    internal fun heldForAppleTv(n: Notice): Boolean = n.alert != null || n.source == NoticeSource.MAIL

    /** Whether a doorbell's card is up now: Woodruff's own banner, the card over other apps, or the player's over a film. */
    private fun doorbellCardUp(): Boolean =
        _latest.value?.chime == Chime.Sound.DOORBELL || NoticeOverlay.up?.chime == Chime.Sound.DOORBELL || playerDoorbell

    /** Woodruff's player shows its own doorbell card over the film ([playerDoorbellCard]). */
    @Volatile private var playerDoorbell = false

    /**
     * Woodruff's player put its own doorbell card up over the film ([up]), or took it away. A card over other apps goes
     * as it comes (it sat over the player's card, which glided in underneath), and while it's up no other card covers it.
     */
    fun playerDoorbellCard(up: Boolean) {
        playerDoorbell = up
        if (up) NoticeOverlay.hide()
    }

    /**
     * Woodruff on the Apple TV says it's on screen: a card of the kinds it shows itself ([heldForAppleTv]) that's up over
     * another app goes, if the TV's screen is on the Apple TV now (it was switched there with the card up).
     */
    fun appleTvOnScreen(context: Context) {
        val up = NoticeOverlay.up ?: return
        if (heldForAppleTv(up) && onAppleTvNow(context)) NoticeOverlay.hide { heldForAppleTv(it) }
    }

    /** The TV's screen went off: no card is left on it (its time runs on a clock standby stops, so it was still up after). */
    fun screenWentOff() {
        NoticeOverlay.hide()
        _latest.value = null
    }

    /** Whether the TV's screen is on (it isn't in standby); replaced in tests. */
    internal var screenOn: (Context) -> Boolean = { c -> c.applicationContext.getSystemService(PowerManager::class.java)?.isInteractive != false }

    /**
     * [n]'s card, where the viewer is: Woodruff's own banner, or over other apps and over the main window's
     * pop-ups (else an Android notification). Also a doorbell ring's card when its camera couldn't open after
     * all. A camera alert's card shows its picture when that came first; one that comes later is added to it
     * ([pictureCame]).
     */
    fun popUp(context: Context, n: Notice) {
        // Woodruff's own banner only while its main screen is in front. One set while it wasn't (over the player,
        // another app or a dark screen) waited there, and came up again when that screen next did, beside or long after
        // the card over other apps.
        val inApp = AppVisibility.foreground
        // That banner is drawn in the main window, under the window's pop-ups (a title page, Search, the filters, a
        // menu): with one of them up, the card shows over them as over another app, and not under them too.
        val underPopUp = inApp && com.woodruff.tvexplorer.ui.motion.DialogWindows.overMainWindow
        val overPopUp = underPopUp && Settings.canDrawOverlays(context)
        // (With the picture's arrival on another thread, in one step: either the card finds it here, or it finds the card.)
        val card = synchronized(cards) { withPicture(n).also { if (inApp) _latest.value = if (overPopUp) null else it } }
        if (inApp && !underPopUp) return
        // Woodruff's own player shows the doorbell in its own card (with OK to look): not twice.
        if (card.chime == Chime.Sound.DOORBELL && NowPlaying.ownPlayer != null) return
        if (Settings.canDrawOverlays(context)) NoticeOverlay.show(context, card) else systemNotification(context, card)
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
            // In one step: a card dismissed at that very moment stays dismissed (read, then set, brought it back).
            _latest.update { n -> if (n != null && takes(n, p)) n.copy(picture = p.picture) else n }
            trayCards.firstOrNull { takes(it, p) }?.let { n ->
                trayCards.remove(n)
                notify(app, n.copy(picture = p.picture), again = true)
            }
        }
        NoticeOverlay.picture(p)
    }

    /** Whether the TV's screen is on the Apple TV ([NowPlaying.onAppleTv]); replaced in tests. */
    internal var overAppleTv: (Context) -> Boolean = { NowPlaying.onAppleTv(it) }

    /**
     * Whether one of Woodruff's own screens is up (the main window, the photo viewer, the slideshow, the
     * doorbell's camera, the player) or Woodruff is the TV's screensaver; replaced in tests.
     */
    internal var ownScreenUp: () -> Boolean = { AppVisibility.foreground || TvScreen.onScreen || Doorbell.dreaming }

    /**
     * Whether what this TV would draw now would land on the Apple TV ([overAppleTv]). Never over Woodruff's own
     * screens or its screensaver ([ownScreenUp]), where everything shows as before: Woodruff on the Apple TV
     * only knows what's on its own screen, and can say so while this TV shows Woodruff. A doorbell ring's card
     * that comes late asks it again.
     */
    internal fun onAppleTvNow(context: Context): Boolean = !ownScreenUp() && overAppleTv(context)

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

    /** Writes the notices out as they are now: one at a time, so a slower write of fewer can't land after this one. */
    private fun save(context: Context) = synchronized(disk) {
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
