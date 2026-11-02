package com.woodruff.tvexplorer.tvs

import android.content.Context
import com.woodruff.tvexplorer.cloud.GoogleAuth
import com.woodruff.tvexplorer.util.NetworkWatch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * How each Google sign-in on this TV is doing, for the health report and the phone. What happened to each sign-in
 * has one record, GoogleAuth's own ([GoogleAuth.signIn]: signed in, ended and since when, keys refused), kept
 * encrypted with its tokens, read alike by Settings → Accounts, and cleared by a new sign-in (from the TV or the
 * phone), a sign-out or new keys; the report adds only whether a signed-in TV can reach Google just now. When Google
 * ends a sign-in the phones are told, once ([SignInAlerts]), and the ending is noted for "every week" ([endsWeekly]).
 */
object SignIns {
    private const val PREFS = "health"
    /** The last two times Google ended each sign-in ("t1,t2"), kept across new sign-ins: "every week" ([endsWeekly]). */
    private const val ENDINGS = "endings."
    /** Two endings this close together: the consent screen's 7-day limit in Testing, not a one-off. */
    private const val WEEKLY_MS = 8 * 24 * 3_600_000L
    /** A renewal that couldn't reach Google counts for this long (then the next try decides). */
    private const val UNREACHABLE_FOR_MS = 15 * 60_000L
    /** A sign-in nothing has used for this long is renewed once when a full report is asked for. */
    private const val PROBE_EVERY_MS = 30 * 60_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val probed = ConcurrentHashMap<GoogleAuth.Grant, Long>()
    @Volatile private var started = false

    /**
     * Starts listening (once per process), from the app's start so no ending is missed: Google ending a sign-in is
     * told to the phones and noted for "every week"; signing out of Drive on the TV, or new keys, starts that over.
     */
    fun init(c: Context) {
        synchronized(this) { if (started) return; started = true }
        val app = c.applicationContext
        // Undispatched: listening before anything can ask for a token.
        scope.launch(start = CoroutineStart.UNDISPATCHED) { GoogleAuth.expirations.collect { grant -> ended(app, grant) } }
        scope.launch(start = CoroutineStart.UNDISPATCHED) { GoogleAuth.driveSignOuts.collect { forgetEndings(app) } }
    }

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * When Google ended [grant]'s sign-in on this TV, as GoogleAuth keeps it; null when it hasn't since the last
     * sign-in (or sign-out), and while the Keystore can't read the time just now.
     */
    fun endedAt(c: Context, grant: GoogleAuth.Grant): Long? = GoogleAuth.endedAt(c, grant)

    /**
     * Google ended [grant]'s sign-in (heard as it happened, or found later by [SignInAlerts]' look for endings it
     * missed): noted for [endsWeekly] and told to the phones, each once per ending, by the time GoogleAuth kept for
     * it, which the TV's notice, Accounts and the report go by too. Visible for tests.
     */
    internal fun ended(c: Context, grant: GoogleAuth.Grant) {
        val at = GoogleAuth.endedAt(c, grant) ?: return
        val p = prefs(c)
        synchronized(this) {
            val history = p.getString(ENDINGS + grant.name, null)
            val noted = withEnding(history, at)
            if (noted != history) p.edit().putString(ENDINGS + grant.name, noted).apply()
        }
        runCatching { SignInAlerts.ended(c, grant, at) }
    }

    private fun forgetEndings(c: Context) {
        val e = prefs(c).edit()
        GoogleAuth.Grant.entries.forEach { e.remove(ENDINGS + it.name) }
        e.apply()
    }

    /**
     * Whether Google ended [grant]'s sign-in twice within 8 days: the consent screen's 7-day limit while it's in
     * Testing (the phone then says how to stop it), not a one-off like a changed password.
     */
    fun endsWeekly(c: Context, grant: GoogleAuth.Grant): Boolean = endedTwiceWithin(prefs(c).getString(ENDINGS + grant.name, null), WEEKLY_MS)

    /**
     * [grant]'s sign-in as Settings → Accounts and the phone's Connections say it: GoogleAuth's record, which being
     * offline (or Google out of reach) never changes, since a TV that's offline is still signed in (offline-states.md
     * §3.7). The cameras need their project too (the phone offers "Connect Your Cameras" once it's saved). A sign-in
     * or key the Keystore can't read just now counts as there. Off the main thread.
     */
    fun account(c: Context, grant: HealthGrant): SignInFact {
        val signIn = if (grant == HealthGrant.CAMERAS && !GoogleAuth.hasNestProject(c)) GoogleAuth.SignIn.NOT_SET_UP else GoogleAuth.signIn(c, grant.auth)
        return SignInFact(SignInState.of(signIn), if (signIn == GoogleAuth.SignIn.ENDED) GoogleAuth.endedAt(c, grant.auth) else null)
    }

    /** [history] ("t1,t2": the last endings, oldest first) with one more at [at], keeping the last two; an ending noted already counts once. Visible for tests. */
    internal fun withEnding(history: String?, at: Long): String =
        (history.orEmpty().split(',').mapNotNull { it.trim().toLongOrNull() } + at).distinct().sorted().takeLast(2).joinToString(",")

    /** Whether the last two endings in [history] came at most [windowMs] apart. Visible for tests. */
    internal fun endedTwiceWithin(history: String?, windowMs: Long): Boolean {
        val t = history.orEmpty().split(',').mapNotNull { it.trim().toLongOrNull() }
        return t.size >= 2 && t[t.size - 1] - t[t.size - 2] in 0..windowMs
    }

    /** Google's grant for the report's [grant] (the phone and the report say "cameras" for Google's NEST). */
    fun googleGrant(grant: HealthGrant): GoogleAuth.Grant = grant.auth

    /** The report's grant for Google's [grant]. */
    fun healthGrant(grant: GoogleAuth.Grant): HealthGrant = HealthGrant.entries.first { it.auth == grant }

    /**
     * How [grant]'s sign-in is doing now, for the health report: [account], and for one that's signed in, whether the
     * TV can reach Google just now ("Offline", "Can't reach Google"). Cheap (the keys and tokens are read from memory);
     * safe off the main thread.
     */
    fun state(c: Context, grant: HealthGrant): SignInFact {
        val kept = account(c, grant)
        if (kept.state != SignInState.SIGNED_IN) return kept
        return SignInFact(connectedState(GoogleAuth.lastAsk(grant.auth), NetworkWatch.online.value, System.currentTimeMillis()))
    }

    /**
     * Renews, in the background, each sign-in nothing has asked about for a while (at most every 30
     * minutes each), so a report someone is looking at finds out about a sign-in Google ended or
     * keys it turned down, instead of the TV only finding out the next time it uses that sign-in.
     * Its answer shows in the next report.
     */
    fun probe(c: Context) {
        val app = c.applicationContext
        val now = System.currentTimeMillis()
        for (grant in HealthGrant.entries) {
            val g = grant.auth
            if (!GoogleAuth.isConnected(app, g)) continue
            val last = maxOf(GoogleAuth.lastAsk(g)?.at ?: 0L, probed[g] ?: 0L)
            if (now - last < PROBE_EVERY_MS) continue
            probed[g] = now
            scope.launch { runCatching { GoogleAuth.tokenResult(app, g) } }
        }
    }

    /**
     * A signed-in grant's state from how asking for its token last went ([ask]): no network is Offline; a recent
     * renewal that got no answer while the network was up is Can't reach Google. (Keys Google turned down are kept
     * with the sign-in, [account], and outlast a restart.) Visible for tests.
     */
    internal fun connectedState(ask: GoogleAuth.LastAsk?, networkUp: Boolean, now: Long): SignInState = when {
        !networkUp -> SignInState.OFFLINE
        ask != null && ask.offline && ask.networkUp && now - ask.at < UNREACHABLE_FOR_MS -> SignInState.UNREACHABLE
        else -> SignInState.SIGNED_IN
    }
}
