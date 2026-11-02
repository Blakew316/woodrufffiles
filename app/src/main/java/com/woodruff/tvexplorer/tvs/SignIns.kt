package com.woodruff.tvexplorer.tvs

import android.content.Context
import com.woodruff.tvexplorer.cloud.GoogleAuth
import com.woodruff.tvexplorer.server.CompanionHub
import com.woodruff.tvexplorer.util.NetworkWatch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * How each Google sign-in on this TV is doing, for the health report: signed in, ended (and since
 * when), Google turning down the keys, or Google out of reach. A sign-in Google ended is already
 * gone from [GoogleAuth] by the time anyone looks, so the moment it ends
 * ([GoogleAuth.expirations]) is kept here, in plain settings (a time is no secret), until that
 * sign-in is made again or the keys change.
 */
object SignIns {
    private const val PREFS = "health"
    private const val ENDED = "ended."
    /** A renewal that couldn't reach Google counts for this long (then the next try decides). */
    private const val UNREACHABLE_FOR_MS = 15 * 60_000L
    /** A sign-in nothing has used for this long is renewed once when a full report is asked for. */
    private const val PROBE_EVERY_MS = 30 * 60_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val probed = ConcurrentHashMap<GoogleAuth.Grant, Long>()
    @Volatile private var started = false

    /**
     * Starts listening (once per process), from the app's start so no ending is missed: Google
     * ending a sign-in marks it; a new sign-in for it, or new keys, clears the mark.
     */
    fun init(c: Context) {
        synchronized(this) { if (started) return; started = true }
        val app = c.applicationContext
        // Undispatched: listening before anything can ask for a token.
        scope.launch(start = CoroutineStart.UNDISPATCHED) { GoogleAuth.expirations.collect { grant -> markEnded(app, grant) } }
        scope.launch(start = CoroutineStart.UNDISPATCHED) { GoogleAuth.driveSignOuts.collect { clearAll(app) } }
        scope.launch(start = CoroutineStart.UNDISPATCHED) { CompanionHub.googleCodes.collect { (_, attempt) -> clear(app, attempt.grant) } }
    }

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** When Google ended [grant]'s sign-in on this TV; null when it hasn't (or it was made again since). */
    fun endedAt(c: Context, grant: GoogleAuth.Grant): Long? = prefs(c).getLong(ENDED + grant.name, 0L).takeIf { it > 0 }

    private fun markEnded(c: Context, grant: GoogleAuth.Grant) { prefs(c).edit().putLong(ENDED + grant.name, System.currentTimeMillis()).apply() }
    private fun clear(c: Context, grant: GoogleAuth.Grant) { if (endedAt(c, grant) != null) prefs(c).edit().remove(ENDED + grant.name).apply() }
    private fun clearAll(c: Context) { val e = prefs(c).edit(); GoogleAuth.Grant.entries.forEach { e.remove(ENDED + it.name) }; e.apply() }

    /** The report's grant for Google's. */
    val HealthGrant.auth: GoogleAuth.Grant
        get() = when (this) {
            HealthGrant.DRIVE -> GoogleAuth.Grant.DRIVE
            HealthGrant.PHOTOS -> GoogleAuth.Grant.PHOTOS
            HealthGrant.CALENDAR -> GoogleAuth.Grant.CALENDAR
            HealthGrant.CAMERAS -> GoogleAuth.Grant.NEST
        }

    /** How [grant]'s sign-in is doing now. Cheap (the keys and tokens are read from memory); safe off the main thread. */
    fun state(c: Context, grant: HealthGrant): SignInFact {
        val g = grant.auth
        if (!GoogleAuth.isConfigured(c) || (grant == HealthGrant.CAMERAS && GoogleAuth.nestProject(c) == null)) return SignInFact(SignInState.NOT_SET_UP)
        if (!GoogleAuth.isConnected(c, g)) {
            val ended = endedAt(c, g)
            return if (ended != null) SignInFact(SignInState.EXPIRED, ended) else SignInFact(SignInState.NOT_SIGNED_IN)
        }
        // Signed in: an ending marked before this sign-in is stale.
        clear(c, g)
        return SignInFact(connectedState(GoogleAuth.lastAsk(g), NetworkWatch.online.value, System.currentTimeMillis()))
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
     * A signed-in grant's state from how asking for its token last went ([ask]): keys Google turned
     * down stay that way until it accepts them (that never clears by itself); no network is Offline;
     * a recent renewal that got no answer while the network was up is Can't reach Google. Visible for tests.
     */
    internal fun connectedState(ask: GoogleAuth.LastAsk?, networkUp: Boolean, now: Long): SignInState = when {
        ask?.rejected == true -> SignInState.KEYS_REJECTED
        !networkUp -> SignInState.OFFLINE
        ask != null && ask.offline && ask.networkUp && now - ask.at < UNREACHABLE_FOR_MS -> SignInState.UNREACHABLE
        else -> SignInState.SIGNED_IN
    }
}
