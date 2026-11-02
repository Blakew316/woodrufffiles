package com.woodruff.tvexplorer.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.JsonReader
import android.util.JsonToken
import android.util.Log
import com.woodruff.tvexplorer.BuildConfig
import com.woodruff.tvexplorer.notify.AppVisibility
import com.woodruff.tvexplorer.ui.Screen
import com.woodruff.tvexplorer.util.EarlyCrashes
import com.woodruff.tvexplorer.util.Presence
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.IOException
import java.io.Reader
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

/**
 * Keeps Woodruff up to date from this project's GitHub releases. Release "v2.0.N" is built with
 * versionCode N, so a higher N is newer. The APK is downloaded, checked (this app's package, a
 * newer version), and handed to Android's package installer, which also refuses anything not
 * signed with the same key as the installed app.
 *
 * Android 12+ lets an app update itself without a prompt. Silent installs wait until nobody is
 * using Woodruff ([quietNow]: nothing playing, no key pressed anywhere in it for 2 minutes, no copy
 * or transfer under way, a paused film left for half an hour), since an update restarts it. What
 * was on screen comes back by itself afterwards ([Restore]): the slideshow, the cameras, the photo,
 * the paused film at its spot, the screen. Some TVs still ask for a press for every update (and
 * Android 11 and older always do): that update is offered the next time someone opens Woodruff
 * instead ([offer]).
 *
 * On Android 9+ the update brings its speed profile (a .dm file published with the release) in the
 * same install, as Play and adb do: Android compiles the new code while installing, instead of
 * running it uncompiled until the TV's overnight compile.
 *
 * After this version closed while starting ([EarlyCrashes]) it repairs itself: every start of
 * Woodruff looks for a fix at once, and every few minutes after that, taking any newer build without
 * the day's wait; a fix goes in as soon as it's there, even with someone at Woodruff ([urgentNow]).
 */
object Updater {
    /** The newest releases, in one request (GitHub allows 60 an hour for the whole home, shared by every TV). */
    private const val RELEASES = "https://api.github.com/repos/Blakew316/woodrufffiles/releases?per_page=30"
    private const val ASSET = "WoodruffFamily.apk"
    /** A release's speed profiles, named by the lowest API level each serves ("-api28-30" if it stops early). */
    private val PROFILE_NAME = Regex("""WoodruffFamily-api(\d+)(?:-(\d+))?\.dm""")
    /** Which build's speed profile went into an install (0: none), so a refusal can be retried without it. */
    internal const val EXTRA_PROFILE = "com.woodruff.tvexplorer.update.PROFILE"
    /** Who started the install ([How.code]), which build, and its installer session: see [onResult]. */
    internal const val EXTRA_HOW = "com.woodruff.tvexplorer.update.HOW"
    internal const val EXTRA_BUILD = "com.woodruff.tvexplorer.update.BUILD"
    internal const val EXTRA_SESSION = "com.woodruff.tvexplorer.update.SESSION"
    private const val PREFS = "updates"
    private const val HOUR = 3_600_000L
    /** In the background (the phone-connection service's loop). */
    private const val CHECK_EVERY_MS = HOUR
    /** When Woodruff is opened: often enough that a fresh release shows up the next time you open it. */
    private const val CHECK_ON_OPEN_MS = 10 * 60_000L
    /** No key pressed in Woodruff for this long: nobody's using it. */
    internal const val IDLE_MS = 2 * 60_000L
    /** A film paused in Woodruff's player (on screen or under the TV's screensaver) waits this long: someone may be back. */
    internal const val PAUSED_IDLE_MS = 30 * 60_000L
    /** How long a build must be the newest before the TVs other than the test TV take it. */
    internal const val SETTLE_MS = 24 * HOUR
    /** "Later" on an offered update: not offered again for this long (Settings → Updates still installs it). */
    private const val OFFER_LATER_MS = 12 * HOUR
    /** How long Android's own prompt may wait for an answer before a background install may go ahead. */
    private const val PROMPT_WAIT_MS = 10 * 60_000L
    /** Woodruff opens again after an update only if the install started this recently. */
    private const val REOPEN_WITHIN_MS = HOUR
    /**
     * After an early close: how often it looks for a fix by itself (just under the phone connection's
     * 5-minute round, so each round checks; GitHub's 60 an hour are shared by every TV in the home).
     */
    internal const val URGENT_EVERY_MS = 4 * 60_000L
    /** ...and how soon again after a check that didn't go through. */
    internal const val URGENT_RETRY_MS = 60_000L

    // Saved in the "updates" settings file.
    /** The build Android asked a press for when it tried to go in by itself: it's offered instead from then on. */
    private const val K_ASKED = "askedFor"
    /** This TV asked for a press the last time an update tried to go in by itself (Settings → Updates says so). */
    private const val K_ASKS = "asksEachTime"
    /** Android's own prompt has worked on this TV: later offers open it straight away (one press). */
    private const val K_PROMPT_WORKS = "promptWorks"
    /** Android's prompt is on screen for an install (until then): no background install meanwhile. */
    private const val K_PROMPT_UNTIL = "promptUntil"
    private const val K_LATER_FOR = "offerLaterFor"
    private const val K_LATER_UNTIL = "offerLaterUntil"
    /** Woodruff was on screen as this build started installing (and when): it opens again once it's in. */
    private const val K_REOPEN_FOR = "reopenFor"
    private const val K_REOPEN_AT = "reopenAt"
    /** What it showed then ([Restore.encode]): what it opens again to. */
    private const val K_REOPEN_ROUTE = "reopenRoute"
    /** What 2.0.165 and older saved instead (no build, no time): still honoured once. */
    private const val K_REOPEN_OLD = "reopen"

    enum class Step { IDLE, CHECKING, UP_TO_DATE, DOWNLOADING, READY, INSTALLING, ERROR }

    data class State(
        val step: Step = Step.IDLE,
        /** The build this is about: the one ready or downloading, the one waited for, or the newest release. */
        val latest: Int? = null,
        val progress: Float = 0f,
        val message: String? = null,
        /** A check that didn't go through ([Step.ERROR]) because this TV had no network at all. */
        val offline: Boolean = false,
    ) {
        val latestName: String? get() = latest?.let { "2.0.$it" }
    }

    /** Who started an install, which decides what happens when Android asks for a press ([onResult]). */
    enum class How(internal val code: Int) {
        /** By itself, in the background: nobody chose it, and nobody may be watching. */
        BY_ITSELF(1),
        /** Offered as Woodruff came on screen ([offer]). */
        OFFERED(2),
        /** Someone chose Install. */
        CHOSEN(3),
        /**
         * After this version closed while starting ([EarlyCrashes]): in as soon as it's ready, even with
         * someone at Woodruff (the version they have is the one that closes). If Android asks for a
         * press, its prompt opens while Woodruff is on screen; otherwise it's offered the next time.
         */
        URGENT(4);

        internal companion object {
            fun of(code: Int): How? = entries.firstOrNull { it.code == code }
        }
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()

    private val _offer = MutableStateFlow<Int?>(null)
    /**
     * A downloaded update that needs someone's press, to offer now that Woodruff has come on screen
     * (UpdatePrompt shows it); null when there's nothing to offer. Set only as Woodruff opens, never in
     * the middle of using it or while nobody's there.
     */
    val offer: StateFlow<Int?> = _offer.asStateFlow()

    val current: Int get() = BuildConfig.VERSION_CODE

    /**
     * Test TV: installs each release as soon as it's out. Every other TV only installs a build that
     * was the newest for a whole day ([SETTLE_MS]), so a bad build is caught (and replaced) on the
     * test TV before it reaches them. "Check for updates" in Settings skips the wait.
     */
    fun isTestTv(c: Context) = prefs(c).getBoolean("testTv", false)
    fun setTestTv(c: Context, on: Boolean) { prefs(c).edit().putBoolean("testTv", on).apply() }

    fun autoEnabled(c: Context) = prefs(c).getBoolean("auto", true)
    fun setAuto(c: Context, on: Boolean) { prefs(c).edit().putBoolean("auto", on).apply() }
    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * Whether [build] needs someone's press to install: always on Android 11 and older; on 12+ only once
     * Android has asked for one for it ([K_ASKED]). Android 12+ lets an app with
     * UPDATE_PACKAGES_WITHOUT_USER_ACTION update itself without asking, whoever installed it, so every
     * build tries that first. It isn't held back by "Install unknown apps" answering no: a Sony answered
     * no while its Settings showed it allowed. If Android asks anyway, the build is offered instead.
     */
    fun needsPress(c: Context, build: Int): Boolean =
        Build.VERSION.SDK_INT < 31 || prefs(c).getInt(K_ASKED, 0) == build

    /** This TV asked for a press the last time an update tried to install by itself. */
    fun asksEachTime(c: Context): Boolean = Build.VERSION.SDK_INT < 31 || prefs(c).getBoolean(K_ASKS, false)

    /** Android's own install prompt has worked here, so an offer can open it directly (one press). */
    fun promptWorked(c: Context): Boolean = prefs(c).getBoolean(K_PROMPT_WORKS, false)

    private fun canInstallSilently(c: Context): Boolean {
        val build = _state.value.latest ?: return Build.VERSION.SDK_INT >= 31
        return !needsPress(c, build)
    }

    /**
     * The background routine: check and download (at most hourly, or every 10 minutes when
     * [opening] the app); then install right away if that needs no press and nobody is using
     * Woodruff. Called by the phone-connection service's loop and when the app opens; with [offer]
     * as Woodruff comes on screen, a downloaded update that needs a press is offered first. Does
     * nothing when automatic updates are off. After this version closed while starting, it looks
     * for a fix and puts it in sooner instead ([urgentNow]).
     */
    fun maybeAuto(c: Context, opening: Boolean = false, offer: Boolean = false) {
        val app = c.applicationContext
        if (!autoEnabled(app)) return
        scope.launch {
            val p = prefs(app)
            // After a restart: an update downloaded and checked earlier is ready again at once,
            // instead of after the next check (up to an hour, or a day's wait on other TVs).
            if (_state.value.step == Step.IDLE) restoreReady(app)
            // Before checking, which can take a while: the offer belongs to the moment Woodruff opens.
            if (offer) offerIfDue(app)
            if (EarlyCrashes.count(app) > 0) {
                urgentNow(app, manual = false)
                return@launch
            }
            val every = if (opening) CHECK_ON_OPEN_MS else CHECK_EVERY_MS
            val now = System.currentTimeMillis()
            // After a failed check, try again sooner — but not on every pass: GitHub allows 60 checks
            // an hour for the whole home connection, shared by every TV.
            val retryError = _state.value.step == Step.ERROR && now - lastAttempt >= ERROR_RETRY_MS
            if (now - p.getLong("lastCheck", 0) >= every || retryError) {
                checkAndDownload(app)
            }
            val afterCheck = System.currentTimeMillis()
            if (_state.value.step == Step.READY && canInstallSilently(app) && afterCheck >= retryAt &&
                afterCheck >= p.getLong(K_PROMPT_UNTIL, 0) &&
                // Someone's using it, or something mustn't be cut short: no need to look further.
                Presence.sinceUseMs() >= IDLE_MS && !Presence.isBusy
            ) {
                // What's up, asked of the screens themselves: whether it's a film playing, and what to
                // put back once the update is in (Woodruff restarts).
                val now = OnScreen.momentNow(app) ?: return@launch
                if (quietNow(Presence.sinceUseMs(), now.film, Presence.isBusy, now.screenOn)) {
                    startInstall(app, How.BY_ITSELF, reopen = now.route)
                }
            }
        }
    }

    /**
     * Whether an update may restart Woodruff now, with no one asked: nothing that mustn't be cut short
     * is running ([busy]: a copy, a file from the phone, a Google Photos pick), its player isn't
     * playing, and nobody has pressed a key in it for [IDLE_MS] — or [PAUSED_IDLE_MS] when a film is
     * paused in it (on screen, or under the TV's screensaver), since someone who paused may well be
     * back. With the TV's screen off nobody is watching anything (but a film playing still isn't
     * interrupted). What was on screen comes back afterwards. Visible for tests.
     */
    internal fun quietNow(sinceUseMs: Long, film: OnScreen.Film, busy: Boolean, screenOn: Boolean): Boolean = when {
        busy -> false
        film == OnScreen.Film.PLAYING -> false
        !screenOn -> true
        film == OnScreen.Film.PAUSED -> sinceUseMs >= PAUSED_IDLE_MS
        else -> sinceUseMs >= IDLE_MS
    }

    // ---- After an early close: self-repair ------------------------------------------------------------

    /**
     * As Woodruff starts, whatever started it (the TV turning on, the phone connection coming back, the
     * screen opening): after this version closed while starting, it looks for a fix at once instead of
     * waiting its turn, and puts it in as soon as it's there. Nothing otherwise, or with automatic
     * updates off.
     */
    fun atStart(c: Context) {
        val app = c.applicationContext
        scope.launch { if (autoEnabled(app) && EarlyCrashes.count(app) > 0) urgentNow(app, manual = false) }
    }

    /**
     * The recovery screen's look for a fix: as it opens and every few minutes while it's up (each at most
     * every [URGENT_EVERY_MS]), and at once when someone chooses Check for a fix ([manual]). It looks
     * even with automatic updates off, since it says so on screen; only putting the fix in by itself
     * waits for a press then.
     */
    fun lookForFix(c: Context, manual: Boolean = false) {
        val app = c.applicationContext
        scope.launch { urgentNow(app, manual) }
    }

    /**
     * After this version closed while starting ([EarlyCrashes]): checks for a newer build now unless it
     * did lately ([urgentCheckDue]; [manual] always checks), taking any newer one at once rather than one
     * the test TV has had for a day (anything newer beats a version that closes as it starts), then puts
     * it in ([installUrgently]).
     */
    private suspend fun urgentNow(app: Context, manual: Boolean) {
        if (_state.value.step == Step.IDLE) restoreReady(app)
        if (claimUrgentCheck(manual)) {
            checkAndDownload(app, manual = true)
            // No network at all (just after the TV turned on): the next look, once there is one, goes at once.
            if (_state.value.step == Step.ERROR && _state.value.offline) urgentClaim.set(0)
        }
        installUrgently(app)
    }

    /** When the last urgent check started (elapsedRealtime; 0: none in this run, so the first one goes at once). */
    private val urgentClaim = java.util.concurrent.atomic.AtomicLong(0)

    /** Claims the next urgent check, so two callers at once make one request; false when one isn't due. */
    private fun claimUrgentCheck(manual: Boolean): Boolean {
        while (true) {
            val last = urgentClaim.get()
            val now = android.os.SystemClock.elapsedRealtime()
            if (!manual && !urgentCheckDue(last, now, failed = _state.value.step == Step.ERROR)) return false
            if (urgentClaim.compareAndSet(last, now.coerceAtLeast(1))) return true
        }
    }

    /**
     * Whether an urgent check is due at [now], the last having started at [last] (0: none yet in this
     * run): every [URGENT_EVERY_MS], or [URGENT_RETRY_MS] after one that didn't go through ([failed]).
     * Visible for tests.
     */
    internal fun urgentCheckDue(last: Long, now: Long, failed: Boolean): Boolean =
        last == 0L || now - last >= (if (failed) URGENT_RETRY_MS else URGENT_EVERY_MS)

    /**
     * After an early close, a fix that's ready goes in now: there's no waiting for nobody to be using
     * Woodruff (the version on screen is the one that closes), and what's on screen comes back once the
     * fix is in. Only a film that's playing or a job that mustn't be cut short holds it back
     * ([mayInstallUrgently]); a no to Android's prompt for it, and the back-off after a try that didn't
     * go through, are respected. Where it needs a press (Android 11 and older, a TV that asked for this
     * build), it's offered at once if Woodruff's screen is up, else the next time it opens.
     */
    private suspend fun installUrgently(app: Context) {
        val s = _state.value
        val build = s.latest ?: return
        if (s.step != Step.READY || !autoEnabled(app)) return
        val now = System.currentTimeMillis()
        val p = prefs(app)
        val putOff = p.getInt(K_LATER_FOR, 0) == build && now < p.getLong(K_LATER_UNTIL, 0)
        if (now < retryAt || now < p.getLong(K_PROMPT_UNTIL, 0) || putOff) return
        if (needsPress(app, build)) {
            if (AppVisibility.foreground && offerDue(app, s, now)) _offer.value = build
            return
        }
        val moment = OnScreen.momentNow(app)
        if (!mayInstallUrgently(moment?.film ?: OnScreen.Film.NONE, Presence.isBusy)) return
        // A screen that didn't answer in time is up but stuck: its Home comes back.
        val route = moment?.route ?: if (mainScreenInFront()) Restore.App(Screen.HOME) else null
        startInstall(app, How.URGENT, reopen = route)
    }

    /**
     * Woodruff's main screen is in front, as itself or as the recovery screen: where Android's install
     * prompt may open. (The recovery screen doesn't count as Woodruff in front for the rest of the app:
     * see [RecoveryView.inFront].)
     */
    private fun mainScreenInFront(): Boolean = AppVisibility.foreground || RecoveryView.inFront

    /**
     * Whether a fix after an early close may restart Woodruff now: even with someone using it, but never
     * over a film that's playing (it goes in once the film is paused or over) nor a job that mustn't be
     * cut short ([busy]). Visible for tests.
     */
    internal fun mayInstallUrgently(film: OnScreen.Film, busy: Boolean): Boolean = !busy && film != OnScreen.Film.PLAYING

    /**
     * Whether this profile forbids apps from outside the Play Store (a kids profile, parental controls, a
     * work setup), which no toggle in Settings lifts: an update can't install here.
     */
    fun installsBlockedByProfile(c: Context): Boolean {
        val um = c.getSystemService(android.os.UserManager::class.java) ?: return false
        return runCatching {
            um.hasUserRestriction(android.os.UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES) ||
                (Build.VERSION.SDK_INT >= 29 && um.hasUserRestriction(android.os.UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES_GLOBALLY))
        }.getOrDefault(false)
    }

    private fun offerIfDue(c: Context) {
        val s = _state.value
        if (offerDue(c, s, System.currentTimeMillis())) _offer.value = s.latest
    }

    /**
     * Whether [s] is an update to offer as Woodruff opens, at [now]: downloaded, needing a press, not
     * put off with Later (or a no to Android's prompt) lately, and no Android prompt up for it already.
     * Visible for tests.
     */
    internal fun offerDue(c: Context, s: State, now: Long): Boolean {
        val build = s.latest ?: return false
        val p = prefs(c)
        val putOff = p.getInt(K_LATER_FOR, 0) == build && now < p.getLong(K_LATER_UNTIL, 0)
        return s.step == Step.READY && needsPress(c, build) && !putOff && now >= p.getLong(K_PROMPT_UNTIL, 0)
    }

    /**
     * Back to how a fresh run starts: the tests share this one object. From then on in that run,
     * releases and downloads are stand-ins that never reach the network (a test sets its own), so a
     * check started by Woodruff's own start in a test can't go out either.
     */
    internal fun resetForTest() {
        _state.value = State(); _offer.value = null
        session = -1; letGoSessions.clear(); failures = 0; retryAt = 0
        urgentClaim.set(0); lastAttempt = 0
        listReleases = { emptyList() }
        fetchFile = { _, _, _, _ -> throw IOException("no downloads in tests") }
    }

    /** Shows [s] as the state, for tests and the UI gallery (nothing is checked or installed). */
    internal fun showForTest(s: State) { _state.value = s }

    /** The offer was shown (or Android's own prompt opened for it). */
    fun offered() { _offer.value = null }

    /** "Later" on the offer of [build]: not offered again for [OFFER_LATER_MS]; Settings → Updates still installs it. */
    fun later(c: Context, build: Int) {
        offered()
        prefs(c).edit().putInt(K_LATER_FOR, build).putLong(K_LATER_UNTIL, System.currentTimeMillis() + OFFER_LATER_MS).apply()
    }

    /**
     * After an update: what to open again, if Woodruff was on screen as it started (once). What it
     * showed then, or its Home when that wasn't saved (an update started by a version from before
     * that was).
     */
    fun takeReopen(c: Context): Restore.Route? {
        val p = prefs(c)
        val due = reopenDue(p.getInt(K_REOPEN_FOR, 0), p.getLong(K_REOPEN_AT, 0), current, System.currentTimeMillis()) ||
            p.getBoolean(K_REOPEN_OLD, false)
        val saved = p.getString(K_REOPEN_ROUTE, null)
        p.edit().remove(K_REOPEN_FOR).remove(K_REOPEN_AT).remove(K_REOPEN_OLD).remove(K_REOPEN_ROUTE).apply()
        if (!due) return null
        return Restore.decode(saved) ?: Restore.App(Screen.HOME)
    }

    /**
     * Whether to open Woodruff again now that build [installed] is in: Woodruff was on screen when
     * [forBuild] started installing at [at], it's that build, and not long ago. A flag left by an
     * install that never finished must not open Woodruff over another app after a later update.
     * Visible for tests.
     */
    internal fun reopenDue(forBuild: Int, at: Long, installed: Int, now: Long): Boolean =
        forBuild > 0 && forBuild == installed && abs(now - at) < REOPEN_WITHIN_MS

    /**
     * Written at once (on the install's background thread): the install ends this run of Woodruff
     * moments later. [route]: what it opens again to.
     */
    private fun setReopen(c: Context, build: Int, route: Restore.Route) = prefs(c).edit()
        .putInt(K_REOPEN_FOR, build).putLong(K_REOPEN_AT, System.currentTimeMillis())
        .putString(K_REOPEN_ROUTE, Restore.encode(route)).remove(K_REOPEN_OLD).commit()

    private fun clearReopen(c: Context) =
        prefs(c).edit().remove(K_REOPEN_FOR).remove(K_REOPEN_AT).remove(K_REOPEN_OLD).remove(K_REOPEN_ROUTE).apply()

    /**
     * How this copy of Woodruff came to be, for Settings → Updates and the phone's TV report: when it
     * was installed fresh (an uninstall before it erases every sign-in), when it was last updated, and
     * what installed it. Plus, when it happened, the day the TV's Keystore lost the sign-ins' key.
     */
    fun installFacts(c: Context): InstallFacts {
        val pm = c.packageManager
        @Suppress("DEPRECATION")
        val info = runCatching { pm.getPackageInfo(c.packageName, 0) }.getOrNull()
        @Suppress("DEPRECATION")
        val installer = runCatching {
            if (Build.VERSION.SDK_INT >= 30) pm.getInstallSourceInfo(c.packageName).installingPackageName else pm.getInstallerPackageName(c.packageName)
        }.getOrNull()
        val by = when (installer) {
            null -> "adb or a file app"
            c.packageName -> "Woodruff itself"
            else -> runCatching { pm.getApplicationLabel(pm.getApplicationInfo(installer, 0)).toString() }.getOrDefault(installer)
        }
        return InstallFacts(info?.firstInstallTime ?: 0, info?.lastUpdateTime ?: 0, by,
            com.woodruff.tvexplorer.settings.SecretStore.get(c).lostAt())
    }

    data class InstallFacts(val firstInstall: Long, val lastUpdate: Long, val installedBy: String, val signInsLostAt: Long?) {
        /** "Installed fresh Oct 31 · updated Nov 2 by Woodruff itself" (a time Android didn't give is left out). */
        fun summary(): String {
            if (firstInstall <= 0) return "Installed by $installedBy"
            val fresh = "Installed fresh ${day(firstInstall)}"
            return if (lastUpdate > firstInstall + 60_000) "$fresh · updated ${day(lastUpdate)} by $installedBy" else "$fresh by $installedBy"
        }

        /** Why this TV's sign-ins may be gone, in words; null when nothing says they were lost. */
        fun signIns(): String? = signInsLostAt?.let { "Lost ${day(it)}: the TV's key store lost their key" }

        private fun day(ms: Long): String = if (ms <= 0) "?" else
            java.text.SimpleDateFormat("MMM d, h:mm a", java.util.Locale.US).format(java.util.Date(ms))
    }

    /** Settings → Check for updates. */
    fun checkNow(c: Context) { val app = c.applicationContext; scope.launch { checkAndDownload(app, manual = true) } }

    /** When the last check started (successful or not). */
    @Volatile private var lastAttempt = 0L
    private const val ERROR_RETRY_MS = 15 * 60_000L

    private fun restoreReady(c: Context) {
        val version = verify(c, apkFile(c)) ?: return
        _state.compareAndSet(State(), State(Step.READY, version))
    }

    /** After a failed silent install, the next try waits (5 min, doubling, up to 6 h) instead of looping. */
    @Volatile private var retryAt = 0L
    @Volatile private var failures = 0
    private fun installFailed() {
        failures++
        retryAt = System.currentTimeMillis() + (5 * 60_000L shl (failures - 1).coerceAtMost(6)).coerceAtMost(6 * 3_600_000L)
    }

    /** One published release, as far as updating goes. */
    internal data class Release(
        val build: Int,
        /** When it was published (ms). */
        val published: Long,
        /** Re-released to undo a bad build, or an "[urgent]" fix: every TV takes it at once. */
        val rollback: Boolean = false,
        /** Its files that have finished uploading. */
        val files: List<ReleaseFile> = emptyList(),
        /** Marked pre-release on GitHub: never installed, and not counted as newer (as /releases/latest does). */
        val prerelease: Boolean = false,
    ) {
        val apk: ReleaseFile? get() = files.firstOrNull { it.name == ASSET }
    }

    internal data class ReleaseFile(val name: String, val size: Long, val url: String)

    /** What [choose] decided. */
    internal data class Choice(
        /** The build to have ready to install (download it, unless it's the one ready already); null: none now. */
        val build: Int?,
        /** The newest build above this TV's that it's waiting for, when it waits. */
        val waitingFor: Int? = null,
        /** When that one arrives if nothing newer comes out first (ms from now; 0: as soon as it's uploaded). */
        val arrivesInMs: Long = 0,
        /** The newest release listed. */
        val newest: Int? = null,
    )

    /**
     * Which build this TV should have ready to install, from [releases] (GitHub's newest, in any order).
     * [eager]: the test TV, or "Check for updates": the newest. Every other TV takes the newest build
     * that was the newest for a whole day ([SETTLE_MS]) — the test TV has had it a day and nothing had
     * to replace it — or a rollback, which every TV takes at once. However quickly releases come,
     * those TVs never install a build the test TV hasn't had a day with (a burst of releases only
     * makes them wait longer; Check for updates is always there). The build already downloaded
     * ([ready]) stays, unless a later one is chosen or it has been taken down since. Visible for tests.
     */
    internal fun choose(releases: List<Release>, installed: Int, now: Long, eager: Boolean, ready: Int? = null): Choice {
        val listed = releases.filter { !it.prerelease }
        // The builds that became the latest, in turn. Builds can finish out of order: one published after
        // a higher-numbered one never did (the build workflow doesn't make it the latest), so it's left out.
        val line = listed.filter { r -> listed.none { it.build > r.build && it.published < r.published } }.sortedBy { it.build }
        val newest = line.lastOrNull()?.build
        // How long each was the newest: until the next one came out, or until now for the newest.
        fun stood(r: Release): Long = (line.firstOrNull { it.build > r.build }?.published ?: now) - r.published
        val above = line.filter { it.build > installed }
        val pick = if (eager) {
            above.lastOrNull()?.takeIf { it.apk != null }
        } else {
            above.lastOrNull { it.apk != null && (it.rollback || stood(it) >= SETTLE_MS) }
        }
        if (ready != null && ready > installed && (pick == null || pick.build <= ready)) {
            // Taken down since it was downloaded (deleted, or made a pre-release): not installed after all.
            // (Missing only because it's older than every release listed is no reason.)
            val takenDown = listed.none { it.build == ready && it.apk != null } && releases.any { it.build <= ready }
            if (!takenDown) return Choice(ready, newest = newest)
        }
        if (pick != null) return Choice(pick.build, newest = newest)
        val next = above.lastOrNull() ?: return Choice(null, newest = newest)
        val arrives = if (eager || next.rollback) 0L else (SETTLE_MS - stood(next)).coerceAtLeast(0)
        return Choice(null, waitingFor = next.build, arrivesInMs = arrives, newest = newest)
    }

    /** What Settings says while this TV waits for [build], due in [arrivesInMs] if nothing newer comes out. Visible for tests. */
    internal fun waitingMessage(build: Int, arrivesInMs: Long): String =
        if (arrivesInMs <= 0) "2.0.$build is on its way"
        else "2.0.$build arrives in about ${((arrivesInMs + HOUR - 1) / HOUR).coerceAtLeast(1)} h (after the test TV)"

    private suspend fun checkAndDownload(c: Context, manual: Boolean = false) { lock.withLock {
        // Into CHECKING only from the state it was in, so an Install pressed at this very moment (which
        // claims READY the same way) can't be overwritten and then offered again mid-install.
        var before: State
        do {
            before = _state.value
            if (before.step == Step.DOWNLOADING || before.step == Step.INSTALLING) return@withLock
        } while (!_state.compareAndSet(before, before.copy(step = Step.CHECKING, message = null)))
        lastAttempt = System.currentTimeMillis()
        try {
            val releases = listReleases()
            if (releases.isEmpty()) throw UpdateProblem("Couldn’t check · trying again soon")
            val p = prefs(c)
            // (behindOn/behindSince: what the 48-hour limit this replaced kept.)
            p.edit().putLong("lastCheck", System.currentTimeMillis()).remove("behindOn").remove("behindSince").apply()
            val ready = before.latest.takeIf { before.step == Step.READY && apkFile(c).exists() }
            val choice = choose(releases, current, System.currentTimeMillis(), eager = manual || isTestTv(c), ready = ready)
            val build = choice.build
            if (build == null) {
                // A download that's no longer wanted (taken down since) goes.
                if (ready != null) { apkFile(c).delete(); deleteProfiles(c) }
                _state.value = choice.waitingFor?.let { State(Step.UP_TO_DATE, it, message = waitingMessage(it, choice.arrivesInMs)) }
                    ?: State(Step.UP_TO_DATE, choice.newest)
                return@withLock
            }
            // Already downloaded and waiting for a quiet moment: keep it ready.
            if (build == ready) { _state.value = before; return@withLock }
            val release = releases.first { it.build == build }
            val apk = release.apk ?: throw UpdateProblem("Couldn’t check · trying again soon")
            val file = apkFile(c)
            // Already downloaded and checked (e.g. the app was busy last time): don't fetch it again.
            if (!(file.length() == apk.size && verify(c, file) == build)) {
                _state.value = State(Step.DOWNLOADING, build)
                fetchFile(apk.url, file, apk.size) { f -> _state.update { it.copy(progress = f) } }
                val version = verify(c, file)
                if (version != build) { file.delete(); deleteProfiles(c); throw UpdateProblem("The download was damaged · trying again soon") }
            }
            fetchProfile(c, release.files, build)
            // A different build than the one that failed to install: start the install backoff afresh.
            if (before.latest != build) { failures = 0; retryAt = 0 }
            _state.value = State(Step.READY, build)
        } catch (e: Exception) {
            Log.w("Updater", "update check failed", e)
            // An update already downloaded and checked stays ready: a failed check (no network,
            // GitHub's hourly limit) mustn't take away its install.
            if (before.step == Step.READY && apkFile(c).exists()) _state.value = before
            else _state.update { State(Step.ERROR, it.latest, message = plainCheckError(e), offline = !hasNetwork(c)) }
        }
        Unit
    } }

    /** Whether this TV has a network at all (true when Android can't say). */
    private fun hasNetwork(c: Context): Boolean = runCatching {
        c.getSystemService(android.net.ConnectivityManager::class.java)?.activeNetwork != null
    }.getOrDefault(true)

    /** Where the list of releases comes from; tests put in their own (no network in tests). */
    @Volatile internal var listReleases: () -> List<Release> = ::fetchReleases

    /** How a release's file is fetched; tests put in their own. */
    @Volatile internal var fetchFile: (String, File, Long, (Float) -> Unit) -> Unit = ::download

    /** A problem worded for Settings already. */
    private class UpdateProblem(message: String) : IOException(message)

    /** A failed check or download in plain words for Settings (the details go to the log). Visible for tests. */
    internal fun plainCheckError(e: Exception): String = when (e) {
        is UpdateProblem -> e.message ?: "Couldn’t check · trying again soon"
        is java.net.UnknownHostException, is java.net.SocketException, is java.io.InterruptedIOException,
        is javax.net.ssl.SSLException -> "Couldn’t connect · trying again soon"
        else -> "Couldn’t check · trying again soon"
    }

    /** GitHub's "2026-10-14T22:06:24Z" → ms. Visible for tests. */
    fun published(s: String): Long? = runCatching {
        java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssX", java.util.Locale.US).parse(s)?.time
    }.getOrNull()

    /** "v2.0.41" → 41. Visible for tests. */
    fun buildNumber(tag: String): Int? = Regex("^v?\\d+\\.\\d+\\.(\\d+)$").find(tag.trim())?.groupValues?.get(1)?.toIntOrNull()

    /**
     * Which of a release's files (by [names]) is the speed profile for Android API level [sdk]: the
     * one for the highest API level at or below it ("WoodruffFamily-api28.dm" on Android 9 to 11 when
     * there's also an "-api31" one), unless its name says it stops below [sdk]. None below Android 9,
     * which can't install one, or when the release has none. Visible for tests.
     */
    fun profileFor(names: List<String>, sdk: Int): String? {
        if (sdk < 28) return null
        val profiles = names.mapNotNull { name ->
            val m = PROFILE_NAME.matchEntire(name) ?: return@mapNotNull null
            val min = m.groupValues[1].toIntOrNull()?.takeIf { it in 28..sdk } ?: return@mapNotNull null
            Triple(name, min, m.groupValues[2].toIntOrNull() ?: Int.MAX_VALUE)
        }
        val (name, _, max) = profiles.maxByOrNull { it.second } ?: return null
        return name.takeIf { sdk <= max }
    }

    /** The APK's version when it is this app and newer than what's installed, else null. */
    private fun verify(c: Context, file: File): Int? {
        if (!file.exists()) return null
        @Suppress("DEPRECATION")
        val info = c.packageManager.getPackageArchiveInfo(file.path, 0) ?: return null
        @Suppress("DEPRECATION")
        val code = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode.toInt() else info.versionCode
        return code.takeIf { info.packageName == c.packageName && it > current }
    }

    /** The installer session whose answer counts (the latest one started); -1 when none in this run. */
    @Volatile private var session = -1
    /** Sessions let go of on purpose: their "aborted" answers are expected, not a refusal. */
    private val letGoSessions = ConcurrentHashMap.newKeySet<Int>()

    /** Ends an installer session that's waiting for a press nobody will give; it holds a copy of the update. */
    private fun letGo(installer: PackageInstaller, id: Int) {
        letGoSessions.add(id)
        runCatching { installer.abandonSession(id) }.onFailure { Log.w("Updater", "couldn't let go of install $id", it) }
    }

    /**
     * Hands the downloaded APK to the package installer. With a press needed, Android shows its
     * "Update" screen (only possible while Woodruff is on screen); without, it just installs and
     * Woodruff restarts (the phone connection comes back by itself). [how]: who started it; when
     * someone's at Woodruff ([How.OFFERED], [How.CHOSEN]) it opens again after the update, to the
     * screen they were on (asked here when that's the main thread, as the screens call it; else Home).
     */
    fun install(c: Context, how: How = How.CHOSEN) {
        val app = c.applicationContext
        val route = if (how == How.BY_ITSELF) null
        else (if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) OnScreen.routeNow(app) else null) ?: Restore.App(Screen.HOME)
        startInstall(app, how, reopen = route)
    }

    /**
     * [reopen]: what Woodruff opens again to once the update is in (null: it doesn't); a [retry]
     * leaves that as it was. Called from the screen too: only the claim happens here, the files are
     * looked at in the background.
     */
    private fun startInstall(app: Context, how: How, reopen: Restore.Route?, retry: Boolean = false) {
        // Claimed at once, so a second press (or the background loop) can't start another meanwhile.
        val ready = _state.value
        if (ready.step != Step.READY || !_state.compareAndSet(ready, ready.copy(step = Step.INSTALLING))) return
        _offer.value = null
        val build = ready.latest ?: 0
        scope.launch {
            val file = apkFile(app)
            if (!file.exists()) {
                // Android can clear the app's cache, update and all: fetch it again (Install then works
                // once it's back) rather than doing nothing. Only as a manual check when someone chose
                // it, or for a fix after an early close: otherwise that would fetch the newest release at
                // once, skipping the test TV's day.
                _state.update { it.copy(step = Step.READY) }
                checkAndDownload(app, manual = how == How.CHOSEN || how == How.URGENT)
                return@launch
            }
            // This build's speed profile goes in too (Android 9+), unless this TV already turned it down.
            val profile = profileFile(app, build).takeIf {
                Build.VERSION.SDK_INT >= 28 && it.exists() && prefs(app).getInt("profileRefused", 0) != build
            }
            when {
                retry -> Unit
                reopen != null -> setReopen(app, build, reopen)
                else -> clearReopen(app)
            }
            val installer = app.packageManager.packageInstaller
            var id = -1
            var addedProfile = false
            try {
                // Earlier tries nobody answered (a prompt left on screen, a run cut short) each hold a
                // copy of the update: let go of them first. (Not one that's installing right now.)
                runCatching { installer.mySessions }.getOrDefault(emptyList()).filter { !it.isActive }.forEach { letGo(installer, it.sessionId) }
                val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                    setAppPackageName(app.packageName)
                    if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
                    setSize(file.length())
                }
                id = installer.createSession(params)
                session = id
                installer.openSession(id).use { s ->
                    s.openWrite("base.apk", 0, file.length()).use { out ->
                        file.inputStream().use { it.copyTo(out, 256 * 1024) }
                        s.fsync(out)
                    }
                    // Named after the APK it belongs to, as adb names it: Android compiles with it as it installs.
                    if (profile != null) {
                        addedProfile = true
                        Log.i("Updater", "installing 2.0.$build with its speed profile (${profile.length()} bytes)")
                        s.openWrite("base.dm", 0, profile.length()).use { out ->
                            profile.inputStream().use { it.copyTo(out) }
                            s.fsync(out)
                        }
                    }
                    // Mutable: the installer adds the result (and the prompt to show) to this intent.
                    val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                    val answer = Intent(app, InstallResultReceiver::class.java)
                        .putExtra(EXTRA_PROFILE, if (profile != null) build else 0)
                        .putExtra(EXTRA_HOW, how.code).putExtra(EXTRA_BUILD, build).putExtra(EXTRA_SESSION, id)
                    s.commit(PendingIntent.getBroadcast(app, id, answer, flags).intentSender)
                }
            } catch (e: Exception) {
                Log.w("Updater", "install failed", e)
                if (id != -1) letGo(installer, id)
                if (addedProfile && refuseProfile(app, build, e.message)) {
                    // Go again at once without the profile, in a new session.
                    _state.update { it.copy(step = Step.READY) }
                    startInstall(app, how, reopen = null, retry = true)
                    return@launch
                }
                installFailed()
                clearReopen(app)
                _state.update { it.copy(step = Step.READY, message = plainStartError(e)) }
            }
        }
    }

    /** What to do with the package installer's answer: see [nextStep]. */
    internal enum class Next {
        /** About an install already let go of (or an older one): nothing to do. */
        IGNORE,
        INSTALLED,
        /**
         * Android wants a press for an install nobody asked for (or a fix after an early close, with
         * Woodruff off screen by then): let it go, and offer it as Woodruff next opens.
         */
        OFFER_LATER,
        /** Android wants a press and someone is at Woodruff: open Android's prompt. */
        PROMPT,
        /** Android wants a press but Woodruff isn't on screen any more: let it go; it's offered next time. */
        LET_GO,
        /** Someone said no to Android's prompt: not offered again for a while, and not an error. */
        DECLINED,
        /** Turned down with its speed profile: go again without it. */
        RETRY_WITHOUT_PROFILE,
        FAILED,
    }

    /**
     * The decision behind [onResult], from the installer's [status] for [answered] (the session it's
     * about), the session that counts ([latest]), whether Android's prompt [canPrompt] (Woodruff is on
     * screen and Android sent one), and whether a refusal [mayRetryWithoutProfile]. [how] is null for an
     * install started by a version from before it was sent. Visible for tests.
     */
    internal fun nextStep(status: Int, how: How?, answered: Int, latest: Int, wasLetGo: Boolean, canPrompt: Boolean, mayRetryWithoutProfile: Boolean): Next = when {
        wasLetGo -> Next.IGNORE
        answered != -1 && latest != -1 && answered != latest && status != PackageInstaller.STATUS_SUCCESS -> Next.IGNORE
        status == PackageInstaller.STATUS_SUCCESS -> Next.INSTALLED
        status == PackageInstaller.STATUS_PENDING_USER_ACTION -> when {
            how == How.BY_ITSELF -> Next.OFFER_LATER
            canPrompt -> Next.PROMPT
            // A fix after an early close, with Woodruff no longer on screen: offered as it next opens.
            how == How.URGENT -> Next.OFFER_LATER
            else -> Next.LET_GO
        }
        status == PackageInstaller.STATUS_FAILURE_ABORTED && how != How.BY_ITSELF -> Next.DECLINED
        mayRetryWithoutProfile -> Next.RETRY_WITHOUT_PROFILE
        else -> Next.FAILED
    }

    /**
     * The installer's answer for build [build], from session [answered]. [profile]: the build whose speed
     * profile went in with the APK (0: none). [how]: who started it (null: a version from before that was sent).
     */
    internal fun onResult(
        c: Context, status: Int, message: String?, confirm: Intent?, profile: Int = 0,
        how: How? = How.CHOSEN, build: Int = 0, answered: Int = -1,
    ) {
        val app = c.applicationContext
        val p = prefs(app)
        val next = nextStep(
            status, how, answered, session, answered in letGoSessions, canPrompt = confirm != null && mainScreenInFront(),
            mayRetryWithoutProfile = profile > 0 && p.getInt("profileRefused", 0) != profile,
        )
        Log.i("Updater", "installer answered $status for 2.0.$build (${how ?: "?"}, session $answered): $next${message?.let { " ($it)" } ?: ""}")
        if (next != Next.IGNORE && next != Next.PROMPT) p.edit().remove(K_PROMPT_UNTIL).apply()
        when (next) {
            Next.IGNORE -> Unit
            Next.INSTALLED -> {
                failures = 0; retryAt = 0
                scope.launch { apkFile(app).delete(); deleteProfiles(app) }
                _offer.value = null
                p.edit().apply {
                    // A press that went through: the next offer opens Android's prompt straight away.
                    if (how == How.OFFERED || how == How.CHOSEN) putBoolean(K_PROMPT_WORKS, true)
                    // In by itself: this TV doesn't ask for a press (any more).
                    if (how == How.BY_ITSELF) putBoolean(K_ASKS, false)
                }.apply()
                _state.value = State(Step.UP_TO_DATE, current)
            }
            Next.OFFER_LATER -> {
                // Android wants a press for this build after all (some TVs ask for every update). Nobody
                // chose to install it and nobody may be watching (or, for a fix after an early close,
                // Woodruff is off screen by now), so no prompt now: the waiting install is let go of, and
                // the build is offered the next time someone opens Woodruff.
                p.edit().putInt(K_ASKED, build).putBoolean(K_ASKS, true).apply()
                clearReopen(app)
                letGoLater(app, answered)
                backToReady(build, null)
            }
            Next.PROMPT -> {
                val opened = runCatching { app.startActivity(confirm!!.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
                if (opened) {
                    p.edit().putLong(K_PROMPT_UNTIL, System.currentTimeMillis() + PROMPT_WAIT_MS).apply()
                } else {
                    clearReopen(app)
                    letGoLater(app, answered)
                }
                backToReady(build, if (opened) null else "the TV’s install screen didn’t open")
            }
            Next.LET_GO -> {
                // Woodruff isn't on screen any more: offered again the next time it opens.
                clearReopen(app)
                letGoLater(app, answered)
                backToReady(build, null)
            }
            Next.DECLINED -> {
                // Their answer: no "error", and not offered again for a while (Settings → Updates still installs it).
                clearReopen(app)
                if (build > 0) p.edit().putInt(K_LATER_FOR, build).putLong(K_LATER_UNTIL, System.currentTimeMillis() + OFFER_LATER_MS).apply()
                backToReady(build, null)
            }
            Next.RETRY_WITHOUT_PROFILE -> {
                // Turned down with its speed profile, not by someone saying no: maybe this TV won't take
                // the profile, so once per build the update goes again without it.
                refuseProfile(app, profile, message)
                backToReady(build, null)
                startInstall(app, how ?: How.BY_ITSELF, reopen = null, retry = true)
            }
            Next.FAILED -> {
                installFailed()
                clearReopen(app)
                backToReady(build, plainReason(status))
            }
        }
    }

    /**
     * Ready to install [build] again (after an answer that didn't install it). The answer may reach a
     * fresh run of Woodruff, whose state doesn't know the build yet.
     */
    private fun backToReady(build: Int, message: String?) =
        _state.update { State(Step.READY, if (build > 0) build else it.latest, message = message) }

    /** Lets go of installer session [id] in the background (its answer, "aborted", is then expected). */
    private fun letGoLater(app: Context, id: Int) {
        if (id == -1) return
        letGoSessions.add(id)
        scope.launch { letGo(app.packageManager.packageInstaller, id) }
    }

    /** Why the installer turned an update down, in plain words for Settings ("last try: …"). Visible for tests. */
    internal fun plainReason(status: Int): String = when (status) {
        PackageInstaller.STATUS_FAILURE_STORAGE -> "the TV is out of space"
        PackageInstaller.STATUS_FAILURE_BLOCKED -> "this TV doesn’t allow it"
        PackageInstaller.STATUS_FAILURE_INVALID -> "the download was damaged"
        PackageInstaller.STATUS_FAILURE_CONFLICT, PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> "the TV turned it down"
        else -> "the TV couldn’t install it"
    }

    /** An install that couldn't even start, in plain words. */
    private fun plainStartError(e: Exception): String {
        val m = generateSequence<Throwable>(e) { it.cause }.mapNotNull { it.message }.joinToString(" ")
        return if ("ENOSPC" in m || "No space" in m) "the TV is out of space" else "the update couldn’t start"
    }

    private fun apkFile(c: Context) = File(File(c.applicationContext.cacheDir, "updates").apply { mkdirs() }, "update.apk")

    /** Build [build]'s speed profile, kept next to update.apk. */
    private fun profileFile(c: Context, build: Int) = File(apkFile(c).parentFile, "update-$build.dm")

    /** Deletes the downloaded speed profiles: all of them, or all but [keep]'s. */
    private fun deleteProfiles(c: Context, keep: Int = 0) {
        val kept = profileFile(c, keep).name
        apkFile(c).parentFile?.listFiles()?.filter { it.name.endsWith(".dm") && it.name != kept }?.forEach { it.delete() }
    }

    /**
     * The release's speed profile for this TV's Android version, downloaded next to the APK when it
     * has one (releases from before them don't). Never in the way: if it can't be had, the update
     * installs without it, as before.
     */
    private fun fetchProfile(c: Context, files: List<ReleaseFile>, build: Int) {
        val file = profileFile(c, build)
        try {
            val name = profileFor(files.map { it.name }, Build.VERSION.SDK_INT)
            deleteProfiles(c, keep = if (name != null) build else 0)
            val asset = files.firstOrNull { it.name == name } ?: return
            if (asset.size > 0 && file.length() == asset.size) return // already here
            file.delete()
            if (asset.size <= 0) return
            // Through the same door as the APK, which tests replace (they never reach the network).
            fetchFile(asset.url, file, asset.size) {}
            Log.i("Updater", "downloaded $name for 2.0.$build")
        } catch (e: Exception) {
            Log.w("Updater", "couldn't download the speed profile", e)
            file.delete()
        }
    }

    /** Remembers that [build] didn't install with its speed profile; false if that already happened once. */
    private fun refuseProfile(c: Context, build: Int, why: String?): Boolean {
        val p = prefs(c)
        if (p.getInt("profileRefused", 0) == build) return false
        p.edit().putInt("profileRefused", build).apply()
        Log.w("Updater", "2.0.$build didn't install with its speed profile ($why): trying again without it")
        return true
    }

    /** The newest releases, read straight off the connection (only the parts updating needs are kept). */
    private fun fetchReleases(): List<Release> {
        val conn = (URL(RELEASES).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000; readTimeout = 20_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "WoodruffFamily/${BuildConfig.VERSION_NAME}")
        }
        try {
            val code = conn.responseCode
            if (code == 403 || code == 429) throw UpdateProblem("The update server is busy · trying again soon")
            if (code !in 200..299) throw UpdateProblem("Couldn’t check · trying again soon")
            return conn.inputStream.bufferedReader().use { parseReleases(it) }
        } finally {
            conn.disconnect()
        }
    }

    /**
     * GitHub's list of releases (a JSON array of about 12 KB each, mostly release notes and uploader
     * details), read as it streams in, keeping only what updating needs. Releases without a version
     * tag or a publish time (drafts) are left out. Visible for tests.
     */
    internal fun parseReleases(reader: Reader): List<Release> {
        val out = ArrayList<Release>()
        JsonReader(reader).use { json ->
            json.beginArray()
            while (json.hasNext()) readRelease(json)?.let { out += it }
            json.endArray()
        }
        return out
    }

    private fun readRelease(json: JsonReader): Release? {
        var tag = ""
        var name = ""
        var published: String? = null
        var draft = false
        var prerelease = false
        val files = ArrayList<ReleaseFile>()
        json.beginObject()
        while (json.hasNext()) {
            when (json.nextName()) {
                "tag_name" -> tag = json.stringOrNull().orEmpty()
                "name" -> name = json.stringOrNull().orEmpty()
                "published_at" -> published = json.stringOrNull()
                "draft" -> draft = json.booleanOrFalse()
                "prerelease" -> prerelease = json.booleanOrFalse()
                "assets" -> readFiles(json, files)
                else -> json.skipValue()
            }
        }
        json.endObject()
        if (draft) return null
        val build = buildNumber(tag) ?: return null
        val at = published?.let { published(it) } ?: return null
        // "rollback" in its name: a re-release to undo a bad build, or an urgent fix (see the build workflow).
        val rollback = name.contains("rollback", ignoreCase = true) || tag.contains("rollback", ignoreCase = true)
        return Release(build, at, rollback, files, prerelease)
    }

    private fun readFiles(json: JsonReader, into: MutableList<ReleaseFile>) {
        if (json.peek() == JsonToken.NULL) { json.nextNull(); return }
        json.beginArray()
        while (json.hasNext()) {
            var name = ""
            var size = 0L
            var url = ""
            var state = "uploaded"
            json.beginObject()
            while (json.hasNext()) {
                when (json.nextName()) {
                    "name" -> name = json.stringOrNull().orEmpty()
                    "size" -> size = if (json.peek() == JsonToken.NUMBER) json.nextLong() else { json.skipValue(); 0L }
                    "browser_download_url" -> url = json.stringOrNull().orEmpty()
                    "state" -> state = json.stringOrNull() ?: "uploaded"
                    else -> json.skipValue()
                }
            }
            json.endObject()
            // Still uploading ("open"): not there yet.
            if (state == "uploaded" && name.isNotEmpty() && url.isNotEmpty()) into += ReleaseFile(name, size, url)
        }
        json.endArray()
    }

    private fun JsonReader.stringOrNull(): String? = when (peek()) {
        JsonToken.NULL -> { nextNull(); null }
        JsonToken.STRING, JsonToken.NUMBER -> nextString()
        else -> { skipValue(); null }
    }

    private fun JsonReader.booleanOrFalse(): Boolean =
        if (peek() == JsonToken.BOOLEAN) nextBoolean() else { skipValue(); false }

    private fun download(url: String, dest: File, size: Long, progress: (Float) -> Unit) {
        val tmp = File(dest.parentFile, dest.name + ".part")
        // GitHub redirects release downloads to its file servers (https to https, which this follows).
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000; readTimeout = 30_000; instanceFollowRedirects = true
            setRequestProperty("User-Agent", "WoodruffFamily/${BuildConfig.VERSION_NAME}")
        }
        try {
            if (conn.responseCode !in 200..299) throw UpdateProblem("Couldn’t download it · trying again soon")
            var done = 0L
            var shown = 0L
            conn.inputStream.use { input ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(256 * 1024)
                    while (true) {
                        val n = input.read(buf); if (n < 0) break
                        out.write(buf, 0, n); done += n
                        if (size > 0 && done - shown > size / 50) { shown = done; progress(done.toFloat() / size) }
                    }
                }
            }
            if (size > 0 && done != size) throw UpdateProblem("The download stopped · trying again soon")
            if (!tmp.renameTo(dest)) throw UpdateProblem("Couldn’t save it: the TV may be out of space")
        } finally {
            conn.disconnect()
            tmp.delete()
        }
    }
}

/** Receives the package installer's answer for an update (only from our own PendingIntent). */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        @Suppress("DEPRECATION")
        val confirm = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
        else intent.getParcelableExtra(Intent.EXTRA_INTENT)
        Updater.onResult(
            context,
            intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE),
            intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE),
            confirm,
            intent.getIntExtra(Updater.EXTRA_PROFILE, 0),
            Updater.How.of(intent.getIntExtra(Updater.EXTRA_HOW, 0)),
            intent.getIntExtra(Updater.EXTRA_BUILD, 0),
            intent.getIntExtra(Updater.EXTRA_SESSION, intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1)),
        )
    }
}
