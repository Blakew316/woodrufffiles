package com.woodruff.tvexplorer.nest

import android.content.Context
import android.provider.Settings
import android.util.Base64
import android.util.Log
import com.woodruff.tvexplorer.cloud.GoogleAuth
import com.woodruff.tvexplorer.notify.AppVisibility
import com.woodruff.tvexplorer.notify.Chime
import com.woodruff.tvexplorer.notify.NoticeSource
import com.woodruff.tvexplorer.notify.Notices
import com.woodruff.tvexplorer.settings.SharedKey
import com.woodruff.tvexplorer.settings.SharedKeys
import com.woodruff.tvexplorer.tvs.TvLinks
import com.woodruff.tvexplorer.util.NetworkWatch
import com.woodruff.tvexplorer.util.isNetworkFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Doorbell alerts. Google announces a doorbell press (and a person or motion seen) only as an event
 * on a Cloud Pub/Sub topic: there's nothing to ask the camera itself. The family sets up one pull
 * subscription to that topic in the phone app, on any TV, and its name goes to every linked TV
 * ([SharedKey.DOORBELL]). Each TV whose cameras are connected then makes a subscription of its own
 * on the same topic and pulls that. Pub/Sub hands each message of a subscription to only one of its
 * listeners, but every message to every subscription: so the doorbell rings on every TV that's on,
 * whichever others are off. A TV's own subscription deletes itself after a day nobody pulls it, so a
 * TV that's removed or reset leaves nothing behind ([ownSubscriptionBody]).
 *
 * On a ring this TV chimes, and opens the camera over Woodruff's own screens, slideshow and
 * screensaver ([opens]), or pops up a card over the player, the photo viewer and other apps; and it
 * tells the TVs linked to it, for those whose cameras aren't connected. One visit rings once
 * ([Visits]), however many messages Google sends about it and whichever way they arrive.
 *
 * Where Google won't make a TV a subscription of its own (a project that doesn't allow it), the TV
 * where the subscription was set listens on the family's one itself, as every TV did before, and the
 * others hear the doorbell through it.
 */
object Doorbell {
    private const val PREFS = "doorbell"
    private const val K_SUBSCRIPTION = "subscription"
    /** The family's subscription was set on this TV (in the phone app), not passed on by another TV. */
    private const val K_SET_HERE = "setHere"
    /** This TV's own subscription, and the family's subscription it was made from. */
    private const val K_OWN = "own"
    private const val K_OWN_FOR = "ownFor"
    /** Google wouldn't make this TV one of its own for that subscription, at that time. */
    private const val K_REFUSED_FOR = "refusedFor"
    private const val K_REFUSED_AT = "refusedAt"
    /** The TV that last passed an alert on to this one ("Through Living Room"). */
    private const val K_RELAY_FROM = "relayFrom"

    /** The pause after an empty pull (Google already held it open a while). */
    private const val EMPTY_PAUSE_MS = 5_000L
    /** Once Google refused this TV a subscription of its own, how long until it asks again (the family may change the project's permissions). */
    private const val REFUSED_RETRY_MS = 6 * 60 * 60_000L
    /** A problem only a person can fix: looked at again this often. */
    private const val FAILING_RETRY_MS = 5 * 60_000L
    /** Not listening (nothing set up, the cameras not connected here): looked at again this often, or when nudged. */
    private const val IDLE_CHECK_MS = 60_000L
    /** Asleep: looked at again at least this often, or as soon as the screen comes on. */
    private const val ASLEEP_CHECK_MS = 60 * 60_000L
    /** How long a ring's camera may take to show before the ring's card pops up instead. */
    private const val SHOW_GRACE_MS = 4_000L
    /** Waiting for the network: 5 s, 15 s, 30 s, then every minute, or at once when it's back. */
    private val OFFLINE_PAUSES = longArrayOf(5_000, 15_000, 30_000, 60_000)
    private const val SIGNED_OUT = "Connect your cameras first"
    private const val SOME_CAMERA = "One of your cameras"

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * Person / motion pop-ups (same Pub/Sub feed as the doorbell). Person on, motion off by default.
     * The same on every TV ([SharedKey.ALERTS]): each TV that listens on its own subscription
     * announces what the family chose, wherever they chose it.
     */
    fun personAlerts(c: Context) = prefs(c).getBoolean("person", true)
    fun motionAlerts(c: Context) = prefs(c).getBoolean("motion", false)

    /** Set from the phone on this TV: kept here, and passed on to every linked TV. */
    fun setAlerts(c: Context, person: Boolean?, motion: Boolean?) {
        val app = c.applicationContext
        SharedKeys.set(app, SharedKey.ALERTS) {
            prefs(app).edit().apply {
                if (person != null) putBoolean("person", person)
                if (motion != null) putBoolean("motion", motion)
            }.apply()
        }
    }

    /** The person / motion choice as the TVs share it; null while nobody has chosen (the defaults). */
    internal fun alertsChoice(c: Context): String? {
        val p = prefs(c)
        if (!p.contains("person") && !p.contains("motion")) return null
        return alertsKey(personAlerts(c), motionAlerts(c))
    }

    /** Another TV passed on the family's person / motion choice ([value]; null: back to the defaults). */
    internal fun receivedAlerts(c: Context, value: String?) {
        val edit = prefs(c).edit()
        if (value == null) {
            edit.remove("person").remove("motion")
        } else {
            val (person, motion) = parseAlertsKey(value) ?: throw IllegalArgumentException("Not a person / motion choice")
            edit.putBoolean("person", person).putBoolean("motion", motion)
        }
        edit.apply()
    }

    /** The alert kinds there are; anything else (e.g. from a linked TV) is ignored. */
    val KINDS = setOf("doorbell", "person", "motion")

    /**
     * One pop-up per camera and kind every 2 minutes (30 s for the doorbell): a person walking
     * around isn't ten alerts, and a double press is one ring.
     */
    private val lastAlert = ConcurrentHashMap<String, Long>()
    /**
     * Messages already handled. Pub/Sub delivers at least once, so a redelivery doesn't ring again;
     * and a message keeps its id in every subscription, so one heard on two (while switching) rings once.
     */
    private val seen = object : LinkedHashMap<String, Unit>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Unit>?) = size > 200
    }
    private val visits = Visits()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val main = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null

    private val _rings = MutableSharedFlow<String>(extraBufferCapacity = 4)
    /** Camera resource names that just rang (for the player's "Someone's at the door" card). */
    val rings: SharedFlow<String> = _rings.asSharedFlow()

    /** The screens a ring's camera opens on (doorbell.md §2). */
    enum class Host {
        /** A doorbell view already up ([com.woodruff.tvexplorer.player.DoorbellActivity]): it switches to the ringing camera. */
        VIEW,
        /** Woodruff's photo slideshow. */
        SLIDESHOW,
        /** Woodruff as the TV's screensaver (only with "Pop-ups over other apps" allowed). */
        SCREENSAVER,
        /** Woodruff's own screens (MainActivity in front). */
        APP,
    }

    /** A doorbell ring whose camera opens on [host]: [camera] rang at [at] (this TV's clock). */
    data class Ring(val id: Long, val camera: String, val at: Long, val host: Host)

    private val _opens = MutableSharedFlow<Ring>(replay = 1, extraBufferCapacity = 4)
    /**
     * Rings whose camera opens on this TV, for the screen each names: it [claim]s it, then opens the
     * camera. The latest is replayed, so a screen that was just starting doesn't miss it; claiming
     * keeps any ring from opening twice. When a ring's camera hasn't opened a few seconds later, its
     * card pops up after all.
     */
    val opens: SharedFlow<Ring> = _opens.asSharedFlow()
    private val claims = RingClaims()
    private val ringIds = AtomicLong()

    private val _ringTimes = MutableStateFlow<Map<String, Long>>(emptyMap())
    /** When each camera last rang in this run of the app (this TV's clock), for "RANG AT 7:42 PM". */
    val ringTimes: StateFlow<Map<String, Long>> = _ringTimes.asStateFlow()

    private val _status = MutableStateFlow<String?>(null)
    /** What's wrong with this TV's doorbell alerts, when something a person can fix is (the phone shows it). */
    val status: StateFlow<String?> = _status.asStateFlow()

    private val _listening = MutableStateFlow<DoorbellListening>(DoorbellListening.Idle)
    /** What this TV's listener is doing; [alerts] puts it in words. */
    val listening: StateFlow<DoorbellListening> = _listening.asStateFlow()

    /** Ticks when something listening depends on changes (see [nudge]). */
    private val nudges = MutableStateFlow(0)

    // ---- The screens a camera opens on ------------------------------------------------------------

    private val views = AtomicInteger()
    private val slideshows = AtomicInteger()
    @Volatile private var dreaming = false
    /** When a doorbell view last came on screen. */
    @Volatile private var viewStartedAt = 0L

    /** A screen opening rings' cameras while it's up ([watchFor]); [stop] when it goes. */
    class Watch internal constructor(private val host: Host) {
        internal var job: Job? = null
        private var here = false
        private var gone = false

        @Synchronized internal fun arrived() {
            if (gone || here) return
            here = true
            present(host, true)
        }

        /** The screen went (its onStop): rings no longer open there. */
        @Synchronized fun stop() {
            if (gone) return
            gone = true
            if (here) present(host, false)
            job?.cancel()
        }
    }

    /**
     * For a screen that opens rings' cameras ([host]: the slideshow, the screensaver, a doorbell view
     * already up), from its onStart until it calls [Watch.stop]: [open] gets each ring meant for it,
     * on the main thread. It counts as on screen only once it's listening, so no ring is meant for it
     * that it can't hear.
     */
    fun watchFor(host: Host, open: (Ring) -> Unit): Watch {
        val watch = Watch(host)
        watch.job = main.launch {
            _opens.onSubscription { watch.arrived() }.collect { if (claim(it, host)) open(it) }
        }
        return watch
    }

    /** True once for [ring], on the screen it's meant for ([host]), while it's fresh: that screen opens it. */
    fun claim(ring: Ring, host: Host): Boolean = claims.claim(ring, host, System.currentTimeMillis())

    private fun present(host: Host, on: Boolean) {
        when (host) {
            Host.VIEW -> if (on) { viewStartedAt = System.currentTimeMillis(); views.incrementAndGet() } else views.decrementAndGet()
            Host.SLIDESHOW -> if (on) slideshows.incrementAndGet() else slideshows.decrementAndGet()
            Host.SCREENSAVER -> dreaming = on
            Host.APP -> Unit // MainActivity says so itself (AppVisibility.foreground)
        }
    }

    // ---- The family's subscription ------------------------------------------------------------------

    fun subscription(c: Context): String? = prefs(c).getString(K_SUBSCRIPTION, null)?.trim()?.takeIf { it.isNotEmpty() }

    /** Set from the phone on this TV: kept here, and passed on to every linked TV. */
    fun setSubscription(c: Context, value: String?) {
        val clean = value?.trim().orEmpty()
        require(clean.isEmpty() || isSubscription(clean)) {
            "Enter the full subscription name: projects/your-project/subscriptions/your-subscription"
        }
        val app = c.applicationContext
        // Saving the phone's Cameras page sends it again unchanged: that changes nothing (a TV that
        // was given it by another TV doesn't become the one it was set on).
        if (clean != subscription(app).orEmpty()) {
            SharedKeys.set(app, SharedKey.DOORBELL) { store(app, clean.ifEmpty { null }, setHere = true) }
        }
        start(app)
    }

    /** Another TV passed on the family's subscription ([value]; null: it was removed). */
    internal fun received(c: Context, value: String?) {
        val clean = value?.trim()?.ifEmpty { null }
        require(clean == null || isSubscription(clean)) { "That isn't a subscription name" }
        store(c.applicationContext, clean, setHere = false)
        nudge()
    }

    private fun store(app: Context, value: String?, setHere: Boolean) {
        prefs(app).edit().putString(K_SUBSCRIPTION, value.orEmpty()).putBoolean(K_SET_HERE, setHere).apply()
    }

    /** Set on this TV (also when it was set before subscriptions were shared, which is the only way it could be). */
    private fun setHere(app: Context) = prefs(app).getBoolean(K_SET_HERE, true)

    /** Something listening depends on changed (cameras connected or signed out, a new subscription): look again now. */
    internal fun nudge() = nudges.update { it + 1 }

    private fun relayFrom(app: Context) = cleanTvName(prefs(app).getString(K_RELAY_FROM, null))

    /** How this TV hears the doorbell, in words (Settings → Advanced and the health report). Reads settings: not on the main thread. */
    fun alerts(c: Context): DoorbellAlerts {
        val app = c.applicationContext
        TvLinks.load(app)
        return doorbellAlerts(
            subscription = subscription(app) != null,
            camerasHere = GoogleAuth.isConnected(app, GoogleAuth.Grant.NEST),
            relaysOut = TvLinks.linked.value.isNotEmpty(),
            relayFrom = relayFrom(app),
            listening = _listening.value,
        )
    }

    private fun setListening(app: Context, now: DoorbellListening) {
        if (_listening.value == now && now != DoorbellListening.Idle) return
        _listening.value = now
        val words = alerts(app)
        _status.value = words.about.takeIf { words.attention }
    }

    // ---- A ring ---------------------------------------------------------------------------------

    /**
     * A doorbell rang ([kind] "doorbell"), or a person / motion was seen: here, and (when [relay])
     * on every linked TV. [session] is the visit it belongs to (see [Visits]); [from], the TV that
     * passed it on to this one. [label]: the camera's name as the sender knows it (this TV's own
     * camera list comes first). Only the doorbell opens a camera.
     */
    fun ring(c: Context, camera: String, label: String, relay: Boolean, kind: String = "doorbell", session: String? = null, from: String? = null) {
        if (kind !in KINDS) return
        val app = c.applicationContext
        cleanTvName(from)?.let { name -> if (relayFrom(app) != name) prefs(app).edit().putString(K_RELAY_FROM, name).apply() }
        val now = System.currentTimeMillis()
        if (!isNews(camera, session, kind, now)) return
        // What happened, then which camera: "Person seen" / "Backyard" reads right for every name.
        val name = Nest.label(camera) ?: label.trim().ifEmpty { null }
        when (kind) {
            "person" -> Notices.post(app, NoticeSource.SYSTEM, "Person seen", name ?: SOME_CAMERA)
            "motion" -> Notices.post(app, NoticeSource.SYSTEM, "Motion seen", name ?: SOME_CAMERA)
            else -> announce(app, camera, name ?: "Doorbell", now)
        }
        if (relay) scope.launch {
            val me = TvLinks.name(app)
            TvLinks.forEachLinked(app) { TvLinks.doorbell(it, camera, name.orEmpty(), kind, session, me) }
        }
    }

    /**
     * Whether [kind] on [camera] at [now] is news: a new visit (or one without a visit) and past the
     * camera's pause. In one step, as the same event can arrive from two TVs at the same moment.
     */
    private fun isNews(camera: String, session: String?, kind: String, now: Long): Boolean = synchronized(lastAlert) {
        if (!visits.admit(camera, session, kind)) return false
        val k = "$kind|$camera"
        if (now - (lastAlert[k] ?: 0L) < if (kind == "doorbell") 30_000 else 120_000) return false
        lastAlert[k] = now
        true
    }

    /** The doorbell [camera] ([name]) rang at [at]: the chime, and its camera or its card. */
    private fun announce(app: Context, camera: String, name: String, at: Long) {
        _ringTimes.update { it + (camera to at) }
        val host = pickHost(
            camerasHere = GoogleAuth.isConnected(app, GoogleAuth.Grant.NEST),
            dreaming = dreaming, canOverlay = { Settings.canDrawOverlays(app) },
            views = views.get(), slideshows = slideshows.get(), inApp = AppVisibility.foreground,
        )
        // The camera opening is the notice: no card over it (it's kept in Notifications all the same).
        val notice = Notices.post(app, NoticeSource.SYSTEM, "Someone's at the door", name, chime = Chime.Sound.DOORBELL, popUp = host == null)
        if (host != null) {
            val ring = Ring(ringIds.incrementAndGet(), camera, at, host)
            _opens.tryEmit(ring)
            // The camera couldn't open after all (that screen went just then, or Android wouldn't let
            // the screensaver open it): the card, a moment late, rather than nothing.
            scope.launch {
                delay(SHOW_GRACE_MS)
                val opened = if (host == Host.SCREENSAVER) viewStartedAt >= at else claims.taken(ring.id)
                if (!opened) Notices.popUp(app, notice)
            }
        }
        _rings.tryEmit(camera)
    }

    // ---- Listening ------------------------------------------------------------------------------

    /** Stops listening (the phone-connection service stopped). */
    @Synchronized fun stop() { job?.cancel(); job = null }

    /** Starts (or restarts) listening for rings; called when the phone-connection service starts. */
    @Synchronized fun start(c: Context) {
        val app = c.applicationContext
        Awake.start(app)
        job?.cancel()
        job = scope.launch { listen(app) }
    }

    /** What to wait for before the next step. */
    private sealed interface Next {
        data object Now : Next
        data class After(val ms: Long) : Next
        /** The network or Google out of reach: until it's back. */
        data object Offline : Next
        /** Until the screen comes on. */
        data object Asleep : Next
    }

    private suspend fun listen(app: Context) {
        var offline = 0
        while (true) {
            val mark = NetworkWatch.comebacks.value
            val nudged = nudges.value
            val next = try {
                step(app)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("Doorbell", "listening: $e")
                Next.After(30_000)
            }
            when (next) {
                Next.Now -> yield()
                is Next.After -> pause(next.ms, null, nudged)
                Next.Offline -> pause(OFFLINE_PAUSES[offline.coerceAtMost(OFFLINE_PAUSES.size - 1)], mark, nudged)
                Next.Asleep -> withTimeoutOrNull(ASLEEP_CHECK_MS) {
                    merge(Awake.on.filter { it }.map { }, nudges.filter { it != nudged }.map { }).first()
                }
            }
            offline = if (next == Next.Offline) offline + 1 else 0
        }
    }

    /** Waits [ms], or until something changes ([nudge]), or (given [mark]) until the network is back. */
    private suspend fun pause(ms: Long, mark: Int?, nudged: Int) {
        withTimeoutOrNull(ms) {
            val nudge = nudges.filter { it != nudged }.map { }
            if (mark == null) nudge.first() else merge(nudge, NetworkWatch.comebacks.filter { it != mark }.map { }).first()
        }
    }

    /** One step of listening: makes this TV's own subscription when it needs one, then pulls. */
    private suspend fun step(app: Context): Next {
        val shared = subscription(app)
        if (shared == null) {
            forgetOwn(app)
            setListening(app, DoorbellListening.Idle)
            return Next.After(IDLE_CHECK_MS)
        }
        if (!GoogleAuth.isConnected(app, GoogleAuth.Grant.NEST)) {
            // It hears the doorbell from the TV linked to it, if any.
            setListening(app, DoorbellListening.Idle)
            return Next.After(IDLE_CHECK_MS)
        }
        TvLinks.load(app)
        // Asleep, a TV that passes alerts on to no other TV has nobody to show them to.
        if (!Awake.on.value && TvLinks.linked.value.isEmpty()) return Next.Asleep
        return try {
            // Offline is "can't reach Google right now" (tried again soon), never "connect first".
            val token = GoogleAuth.requireToken(app, GoogleAuth.Grant.NEST, SIGNED_OUT)
            val target = target(app, token, shared)
            if (target == null) {
                setListening(app, DoorbellListening.Refused)
                Next.After(REFUSED_RETRY_MS)
            } else {
                pull(app, token, target)
            }
        } catch (e: GoogleAuth.AuthException.SignedOut) {
            setListening(app, DoorbellListening.Idle)
            Next.After(IDLE_CHECK_MS)
        } catch (e: GoogleAuth.AuthException.Rejected) {
            setListening(app, DoorbellListening.Failing("Keys not accepted", "Google doesn't accept the Client ID or secret. Fix them in the phone app: Settings → Connections → Google Drive."))
            Next.After(FAILING_RETRY_MS)
        } catch (e: PubSub.Answer) {
            answered(app, e)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (isNetworkFailure(e)) Next.Offline else { Log.w("Doorbell", "pull: $e"); Next.After(30_000) }
        }
    }

    /** What Google's answer [e] (not a success) means for listening. */
    private fun answered(app: Context, e: PubSub.Answer): Next {
        Log.w("Doorbell", e.message.orEmpty())
        return when (e.code) {
            // A token Google won't take (the TV's clock was off, or access just revoked): renewed on the next try.
            401 -> { GoogleAuth.forgetAccess(app, GoogleAuth.Grant.NEST); Next.After(5_000) }
            403 -> {
                setListening(app, DoorbellListening.Failing("Google refused", "Google turned down this TV's doorbell subscription. Connect the cameras again on this TV, and check the subscription in the phone app: Settings → Connections → Cameras."))
                Next.After(FAILING_RETRY_MS)
            }
            404 -> {
                setListening(app, DoorbellListening.Failing("Subscription not found", "Google can't find the doorbell's subscription. Check its name in the phone app: Settings → Connections → Cameras."))
                Next.After(FAILING_RETRY_MS)
            }
            429 -> Next.After(60_000)
            else -> Next.After(30_000)
        }
    }

    /** The subscription to pull, and whether it's this TV's own. */
    private data class Target(val name: String, val own: Boolean)

    /** When this TV last made its own subscription (a pull that can't find it right after doesn't loop). */
    @Volatile private var ownMadeAt = 0L

    /**
     * Which subscription this TV pulls: its own (made now if need be), or, where Google won't make
     * it one, the family's itself when that was set here; null when it should hear the doorbell
     * through another TV instead.
     */
    private fun target(app: Context, token: String, shared: String): Target? {
        val p = prefs(app)
        p.getString(K_OWN, null)?.let { own ->
            if (p.getString(K_OWN_FOR, null) == shared) return Target(own, own = true)
            // Made for a subscription the family has since changed: it goes (it would only fill up unread).
            runCatching { PubSub.delete(token, own) }
            p.edit().remove(K_OWN).remove(K_OWN_FOR).apply()
        }
        val refusedLately = p.getString(K_REFUSED_FOR, null) == shared &&
            System.currentTimeMillis() - p.getLong(K_REFUSED_AT, 0L) in 0 until REFUSED_RETRY_MS
        if (!refusedLately) {
            try {
                val own = makeOwn(app, token, shared)
                p.edit().putString(K_OWN, own).putString(K_OWN_FOR, shared).remove(K_REFUSED_FOR).remove(K_REFUSED_AT).apply()
                ownMadeAt = System.currentTimeMillis()
                Log.i("Doorbell", "listening on this TV's own subscription")
                return Target(own, own = true)
            } catch (e: PubSub.Answer) {
                if (e.code != 403) throw e
                Log.i("Doorbell", "Google won't make this TV a subscription of its own (${e.status})")
                p.edit().putString(K_REFUSED_FOR, shared).putLong(K_REFUSED_AT, System.currentTimeMillis()).apply()
            }
        }
        // As before each TV had its own: the TV it was set on listens, and passes rings on.
        return if (setHere(app)) Target(shared, own = false) else null
    }

    /** Makes this TV's own subscription on the topic of the family's [shared] one; returns its name. */
    private fun makeOwn(app: Context, token: String, shared: String): String {
        val topic = PubSub.subscription(token, shared).optString("topic")
        if (topic.isBlank() || topic == DELETED_TOPIC) throw PubSub.Answer(404, "TOPIC_DELETED")
        val name = ownSubscription(shared, TvLinks.id(app)) ?: throw PubSub.Answer(400, "BAD_NAME")
        try {
            PubSub.create(token, name, ownSubscriptionBody(topic))
        } catch (e: PubSub.Answer) {
            if (e.code != 409) throw e
            // Made before (by this TV, whose settings were cleared since): kept when it's on the
            // family's topic, made again when it isn't.
            if (PubSub.subscription(token, name).optString("topic") != topic) {
                PubSub.delete(token, name)
                PubSub.create(token, name, ownSubscriptionBody(topic))
            }
        }
        return name
    }

    /** The family's subscription was removed: this TV's own goes too (if it can't now, it goes by itself in a day). */
    private fun forgetOwn(app: Context) {
        val p = prefs(app)
        val own = p.getString(K_OWN, null) ?: return
        if (GoogleAuth.isConnected(app, GoogleAuth.Grant.NEST)) runCatching {
            PubSub.delete(GoogleAuth.requireToken(app, GoogleAuth.Grant.NEST, SIGNED_OUT), own)
        }
        p.edit().remove(K_OWN).remove(K_OWN_FOR).apply()
    }

    /** One pull from [target]: handles any rings, acknowledges everything. */
    private suspend fun pull(app: Context, token: String, target: Target): Next {
        val messages = try {
            PubSub.pull(token, target.name)
        } catch (e: PubSub.Answer) {
            if (!target.own || (e.code != 404 && e.code != 403)) throw e
            // Gone (deleted after a day unused, the TV having been off; or in Google Cloud) or no
            // longer allowed: made again now (or found refused). Not straight after making it.
            prefs(app).edit().remove(K_OWN).remove(K_OWN_FOR).apply()
            return if (System.currentTimeMillis() - ownMadeAt < 60_000) Next.After(30_000) else Next.Now
        }
        setListening(app, if (target.own) DoorbellListening.Own else DoorbellListening.Shared)
        if (messages.length() == 0) return Next.After(EMPTY_PAUSE_MS)
        // Acknowledged first: if handling one goes wrong, it still isn't delivered (and announced) again.
        val acks = JSONArray()
        for (i in 0 until messages.length()) acks.put(messages.getJSONObject(i).optString("ackId"))
        runCatching { PubSub.acknowledge(token, target.name, acks) }.onFailure { Log.w("Doorbell", "acknowledge: ${it.message}") }
        for (i in 0 until messages.length()) messages.getJSONObject(i).optJSONObject("message")?.let { handle(app, it) }
        return Next.Now
    }

    /** One message from Google: each event in it, if it's news. */
    private suspend fun handle(app: Context, message: JSONObject) {
        val id = message.optString("messageId")
        if (id.isNotEmpty() && synchronized(seen) { seen.put(id, Unit) != null }) return
        val json = runCatching { String(Base64.decode(message.optString("data"), Base64.DEFAULT)) }.getOrNull() ?: return
        val now = System.currentTimeMillis()
        for (event in cameraEvents(json)) {
            if (isStale(event.at, now)) continue
            val wanted = when (event.kind) {
                "person" -> personAlerts(app)
                "motion" -> motionAlerts(app)
                else -> true
            }
            if (!wanted) continue
            ring(app, event.camera, labelFor(event.camera).orEmpty(), relay = true, kind = event.kind, session = event.session)
        }
    }

    /**
     * A camera's name for an alert: from the saved camera list (read at start-up), or, for a camera
     * it doesn't know, from Google: once in each run of the app, waiting at most 2 s.
     */
    private suspend fun labelFor(camera: String): String? {
        Nest.awaitSaved(2_000)
        return Nest.label(camera) ?: if (Nest.listOnce(2_000)) Nest.label(camera) else null
    }
}
