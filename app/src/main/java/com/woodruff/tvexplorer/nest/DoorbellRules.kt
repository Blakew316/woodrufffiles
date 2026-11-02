package com.woodruff.tvexplorer.nest

import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

// The doorbell's rules, kept apart from Android and the network so they can be unit-tested:
// what Google's messages say, which of them are news, how a TV's own subscription is named and
// made, how Settings describes the doorbell, and where a ring's camera opens.

// ---- What Google's messages say -----------------------------------------------------------------

/** One event in a message from Google: a doorbell press, or a person or motion seen. */
internal data class CameraEvent(
    /** The camera or doorbell's resource name: enterprises/{project}/devices/{id}. */
    val camera: String,
    /** "doorbell", "person" or "motion". */
    val kind: String,
    /**
     * The visit it belongs to (Nest's eventSessionId): every message about one visit carries the
     * same one. Null from devices that don't say.
     */
    val session: String?,
    /** When it happened, by Google's clock; null when the message doesn't say. */
    val at: Long?,
)

/** The events Woodruff announces, by Google's names, the press first. */
private val EVENT_KINDS = linkedMapOf(
    "sdm.devices.events.DoorbellChime.Chime" to "doorbell",
    "sdm.devices.events.CameraPerson.Person" to "person",
    "sdm.devices.events.CameraMotion.Motion" to "motion",
)

/**
 * The events in one message ([json]: its data, decoded). Each counts on its own: one message can
 * carry a press and a person seen. The press comes first, so a person seen in the same message
 * as the press it belongs to doesn't pop up a card of its own. Anything else in a message (a clip
 * preview, a sound) is left out.
 */
internal fun cameraEvents(json: String): List<CameraEvent> {
    val event = runCatching { JSONObject(json) }.getOrNull() ?: return emptyList()
    val update = event.optJSONObject("resourceUpdate") ?: return emptyList()
    val events = update.optJSONObject("events") ?: return emptyList()
    val camera = update.optString("name")
    val at = eventTime(event.optString("timestamp"))
    return EVENT_KINDS.mapNotNull { (key, kind) ->
        if (!events.has(key)) return@mapNotNull null
        val session = events.optJSONObject(key)?.optString("eventSessionId")?.trim()?.ifEmpty { null }
        CameraEvent(camera, kind, session, at)
    }
}

/** Google's time ("2026-11-02T19:42:05.123Z", always in UTC) in ms since 1970; null when it isn't one. */
internal fun eventTime(s: String?): Long? {
    if (s == null || s.length < 19) return null
    return runCatching {
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC"); isLenient = false }
            .parse(s.take(19))?.time
    }.getOrNull()
}

/**
 * Whether an event from [at] is too old to announce at [now]: a backlog from while the TV was off
 * or asleep isn't news any more. One without a time is taken as just now.
 */
internal fun isStale(at: Long?, now: Long): Boolean = at != null && now - at > 90_000

/**
 * One visit, one ring. Google sends several messages about one visit (Nest's eventSessionId): a
 * battery doorbell's press, and about 45 s later the same press again with its clip. A visit rings
 * at most once, and once it has rung, no person or motion card follows it; a person seen before the
 * press still shows, and the press still rings. Each kind shows once per visit. It's the same on
 * every TV, whether the event came from its own subscription or from the TV that passed it on, so
 * hearing a visit both ways rings once. Events without a visit (older devices) are always new here:
 * the per-camera pause in [Doorbell.ring] covers them. The last [max] visits are remembered.
 */
internal class Visits(private val max: Int = 200) {
    private val seen = object : LinkedHashMap<String, MutableSet<String>>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, MutableSet<String>>?) = size > max
    }

    /** Whether [kind] seen by [camera] in the visit [session] is news; if it is, it's noted. */
    @Synchronized
    fun admit(camera: String, session: String?, kind: String): Boolean {
        if (session.isNullOrBlank()) return true
        val kinds = seen.getOrPut("$camera|$session") { HashSet() }
        if ("doorbell" in kinds || kind in kinds) return false
        kinds += kind
        return true
    }
}

// ---- This TV's own subscription -----------------------------------------------------------------

private val SUBSCRIPTION = Regex("^projects/([^/]+)/subscriptions/([^/]+)$")

/** Whether [name] is a whole Pub/Sub subscription name: projects/{project}/subscriptions/{id}. */
internal fun isSubscription(name: String): Boolean = SUBSCRIPTION.matches(name)

/**
 * This TV's own subscription, made from the family's [shared] one: in the same Cloud project, and
 * named after it and this TV's random id ([tvId]), so a TV always finds its own again:
 * projects/p/subscriptions/doorbell-tv-1a2b3c4d5e6f. Null when [shared] isn't a subscription name.
 * Pub/Sub allows ids of up to 255 characters.
 */
internal fun ownSubscription(shared: String, tvId: String): String? {
    val (project, id) = SUBSCRIPTION.matchEntire(shared.trim())?.destructured ?: return null
    val tv = tvId.lowercase(Locale.ROOT).filter { it in 'a'..'z' || it in '0'..'9' }.take(12)
    if (tv.isEmpty()) return null
    val suffix = "-tv-$tv"
    return "projects/$project/subscriptions/${id.take(255 - suffix.length)}$suffix"
}

/**
 * How a TV's own subscription is made: on the family's [topic], keeping messages 10 minutes (a ring
 * older than that is no news), and gone by itself after a day nobody pulls it, so a TV removed or
 * reset leaves nothing behind (a TV that comes back after that makes it again). Both are Pub/Sub's
 * minimums. The label tells the family, in Google Cloud, where it came from.
 */
internal fun ownSubscriptionBody(topic: String): JSONObject = JSONObject()
    .put("topic", topic)
    .put("ackDeadlineSeconds", 10)
    .put("messageRetentionDuration", "600s")
    .put("expirationPolicy", JSONObject().put("ttl", "86400s"))
    .put("labels", JSONObject().put("made-by", "woodruff-family-tv"))

/**
 * Whether Google's answer [code] to making a TV's own subscription means it won't make one (not
 * allowed, or not in that form): the TV then hears the doorbell as before. Not for a token to renew
 * (401), something missing (404: the family's subscription or its topic, which no TV can use), or
 * Google busy (429, 5xx), which are tried again.
 */
internal fun isRefusal(code: Int): Boolean = code in 400..499 && code != 401 && code != 404 && code != 408 && code != 429

/** Pub/Sub's topic name for a subscription whose topic was deleted: it will never deliver anything. */
internal const val DELETED_TOPIC = "_deleted-topic_"

// ---- How Settings describes the doorbell --------------------------------------------------------

/** What this TV's doorbell listener is doing. */
sealed interface DoorbellListening {
    /** Not pulling: nothing set up, the cameras aren't connected here, the TV is asleep, or not started yet. */
    data object Idle : DoorbellListening
    /** On this TV's own subscription. */
    data object Own : DoorbellListening
    /** On the family's subscription itself: Google wouldn't make this TV one of its own, and it was set here. */
    data object Shared : DoorbellListening
    /** Google wouldn't make this TV a subscription of its own: it hears the doorbell through another TV. */
    data object Refused : DoorbellListening
    /** Set up, but it can't listen, for a reason a person has to fix: [value] in a word or two, [about] says what to do. */
    data class Failing(val value: String, val about: String) : DoorbellListening
}

/**
 * How this TV hears the doorbell: Settings → Advanced → Doorbell alerts, and the TV health report's
 * "Doorbell alerts" row (its JSON state is [State.id]). [value] is the row's value, [about] its
 * explanation.
 */
data class DoorbellAlerts(val state: State, val value: String, val about: String) {
    enum class State(val id: String) { WATCHING("watching"), THROUGH("through"), OFF("off"), FAILING("failing") }

    /** Something a person has to fix: amber in Settings, and "needs attention" in the health report. */
    val attention: Boolean get() = state == State.FAILING
}

/**
 * [DoorbellAlerts] from what this TV knows: whether the family's [subscription] reached it, whether
 * its cameras are connected ([camerasHere]), whether it passes alerts on to TVs linked to it
 * ([relaysOut]), which TV last passed one on to it ([relayFrom]), and what its own listener is doing.
 * Offline never changes it: the doorbell comes back by itself.
 */
internal fun doorbellAlerts(
    subscription: Boolean, camerasHere: Boolean, relaysOut: Boolean, relayFrom: String?, listening: DoorbellListening,
): DoorbellAlerts {
    val from = relayFrom ?: "another TV"
    val off = DoorbellAlerts(DoorbellAlerts.State.OFF, "Off", "Set it up in the phone app.")
    return when {
        camerasHere && subscription -> when (listening) {
            is DoorbellListening.Failing -> DoorbellAlerts(DoorbellAlerts.State.FAILING, listening.value, listening.about)
            DoorbellListening.Refused -> DoorbellAlerts(
                DoorbellAlerts.State.FAILING, "Google refused",
                "Google's project doesn't let this TV listen on its own; it hears the doorbell through $from instead.",
            )
            else -> DoorbellAlerts(DoorbellAlerts.State.WATCHING, "Watching", "Doorbell presses pop up on this TV, even when the other TVs are off.")
        }
        // Cameras here, but the family's subscription hasn't reached this TV (a TV that relays to it
        // runs an older Woodruff): it hears the doorbell through that TV meanwhile.
        camerasHere -> if (relayFrom == null) off
            else DoorbellAlerts(DoorbellAlerts.State.THROUGH, "Through $relayFrom", "This TV hears the doorbell from $relayFrom while that TV is on.")
        // No cameras here. A TV that passes alerts on to others, and hears from none, hears nothing.
        subscription || relayFrom != null -> if (relaysOut && relayFrom == null) {
            DoorbellAlerts(DoorbellAlerts.State.OFF, "Off", "Connect the cameras on this TV to hear the doorbell.")
        } else {
            DoorbellAlerts(
                DoorbellAlerts.State.THROUGH, "Through $from",
                "This TV hears the doorbell from $from while that TV is on. Connect the cameras on this TV to hear it on its own.",
            )
        }
        else -> off
    }
}

/** The family's person / motion choice as the TVs share it: "person=1;motion=0". */
internal fun alertsKey(person: Boolean, motion: Boolean): String = "person=${if (person) 1 else 0};motion=${if (motion) 1 else 0}"

/** [alertsKey] back into (person, motion); null for anything else. */
internal fun parseAlertsKey(value: String?): Pair<Boolean, Boolean>? {
    val parts = value?.split(';')?.mapNotNull { part -> part.split('=').takeIf { it.size == 2 }?.let { it[0].trim() to it[1].trim() } }?.toMap() ?: return null
    fun flag(name: String) = when (parts[name]) { "1" -> true; "0" -> false; else -> null }
    return (flag("person") ?: return null) to (flag("motion") ?: return null)
}

/** A TV's name as another TV sent it, fit to show ("Living Room"); null when there's none. */
internal fun cleanTvName(name: String?): String? =
    name?.filterNot { it.isISOControl() }?.trim()?.take(40)?.trim()?.ifEmpty { null }

// ---- Where a ring's camera opens ----------------------------------------------------------------

/**
 * Where a doorbell ring's camera opens on this TV (doorbell.md §2), or null for its card: nothing
 * opens without this TV's own cameras ([camerasHere]). Woodruff's screensaver covers everything, so
 * it comes first: it wakes for the camera, given the right to open it over other apps
 * ([canOverlay], asked only then); without it, the card. Then a doorbell view already up (it
 * switches to the ringing camera), the slideshow, and Woodruff's own screens ([inApp]). The
 * player, the photo viewer and other apps get the card.
 */
internal fun pickHost(
    camerasHere: Boolean, dreaming: Boolean, canOverlay: () -> Boolean, views: Int, slideshows: Int, inApp: Boolean,
): Doorbell.Host? = when {
    !camerasHere -> null
    dreaming -> if (canOverlay()) Doorbell.Host.SCREENSAVER else null
    views > 0 -> Doorbell.Host.VIEW
    slideshows > 0 -> Doorbell.Host.SLIDESHOW
    inApp -> Doorbell.Host.APP
    else -> null
}

/**
 * Which rings a screen has taken to open, so each ring opens once, on the one screen it was meant
 * for, and only while it's fresh: a screen that starts again a moment later (the slideshow, back
 * from the camera) never opens the same ring twice.
 */
internal class RingClaims(private val max: Int = 32, private val freshMs: Long = 10_000) {
    private val taken = LinkedHashSet<Long>()

    /** True once for [ring], for [host] (the screen it's meant for) at [now], while it's fresh. */
    @Synchronized
    fun claim(ring: Doorbell.Ring, host: Doorbell.Host, now: Long): Boolean {
        if (ring.host != host || now - ring.at > freshMs || !taken.add(ring.id)) return false
        if (taken.size > max) taken.remove(taken.first())
        return true
    }

    /** Whether a screen took the ring [id]. */
    @Synchronized
    fun taken(id: Long): Boolean = id in taken
}

// ---- The saved camera list ----------------------------------------------------------------------

/** The camera list as the TV keeps it (names, labels and rooms: nothing secret), as JSON. */
internal fun camerasToJson(cameras: List<Camera>): String = JSONArray().apply {
    cameras.forEach { c ->
        put(JSONObject().put("name", c.name).put("label", c.label).put("room", c.room ?: "").put("webRtc", c.webRtc).put("doorbell", c.doorbell))
    }
}.toString()

/** [camerasToJson] back into cameras; nothing for anything unreadable, and an entry without a name is skipped. */
internal fun camerasFromJson(json: String?): List<Camera> {
    val arr = runCatching { JSONArray(json ?: return emptyList()) }.getOrNull() ?: return emptyList()
    return (0 until arr.length()).mapNotNull { i ->
        val o = arr.optJSONObject(i) ?: return@mapNotNull null
        val name = o.optString("name").ifBlank { return@mapNotNull null }
        Camera(name, o.optString("label").ifBlank { "Camera" }, o.optString("room").ifBlank { null }, o.optBoolean("webRtc"), o.optBoolean("doorbell"))
    }
}
