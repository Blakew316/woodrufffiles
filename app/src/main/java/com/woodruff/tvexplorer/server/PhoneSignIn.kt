package com.woodruff.tvexplorer.server

import android.content.Context
import android.util.Log
import com.woodruff.tvexplorer.calendar.TodayCalendar
import com.woodruff.tvexplorer.cloud.GoogleAuth
import com.woodruff.tvexplorer.nest.Nest
import com.woodruff.tvexplorer.tvs.Health
import com.woodruff.tvexplorer.tvs.SignIns
import com.woodruff.tvexplorer.tvs.TvLinks
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException

/**
 * Signing this TV in to Google from its own phone app (phone-signin.md), with no QR code and no walk to the TV:
 * the phone asks for an attempt ([start]) and opens Google's page itself; Google's answer comes back to this TV the
 * usual way (the relay page, then /oauth/callback), where this swaps its code for tokens in the background
 * ([finish]): no screen has to be open on the TV, and Woodruff needn't be in front. The phone follows along
 * ([status]) until it's done.
 *
 * Only this TV's own pairing key starts one, never a link key (sign-ins aren't shared between TVs: the routes
 * aren't in [TvLinks.LINK_ROUTES]), and Google's answer goes to this TV's home-network address, as for its QR
 * codes: the relay page forwards only to private addresses, so it works on the home Wi-Fi only.
 */
object PhoneSignIn {
    /** How long an attempt waits for Google's answer; the phone stops asking then too. */
    const val WAIT_MS = 10 * 60_000L
    /** How long Google's answer page waits for the TV to finish before saying it's still finishing. */
    private const val PAGE_WAIT_MS = 25_000L

    private val attempts = PhoneAttempts(WAIT_MS) { System.currentTimeMillis() }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Swaps Google's code for tokens and keeps them ([GoogleAuth.exchange]); replaced in tests, which never reach Google. */
    internal var exchange: suspend (Context, String, GoogleAuth.Attempt) -> Unit = { c, code, a -> GoogleAuth.exchange(c, code, a) }

    /** What asking for an attempt came to. */
    sealed interface Start {
        /** Ready: the phone opens [authUrl]; [again]: Google ended this sign-in before (the sheet says "Again"). */
        data class Ready(val state: String, val authUrl: String, val expiresAt: Long, val again: Boolean, val tv: String) : Start
        /** Something to set up first: [need] is "keys" (the Google Client ID and secret) or "project" (the cameras'). */
        data class Needs(val need: String, val message: String) : Start
        /** No home-network address to have Google's answer sent to. */
        data class CantStart(val message: String) : Start
    }

    /**
     * A new attempt for [grant], answered at [base] (this TV's home-network address and port, never the address the
     * phone used: through Tailscale that's one the relay page refuses). It replaces any earlier attempt for the same
     * sign-in, from a phone or the TV ([CompanionHub.expect]). Reads the keys: off the main thread.
     */
    fun start(c: Context, grant: GoogleAuth.Grant, base: String?): Start {
        val app = c.applicationContext
        // Keys and a project the Keystore can't read just now (still waking after a power cut) are there all the same.
        if (!GoogleAuth.isConfigured(app)) return Start.Needs("keys", "Add the Google Client ID and secret first")
        if (grant == GoogleAuth.Grant.NEST && !GoogleAuth.hasNestProject(app)) return Start.Needs("project", "Add the cameras project first")
        val home = base ?: return Start.CantStart("Couldn’t start signing in. Check that ${TvLinks.name(app)} is on the home Wi-Fi.")
        // But Google's page needs them read: in a moment they are.
        if (GoogleAuth.clientId(app) == null || (grant == GoogleAuth.Grant.NEST && GoogleAuth.nestProject(app) == null)) {
            return Start.CantStart("${TvLinks.name(app)} can’t read its Google keys just now. Try again in a moment.")
        }
        return begin(app, grant, home)
    }

    /** The attempt itself, once [start] has checked what it needs: made, remembered, and the one Google's answer is for. */
    internal fun begin(c: Context, grant: GoogleAuth.Grant, base: String): Start.Ready {
        val a = GoogleAuth.newAttempt(c, base, grant, fromPhone = true)
        attempts.started(a.state, grant)
        CompanionHub.expect(a)
        // "Again" when Google ended it: the same record Accounts and the health report read.
        val again = GoogleAuth.signIn(c, grant) == GoogleAuth.SignIn.ENDED
        return Start.Ready(a.state, a.authUrl, System.currentTimeMillis() + WAIT_MS, again, TvLinks.name(c))
    }

    /**
     * Where the attempt [state] stands, for the phone: {status, message, weekly}. One this TV doesn't know (it
     * restarted since: attempts are kept in memory only) reads as signed in when [grant] is signed in now, else as
     * not finished, saying why.
     */
    fun status(c: Context, state: String, grant: GoogleAuth.Grant?): JSONObject {
        val app = c.applicationContext
        val e = attempts.get(state)
        if (e != null) return e.toJson()
        if (grant != null && GoogleAuth.isConnected(app, grant)) return progressJson(SignInProgress.SIGNED_IN, null, SignIns.endsWeekly(app, grant))
        return progressJson(SignInProgress.FAILED, "${TvLinks.name(app)} restarted while you were signing in.", false)
    }

    /** Whether [state] is an attempt started from the phone that this TV remembers (also once it's over). */
    fun known(state: String): Boolean = attempts.get(state) != null

    /** The phone's attempt [state] was replaced by a newer one for the same sign-in (on another phone, or the TV). */
    fun replaced(state: String) = attempts.replaced(state)

    /** Google's page was left with Cancel (or Google said no) for the attempt [state]: nothing changed. */
    fun cancelled(state: String) = attempts.cancelled(state)

    /** Google's answer for the attempt [state] came without its code: it didn't finish. Returns why, in plain words. */
    fun incomplete(c: Context, state: String): String =
        "Google’s answer to ${TvLinks.name(c)} was incomplete.".also { attempts.failed(state, it) }

    /**
     * Google's [code] for the phone's [attempt] reached this TV: swapped for tokens now, in the background (it
     * carries on if the phone's page goes away), then the screens and loops that use this sign-in pick it up. Null
     * when the attempt wasn't waiting any more (its 10 minutes were up: the phone has said so).
     */
    fun finish(c: Context, code: String, attempt: GoogleAuth.Attempt): Deferred<Outcome>? {
        val app = c.applicationContext
        if (!attempts.finishing(attempt.state)) return null
        return scope.async {
            try {
                exchange(app, code, attempt)
                after(app, attempt.grant)
                attempts.signedIn(attempt.state, SignIns.endsWeekly(app, attempt.grant))
                Outcome.SignedIn
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("PhoneSignIn", "signing in from the phone didn't finish: ${e.javaClass.simpleName}")
                val reason = exchangeReason(e, TvLinks.name(app))
                attempts.failed(attempt.state, reason)
                Outcome.Failed(reason)
            }
        }
    }

    /** [finish]'s outcome within [PAGE_WAIT_MS] (Google's answer page waits that long), else [Outcome.Slow]. Blocking. */
    fun outcome(job: Deferred<Outcome>): Outcome = kotlinx.coroutines.runBlocking {
        kotlinx.coroutines.withTimeoutOrNull(PAGE_WAIT_MS) { job.await() } ?: Outcome.Slow
    }

    /**
     * After a sign-in from the phone, everything that uses it hears of it, as after one from the TV: Streaming's QR
     * code gives way to the library and Settings → Accounts reads it again (both follow [CompanionHub.changes]), the
     * calendar starts refreshing, the cameras load. None of this can undo the sign-in.
     */
    private suspend fun after(c: Context, grant: GoogleAuth.Grant) {
        // The sign-in's record (ended, keys refused) was cleared as its tokens were kept (GoogleAuth.exchange).
        Health.forget()
        CompanionHub.settingsChanged.tryEmit(Unit)
        runCatching {
            when (grant) {
                GoogleAuth.Grant.CALENDAR -> TodayCalendar.signedIn()
                // The cameras' state is changed on the main thread, as everything else there does.
                GoogleAuth.Grant.NEST -> withContext(Dispatchers.Main) { Nest.warm() }
                else -> Unit
            }
        }.onFailure { Log.w("PhoneSignIn", "couldn't pass the sign-in on: $it") }
    }

    /** How [finish] went, for Google's answer page. */
    sealed interface Outcome {
        data object SignedIn : Outcome
        /** It didn't finish: [reason], in plain words. */
        data class Failed(val reason: String) : Outcome
        /** Still finishing when the page couldn't wait any longer. */
        data object Slow : Outcome
    }
}

/** Where a sign-in started from the phone stands, as the phone's sheet shows it (its JSON name: [json]). */
enum class SignInProgress(val json: String) {
    /** Waiting for Google's answer. */
    PENDING("pending"),
    /** Google's answer came; the TV is swapping it for tokens. */
    FINISHING("finishing"),
    SIGNED_IN("signedIn"),
    /** Cancelled on Google's page. */
    CANCELLED("cancelled"),
    /** The TV couldn't finish (the reason says why). */
    FAILED("failed"),
    /** A newer attempt for the same sign-in replaced it, from another phone or the TV. */
    REPLACED("replaced"),
    /** No answer from Google in time. */
    EXPIRED("expired"),
}

/** The phone's status JSON: what [progress] is, why it didn't finish ([message]) and whether Google ends it every week. */
internal fun progressJson(progress: SignInProgress, message: String?, weekly: Boolean): JSONObject =
    JSONObject().put("status", progress.json).put("message", message ?: JSONObject.NULL).put("weekly", weekly)

/**
 * The attempts started from the phone, by `state`, and where each stands. One only moves forward (waiting →
 * finishing → signed in or not), and one still waiting after [waitMs] has expired. Kept a few minutes longer than
 * that (the phone may ask once more), and at most 32. Thread-safe; plain logic, unit-tested.
 */
internal class PhoneAttempts(private val waitMs: Long, private val now: () -> Long) {
    data class Entry(val grant: GoogleAuth.Grant, val startedAt: Long, val progress: SignInProgress, val reason: String? = null, val weekly: Boolean = false) {
        fun toJson(): JSONObject = progressJson(progress, reason, weekly)
    }

    private val entries = LinkedHashMap<String, Entry>()

    @Synchronized fun started(state: String, grant: GoogleAuth.Grant) {
        val t = now()
        entries.entries.removeAll { t - it.value.startedAt > waitMs + KEEP_AFTER_MS }
        while (entries.size >= MAX) entries.remove(entries.keys.first())
        entries[state] = Entry(grant, t, SignInProgress.PENDING)
    }

    /** [state]'s entry; null when it isn't one (or was forgotten). */
    @Synchronized fun get(state: String): Entry? {
        val e = entries[state] ?: return null
        if (e.progress == SignInProgress.PENDING && now() - e.startedAt > waitMs) {
            return e.copy(progress = SignInProgress.EXPIRED).also { entries[state] = it }
        }
        return e
    }

    @Synchronized fun finishing(state: String) = move(state, SignInProgress.FINISHING, from = SignInProgress.PENDING)
    @Synchronized fun replaced(state: String) = move(state, SignInProgress.REPLACED, from = SignInProgress.PENDING)
    @Synchronized fun cancelled(state: String) = move(state, SignInProgress.CANCELLED, from = SignInProgress.PENDING)

    @Synchronized fun signedIn(state: String, weekly: Boolean) {
        val e = entries[state] ?: return
        if (e.progress == SignInProgress.FINISHING) entries[state] = e.copy(progress = SignInProgress.SIGNED_IN, weekly = weekly)
    }

    @Synchronized fun failed(state: String, reason: String) {
        val e = get(state) ?: return
        if (e.progress == SignInProgress.FINISHING || e.progress == SignInProgress.PENDING) entries[state] = e.copy(progress = SignInProgress.FAILED, reason = reason)
    }

    /** [state] goes to [to], only from [from] (an attempt that expired meanwhile stays expired). */
    private fun move(state: String, to: SignInProgress, from: SignInProgress): Boolean {
        val e = get(state) ?: return false
        if (e.progress != from) return false
        entries[state] = e.copy(progress = to)
        return true
    }

    private companion object {
        const val KEEP_AFTER_MS = 5 * 60_000L
        const val MAX = 32
    }
}

/**
 * Why swapping Google's code for tokens didn't work, in the family's words (the phone adds "Try again."): no codes,
 * no Google wording. [tv] is this TV's name.
 */
internal fun exchangeReason(e: Throwable, tv: String): String = when (e) {
    is GoogleAuth.TokenError -> when (e.kind) {
        GoogleAuth.TokenError.Kind.NETWORK -> "$tv couldn’t reach Google."
        GoogleAuth.TokenError.Kind.UNAVAILABLE -> "Google’s sign-in is busy right now."
        GoogleAuth.TokenError.Kind.BAD_CLIENT -> "Google doesn’t accept the Client ID or secret saved on $tv."
        GoogleAuth.TokenError.Kind.REVOKED, GoogleAuth.TokenError.Kind.OTHER -> "Google didn’t accept the answer."
    }
    is IOException -> when {
        e.message.orEmpty().contains("refresh token") -> "Google didn’t give $tv a sign-in that lasts."
        e.message.orEmpty().contains("set up") -> "The Google keys aren’t on $tv any more."
        else -> "$tv couldn’t reach Google."
    }
    else -> "$tv couldn’t finish with Google."
}

/**
 * The page Google's answer lands on, for a sign-in started from the phone (phone-signin.md §3.5): its title and
 * words, pointing back to the phone. [tv] is this TV's name. (A sign-in started on the TV keeps the TV's words.)
 */
internal sealed interface PhonePage {
    data class Done(val grant: GoogleAuth.Grant) : PhonePage
    data class NotFinished(val reason: String) : PhonePage
    data object Finishing : PhonePage
    data object Cancelled : PhonePage
    data object Old : PhonePage
}

internal fun phonePage(page: PhonePage, tv: String): Pair<String, String> {
    val back = "Go back to Woodruff Family on your phone."
    return when (page) {
        is PhonePage.Done -> when (page.grant) {
            GoogleAuth.Grant.DRIVE -> "$tv is signed in ✓" to "Google Drive is back on $tv. $back"
            GoogleAuth.Grant.CALENDAR -> "Google Calendar connected ✓" to "Today’s events show on $tv’s Home in a moment. $back"
            GoogleAuth.Grant.PHOTOS -> "Google Photos connected ✓" to "To add photos, open Family Memories on $tv and choose Add from Google Photos. $back"
            GoogleAuth.Grant.NEST -> "Cameras connected ✓" to "$tv is loading your cameras. $back"
        }
        is PhonePage.NotFinished -> "Signing in didn’t finish" to "${page.reason} Go back to Woodruff Family on your phone to try again."
        PhonePage.Finishing -> "Finishing on $tv…" to "$back It says when $tv is signed in."
        PhonePage.Cancelled -> "Sign-in cancelled" to "Nothing changed. You can try again from your phone."
        PhonePage.Old -> "This sign-in link is old" to "Start again from your phone or the TV."
    }
}
