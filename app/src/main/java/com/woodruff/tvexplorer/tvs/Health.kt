package com.woodruff.tvexplorer.tvs

import com.woodruff.tvexplorer.nest.DoorbellAlerts
import com.woodruff.tvexplorer.util.Format
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.roundToInt

// A TV's health report: every fact and every word that Settings → TV health on the TV and the
// phone's Settings → TVs → a TV show. It is built in one place ([buildHealth]) from facts the TV
// gathers ([Health]), so the two apps can never word the same thing differently; a linked TV's
// report travels as JSON ([HealthReport.toJson]) and keeps its own TV's words. Plain logic only:
// no Android here, so all of it is unit-tested.

private const val MINUTE = 60_000L
private const val HOUR = 60 * MINUTE
private const val DAY = 24 * HOUR
private const val GIB = 1024L * 1024 * 1024

// ---- The vocabulary (health-tv.md §3.1) ------------------------------------------------------

/** The four Google sign-ins a report shows, as the JSON names them. */
enum class HealthGrant(val json: String, val title: String, private val owner: String) {
    DRIVE("drive", "Google Drive", "Google Drive’s"),
    PHOTOS("photos", "Google Photos", "Google Photos’"),
    CALENDAR("calendar", "Google Calendar", "Google Calendar’s"),
    CAMERAS("cameras", "Cameras", "the cameras’");

    /** "Google Drive’s sign-in ended": its attention item. */
    val endedWords: String get() = "$owner sign-in ended"

    /** Where [tv] signs this one in again, for an ended sign-in's about text. */
    fun whereOn(tv: String): String = when (this) {
        DRIVE -> "On $tv: open Streaming and scan the code with your phone."
        PHOTOS -> "On $tv: Settings → Accounts → Google Photos."
        CALENDAR -> "On $tv: Settings → Accounts → Google Calendar."
        CAMERAS -> "On $tv: open the Command Center and scan the code with your phone."
    }

    companion object {
        fun of(json: String?): HealthGrant? = entries.firstOrNull { it.json == json }
    }
}

/** How one sign-in is doing right now. Only [EXPIRED] and [KEYS_REJECTED] need a person. */
enum class SignInState(val json: String) {
    /** Signed in, and nothing says Google can't be reached. */
    SIGNED_IN("signedIn"),
    /** The TV has no network: it signs in again by itself once it has. */
    OFFLINE("offline"),
    /** The network is fine but Google didn't answer the last renewal. */
    UNREACHABLE("unreachable"),
    /** Google revoked or expired it. */
    EXPIRED("expired"),
    /** Google turns down the saved Client ID or secret (invalid_client). */
    KEYS_REJECTED("keysRejected"),
    /** Keys saved, never signed in. */
    NOT_SIGNED_IN("notSignedIn"),
    /** No Google keys (or, for the cameras, no Device Access project). */
    NOT_SET_UP("notSetUp");

    val needsAttention: Boolean get() = this == EXPIRED || this == KEYS_REJECTED

    /** The value word, as Settings → Accounts says it for that sign-in. */
    fun words(grant: HealthGrant): String = when (this) {
        SIGNED_IN -> if (grant == HealthGrant.DRIVE) "Signed in" else "Connected"
        OFFLINE -> "Offline"
        UNREACHABLE -> "Can’t reach Google"
        EXPIRED -> "Sign-in ended"
        KEYS_REJECTED -> "Keys not accepted"
        NOT_SIGNED_IN -> if (grant == HealthGrant.DRIVE) "Not signed in" else "Not connected"
        NOT_SET_UP -> if (grant == HealthGrant.DRIVE) "Not set up" else "Not connected"
    }

    companion object {
        fun of(json: String?): SignInState? = entries.firstOrNull { it.json == json }
    }
}

/** Why Android last closed Woodruff, for the reasons worth telling (everything else is routine). */
enum class ExitKind(val json: String, val words: String) {
    LOW_MEMORY("lowMemory", "Low memory"),
    ANR("anr", "Stopped responding"),
    CRASH("crash", "Crashed"),
    NATIVE_CRASH("nativeCrash", "Crashed"),
    INIT_FAILURE("initFailure", "Couldn’t start"),
}

/** One time Android closed Woodruff ([kind], at [at]); for a native crash, whether its trace shows the camera or video code. */
data class AppExit(val kind: ExitKind, val at: Long, val inMediaCode: Boolean = false)

/** How one TV hears the doorbell, as Settings → Advanced → Doorbell alerts says it (doorbell.md §3.5). */
enum class DoorbellState(val json: String) { WATCHING("watching"), THROUGH("through"), OFF("off"), FAILING("failing") }

/**
 * The doorbell row: its state, its value ([text]), what it's about ([detail]) and whether a person has
 * to fix it ([attention]: amber, and the report's "doorbell alerts aren't working").
 */
data class DoorbellFact(val state: DoorbellState, val text: String, val detail: String?, val attention: Boolean = state == DoorbellState.FAILING)

/**
 * The doorbell row from how this TV hears the doorbell ([com.woodruff.tvexplorer.nest.Doorbell.alerts]),
 * word for word as Settings → Advanced says it: "Watching", "Through Living Room" (a TV without its own
 * subscription, hearing it from the hub), "Off", or what's wrong ("Google refused"), which needs attention.
 * Offline never changes it: the doorbell comes back by itself.
 */
fun doorbellFact(alerts: DoorbellAlerts): DoorbellFact = DoorbellFact(
    state = when (alerts.state) {
        DoorbellAlerts.State.WATCHING -> DoorbellState.WATCHING
        DoorbellAlerts.State.THROUGH -> DoorbellState.THROUGH
        DoorbellAlerts.State.OFF -> DoorbellState.OFF
        DoorbellAlerts.State.FAILING -> DoorbellState.FAILING
    },
    text = alerts.value,
    detail = alerts.about,
    attention = alerts.attention,
)

private val NETWORK_WORDS = listOf("reach google", "resolve host", "timeout", "timed out", "failed to connect", "connection", "network", "ssl", "unreachable", "no route")

/**
 * An update's failure in the family's words, as a sentence. Updater's own messages are already
 * plain, written to follow "last try:" in Settings ("the TV is out of space"), so they only gain a
 * capital and a full stop; a raw one from the package installer or the network (a code, an
 * exception) is put into words here.
 */
fun plainUpdateReason(message: String?): String? {
    val m = message?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val low = m.lowercase()
    return when {
        "insufficient_storage" in low || "not enough space" in low || "no space" in low -> "There wasn’t enough free space."
        "update_incompatible" in low || "signatures do not match" in low || "signature" in low -> "The update isn’t signed like the Woodruff on this TV."
        "version_downgrade" in low -> "It was older than the Woodruff on this TV."
        "aborted" in low || "cancel" in low -> "Someone chose Cancel."
        "blocked" in low || "user_restricted" in low || "policy" in low -> "This TV’s settings blocked it."
        "install_failed" in low || "install_parse" in low || "status_failure" in low -> "The package installer said no."
        "github answered" in low || "http" in low -> "GitHub didn’t answer. It tries again by itself."
        NETWORK_WORDS.any { it in low } -> "The TV couldn’t reach GitHub. It tries again by itself."
        "exception" in low || "java." in low -> "The package installer said no."
        else -> capitalised(if (m.endsWith('.')) m else "$m.")
    }
}

// ---- Dates and times as the TV writes them ----------------------------------------------------

/**
 * How moments read in a report, at [now]: today the time ("3:10 AM", in the TV's own 12- or
 * 24-hour format, [time]), then "yesterday", then the date ("Oct 30", [date]), in [zone].
 */
class HealthClock(val now: Long, private val zone: TimeZone, private val time: (Long) -> String, private val date: (Long) -> String) {
    /** Calendar days from [ms] to now in the TV's time zone: 0 today, 1 yesterday. */
    fun daysAgo(ms: Long): Int {
        fun midnight(t: Long) = Calendar.getInstance(zone).apply {
            timeInMillis = t
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        // Rounded: a day with a clock change has 23 or 25 hours.
        return Math.round((midnight(now) - midnight(ms)).toDouble() / DAY).toInt()
    }

    /** "just now", "3 min ago", "2 h ago", "yesterday", "Oct 30": how long ago, for "Checked …" and "seen …". */
    fun ago(ms: Long): String {
        val d = now - ms
        return when {
            d < MINUTE -> "just now"
            d < HOUR -> "${d / MINUTE} min ago"
            d < 12 * HOUR || daysAgo(ms) == 0 -> "${d / HOUR} h ago"
            daysAgo(ms) == 1 -> "yesterday"
            else -> date(ms)
        }
    }

    /** "3:10 AM" today, "yesterday", else "Oct 30": a moment in a value. */
    fun moment(ms: Long): String = when (daysAgo(ms)) {
        0 -> time(ms)
        1 -> "yesterday"
        else -> date(ms)
    }

    /** "9:41 PM" today, "Yesterday, 9:41 PM", else "Nov 1, 9:41 PM": the last crash's value. */
    fun stamp(ms: Long): String = when (daysAgo(ms)) {
        0 -> time(ms)
        1 -> "Yesterday, ${time(ms)}"
        else -> "${date(ms)}, ${time(ms)}"
    }

    /** "today", "yesterday", "on Nov 1": a day inside a sentence ("Woodruff crashed today"). */
    fun onDay(ms: Long): String = when (daysAgo(ms)) {
        0 -> "today"
        1 -> "yesterday"
        else -> "on ${date(ms)}"
    }

    /** "Nov 1, 9:41 PM", always with the date. */
    fun full(ms: Long): String = "${date(ms)}, ${time(ms)}"
}

// ---- What a report is made from ---------------------------------------------------------------

/** One sign-in's state, and since when (for one that ended). */
data class SignInFact(val state: SignInState, val since: Long? = null)

/** The update facts: Updater's step ([step], as the JSON names it), the build it's about, and the settings. */
data class UpdateFact(
    val step: String,
    val latestName: String? = null,
    val progress: Float = 0f,
    /** Updater's message: why an install didn't go in (while ready) or why the check failed. */
    val message: String? = null,
    /** When the waiting update became ready (null: none waiting). */
    val readySince: Long? = null,
    val auto: Boolean = true,
    val testTv: Boolean = false,
    /**
     * Whether the update this is about needs someone's press to install (Updater.needsPress): always
     * on Android 11 and older, on 12+ only once Android asked for one. One that doesn't installs by
     * itself when nobody is using Woodruff, however long it has been ready, so it never waits for a press.
     */
    val needsPress: Boolean = true,
    /** This TV asks before each update (Updater.asksEachTime), so each one is offered as Woodruff opens. */
    val asks: Boolean = false,
)

/** How this copy of Woodruff came to be ([com.woodruff.tvexplorer.update.Updater.installFacts]). */
data class InstallFact(val summary: String, val by: String, val firstInstall: Long, val lastUpdate: Long)

/** The last crash Woodruff's own handler kept: when, and the report. */
data class CrashFact(val at: Long, val report: String)

/** Which shared keys this TV has: present or not, never their values. */
data class KeysFact(val googleClientId: Boolean, val googleClientSecret: Boolean, val camerasProject: Boolean, val tmdb: Boolean)

/** Everything a report says about a TV, gathered by [Health] on that TV: facts only, no words. */
data class HealthFacts(
    val id: String,
    val name: String,
    val version: String,
    /** Android's API level: 30 and up say why Android closed Woodruff. */
    val sdk: Int,
    val uptimeMs: Long,
    val signIns: Map<HealthGrant, SignInFact>,
    val doorbell: DoorbellFact,
    val update: UpdateFact,
    val installed: InstallFact,
    /** Whether Woodruff may install apps ("Install unknown apps"); null below Android 8. */
    val installAllowed: Boolean?,
    val crash: CrashFact?,
    /** When the phone last cleared the crash report: crashes before it no longer need attention. */
    val crashesClearedAt: Long = 0,
    /** Android's reasons for closing Woodruff (Android 11+); null below it. */
    val exits: List<AppExit>?,
    val freeBytes: Long?,
    val keys: KeysFact,
)

// ---- The report -----------------------------------------------------------------------------

/**
 * One thing that needs attention: its [id], the sentence fragment both apps show ([words]), the
 * about text for its row ([detail], naming the TV), what the phone can do about it ([fix]) and,
 * for a sign-in, which ([grant]).
 */
data class HealthItem(val id: String, val words: String, val detail: String, val fix: String, val grant: String? = null)

data class SignInRow(val state: SignInState, val text: String, val since: Long? = null) {
    val attention: Boolean get() = state.needsAttention
}

/** The Version row: [asks] is a TV whose updates each wait to be offered (a report from an older Woodruff never says so). */
data class UpdatesRow(val step: String, val text: String, val attention: Boolean, val auto: Boolean, val testTv: Boolean, val reason: String? = null, val asks: Boolean = false)
data class LastTryRow(val text: String, val reason: String)
data class PermissionRow(val allowed: Boolean, val text: String, val attention: Boolean)
data class CrashRow(val at: Long, val text: String, val attention: Boolean, val summary: String, val lines: String, val report: String)
data class ClosedRow(val text: String, val attention: Boolean, val detail: String?)
data class SpaceRow(val free: Long, val text: String, val attention: Boolean)
data class KeysRow(val text: String, val googleClientId: Boolean, val googleClientSecret: Boolean, val camerasProject: Boolean, val tmdb: Boolean)

/**
 * A TV's report, with every value already in words. [at] is when it was made (on the hub: when it
 * arrived there, by the hub's clock).
 */
data class HealthReport(
    val id: String,
    val name: String,
    val at: Long,
    val android: Int,
    val version: String,
    val attention: List<HealthItem>,
    val installed: InstallFact,
    val updates: UpdatesRow,
    val lastUpdateTry: LastTryRow?,
    val installPermission: PermissionRow?,
    val signIns: Map<HealthGrant, SignInRow>,
    val doorbell: DoorbellFact,
    val lastCrash: CrashRow?,
    val closedByAndroid: ClosedRow?,
    val freeSpace: SpaceRow?,
    val keys: KeysRow,
    val uptime: Long,
) {
    val good: Boolean get() = attention.isEmpty()

    /** The about sentence: "Google Drive’s sign-in ended, and the last update didn’t install." or "Everything’s working." */
    val sentence: String get() = sentenceOf(attention)

    /**
     * What /api/status and /api/tvs carry: good or attention, how many, the first, capitalised, and which sign-ins
     * Google ended and when (the hub tells its phones about those once each: [SignInAlerts]).
     */
    val summary: HealthSummary get() = HealthSummary(
        if (good) "good" else "attention", attention.size, attention.firstOrNull()?.words?.let(::capitalised),
        signIns.entries.mapNotNull { (g, s) -> s.since?.takeIf { s.state == SignInState.EXPIRED }?.let { g.json to it } }.toMap(),
    )

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("name", name).put("at", at).put("android", android).put("version", version)
        .put("status", if (good) "good" else "attention")
        .put("sentence", sentence)
        .put("attention", JSONArray().apply {
            attention.forEach { i ->
                put(JSONObject().put("id", i.id).put("words", capitalised(i.words)).put("detail", i.detail).put("fix", i.fix).apply { i.grant?.let { put("grant", it) } })
            }
        })
        .put("installedBy", JSONObject().put("summary", installed.summary).put("by", installed.by).put("firstInstall", installed.firstInstall).put("lastUpdate", installed.lastUpdate))
        .put("updates", JSONObject().put("step", updates.step).put("text", updates.text).put("attention", updates.attention)
            .put("auto", updates.auto).put("testTv", updates.testTv).put("asks", updates.asks).apply { updates.reason?.let { put("reason", it) } })
        .put("lastUpdateTry", lastUpdateTry?.let { JSONObject().put("text", it.text).put("reason", it.reason) } ?: JSONObject.NULL)
        .put("installPermission", installPermission?.let { JSONObject().put("allowed", it.allowed).put("text", it.text).put("attention", it.attention) } ?: JSONObject.NULL)
        .put("signIns", JSONObject().apply {
            signIns.forEach { (g, s) ->
                put(g.json, JSONObject().put("state", s.state.json).put("text", s.text).put("attention", s.attention).apply { s.since?.let { put("since", it) } })
            }
            put("doorbell", JSONObject().put("state", doorbell.state.json).put("text", doorbell.text).put("attention", doorbell.attention)
                .apply { doorbell.detail?.let { put("detail", it) } })
        })
        .put("lastCrash", lastCrash?.let {
            JSONObject().put("at", it.at).put("text", it.text).put("attention", it.attention).put("summary", it.summary).put("lines", it.lines).put("report", it.report)
        } ?: JSONObject.NULL)
        .put("closedByAndroid", closedByAndroid?.let { JSONObject().put("text", it.text).put("attention", it.attention).put("detail", it.detail ?: JSONObject.NULL) } ?: JSONObject.NULL)
        .put("freeSpace", freeSpace?.let { JSONObject().put("free", it.free).put("text", it.text).put("attention", it.attention) } ?: JSONObject.NULL)
        .put("keys", JSONObject().put("text", keys.text).put("googleClientId", keys.googleClientId).put("googleClientSecret", keys.googleClientSecret)
            .put("camerasProject", keys.camerasProject).put("tmdb", keys.tmdb))
        .put("uptime", uptime)

    companion object {
        /** A report from its JSON (another TV's, or one kept on disk); null when it isn't one. */
        fun fromJson(o: JSONObject?): HealthReport? = runCatching {
            o ?: return null
            if (!o.has("signIns") || !o.has("updates")) return null
            val signIns = o.getJSONObject("signIns")
            val u = o.getJSONObject("updates")
            val ib = o.optJSONObject("installedBy")
            val bell = signIns.optJSONObject("doorbell")
            HealthReport(
                id = o.optString("id"), name = o.optString("name"), at = o.optLong("at"), android = o.optInt("android"),
                version = o.optString("version"),
                attention = o.optJSONArray("attention")?.let { a ->
                    (0 until a.length()).mapNotNull { i ->
                        val x = a.optJSONObject(i) ?: return@mapNotNull null
                        HealthItem(x.optString("id"), fragment(x.optString("words")), x.optString("detail"), x.optString("fix", "none"), x.optString("grant").ifEmpty { null })
                    }
                }.orEmpty(),
                installed = InstallFact(ib?.optString("summary").orEmpty(), ib?.optString("by").orEmpty(), ib?.optLong("firstInstall") ?: 0, ib?.optLong("lastUpdate") ?: 0),
                updates = UpdatesRow(u.optString("step"), u.optString("text"), u.optBoolean("attention"), u.optBoolean("auto", true), u.optBoolean("testTv"), u.optString("reason").ifEmpty { null }, u.optBoolean("asks")),
                lastUpdateTry = o.optJSONObject("lastUpdateTry")?.let { LastTryRow(it.optString("text"), it.optString("reason")) },
                installPermission = o.optJSONObject("installPermission")?.let { PermissionRow(it.optBoolean("allowed"), it.optString("text"), it.optBoolean("attention")) },
                signIns = HealthGrant.entries.associateWith { g ->
                    val s = signIns.optJSONObject(g.json)
                    val state = SignInState.of(s?.optString("state")) ?: SignInState.NOT_SET_UP
                    SignInRow(state, s?.optString("text")?.ifEmpty { null } ?: state.words(g), s?.optLong("since")?.takeIf { it > 0 })
                },
                doorbell = (DoorbellState.entries.firstOrNull { it.json == bell?.optString("state") } ?: DoorbellState.OFF).let { state ->
                    DoorbellFact(
                        state, bell?.optString("text")?.ifEmpty { null } ?: "Off", bell?.optString("detail")?.ifEmpty { null },
                        bell?.optBoolean("attention", state == DoorbellState.FAILING) ?: false,
                    )
                },
                // Scrubbed here too: a report kept from a TV on an older Woodruff passes through this hub to its phones.
                lastCrash = o.optJSONObject("lastCrash")?.let {
                    CrashRow(it.optLong("at"), it.optString("text"), it.optBoolean("attention"), it.optString("summary"), scrubbed(it.optString("lines")), scrubbed(it.optString("report")))
                },
                closedByAndroid = o.optJSONObject("closedByAndroid")?.let { ClosedRow(it.optString("text"), it.optBoolean("attention"), it.optString("detail").takeIf { d -> d.isNotEmpty() && d != "null" }) },
                freeSpace = o.optJSONObject("freeSpace")?.let { SpaceRow(it.optLong("free"), it.optString("text"), it.optBoolean("attention")) },
                keys = o.optJSONObject("keys").let { k ->
                    KeysRow(k?.optString("text").orEmpty(), k?.optBoolean("googleClientId") == true, k?.optBoolean("googleClientSecret") == true, k?.optBoolean("camerasProject") == true, k?.optBoolean("tmdb") == true)
                },
                uptime = o.optLong("uptime"),
            )
        }.getOrNull()
    }
}

/**
 * A report in a few words, for lists: [status] "good" or "attention", how many things, and the first. [ended]: the
 * sign-ins Google ended (by [HealthGrant.json]) and since when, by that TV's clock (a Woodruff from before sends none).
 */
data class HealthSummary(val status: String, val count: Int, val first: String?, val ended: Map<String, Long> = emptyMap()) {
    val attention: Boolean get() = status == "attention"

    fun toJson(): JSONObject = JSONObject().put("status", status).put("count", count).put("first", first ?: JSONObject.NULL)
        .apply { if (ended.isNotEmpty()) put("ended", JSONObject().apply { ended.forEach { (g, at) -> put(g, at) } }) }

    companion object {
        fun fromJson(o: JSONObject?): HealthSummary? {
            o ?: return null
            val status = o.optString("status").ifEmpty { return null }
            val ended = o.optJSONObject("ended")?.let { e -> e.keys().asSequence().mapNotNull { g -> e.optLong(g).takeIf { it > 0 }?.let { g to it } }.toMap() }
            return HealthSummary(status, o.optInt("count"), o.optString("first").takeIf { it.isNotEmpty() && it != "null" }, ended.orEmpty())
        }
    }
}

internal const val SIGN_IN_ABOUT = "Sign-ins aren’t shared between TVs. Offline and “Can’t reach Google” clear up by themselves."
internal const val KEYS_DETAIL = "Google doesn’t accept the Client ID or secret. Fix them in the phone app: Settings → Connections → Google Drive."
internal const val CLOSED_ABOUT = "Android 11 and newer say why Woodruff stopped. Low memory now and then is normal on small TVs."
internal const val CRASH_ABOUT = "The phone app can copy the full report."
internal const val MEDIA_CRASH = "It crashed inside the camera or video code."
internal const val KEYS_ABOUT = "Keys entered on one TV are copied to every linked TV. Their values are never shown here."
internal const val UPDATES_ABOUT = "Updates install by themselves when nobody is using Woodruff."

internal fun permissionDetail(tv: String) = "Woodruff needs this to install its own updates. In $tv’s settings, allow Woodruff Family under Install unknown apps."
internal fun spaceDetail(tv: String) = "Updates and saved posters need room. Free some in Storage & apps on $tv."
internal fun waitingDetail(tv: String) = "On $tv, press Install when it asks."
/** The Version row's about for a TV that asks before each update: Settings → Updates' own words on this TV. */
internal fun asksDetail(tv: String, isThis: Boolean) =
    if (isThis) "This TV asks before each update, so it’s offered when you open Woodruff."
    else "$tv asks before each update, so it’s offered when you open Woodruff there."

/** [s] with its first letter capitalised: on the phone each fragment starts its own row. */
fun capitalised(s: String): String = s.replaceFirstChar { it.uppercaseChar() }

/**
 * A fragment as health-tv.md §3.1 writes it, from its capitalised form on the phone: lower case
 * first ("the last update didn’t install"), except names ("Google Drive’s…", "Woodruff…", "Android…").
 */
fun fragment(words: String): String {
    val first = words.substringBefore(' ')
    return if (first in NAMES) words else words.replaceFirstChar { it.lowercaseChar() }
}

private val NAMES = setOf("Google", "Woodruff", "Android", "TMDB")

/** Attention fragments as one sentence: "A, b, and c." with its first letter capitalised; none: "Everything’s working." */
fun sentenceOf(items: List<HealthItem>): String {
    val words = items.map { it.words }
    val joined = when (words.size) {
        0 -> return "Everything’s working."
        1 -> words[0]
        2 -> "${words[0]}, and ${words[1]}"
        else -> words.dropLast(1).joinToString(", ") + ", and " + words.last()
    }
    return capitalised(joined) + "."
}

/**
 * [text] with anything secret or personal taken out: a crash report leaves the TV in a health report (the
 * phone shows and copies it, and a linked hub keeps it), and an exception's message can carry the address it
 * failed on. Keys, tokens and codes in an address ("?api_key=…"), a "Bearer" token, network addresses and
 * email addresses each become "…"; the trace itself (classes, files, lines) stays as it was.
 */
fun scrubbed(text: String): String = text
    .replace(SECRET_PARAM) { it.groupValues[1] + "…" }
    .replace(BEARER, "Bearer …")
    .replace(EMAIL, "…")
    .replace(IPV4, "…")

private val SECRET_PARAM = Regex(
    """((?<![A-Za-z0-9_])(?:api_?key|key|k|access_token|refresh_token|id_token|token|code|state|client_secret|secret|password|sig)=)[^&;,\s"'<>]+""",
    RegexOption.IGNORE_CASE,
)
private val BEARER = Regex("""(?i)\bBearer\s+[A-Za-z0-9._~+/=-]+""")
private val EMAIL = Regex("""[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}""")
private val IPV4 = Regex("""(?<![\d.])(?:25[0-5]|2[0-4]\d|1?\d?\d)(?:\.(?:25[0-5]|2[0-4]\d|1?\d?\d)){3}(?![\d.])""")

/** "IllegalStateException in the player": a crash report in a few words, from its exception and Woodruff's first frame. */
fun crashSummary(report: String): String {
    val lines = report.lines().map { it.trim() }
    val exception = lines.firstOrNull { l -> Regex("""^[\w.$]+(Exception|Error|Throwable)\b""").containsMatchIn(l) }
        ?.substringBefore(':')?.substringAfterLast('.')?.substringAfterLast('$')
    val frame = lines.firstOrNull { it.startsWith("at com.woodruff.tvexplorer.") }?.removePrefix("at com.woodruff.tvexplorer.")
    val area = frame?.substringBefore('.')?.let { AREAS[it] }
    return when {
        exception == null -> "A crash"
        area == null -> exception
        else -> "$exception in $area"
    }
}

private val AREAS = mapOf(
    "player" to "the player", "nest" to "the cameras", "cloud" to "the library", "photos" to "Family Memories",
    "ui" to "a screen", "server" to "the phone connection", "update" to "updates", "tvs" to "linked TVs",
    "mail" to "mail", "calendar" to "the calendar", "screensaver" to "the slideshow", "remote" to "the remote",
    "notify" to "notices", "weather" to "the weather", "tmdb" to "posters",
)

// ---- Building a report (health-tv.md §3.1 and §3.4) --------------------------------------------

/**
 * The report for [f], every word included, at [clock]'s now. "Needs attention" is decided here
 * and nowhere else, by the seven rules of health-tv.md §3.1 (rule 7, a link that stopped working,
 * is the hub's to tell: see [TvHealthKind.REFUSED]).
 */
fun buildHealth(f: HealthFacts, clock: HealthClock): HealthReport {
    val now = clock.now
    val tv = f.name
    val items = mutableListOf<HealthItem>()

    // Rule 1: a sign-in that was set up has ended, or Google doesn't accept the keys.
    val signIns = HealthGrant.entries.associateWith { g ->
        val s = f.signIns[g] ?: SignInFact(SignInState.NOT_SET_UP)
        SignInRow(s.state, s.state.words(g), s.since.takeIf { s.state == SignInState.EXPIRED })
    }
    for (g in HealthGrant.entries) {
        if (signIns.getValue(g).state == SignInState.EXPIRED) {
            items += HealthItem("signin.${g.json}", g.endedWords, "Google ended this sign-in. ${g.whereOn(tv)}", "signin", g.json)
        }
    }
    if (signIns.values.any { it.state == SignInState.KEYS_REJECTED }) {
        items += HealthItem("signin.keys", "Google doesn’t accept the saved keys", KEYS_DETAIL, "keys")
    }

    // Rule 2: updates.
    val u = f.update
    val failed = u.step == "ready" && !u.message.isNullOrBlank()
    val reason = plainUpdateReason(u.message)
    if (failed) items += HealthItem("update.failed", "the last update didn’t install", reason ?: "The package installer said no.", "update")
    // Waiting for one press: ready for a day, and it can't go in by itself (Updater.needsPress). One
    // that can is installing at the next quiet moment: nothing for a person to do.
    val waiting = !failed && u.step == "ready" && u.needsPress && (u.readySince ?: now) <= now - DAY
    if (waiting) items += HealthItem("update.waiting", "an update is waiting for one press", waitingDetail(tv), "install")
    // Android 8 to 11 put every update to "Install unknown apps"; 12+ installs Woodruff's own updates
    // without it (a Sony answers no there while its settings say yes), and a build that needs a press
    // anyway shows as waiting.
    val notAllowed = u.auto && f.sdk in 26..30 && f.installAllowed == false
    if (notAllowed) items += HealthItem("update.notAllowed", "Woodruff isn’t allowed to install updates", permissionDetail(tv), "none")

    // Rules 3 and 4: crashes and Android's reasons in the last 7 days (one item each, the latest).
    val week = now - 7 * DAY
    val cleared = f.crashesClearedAt
    val crash = f.crash?.takeIf { it.at > cleared }
    val exits = f.exits.orEmpty().filter { it.at <= now + HOUR && !(it.kind == ExitKind.CRASH && it.at <= cleared) }
    val recent = exits.filter { it.at > week }
    fun latest(kind: ExitKind) = recent.filter { it.kind == kind }.maxByOrNull { it.at }
    val reportAt = crash?.at?.takeIf { it > week }
    val exitCrashAt = latest(ExitKind.CRASH)?.at
    val crashAt = maxOf(reportAt ?: 0, exitCrashAt ?: 0).takeIf { it > 0 }
    if (crashAt != null) {
        // One crash is one item, though Woodruff's own handler and Android may both have seen it.
        val withReport = reportAt != null && abs(crashAt - reportAt) <= 2 * MINUTE
        items += HealthItem("stability.crash", "Woodruff crashed ${clock.onDay(crashAt)}", if (withReport) CRASH_ABOUT else CLOSED_ABOUT, if (withReport) "crash" else "none")
    }
    latest(ExitKind.ANR)?.let { items += HealthItem("stability.anr", "Woodruff stopped responding ${clock.onDay(it.at)}", CLOSED_ABOUT, "none") }
    latest(ExitKind.NATIVE_CRASH)?.let { items += HealthItem("stability.native", "Woodruff crashed ${clock.onDay(it.at)}", if (it.inMediaCode) MEDIA_CRASH else CLOSED_ABOUT, "none") }
    latest(ExitKind.INIT_FAILURE)?.let { items += HealthItem("stability.start", "Woodruff couldn’t start ${clock.onDay(it.at)}", CLOSED_ABOUT, "none") }
    val lowMemory = exits.count { it.kind == ExitKind.LOW_MEMORY && it.at > now - DAY }
    if (f.exits != null && lowMemory >= 3) items += HealthItem("stability.memory", "Android closed Woodruff for low memory $lowMemory times today", CLOSED_ABOUT, "none")

    // Rule 5: space. Rule 6: the doorbell.
    val lowSpace = f.freeBytes != null && f.freeBytes < GIB
    if (lowSpace) items += HealthItem("space.low", "only ${Format.bytes(f.freeBytes!!)} is free", spaceDetail(tv), "none")
    // Only what a person has to fix: hearing it through another TV, or no doorbell set up, is information.
    if (f.doorbell.attention) items += HealthItem("doorbell.failing", "doorbell alerts aren’t working", f.doorbell.detail ?: f.doorbell.text, "none")

    val closed = if (f.exits == null || f.sdk < 30) null else {
        val shown = recent.maxByOrNull { it.at }
        val text = when {
            shown == null -> "Nothing unusual"
            shown.kind == ExitKind.LOW_MEMORY && lowMemory >= 2 -> "Low memory · $lowMemory times today"
            else -> "${shown.kind.words} · ${clock.moment(shown.at)}"
        }
        val detail = shown?.let { clock.full(it.at) + if (it.kind == ExitKind.NATIVE_CRASH && it.inMediaCode) "\n$MEDIA_CRASH" else "" }
        ClosedRow(text, shown != null && (shown.kind != ExitKind.LOW_MEMORY || lowMemory >= 3), detail)
    }

    val keys = f.keys
    val missing = listOfNotNull(
        "TMDB key".takeIf { !keys.tmdb },
        "Google keys".takeIf { !(keys.googleClientId && keys.googleClientSecret) },
        "Cameras project".takeIf { !keys.camerasProject },
    )
    return HealthReport(
        id = f.id, name = tv, at = now, android = f.sdk, version = f.version,
        attention = items,
        installed = f.installed,
        updates = UpdatesRow(u.step, updateWords(u), waiting, u.auto, u.testTv, if (u.step == "error") plainUpdateReason(u.message) else null, u.asks),
        lastUpdateTry = if (failed) LastTryRow("Didn’t install", reason ?: "The package installer said no.") else null,
        installPermission = f.installAllowed?.let { PermissionRow(it, if (it) "Allowed" else "Not allowed", notAllowed) },
        signIns = signIns,
        doorbell = f.doorbell,
        lastCrash = crash?.let { c ->
            val text = scrubbed(c.report)
            CrashRow(c.at, clock.stamp(c.at), c.at > week, crashSummary(text), text.lines().map { it.trim() }.filter { it.isNotEmpty() }.take(3).joinToString("\n"), text.take(6_000))
        },
        closedByAndroid = closed,
        freeSpace = f.freeBytes?.let { SpaceRow(it, "${Format.bytes(it)} free", lowSpace) },
        keys = KeysRow(
            when (missing.size) { 0 -> "All saved"; 1 -> "${missing[0]} not set"; else -> "${missing.size} keys not set" },
            keys.googleClientId, keys.googleClientSecret, keys.camerasProject, keys.tmdb,
        ),
        uptime = f.uptimeMs,
    )
}

/** The Version row's value for Updater's state: the report's own words, not Settings → Updates' calls to action. */
fun updateWords(u: UpdateFact): String = when (u.step) {
    "checking" -> "Checking…"
    "upToDate" -> "Up to date"
    "downloading" -> "Downloading ${(u.progress * 100).roundToInt().coerceIn(0, 100)}%"
    "ready" -> u.latestName?.let { "$it ready" } ?: "Update ready"
    "installing" -> "Installing…"
    "error" -> "Couldn’t check"
    else -> "Not checked yet"
}
