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
import com.woodruff.tvexplorer.nowplaying.NowPlaying
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

/**
 * Keeps Woodruff up to date from this project's GitHub releases. Release "v2.0.N" is built with
 * versionCode N, so a higher N is newer. The APK is downloaded, checked (this app's package, a
 * newer version), and handed to Android's package installer, which also refuses anything not
 * signed with the same key as the installed app.
 *
 * Android 12+ lets an app update itself without a prompt once that app installed the current copy
 * (the very first update, after an install from Downloader, shows one "Update" prompt). Silent
 * installs wait until nobody is using Woodruff (nothing playing, no button pressed for 2 minutes),
 * since an update restarts it; if it was on screen, it opens again by itself.
 *
 * On Android 9+ the update brings its speed profile (a .dm file published with the release) in the
 * same install, as Play and adb do: Android compiles the new code while installing, instead of
 * running it uncompiled until the TV's overnight compile.
 */
object Updater {
    /** The newest releases, in one request (GitHub allows 60 an hour for the whole home, shared by every TV). */
    private const val RELEASES = "https://api.github.com/repos/Blakew316/woodrufffiles/releases?per_page=30"
    private const val ASSET = "WoodruffFamily.apk"
    /** A release's speed profiles, named by the lowest API level each serves ("-api28-30" if it stops early). */
    private val PROFILE_NAME = Regex("""WoodruffFamily-api(\d+)(?:-(\d+))?\.dm""")
    /** Which build's speed profile went into an install (0: none), so a refusal can be retried without it. */
    internal const val EXTRA_PROFILE = "com.woodruff.tvexplorer.update.PROFILE"
    /** Whether someone chose Install (else it went in on its own), and which build: see [K_ASKED]. */
    internal const val EXTRA_MANUAL = "com.woodruff.tvexplorer.update.MANUAL"
    internal const val EXTRA_BUILD = "com.woodruff.tvexplorer.update.BUILD"
    private const val PREFS = "updates"
    private const val HOUR = 3_600_000L
    /** In the background (the phone-connection service's loop). */
    private const val CHECK_EVERY_MS = 3_600_000L
    /** When Woodruff is opened: often enough that a fresh release shows up the next time you open it. */
    private const val CHECK_ON_OPEN_MS = 10 * 60_000L
    private const val IDLE_MS = 2 * 60_000L
    /** How long a build must be the newest before the TVs other than the test TV take it. */
    internal const val SETTLE_MS = 24 * HOUR

    enum class Step { IDLE, CHECKING, UP_TO_DATE, DOWNLOADING, READY, INSTALLING, ERROR }

    data class State(
        val step: Step = Step.IDLE,
        /** The build this is about: the one ready or downloading, the one waited for, or the newest release. */
        val latest: Int? = null,
        val progress: Float = 0f,
        val message: String? = null,
    ) {
        val latestName: String? get() = latest?.let { "2.0.$it" }
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()

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
     * Can this update go in without a prompt? Android 12+ lets an app with
     * UPDATE_PACKAGES_WITHOUT_USER_ACTION update itself without asking, whoever installed it
     * (Downloader, or Woodruff itself), so it's tried. It isn't held back by "Install unknown apps"
     * answering no: a Sony answered no while its Settings showed it allowed, and its updates then
     * waited for a button press every time. If Android asks anyway for a build ([K_ASKED]), that
     * build waits for the prompt the next time Woodruff is on screen instead of being retried.
     */
    fun canInstallSilently(c: Context): Boolean {
        if (Build.VERSION.SDK_INT < 31) return false
        val build = _state.value.latest ?: return true
        return prefs(c).getInt(K_ASKED, 0) != build
    }

    /** The build Android asked about (a prompt) when it was installed silently: it waits for someone to press Install. */
    private const val K_ASKED = "askedFor"

    /**
     * The background routine: check and download (at most hourly, or every 10 minutes when
     * [opening] the app); then install right away if that needs no prompt and nobody is using
     * Woodruff. Called by the phone-connection service's loop and when the app opens. Does nothing
     * when automatic updates are off.
     */
    fun maybeAuto(c: Context, opening: Boolean = false) {
        val app = c.applicationContext
        if (!autoEnabled(app)) return
        scope.launch {
            val p = prefs(app)
            // After a restart: an update downloaded and checked earlier is ready again at once,
            // instead of after the next check (up to an hour, or a day's wait on other TVs).
            if (_state.value.step == Step.IDLE) restoreReady(app)
            val every = if (opening) CHECK_ON_OPEN_MS else CHECK_EVERY_MS
            val now = System.currentTimeMillis()
            // After a failed check, try again sooner — but not on every pass: GitHub allows 60 checks
            // an hour for the whole home connection, shared by every TV.
            val retryError = _state.value.step == Step.ERROR && now - lastAttempt >= ERROR_RETRY_MS
            if (now - p.getLong("lastCheck", 0) >= every || retryError) {
                checkAndDownload(app)
            }
            if (_state.value.step == Step.READY && canInstallSilently(app) && idle() && System.currentTimeMillis() >= retryAt) {
                // On screen but untouched: Woodruff restarts, so open it again afterwards.
                if (AppVisibility.foreground) prefs(app).edit().putBoolean("reopen", true).apply()
                install(app, manual = false)
            }
        }
    }

    /**
     * Nobody is using Woodruff: nothing playing in it, and it's either not on screen or no button
     * has been pressed for [IDLE_MS], so a restart for the update interrupts no one.
     */
    private fun idle(): Boolean = NowPlaying.ownPlayer == null &&
        (!AppVisibility.foreground || System.currentTimeMillis() - AppVisibility.lastInput >= IDLE_MS)

    /** After an update that installed while Woodruff was on screen: reopen it (once). */
    fun takeReopen(c: Context): Boolean {
        val p = prefs(c)
        return p.getBoolean("reopen", false).also { if (it) p.edit().remove("reopen").apply() }
    }

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
        /** "Installed fresh Oct 31 · updated Nov 2 by Woodruff itself". */
        fun summary(): String {
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
        if (_state.value.step == Step.DOWNLOADING || _state.value.step == Step.INSTALLING) return@withLock
        val before = _state.value
        lastAttempt = System.currentTimeMillis()
        _state.update { it.copy(step = Step.CHECKING, message = null) }
        try {
            val releases = fetchReleases()
            if (releases.isEmpty()) throw UpdateProblem("Couldn't check · trying again soon")
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
            val apk = release.apk ?: throw UpdateProblem("Couldn't check · trying again soon")
            val file = apkFile(c)
            // Already downloaded and checked (e.g. the app was busy last time): don't fetch it again.
            if (!(file.length() == apk.size && verify(c, file) == build)) {
                _state.value = State(Step.DOWNLOADING, build)
                download(apk.url, file, apk.size) { f -> _state.update { it.copy(progress = f) } }
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
            else _state.update { State(Step.ERROR, it.latest, message = plainCheckError(e)) }
        }
        Unit
    } }

    /** A problem worded for Settings already. */
    private class UpdateProblem(message: String) : IOException(message)

    /** A failed check or download in plain words for Settings (the details go to the log). Visible for tests. */
    internal fun plainCheckError(e: Exception): String = when (e) {
        is UpdateProblem -> e.message ?: "Couldn't check · trying again soon"
        is java.net.UnknownHostException, is java.net.SocketException, is java.io.InterruptedIOException,
        is javax.net.ssl.SSLException -> "Couldn't connect · trying again soon"
        else -> "Couldn't check · trying again soon"
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

    /**
     * Hands the downloaded APK to the package installer. With a prompt, Android shows its
     * "Update" screen (only possible while Woodruff is on screen); without, it just installs and
     * Woodruff restarts (the phone connection comes back by itself). [manual]: someone chose
     * Install; false when it installs on its own.
     */
    fun install(c: Context, manual: Boolean = true) {
        val app = c.applicationContext
        val file = apkFile(app)
        if (_state.value.step != Step.READY) return
        // Android can clear the app's cache, update and all: fetch it again (Install then works
        // once it's back) rather than doing nothing. Not as a manual check when installing on its
        // own: that fetched the newest release at once, skipping the test TV's day.
        if (!file.exists()) { scope.launch { checkAndDownload(app, manual) }; return }
        // This build's speed profile goes in too (Android 9+), unless this TV already turned it down.
        val build = _state.value.latest ?: 0
        val profile = profileFile(app, build).takeIf {
            Build.VERSION.SDK_INT >= 28 && it.exists() && prefs(app).getInt("profileRefused", 0) != build
        }
        _state.update { it.copy(step = Step.INSTALLING) }
        scope.launch {
            var id = -1
            var addedProfile = false
            try {
                val installer = app.packageManager.packageInstaller
                val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                    setAppPackageName(app.packageName)
                    if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
                    setSize(file.length())
                }
                id = installer.createSession(params)
                installer.openSession(id).use { session ->
                    session.openWrite("base.apk", 0, file.length()).use { out ->
                        file.inputStream().use { it.copyTo(out, 256 * 1024) }
                        session.fsync(out)
                    }
                    // Named after the APK it belongs to, as adb names it: Android compiles with it as it installs.
                    if (profile != null) {
                        addedProfile = true
                        Log.i("Updater", "installing 2.0.$build with its speed profile (${profile.length()} bytes)")
                        session.openWrite("base.dm", 0, profile.length()).use { out ->
                            profile.inputStream().use { it.copyTo(out) }
                            session.fsync(out)
                        }
                    }
                    // Mutable: the installer adds the result (and the prompt to show) to this intent.
                    val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                    val answer = Intent(app, InstallResultReceiver::class.java).putExtra(EXTRA_PROFILE, if (profile != null) build else 0)
                        .putExtra(EXTRA_MANUAL, manual).putExtra(EXTRA_BUILD, build)
                    val result = PendingIntent.getBroadcast(app, id, answer, flags)
                    session.commit(result.intentSender)
                }
            } catch (e: Exception) {
                Log.w("Updater", "install failed", e)
                if (addedProfile && refuseProfile(app, build, e.message)) {
                    // Go again at once without the profile, in a new session.
                    if (id != -1) runCatching { app.packageManager.packageInstaller.abandonSession(id) }
                    _state.update { it.copy(step = Step.READY) }
                    install(app, manual)
                    return@launch
                }
                installFailed()
                _state.update { it.copy(step = Step.READY, message = e.message ?: "Couldn't start the update") }
            }
        }
    }

    /** The installer's answer. [profile]: the build whose speed profile went in with the APK (0: none). */
    internal fun onResult(c: Context, status: Int, message: String?, confirm: Intent?, profile: Int = 0, manual: Boolean = true, build: Int = 0) {
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                // Android wants a yes for this one after all: no more silent tries for it, the prompt instead.
                if (!manual && build > 0) prefs(c).edit().putInt(K_ASKED, build).apply()
                if (confirm != null && AppVisibility.foreground) {
                    runCatching { c.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                }
                // Not on screen (or the prompt couldn't open): offer it again next time Woodruff opens.
                _state.update { it.copy(step = Step.READY) }
            }
            PackageInstaller.STATUS_SUCCESS -> { failures = 0; retryAt = 0; apkFile(c).delete(); deleteProfiles(c); _state.value = State(Step.UP_TO_DATE, current) }
            else -> {
                // Turned down with its speed profile, and not by someone saying no to the prompt: maybe
                // this TV won't take the profile, so once per build the update goes again without it.
                if (profile > 0 && status != PackageInstaller.STATUS_FAILURE_ABORTED && refuseProfile(c, profile, message)) {
                    _state.update { it.copy(step = Step.READY) }
                    install(c, manual = false)
                } else {
                    installFailed(); _state.update { it.copy(step = Step.READY, message = message ?: "The update didn't install") }
                }
            }
        }
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
            download(asset.url, file, asset.size) {}
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
            if (code !in 200..299) throw UpdateProblem("Couldn't check · trying again soon")
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
            if (conn.responseCode !in 200..299) throw UpdateProblem("Couldn't download it · trying again soon")
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
            if (!tmp.renameTo(dest)) throw UpdateProblem("Couldn't save it: the TV may be out of space")
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
            intent.getBooleanExtra(Updater.EXTRA_MANUAL, true),
            intent.getIntExtra(Updater.EXTRA_BUILD, 0),
        )
    }
}
