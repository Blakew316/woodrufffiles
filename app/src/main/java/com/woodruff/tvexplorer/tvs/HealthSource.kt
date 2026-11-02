package com.woodruff.tvexplorer.tvs

import android.content.Context
import android.os.Build
import android.os.Process
import android.os.StatFs
import android.os.SystemClock
import android.provider.Settings
import com.woodruff.tvexplorer.BuildConfig
import com.woodruff.tvexplorer.cloud.GoogleAuth
import com.woodruff.tvexplorer.nest.Doorbell
import com.woodruff.tvexplorer.tmdb.Tmdb
import com.woodruff.tvexplorer.update.Updater
import com.woodruff.tvexplorer.util.CrashLog
import com.woodruff.tvexplorer.util.ExitReasons
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * This TV's health report ([buildHealth]), from facts it already holds: each sign-in's state
 * ([SignIns]), how it hears the doorbell ([Doorbell.alerts], the words Settings → Advanced shows),
 * the update state, how this copy was installed, the last crash ([CrashLog]) and
 * Android's reasons for closing Woodruff ([ExitReasons]), free space and which shared keys are saved
 * (never their values). Every read is cheap, but some ask Android's services or the disk: build it
 * off the main thread. Kept for 10 s, so phones and the hub asking at once cost one.
 */
object Health {
    private const val PREFS = "health"
    private const val K_READY_BUILD = "update.readyBuild"
    private const val K_READY_SINCE = "update.readySince"
    private const val K_CRASH_CLEARED = "crash.clearedAt"
    private const val KEEP_MS = 10_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var kept: HealthReport? = null
    @Volatile private var started = false

    /**
     * Starts what the report has to watch from the start of each run: sign-ins Google ends
     * ([SignIns]) and the moment an update becomes ready (for "waiting for one press" after a day).
     * Android's reasons for closing Woodruff are read a little after start-up. Called once, as the app starts.
     */
    fun init(c: Context) {
        synchronized(this) { if (started) return; started = true }
        val app = c.applicationContext
        SignIns.init(app)
        // The phones are told when Google ends one of this TV's sign-ins (and, on the hub, a linked TV's).
        SignInAlerts.init(app)
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            var step: Updater.Step? = null
            Updater.state.collect { s ->
                // A check or an install moved on (one asked for from a phone, too): the next ask says so, not the
                // report kept from just before ("Checking…" for 10 s after it finished).
                if (s.step != step) { step = s.step; kept = null }
                if (s.step == Updater.Step.READY && s.latest != null) noteReady(app, s.latest)
            }
        }
        scope.launch {
            delay(20_000)
            runCatching { ExitReasons.recent(app) }
        }
    }

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Remembers when [build] became ready to install (once per build). */
    private fun noteReady(c: Context, build: Int) {
        val p = prefs(c)
        if (p.getInt(K_READY_BUILD, 0) != build) p.edit().putInt(K_READY_BUILD, build).putLong(K_READY_SINCE, System.currentTimeMillis()).apply()
    }

    /** The phone cleared the crash report: the crashes before now no longer need attention. */
    fun crashesCleared(c: Context) {
        prefs(c).edit().putLong(K_CRASH_CLEARED, System.currentTimeMillis()).apply()
        kept = null
    }

    /** Something the report says just changed (a sign-in made from the phone): the next ask makes a fresh one. */
    fun forget() { kept = null }

    /**
     * This TV's report, at most 10 s old ([fresh]: made now). [probe]: someone is looking at the
     * whole report, so sign-ins nothing has used lately are renewed in the background ([SignIns.probe]).
     * Blocking: call off the main thread.
     */
    fun report(c: Context, fresh: Boolean = false, probe: Boolean = false): HealthReport {
        val app = c.applicationContext
        if (probe) SignIns.probe(app)
        val now = System.currentTimeMillis()
        kept?.let { if (!fresh && now - it.at in 0 until KEEP_MS) return it }
        return buildHealth(facts(app), clock(app, now)).also { kept = it }
    }

    /** The report made last, if any: a screen starts from it while a fresh one is made. */
    fun lastReport(): HealthReport? = kept

    /** The report in a few words, for /api/status (which the hub asks every few seconds while a TV list is open). */
    fun summary(c: Context): HealthSummary = report(c).summary

    /** How times read on this TV: its 12- or 24-hour clock and its time zone. */
    fun clock(c: Context, now: Long = System.currentTimeMillis()): HealthClock {
        val time = android.text.format.DateFormat.getTimeFormat(c)
        val date = SimpleDateFormat(android.text.format.DateFormat.getBestDateTimePattern(Locale.getDefault(), "MMMd"), Locale.getDefault())
        return HealthClock(now, TimeZone.getDefault(), { time.format(java.util.Date(it)) }, { date.format(java.util.Date(it)) })
    }

    private fun facts(c: Context): HealthFacts {
        val now = System.currentTimeMillis()
        val u = Updater.state.value
        val p = prefs(c)
        val readySince = if (u.step == Updater.Step.READY && u.latest != null && p.getInt(K_READY_BUILD, 0) == u.latest) p.getLong(K_READY_SINCE, 0L).takeIf { it > 0 } else null
        val installed = Updater.installFacts(c)
        val crash = runCatching { CrashLog.latest(c) }.getOrNull()?.takeIf { it.isNotBlank() }?.let { CrashFact(crashTime(it, now), it) }
        return HealthFacts(
            id = TvLinks.id(c),
            name = TvLinks.name(c),
            version = BuildConfig.VERSION_NAME,
            sdk = Build.VERSION.SDK_INT,
            uptimeMs = runCatching { SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime() }.getOrDefault(0L),
            signIns = HealthGrant.entries.associateWith { runCatching { SignIns.state(c, it) }.getOrDefault(SignInFact(SignInState.NOT_SET_UP)) },
            doorbell = runCatching { doorbellFact(Doorbell.alerts(c)) }.getOrDefault(DoorbellFact(DoorbellState.OFF, "Off", null)),
            update = UpdateFact(
                step = stepName(u.step), latestName = u.latestName, progress = u.progress, message = u.message,
                readySince = readySince, auto = Updater.autoEnabled(c), testTv = Updater.isTestTv(c),
                needsPress = u.latest?.let { Updater.needsPress(c, it) } ?: true, asks = Updater.asksEachTime(c),
            ),
            installed = InstallFact(installed.summary(), installed.installedBy, installed.firstInstall, installed.lastUpdate),
            installAllowed = if (Build.VERSION.SDK_INT >= 26) runCatching { c.packageManager.canRequestPackageInstalls() }.getOrNull() else null,
            popUpsAllowed = if (Build.VERSION.SDK_INT >= 29) runCatching { Settings.canDrawOverlays(c) }.getOrNull() else null,
            crash = crash,
            crashesClearedAt = p.getLong(K_CRASH_CLEARED, 0L),
            exits = ExitReasons.recent(c),
            freeBytes = runCatching { StatFs(c.filesDir.path).availableBytes }.getOrNull(),
            keys = KeysFact(
                googleClientId = !GoogleAuth.clientId(c).isNullOrBlank(),
                googleClientSecret = !GoogleAuth.clientSecret(c).isNullOrBlank(),
                camerasProject = GoogleAuth.nestProject(c) != null,
                tmdb = Tmdb.isConfigured(c),
            ),
        )
    }

    private fun stepName(s: Updater.Step) = when (s) {
        Updater.Step.IDLE -> "idle"
        Updater.Step.CHECKING -> "checking"
        Updater.Step.UP_TO_DATE -> "upToDate"
        Updater.Step.DOWNLOADING -> "downloading"
        Updater.Step.READY -> "ready"
        Updater.Step.INSTALLING -> "installing"
        Updater.Step.ERROR -> "error"
    }
}

/**
 * When the crash in [report] happened, from the time its first line carries ("Version 2.0.171 ·
 * Nov 1, 9:41 PM · thread main", written without the year): this year's, or last year's when that
 * would be in the future. [now] when the report says no time.
 */
internal fun crashTime(report: String, now: Long, zone: TimeZone = TimeZone.getDefault()): Long {
    val stamp = Regex("""[A-Z][a-z]{2} \d{1,2}, \d{1,2}:\d{2} [AP]M""").find(report.lineSequence().firstOrNull().orEmpty())?.value ?: return now
    val parsed = runCatching { SimpleDateFormat("MMM d, h:mm a", Locale.US).apply { timeZone = zone }.parse(stamp) }.getOrNull() ?: return now
    val year = Calendar.getInstance(zone).apply { timeInMillis = now }.get(Calendar.YEAR)
    val at = Calendar.getInstance(zone).apply { time = parsed; set(Calendar.YEAR, year) }
    if (at.timeInMillis > now + 24 * 3_600_000L) at.add(Calendar.YEAR, -1)
    return at.timeInMillis
}
