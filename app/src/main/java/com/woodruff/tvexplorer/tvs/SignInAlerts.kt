package com.woodruff.tvexplorer.tvs

import android.content.Context
import android.util.Log
import com.woodruff.tvexplorer.cloud.GoogleAuth
import com.woodruff.tvexplorer.phones.Phones
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.URLEncoder

/**
 * Telling the family's phones, once, that Google really ended a TV's sign-in (phone-signin.md §5.2), so it's fixed
 * before someone finds Movies broken: this TV's own endings go to this TV's phones the moment it finds out
 * ([ended]); on the hub, each linked TV's endings go to the hub's phones, read from the health summary its
 * /api/status already carries, in the hub's 5-minute loop ([sync]).
 *
 * Only Google's "revoked" answer ends a sign-in ([GoogleAuth.expirations]), so a slow or missing network never
 * says anything here. Each ending is told once ((TV, sign-in, since) is remembered for 9 days); the same sign-in
 * ending on several TVs at about the same time (the weekly limit of a consent screen in Testing) becomes one
 * message ([EndingBatches]). The TV's own quiet notice is posted by the sign-in notice, not here.
 */
object SignInAlerts {
    private const val DAY = 24 * 3_600_000L
    /** An ending first seen later than this after it happened isn't told any more: it's known by then. */
    private const val FRESH_MS = 8 * DAY
    /** How long a told ending is remembered (longer than [FRESH_MS], so it's never told twice). */
    private const val TOLD_MS = 9 * DAY
    /** How long the first ending of a sign-in waits for the same sign-in ending on the other TVs. */
    private const val HOLD_MS = 2 * 60_000L
    private const val PREFS = "health"
    private const val K_TOLD = "signin.told"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private val batches = EndingBatches()
    @Volatile private var started = false

    /** Starts (once per process): an ending this TV hasn't told its phones of yet (Woodruff closed meanwhile) is told now. */
    fun init(c: Context) {
        synchronized(this) { if (started) return; started = true }
        val app = c.applicationContext
        scope.launch {
            delay(60_000)
            runCatching { catchUp(app) }.onFailure { Log.w("SignInAlerts", "couldn't look for sign-ins that ended: $it") }
        }
    }

    /** Google ended this TV's [grant] sign-in at [at] ([SignIns] saw its answer). Any thread. */
    fun ended(c: Context, grant: GoogleAuth.Grant, at: Long) {
        val app = c.applicationContext
        offer(app, Ending(TvLinks.id(app), TvLinks.name(app), here = true, grant = SignIns.healthGrant(grant), since = at))
    }

    /**
     * The hub's check, run with its shared-keys check every few minutes: endings its linked TVs report that its phones
     * haven't been told of (and this TV's own, in case one slipped by). Blocking: the network.
     */
    suspend fun sync(c: Context) {
        val app = c.applicationContext
        catchUp(app)
        TvLinks.load(app)
        if (TvLinks.linked.value.isEmpty()) return
        linkedEndings(TvLinks.statuses(app), System.currentTimeMillis(), FRESH_MS).forEach { offer(app, it) }
    }

    /** This TV's endings it hasn't told of: still ended (not signed in again since) and fresh enough. */
    private fun catchUp(c: Context) {
        val now = System.currentTimeMillis()
        for (g in GoogleAuth.Grant.entries) {
            val since = SignIns.endedAt(c, g) ?: continue
            if (now - since > FRESH_MS || GoogleAuth.isConnected(c, g)) continue
            offer(c, Ending(TvLinks.id(c), TvLinks.name(c), here = true, grant = SignIns.healthGrant(g), since = since))
        }
    }

    /** [e] waits with the others of its sign-in; the first of them sets the time they're all told. */
    private fun offer(c: Context, e: Ending) {
        val opened = synchronized(lock) { batches.offer(e, told(c)) }
        if (!opened) return
        TvLinks.load(c)
        // With no other TV to wait for, at once.
        val hold = if (TvLinks.linked.value.isEmpty()) 0L else HOLD_MS
        scope.launch {
            delay(hold)
            runCatching { flush(c, e.grant) }.onFailure { Log.w("SignInAlerts", "couldn't tell the phones: $it") }
        }
    }

    /** Tells the phones of [grant]'s waiting endings: one message, however many TVs. */
    private suspend fun flush(c: Context, grant: HealthGrant) {
        // A last look at the linked TVs: the same sign-in may just have ended there too (it joins this message;
        // another sign-in that ended waits for its own), or been signed in again.
        val fresh = if (TvLinks.linked.value.isEmpty()) null else runCatching { TvLinks.statuses(c) }.getOrNull()
        val now = System.currentTimeMillis()
        fresh?.let { linkedEndings(it, now, FRESH_MS).forEach { e -> offer(c, e) } }
        // Taken and remembered in one step: an ending seen again meanwhile can't start a second message.
        val endings = synchronized(lock) {
            batches.take(grant).also { taken -> if (taken.isNotEmpty()) remember(c, taken.map { it.key }, now) }
        }.filter { e ->
            if (e.here) SignIns.endedAt(c, SignIns.googleGrant(e.grant)) == e.since && !GoogleAuth.isConnected(c, SignIns.googleGrant(e.grant))
            // A linked TV that answered just now and no longer reports it was signed in again; one that didn't answer still counts.
            else fresh?.firstOrNull { it.id == e.tvId && it.online }?.let { s -> s.health?.ended?.get(e.grant.json) == e.since } ?: true
        }
        if (endings.isEmpty()) return
        val m = signInMessage(endings)
        val phones = Phones.announce(c, null, m.title, m.body, m.from, id = m.id, link = m.link, pushTitle = m.pushTitle, pushBody = m.pushBody)
        Log.i("SignInAlerts", "told $phones phone(s) that a sign-in ended on ${endings.size} TV(s)")
    }

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** The endings told already (by [Ending.key]). Under [lock]. */
    private fun told(c: Context): Set<String> = toldIn(prefs(c).getString(K_TOLD, null)).keys

    /** Remembers [keys] as told at [now], forgetting what's older than [TOLD_MS]. Under [lock]. */
    private fun remember(c: Context, keys: List<String>, now: Long) {
        val all = toldIn(prefs(c).getString(K_TOLD, null)).filterValues { now - it < TOLD_MS } + keys.associateWith { now }
        prefs(c).edit().putString(K_TOLD, JSONObject().apply { all.forEach { (k, at) -> put(k, at) } }.toString()).apply()
    }

    private fun toldIn(json: String?): Map<String, Long> = runCatching {
        val o = JSONObject(json ?: return emptyMap())
        o.keys().asSequence().associateWith { o.optLong(it) }
    }.getOrDefault(emptyMap())
}

// ---- Plain logic (unit-tested) ------------------------------------------------------------------------------

/**
 * One sign-in Google ended: on which TV ([tvId], [tvName]; [here]: this one), which ([grant]) and since when
 * ([since], by that TV's clock). [key] names it for "told once".
 */
data class Ending(val tvId: String, val tvName: String, val here: Boolean, val grant: HealthGrant, val since: Long) {
    val key: String get() = "signin:$tvId:${grant.json}:$since"
}

/** One message to the phones: the banner's words and the address it opens, then the Lock Screen's words (ntfy). */
data class SignInMessage(
    val id: String, val from: String, val title: String, val body: String, val link: String,
    val pushTitle: String, val pushBody: String,
)

/** The title the TV's own notice uses for [grant] (offline-states.md §3.4): what to do, in a few words. */
fun againTitle(grant: HealthGrant): String = when (grant) {
    HealthGrant.DRIVE -> "Sign in to Google Drive again"
    HealthGrant.PHOTOS -> "Connect Google Photos again"
    HealthGrant.CALENDAR -> "Connect Google Calendar again"
    HealthGrant.CAMERAS -> "Connect your cameras again"
}

/** "Living Room", "Living Room and Den", "Living Room, Den and Kitchen". */
fun namesList(names: List<String>): String = when (names.size) {
    0 -> ""
    1 -> names[0]
    else -> names.dropLast(1).joinToString(", ") + " and " + names.last()
}

/**
 * The message for [endings] of one sign-in (phone-signin.md §5.2). One TV: from that TV, opening its page (and
 * for this TV, its sign-in sheet: the phone can sign it in); several: one message from Woodruff, opening the TV
 * list, this TV named first.
 */
fun signInMessage(endings: List<Ending>): SignInMessage {
    require(endings.isNotEmpty())
    val title = againTitle(endings.first().grant)
    if (endings.size == 1) {
        val e = endings.single()
        return if (e.here) SignInMessage(
            e.key, e.tvName, title, "Google ended ${e.tvName}’s sign-in. Tap to sign it in again from this phone.",
            "#settings/tvs/this/signin/${e.grant.json}",
            "${e.tvName}: $title", "Google ended its sign-in. Open Woodruff Family to sign it in again.",
        ) else SignInMessage(
            e.key, e.tvName, title, "Google ended ${e.tvName}’s sign-in. Tap to see how to sign it in again.",
            "#settings/tvs/" + URLEncoder.encode(e.tvId, "UTF-8").replace("+", "%20"),
            "${e.tvName}: $title", "Google ended its sign-in. Open Woodruff Family to see how to sign it in again.",
        )
    }
    val sorted = endings.sortedBy { !it.here }
    val names = namesList(sorted.map { it.tvName })
    val many = "$title on ${sorted.size} TVs"
    return SignInMessage(
        "signin:all:${sorted.first().grant.json}:${sorted.minOf { it.since }}", "Woodruff", many,
        "Google ended the sign-ins of $names. Tap to see them.", "#settings/tvs",
        many, "Google ended the sign-ins of $names. Open Woodruff Family to see them.",
    )
}

/**
 * The endings the linked TVs report in [statuses] (this TV first: left out), as of [now]: only TVs that answered
 * and take this TV's link, and endings no older than [freshMs] (nor more than a day ahead, a TV with its clock wrong).
 */
internal fun linkedEndings(statuses: List<TvStatus>, now: Long, freshMs: Long): List<Ending> =
    statuses.drop(1).filter { it.online && !it.refused }.flatMap { s ->
        s.health?.ended.orEmpty().mapNotNull { (g, since) ->
            HealthGrant.of(g)?.takeIf { now - since <= freshMs && since - now <= 24 * 3_600_000L }?.let { Ending(s.id, s.name, false, it, since) }
        }
    }

/**
 * Endings waiting to be told, by sign-in, in the order they came. The first of a sign-in opens its batch (its
 * owner then waits a moment for the same sign-in on other TVs and [take]s them all); told ones never come back,
 * and one TV counts once in a batch. Not thread-safe: [SignInAlerts] holds its lock.
 */
internal class EndingBatches {
    private val waiting = LinkedHashMap<HealthGrant, LinkedHashMap<String, Ending>>()

    /** Adds [e] unless it was [told] or is waiting already; true when it opened its sign-in's batch. */
    fun offer(e: Ending, told: Set<String>): Boolean {
        if (e.key in told) return false
        val batch = waiting[e.grant]
        if (batch == null) {
            waiting[e.grant] = linkedMapOf(e.tvId to e)
            return true
        }
        // The same TV twice (an ending seen again): the newest counts.
        val had = batch[e.tvId]
        if (had == null || had.since < e.since) batch[e.tvId] = e
        return false
    }

    /** [grant]'s waiting endings, taken out (empty when none). */
    fun take(grant: HealthGrant): List<Ending> = waiting.remove(grant)?.values?.toList().orEmpty()
}
