package com.woodruff.tvexplorer.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
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
import org.json.JSONObject
import java.io.File
import java.io.IOException
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
 */
object Updater {
    private const val API = "https://api.github.com/repos/Blakew316/woodrufffiles/releases/latest"
    private const val ASSET = "WoodruffFamily.apk"
    private const val PREFS = "updates"
    /** In the background (the phone-connection service's loop). */
    private const val CHECK_EVERY_MS = 3_600_000L
    /** When Woodruff is opened: often enough that a fresh release shows up the next time you open it. */
    private const val CHECK_ON_OPEN_MS = 10 * 60_000L
    private const val IDLE_MS = 2 * 60_000L
    private const val SETTLE_MS = 24 * 3_600_000L
    /** However often releases come, a TV never stays behind longer than this. */
    private const val MAX_BEHIND_MS = 2 * SETTLE_MS

    enum class Step { IDLE, CHECKING, UP_TO_DATE, DOWNLOADING, READY, INSTALLING, ERROR }

    data class State(
        val step: Step = Step.IDLE,
        /** Build number of the newest release, when known. */
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
     * Test TV: installs each release as soon as it's out. Every other TV waits until the newest
     * release has been out for [SETTLE_MS] with nothing newer, so a bad build is caught (and
     * replaced) on the test TV before it reaches them. "Check for updates" in Settings skips the wait.
     */
    fun isTestTv(c: Context) = prefs(c).getBoolean("testTv", false)
    fun setTestTv(c: Context, on: Boolean) { prefs(c).edit().putBoolean("testTv", on).apply() }

    fun autoEnabled(c: Context) = prefs(c).getBoolean("auto", true)
    fun setAuto(c: Context, on: Boolean) { prefs(c).edit().putBoolean("auto", on).apply() }
    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Can this update go in without a prompt? Android 12+, and Woodruff installed the current copy. */
    fun canInstallSilently(c: Context): Boolean {
        if (Build.VERSION.SDK_INT < 31) return false
        val installer = runCatching { c.packageManager.getInstallSourceInfo(c.packageName).installingPackageName }.getOrNull()
        return installer == c.packageName && c.packageManager.canRequestPackageInstalls()
    }

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
            val every = if (opening) CHECK_ON_OPEN_MS else CHECK_EVERY_MS
            if (System.currentTimeMillis() - p.getLong("lastCheck", 0) >= every || _state.value.step == Step.ERROR) {
                checkAndDownload(app)
            }
            if (_state.value.step == Step.READY && canInstallSilently(app) && idle() && System.currentTimeMillis() >= retryAt) {
                // On screen but untouched: Woodruff restarts, so open it again afterwards.
                if (AppVisibility.foreground) prefs(app).edit().putBoolean("reopen", true).apply()
                install(app)
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

    /** Settings → Check for updates. */
    fun checkNow(c: Context) { val app = c.applicationContext; scope.launch { checkAndDownload(app, manual = true) } }

    /** After a failed silent install, the next try waits (5 min, doubling, up to 6 h) instead of looping. */
    @Volatile private var retryAt = 0L
    @Volatile private var failures = 0
    private fun installFailed() {
        failures++
        retryAt = System.currentTimeMillis() + (5 * 60_000L shl (failures - 1).coerceAtMost(6)).coerceAtMost(6 * 3_600_000L)
    }

    private suspend fun checkAndDownload(c: Context, manual: Boolean = false) { lock.withLock {
        if (_state.value.step == Step.DOWNLOADING || _state.value.step == Step.INSTALLING) return@withLock
        val before = _state.value
        _state.update { it.copy(step = Step.CHECKING, message = null) }
        try {
            val release = JSONObject(get(API))
            prefs(c).edit().putLong("lastCheck", System.currentTimeMillis()).apply()
            val latest = buildNumber(release.optString("tag_name"))
                ?: throw IOException("Couldn't read the latest version")
            val p = prefs(c)
            if (latest <= current) { p.edit().remove("behindOn").remove("behindSince").apply(); _state.value = State(Step.UP_TO_DATE, latest); return@withLock }
            // Already downloaded and waiting for a quiet moment: keep it ready.
            if (before.step == Step.READY && before.latest == latest && apkFile(c).exists()) { _state.value = before; return@withLock }
            val now = System.currentTimeMillis()
            val age = now - (published(release.optString("published_at")) ?: 0L)
            // How long this TV has known it's behind (on this installed version).
            if (p.getInt("behindOn", -1) != current) p.edit().putInt("behindOn", current).putLong("behindSince", now).apply()
            val behind = now - p.getLong("behindSince", now)
            // A rollback (an earlier version re-released to undo a bad one) goes to every TV at once.
            val rollback = release.optString("name").contains("rollback", ignoreCase = true) ||
                release.optString("tag_name").contains("rollback", ignoreCase = true)
            if (waits(manual, rollback, isTestTv(c), age, behind)) {
                val hours = (minOf(SETTLE_MS - age, MAX_BEHIND_MS - behind) / 3_600_000L + 1).coerceAtLeast(1)
                _state.value = State(Step.UP_TO_DATE, latest, message = "2.0.$latest arrives in about $hours h (after the test TV)")
                return@withLock
            }
            val assets = release.optJSONArray("assets")
            val asset = (0 until (assets?.length() ?: 0)).map { assets!!.getJSONObject(it) }.firstOrNull { it.optString("name") == ASSET }
                ?: throw IOException("The release has no $ASSET")
            val file = apkFile(c)
            val size = asset.optLong("size")
            // Already downloaded and checked (e.g. the app was busy last time): don't fetch it again.
            if (!(file.length() == size && verify(c, file) == latest)) {
                _state.value = State(Step.DOWNLOADING, latest)
                download(asset.getString("browser_download_url"), file, size) { p -> _state.update { it.copy(progress = p) } }
                val version = verify(c, file)
                if (version != latest) { file.delete(); throw IOException("The download wasn't a valid Woodruff update") }
            }
            _state.value = State(Step.READY, latest)
        } catch (e: Exception) {
            Log.w("Updater", "update check failed", e)
            _state.update { State(Step.ERROR, it.latest, message = e.message ?: "Couldn't check for updates") }
        }
        Unit
    } }

    /** Whether this TV holds off on a release [ageMs] old (see [isTestTv]). Visible for tests. */
    fun waits(manual: Boolean, rollback: Boolean, testTv: Boolean, ageMs: Long, behindMs: Long = 0): Boolean =
        !manual && !rollback && !testTv && ageMs < SETTLE_MS && behindMs < MAX_BEHIND_MS

    /** GitHub's "2026-10-14T22:06:24Z" → ms. Visible for tests. */
    fun published(s: String): Long? = runCatching {
        java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssX", java.util.Locale.US).parse(s)?.time
    }.getOrNull()

    /** "v2.0.41" → 41. Visible for tests. */
    fun buildNumber(tag: String): Int? = Regex("^v?\\d+\\.\\d+\\.(\\d+)$").find(tag.trim())?.groupValues?.get(1)?.toIntOrNull()

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
     * Woodruff restarts (the phone connection comes back by itself).
     */
    fun install(c: Context) {
        val app = c.applicationContext
        val file = apkFile(app)
        if (_state.value.step != Step.READY || !file.exists()) return
        _state.update { it.copy(step = Step.INSTALLING) }
        scope.launch {
            try {
                val installer = app.packageManager.packageInstaller
                val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                    setAppPackageName(app.packageName)
                    if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
                    setSize(file.length())
                }
                val id = installer.createSession(params)
                installer.openSession(id).use { session ->
                    session.openWrite("base.apk", 0, file.length()).use { out ->
                        file.inputStream().use { it.copyTo(out, 256 * 1024) }
                        session.fsync(out)
                    }
                    // Mutable: the installer adds the result (and the prompt to show) to this intent.
                    val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                    val result = PendingIntent.getBroadcast(app, id, Intent(app, InstallResultReceiver::class.java), flags)
                    session.commit(result.intentSender)
                }
            } catch (e: Exception) {
                Log.w("Updater", "install failed", e)
                installFailed()
                _state.update { it.copy(step = Step.READY, message = e.message ?: "Couldn't start the update") }
            }
        }
    }

    internal fun onResult(c: Context, status: Int, message: String?, confirm: Intent?) {
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                if (confirm != null && AppVisibility.foreground) {
                    runCatching { c.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                }
                // Not on screen (or the prompt couldn't open): offer it again next time Woodruff opens.
                _state.update { it.copy(step = Step.READY) }
            }
            PackageInstaller.STATUS_SUCCESS -> { failures = 0; retryAt = 0; apkFile(c).delete(); _state.value = State(Step.UP_TO_DATE, current) }
            else -> { installFailed(); _state.update { it.copy(step = Step.READY, message = message ?: "The update didn't install") } }
        }
    }

    private fun apkFile(c: Context) = File(File(c.applicationContext.cacheDir, "updates").apply { mkdirs() }, "update.apk")

    private fun get(url: String): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000; readTimeout = 20_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "WoodruffFamily/${BuildConfig.VERSION_NAME}")
        }
        try {
            if (conn.responseCode !in 200..299) throw IOException("GitHub answered ${conn.responseCode}")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun download(url: String, dest: File, size: Long, progress: (Float) -> Unit) {
        val tmp = File(dest.parentFile, dest.name + ".part")
        // GitHub redirects release downloads to its file servers (https to https, which this follows).
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000; readTimeout = 30_000; instanceFollowRedirects = true
            setRequestProperty("User-Agent", "WoodruffFamily/${BuildConfig.VERSION_NAME}")
        }
        try {
            if (conn.responseCode !in 200..299) throw IOException("Download failed (${conn.responseCode})")
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
            if (size > 0 && done != size) throw IOException("The download was cut off")
            if (!tmp.renameTo(dest)) throw IOException("Couldn't save the update")
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
        )
    }
}
