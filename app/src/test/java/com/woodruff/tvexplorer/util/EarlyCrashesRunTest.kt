package com.woodruff.tvexplorer.util

import android.app.ActivityManager
import android.app.Application
import android.app.ApplicationExitInfo
import android.os.Looper
import android.os.Process
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.woodruff.tvexplorer.BuildConfig
import com.woodruff.tvexplorer.util.EarlyCrashes.Exit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowActivityManager
import java.io.File
import java.time.Duration

/**
 * Closes while starting as Woodruff sees them run after run ([EarlyCrashes]): counted from the
 * screen opening, written down before the process ends, two in a row bring the recovery screen, a
 * screen that stays up sets the count back, and (Android 11+) native crashes and freezes that no
 * handler sees are counted at the next start. A "new run" here keeps what's on disk and forgets the rest.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class EarlyCrashesRunTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val stateFile get() = File(app.filesDir, "early_crashes.txt")
    private val reportFile get() = File(app.filesDir, "early_crash_report.txt")
    private val report = "Version ${BuildConfig.VERSION_NAME} · Nov 2, 9:41 PM · thread main\njava.lang.IllegalStateException: boom\n\tat com.woodruff.tvexplorer.MainActivity.onCreate(MainActivity.kt:70)"
    private var asked = mutableListOf<Pair<Int, Long>>()

    @Before fun freshTv() {
        // A close counted here makes Woodruff look for a fix: never over the network in a test.
        com.woodruff.tvexplorer.update.Updater.resetForTest()
        newRun()
        stateFile.delete()
        reportFile.delete()
    }

    @After fun forget() {
        EarlyCrashes.resetForTest()
        stateFile.delete()
        reportFile.delete()
    }

    /** Woodruff starting again: what was in memory is gone; Android says nothing unless told to. */
    private fun newRun(exit: Exit? = null) {
        EarlyCrashes.resetForTest()
        asked = mutableListOf()
        EarlyCrashes.lastExit = { _, pid, at -> asked += pid to at; exit }
    }

    /** The screen opens and Woodruff closes [afterMs] later. */
    private fun openAndCrash(afterMs: Long = 3_000) {
        EarlyCrashes.opened(app)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(afterMs))
        EarlyCrashes.crashed(app, report)
    }

    @Test fun aCloseSoonAfterTheScreenOpensCountsAndIsWrittenDownAtOnce() {
        openAndCrash()
        assertEquals(1, EarlyCrashes.count(app))
        // On disk before the process ends (no waiting for a background write).
        assertTrue(stateFile.readText().startsWith("1 ${BuildConfig.VERSION_CODE} 1 "))
        newRun()
        assertEquals(1, EarlyCrashes.count(app))
        assertFalse(EarlyCrashes.needsRecovery(app))
    }

    @Test fun twoInARowBringTheRecoveryScreen() {
        openAndCrash()
        newRun()
        openAndCrash(40_000)
        newRun()
        assertEquals(2, EarlyCrashes.count(app))
        assertTrue(EarlyCrashes.needsRecovery(app))
    }

    @Test fun twoThreadsFailingTogetherAreOneClose() {
        openAndCrash()
        EarlyCrashes.crashed(app, report)
        newRun()
        assertEquals(1, EarlyCrashes.count(app))
    }

    @Test fun aCloseAfterTheScreenHasBeenUpAWhileIsntOneWhileStarting() {
        openAndCrash(EarlyCrashes.EARLY_MS + 1_000)
        newRun()
        assertEquals(0, EarlyCrashes.count(app))
    }

    @Test fun aCloseWithNoScreenOpenedIsntOne() {
        // The phone connection started Woodruff at boot; nobody has opened it.
        EarlyCrashes.crashed(app, report)
        newRun()
        assertEquals(0, EarlyCrashes.count(app))
    }

    @Test fun aScreenThatStaysUpSetsTheCountBack() {
        openAndCrash()
        newRun()
        EarlyCrashes.opened(app)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(EarlyCrashes.EARLY_MS))
        assertEquals(0, EarlyCrashes.count(app))
        EarlyCrashes.flushForTest()
        newRun()
        assertEquals(0, EarlyCrashes.count(app))
        assertFalse(stateFile.exists())
        // And its saved report goes with it.
        assertFalse(reportFile.exists())
    }

    @Test fun theReportOfTheCloseIsKeptForTheRecoveryScreen() {
        openAndCrash()
        assertEquals(report, EarlyCrashes.report(app))
        // Another version's report says nothing about this one.
        reportFile.writeText(report.replace("Version ${BuildConfig.VERSION_NAME} ", "Version 2.0.1 "))
        assertNull(EarlyCrashes.report(app))
    }

    @Test fun tryOpeningAgainLeavesOne() {
        openAndCrash()
        newRun()
        openAndCrash()
        newRun()
        assertTrue(EarlyCrashes.needsRecovery(app))
        EarlyCrashes.tryAgain(app)
        assertEquals(1, EarlyCrashes.count(app))
        assertFalse(EarlyCrashes.needsRecovery(app))
        EarlyCrashes.flushForTest()
        newRun()
        assertEquals(1, EarlyCrashes.count(app))
        // One more close while starting: straight back to the recovery screen.
        openAndCrash()
        newRun()
        assertTrue(EarlyCrashes.needsRecovery(app))
    }

    @Test fun aNewVersionStartsAfresh() {
        openAndCrash()
        newRun()
        openAndCrash()
        newRun()
        EarlyCrashes.current = BuildConfig.VERSION_CODE + 1
        assertEquals(0, EarlyCrashes.count(app))
        assertFalse(EarlyCrashes.needsRecovery(app))
        EarlyCrashes.flushForTest()
        assertFalse(stateFile.exists())
        assertFalse(reportFile.exists())
    }

    @Test fun aNativeCrashOrAFreezeIsCountedAtTheNextStart() {
        for (exit in listOf(Exit.NATIVE_CRASH, Exit.FROZE)) {
            newRun()
            stateFile.delete()
            EarlyCrashes.opened(app)
            EarlyCrashes.flushForTest()
            val openedAt = openedAt()
            // The process ends with no handler seeing it; Android says how at the next start.
            newRun(exit)
            assertEquals(exit.name, 1, EarlyCrashes.count(app))
            assertEquals(Process.myPid() to openedAt, asked.single())
        }
    }

    @Test fun anyOtherEndIsntCountedNorAskedAboutAgain() {
        EarlyCrashes.opened(app)
        EarlyCrashes.flushForTest()
        // Android closed it for memory before the screen had been up 45 seconds.
        newRun(Exit.OTHER)
        assertEquals(0, EarlyCrashes.count(app))
        EarlyCrashes.flushForTest()
        newRun(Exit.NATIVE_CRASH)
        assertEquals(0, EarlyCrashes.count(app))
        assertTrue(asked.isEmpty())
    }

    @Test fun aCloseTheHandlerCountedIsntCountedAgainFromAndroidsReason() {
        openAndCrash()
        newRun(Exit.CRASH)
        assertEquals(1, EarlyCrashes.count(app))
        assertTrue(asked.isEmpty())
    }

    @Test fun androidIsAskedAboutThatProcessOnly() {
        // Android's own record, through the real question (Android 11+).
        EarlyCrashes.resetForTest()
        EarlyCrashes.opened(app)
        EarlyCrashes.flushForTest()
        val am = shadowOf(app.getSystemService(ActivityManager::class.java))
        // An older process that happened to have the same number: not the one.
        am.addApplicationExitInfo(exit(ApplicationExitInfo.REASON_CRASH_NATIVE, at = openedAt() - 3_600_000))
        EarlyCrashes.resetForTest()
        assertEquals(0, EarlyCrashes.count(app))

        stateFile.delete()
        EarlyCrashes.resetForTest()
        EarlyCrashes.opened(app)
        EarlyCrashes.flushForTest()
        am.addApplicationExitInfo(exit(ApplicationExitInfo.REASON_CRASH_NATIVE, at = openedAt() + 2_000))
        EarlyCrashes.resetForTest()
        assertEquals(1, EarlyCrashes.count(app))
    }

    /** When the screen last opened, as written down for Android's answer. */
    private fun openedAt(): Long = EarlyCrashes.parse(stateFile.readText()).openedAt

    private fun exit(reason: Int, at: Long): ApplicationExitInfo = ShadowActivityManager.ApplicationExitInfoBuilder.newBuilder()
        .setPid(Process.myPid()).setReason(reason).setTimestamp(at).setProcessName(app.packageName).build()

    @Test fun crashLogsHandlerCountsTheCloseAndStillEndsTheProcess() {
        val original = Thread.getDefaultUncaughtExceptionHandler()
        var ended: Throwable? = null
        try {
            // What Android's own handler would do: end the process.
            Thread.setDefaultUncaughtExceptionHandler { _, e -> ended = e }
            CrashLog.install(app)
            EarlyCrashes.opened(app)
            val boom = IllegalStateException("boom")
            Thread { throw boom }.apply { start(); join() }
            assertSame(boom, ended)
            assertEquals(1, EarlyCrashes.count(app))
            newRun()
            assertEquals(1, EarlyCrashes.count(app))
            val saved = EarlyCrashes.report(app)!!
            assertTrue(saved.lines()[1].contains("IllegalStateException: boom"))
            // The phone's report and the notice work as before.
            assertTrue(File(app.filesDir, "last_crash.txt").readText().contains("boom"))
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(original)
            File(app.filesDir, "last_crash.txt").delete()
        }
    }
}
