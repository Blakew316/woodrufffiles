package com.woodruff.tvexplorer.update

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.net.ConnectivityManager
import android.os.Looper
import android.os.UserManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.woodruff.tvexplorer.notify.AppVisibility
import com.woodruff.tvexplorer.update.OnScreen.Film
import com.woodruff.tvexplorer.update.Updater.How
import com.woodruff.tvexplorer.update.Updater.Next
import com.woodruff.tvexplorer.update.Updater.Release
import com.woodruff.tvexplorer.update.Updater.ReleaseFile
import com.woodruff.tvexplorer.update.Updater.State
import com.woodruff.tvexplorer.update.Updater.Step
import com.woodruff.tvexplorer.util.EarlyCrashes
import com.woodruff.tvexplorer.util.Presence
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.time.Duration

/**
 * Self-repair in the updater ([Updater.atStart], [Updater.lookForFix]): after this version closed
 * while starting, every start looks for a fix at once (no hourly wait, no day behind the test TV),
 * every few minutes after that, and a fix goes in as soon as it's there, even right after a key press,
 * though never over a film that's playing. Releases and downloads are stand-ins: no network.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class UrgentUpdateTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val prefs get() = app.getSharedPreferences("updates", Context.MODE_PRIVATE)
    private val hour = 3_600_000L
    private val next = Updater.current + 1
    @Volatile private var checks = 0
    @Volatile private var fetched = 0

    @Before fun freshTv() {
        clean()
        EarlyCrashes.lastExit = { _, _, _ -> null }
        Presence.resetForTest()
        // A build out for an hour only: the other TVs would wait a day for it.
        Updater.listReleases = {
            checks++
            listOf(Release(next, System.currentTimeMillis() - hour, files = listOf(ReleaseFile("WoodruffFamily.apk", 4, "https://example.invalid/apk"))))
        }
        Updater.fetchFile = { _, dest, _, _ -> fetched++; throw IOException("no network in tests") }
        // As if it was just checked: without self-repair, nothing would check for an hour.
        prefs.edit().putLong("lastCheck", System.currentTimeMillis()).apply()
    }

    @After fun clean() {
        // Whatever is still on its way (an installer's answer) arrives within this test.
        shadowOf(Looper.getMainLooper()).idle()
        EarlyCrashes.resetForTest()
        File(app.filesDir, "early_crashes.txt").delete()
        File(app.filesDir, "early_crash_report.txt").delete()
        Updater.resetForTest()
        Presence.resetForTest()
        AppVisibility.foreground = false
        prefs.edit().clear().apply()
    }

    /** This version closed as its screen opened; Woodruff has started again since. */
    private fun closedWhileStarting() {
        EarlyCrashes.opened(app)
        EarlyCrashes.crashed(app, null)
        EarlyCrashes.resetForTest()
        EarlyCrashes.lastExit = { _, _, _ -> null }
        assertEquals(1, EarlyCrashes.count(app))
    }

    private fun waitUntil(what: String, check: () -> Boolean) {
        val until = System.currentTimeMillis() + 10_000
        while (!check()) {
            if (System.currentTimeMillis() > until) throw AssertionError("never: $what (${Updater.state.value}, $checks checks, $fetched fetched)")
            // The updater asks the screens what's up on the main thread.
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(20)
        }
    }

    private fun settled() { Thread.sleep(300); shadowOf(Looper.getMainLooper()).idle() }

    @Test fun withoutAnEarlyCloseAStartChecksNothing() {
        Updater.atStart(app)
        settled()
        assertEquals(0, checks)
    }

    @Test fun afterAnEarlyCloseEveryStartLooksAtOnceAndTakesAnyNewerBuild() {
        closedWhileStarting()
        Updater.atStart(app)
        // The build out for an hour is fetched straight away (here the download fails: no network).
        waitUntil("the fix is fetched") { fetched == 1 && Updater.state.value.step == Step.ERROR }
        assertEquals(1, checks)
        assertEquals(next, Updater.state.value.latest)
    }

    @Test fun itLooksAgainEveryFewMinutesNotOnEveryPass() {
        closedWhileStarting()
        Updater.listReleases = { checks++; listOf(Release(Updater.current, System.currentTimeMillis() - 48 * hour, files = listOf(ReleaseFile("WoodruffFamily.apk", 4, "x")))) }
        Updater.atStart(app)
        waitUntil("it looked") { Updater.state.value.step == Step.UP_TO_DATE }
        // The phone connection's round and the screen opening, a moment later: no second request.
        Updater.maybeAuto(app)
        Updater.maybeAuto(app, opening = true)
        settled()
        assertEquals(1, checks)
        // A few minutes on, it looks again.
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(Updater.URGENT_EVERY_MS))
        Updater.maybeAuto(app)
        waitUntil("it looked again") { checks == 2 }
        // "Check for a fix" always looks.
        Updater.lookForFix(app, manual = true)
        waitUntil("it looked when asked") { checks == 3 }
    }

    @Test fun withAutomaticUpdatesOffOnlyTheRecoveryScreenLooks() {
        closedWhileStarting()
        Updater.setAuto(app, false)
        Updater.atStart(app)
        Updater.maybeAuto(app)
        settled()
        assertEquals(0, checks)
        Updater.lookForFix(app)
        waitUntil("the recovery screen looked") { checks == 1 }
    }

    @Test fun withNoNetworkTheNextLookGoesAsSoonAsThereIsOne() {
        closedWhileStarting()
        shadowOf(app.getSystemService(ConnectivityManager::class.java)).setActiveNetworkInfo(null)
        Updater.listReleases = { checks++; throw java.net.UnknownHostException("api.github.com") }
        Updater.atStart(app)
        waitUntil("it tried") { Updater.state.value.step == Step.ERROR }
        assertTrue(Updater.state.value.offline)
        // The phone connection's round once the TV is online: it goes at once, not minutes later.
        Updater.maybeAuto(app)
        waitUntil("it tried again") { checks == 2 }
    }

    @Test fun aFixThatsReadyGoesInAtOnceEvenRightAfterAKeyPress() {
        closedWhileStarting()
        readyToInstall()
        // Someone is using Woodruff this very moment: an ordinary update would wait 2 minutes.
        Presence.pressed()
        Updater.atStart(app)
        waitUntil("the fix installs") { Updater.state.value.step == Step.INSTALLING }
        // The stand-in installer answers at once: heard here, not in the next test.
        waitUntil("the installer answers") { Updater.state.value.step != Step.INSTALLING }
    }

    @Test fun neverOverAFilmThatsPlayingNorAJobThatMustntBeCutShort() {
        assertFalse(Updater.mayInstallUrgently(Film.PLAYING, busy = false))
        assertFalse(Updater.mayInstallUrgently(Film.NONE, busy = true))
        assertTrue(Updater.mayInstallUrgently(Film.PAUSED, busy = false))
        assertTrue(Updater.mayInstallUrgently(Film.NONE, busy = false))
        // In use, a copy running: it waits.
        closedWhileStarting()
        readyToInstall()
        val copy = Presence.begin()
        try {
            Updater.atStart(app)
            waitUntil("it's ready") { Updater.state.value.step == Step.READY }
            settled()
            assertEquals(Step.READY, Updater.state.value.step)
        } finally {
            Presence.end(copy)
        }
    }

    @Test fun aFixWaitsForTheDoorbellsCameraToClose() {
        assertFalse(Updater.mayInstallUrgently(Film.NONE, busy = false, live = true))
        assertFalse(Updater.mayInstallUrgently(Film.PAUSED, busy = false, live = true))
        closedWhileStarting()
        readyToInstall()
        // The doorbell rang and its camera is up: the fix doesn't restart Woodruff under it (it never even
        // starts installing: every step the updater takes is seen here as it takes it).
        val steps = java.util.concurrent.CopyOnWriteArrayList<Step>()
        val watch = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined).launch { Updater.state.collect { steps += it.step } }
        val doorbell = org.robolectric.Robolectric.buildActivity(FakeDoorbell::class.java).setup()
        try {
            Updater.atStart(app)
            waitUntil("it's ready") { Updater.state.value.step == Step.READY }
            // Long enough for the updater to ask the screens what's up (on the main thread) and act on it.
            repeat(50) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(20) }
            assertEquals(Step.READY, Updater.state.value.step)
            assertFalse("installing under the doorbell's camera: $steps", Step.INSTALLING in steps)
        } finally {
            watch.cancel()
            doorbell.pause().stop().destroy()
        }
        // Closed: the next look puts it in.
        Updater.maybeAuto(app)
        waitUntil("the fix installs") { Updater.state.value.step == Step.INSTALLING }
        waitUntil("the installer answers") { Updater.state.value.step != Step.INSTALLING }
    }

    @Test fun aNoToAndroidsPromptIsRespected() {
        closedWhileStarting()
        readyToInstall()
        prefs.edit().putInt("offerLaterFor", next).putLong("offerLaterUntil", System.currentTimeMillis() + hour).apply()
        Updater.atStart(app)
        waitUntil("it's ready") { Updater.state.value.step == Step.READY }
        settled()
        assertEquals(Step.READY, Updater.state.value.step)
    }

    /** The fix downloads and checks out as this app's next version (its install isn't really carried out). */
    private fun readyToInstall() {
        Updater.fetchFile = { _, dest, _, _ ->
            fetched++
            dest.writeBytes(byteArrayOf(1, 2, 3, 4))
            shadowOf(app.packageManager).setPackageArchiveInfo(dest.path, PackageInfo().apply {
                packageName = app.packageName
                @Suppress("DEPRECATION")
                versionCode = next
                longVersionCode = next.toLong()
            })
        }
    }

    @Test fun whereAndroidAsksAPressItsPromptOpensWhileWoodruffIsOnScreen() {
        AppVisibility.foreground = true
        val confirm = Intent("android.content.pm.action.CONFIRM_INSTALL")
        Updater.onResult(app, PackageInstaller.STATUS_PENDING_USER_ACTION, null, confirm, how = How.URGENT, build = next, answered = 7)
        assertEquals(confirm.action, shadowOf(app).nextStartedActivity?.action)
        assertTrue(prefs.getLong("promptUntil", 0) > System.currentTimeMillis())
    }

    @Test fun androidsPromptOpensOverTheRecoveryScreenToo() {
        // The recovery screen up: Woodruff doesn't count as in front for notices, but the prompt for
        // Install the fix (Android 11 and older, or a TV that asks) still opens over it.
        AppVisibility.foreground = false
        RecoveryView.inFront = true
        try {
            val confirm = Intent("android.content.pm.action.CONFIRM_INSTALL")
            Updater.onResult(app, PackageInstaller.STATUS_PENDING_USER_ACTION, null, confirm, how = How.CHOSEN, build = next, answered = 7)
            assertEquals(confirm.action, shadowOf(app).nextStartedActivity?.action)
        } finally {
            RecoveryView.inFront = false
        }
    }

    @Test fun andIsOfferedNextTimeWhenWoodruffIsntOnScreen() {
        AppVisibility.foreground = false
        Updater.onResult(app, PackageInstaller.STATUS_PENDING_USER_ACTION, null, Intent("confirm"), how = How.URGENT, build = next, answered = 7)
        assertNull(shadowOf(app).nextStartedActivity)
        assertTrue(Updater.needsPress(app, next))
        assertTrue(Updater.offerDue(app, Updater.state.value, System.currentTimeMillis()))
    }

    @Test fun theInstallersAnswersForAFix() {
        fun step(status: Int, canPrompt: Boolean = false) = Updater.nextStep(status, How.URGENT, 7, 7, wasLetGo = false, canPrompt = canPrompt, mayRetryWithoutProfile = false)
        assertEquals(Next.PROMPT, step(PackageInstaller.STATUS_PENDING_USER_ACTION, canPrompt = true))
        assertEquals(Next.OFFER_LATER, step(PackageInstaller.STATUS_PENDING_USER_ACTION))
        assertEquals(Next.DECLINED, step(PackageInstaller.STATUS_FAILURE_ABORTED))
        assertEquals(Next.INSTALLED, step(PackageInstaller.STATUS_SUCCESS))
        assertEquals(Next.FAILED, step(PackageInstaller.STATUS_FAILURE_STORAGE))
        // Sent and read back as itself.
        assertEquals(How.URGENT, How.of(How.URGENT.code))
    }

    @Test fun aReleasesSpeedProfileComesThroughTheSameStandInAsTheApk() {
        // A release with a speed profile for this TV (Android 14): both files are fetched through the
        // stand-in, so nothing in a test ever reaches the network for either.
        val fetchedUrls = java.util.concurrent.CopyOnWriteArrayList<String>()
        Updater.listReleases = {
            listOf(Release(next, System.currentTimeMillis() - hour, files = listOf(
                ReleaseFile("WoodruffFamily.apk", 4, "https://example.invalid/apk"),
                ReleaseFile("WoodruffFamily-api28.dm", 3, "https://example.invalid/dm"),
            )))
        }
        Updater.fetchFile = { url, dest, _, _ ->
            fetchedUrls += url
            if (url.endsWith("apk")) {
                dest.writeBytes(byteArrayOf(1, 2, 3, 4))
                shadowOf(app.packageManager).setPackageArchiveInfo(dest.path, PackageInfo().apply {
                    packageName = app.packageName
                    @Suppress("DEPRECATION")
                    versionCode = next
                    longVersionCode = next.toLong()
                })
            } else {
                dest.writeBytes(byteArrayOf(1, 2, 3))
            }
        }
        Updater.checkNow(app)
        waitUntil("it's ready") { Updater.state.value.step == Step.READY }
        assertEquals(listOf("https://example.invalid/apk", "https://example.invalid/dm"), fetchedUrls.toList())
    }

    @Test fun lookingIsSpacedOutButGoesSoonerAfterOneThatDidntGoThrough() {
        val t = 10_000_000L
        assertTrue(Updater.urgentCheckDue(0, t, failed = false))
        assertFalse(Updater.urgentCheckDue(t, t + Updater.URGENT_EVERY_MS - 1, failed = false))
        assertTrue(Updater.urgentCheckDue(t, t + Updater.URGENT_EVERY_MS, failed = false))
        assertFalse(Updater.urgentCheckDue(t, t + Updater.URGENT_RETRY_MS - 1, failed = true))
        assertTrue(Updater.urgentCheckDue(t, t + Updater.URGENT_RETRY_MS, failed = true))
    }

    @Test fun aProfileThatForbidsAppsFromOutsideThePlayStoreIsNoticed() {
        assertFalse(Updater.installsBlockedByProfile(app))
        shadowOf(app.getSystemService(UserManager::class.java)).setUserRestriction(android.os.Process.myUserHandle(), UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES, true)
        assertTrue(Updater.installsBlockedByProfile(app))
        assertNotNull(State(Step.READY, next))
    }
}
