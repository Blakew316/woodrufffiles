package com.woodruff.tvexplorer.update

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.woodruff.tvexplorer.notify.AppVisibility
import com.woodruff.tvexplorer.ui.screens.updateStatus
import com.woodruff.tvexplorer.update.Updater.How
import com.woodruff.tvexplorer.update.Updater.State
import com.woodruff.tvexplorer.update.Updater.Step
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.StringReader

/**
 * What happens with the package installer's answers (the TV's own prompt, letting go of an install,
 * offering it later, opening again afterwards), and reading GitHub's list of releases. Android 14.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class UpdaterAnswersTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val prefs get() = context.getSharedPreferences("updates", Context.MODE_PRIVATE)
    private val hour = 3_600_000L

    @Before fun freshRun() {
        Updater.resetForTest()
        AppVisibility.foreground = false
    }

    private fun waitUntil(what: String, check: () -> Boolean) {
        val until = System.currentTimeMillis() + 5_000
        while (!check()) {
            if (System.currentTimeMillis() > until) throw AssertionError("never: $what")
            Thread.sleep(20)
        }
    }

    @Test fun anInstallNobodyChoseIsLetGoOfWhenAndroidWantsAPressAndOfferedLater() {
        val installer = context.packageManager.packageInstaller
        val id = installer.createSession(PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL))
        // Woodruff was on screen (untouched) as it started, so it would have opened again afterwards.
        prefs.edit().putInt("reopenFor", 170).putLong("reopenAt", System.currentTimeMillis()).apply()
        assertFalse(Updater.needsPress(context, 170))

        Updater.onResult(context, PackageInstaller.STATUS_PENDING_USER_ACTION, null, Intent("confirm"), how = How.BY_ITSELF, build = 170, answered = id)

        // No prompt out of nowhere, even though Android sent one.
        assertNull(shadowOf(context as Application).nextStartedActivity)
        assertEquals(State(Step.READY, 170), Updater.state.value)
        // This build waits for a press now; the next one tries by itself again.
        assertTrue(Updater.needsPress(context, 170))
        assertFalse(Updater.needsPress(context, 171))
        assertTrue(Updater.asksEachTime(context))
        // The waiting install, which holds a copy of the update, is let go of...
        waitUntil("the install is let go of") { installer.allSessions.none { it.sessionId == id } }
        // ...and the "aborted" that answers that changes nothing.
        Updater.onResult(context, PackageInstaller.STATUS_FAILURE_ABORTED, "Session was abandoned", null, how = How.BY_ITSELF, build = 170, answered = id)
        assertEquals(State(Step.READY, 170), Updater.state.value)
        // The flag it left won't open Woodruff after some later update.
        assertNull(Updater.takeReopen(context))
        // Offered the next time Woodruff opens.
        assertTrue(Updater.offerDue(context, Updater.state.value, System.currentTimeMillis()))
    }

    @Test fun someoneAtWoodruffGetsAndroidsPromptAndANoIsNotAnError() {
        Updater.onResult(context, PackageInstaller.STATUS_PENDING_USER_ACTION, null, null, how = How.BY_ITSELF, build = 170, answered = 40)
        AppVisibility.foreground = true
        val now = System.currentTimeMillis()

        val confirm = Intent("android.content.pm.action.CONFIRM_INSTALL")
        Updater.onResult(context, PackageInstaller.STATUS_PENDING_USER_ACTION, null, confirm, how = How.OFFERED, build = 170, answered = 41)
        assertEquals("android.content.pm.action.CONFIRM_INSTALL", shadowOf(context as Application).nextStartedActivity?.action)
        // While Android's prompt is up, it isn't offered again (nor installed in the background).
        assertFalse(Updater.offerDue(context, Updater.state.value, now))

        // "Cancel" on Android's prompt: no "last try" error, and not offered again for a while.
        Updater.onResult(context, PackageInstaller.STATUS_FAILURE_ABORTED, "User rejected permissions", null, how = How.OFFERED, build = 170, answered = 41)
        assertEquals(State(Step.READY, 170), Updater.state.value)
        assertEquals("2.0.170 ready · Install", updateStatus(Updater.state.value))
        assertFalse(Updater.offerDue(context, Updater.state.value, now))
        assertTrue(Updater.offerDue(context, Updater.state.value, now + 13 * hour))
    }

    @Test fun laterHoldsTheOfferBackForHalfADay() {
        Updater.onResult(context, PackageInstaller.STATUS_PENDING_USER_ACTION, null, null, how = How.BY_ITSELF, build = 170, answered = 50)
        val now = System.currentTimeMillis()
        assertTrue(Updater.offerDue(context, Updater.state.value, now))
        Updater.later(context, 170)
        assertFalse(Updater.offerDue(context, Updater.state.value, now + 11 * hour))
        assertTrue(Updater.offerDue(context, Updater.state.value, now + 13 * hour))
        // Later for one build says nothing about the next.
        prefs.edit().putInt("askedFor", 171).apply()
        assertTrue(Updater.offerDue(context, State(Step.READY, 171), now + hour))
        // Nothing to offer unless it's downloaded.
        assertFalse(Updater.offerDue(context, State(Step.DOWNLOADING, 171), now + hour))
    }

    @Test fun aPressThatWentThroughOpensAndroidsPromptStraightAwayNextTime() {
        assertFalse(Updater.promptWorked(context))
        Updater.onResult(context, PackageInstaller.STATUS_SUCCESS, null, null, how = How.OFFERED, build = 170, answered = 60)
        assertTrue(Updater.promptWorked(context))
        assertEquals(Step.UP_TO_DATE, Updater.state.value.step)
    }

    @Test fun anUpdateThatWentInByItselfMeansThisTvDoesntAsk() {
        prefs.edit().putBoolean("asksEachTime", true).apply()
        Updater.onResult(context, PackageInstaller.STATUS_SUCCESS, null, null, how = How.BY_ITSELF, build = 170, answered = 61)
        assertFalse(Updater.asksEachTime(context))
        assertFalse(Updater.promptWorked(context))
        // An install started by a version from before "how" was sent still counts as installed.
        Updater.onResult(context, PackageInstaller.STATUS_SUCCESS, null, null, how = null, build = 0, answered = -1)
        assertEquals(Step.UP_TO_DATE, Updater.state.value.step)
        assertFalse(Updater.promptWorked(context))
    }

    @Test fun aRefusalIsSaidInPlainWords() {
        Updater.onResult(context, PackageInstaller.STATUS_FAILURE_STORAGE, "INSTALL_FAILED_INSUFFICIENT_STORAGE", null, how = How.CHOSEN, build = 170, answered = 70)
        assertEquals(State(Step.READY, 170, message = "the TV is out of space"), Updater.state.value)
        assertEquals("2.0.170 ready · Install (last try: the TV is out of space)", updateStatus(Updater.state.value))
    }

    @Test fun woodruffOpensAgainOnlyAfterTheUpdateItWasOnScreenFor() {
        val now = System.currentTimeMillis()
        val home = Restore.App(com.woodruff.tvexplorer.ui.Screen.HOME)
        // As 2.0.165 and older left it (no build, no time): honoured once, at Home.
        prefs.edit().putBoolean("reopen", true).apply()
        assertEquals(home, Updater.takeReopen(context))
        assertNull(Updater.takeReopen(context))
        // Set a minute ago for the build that's running now (the one that just went in), by a version
        // from before what was on screen was saved: Home.
        prefs.edit().putInt("reopenFor", Updater.current).putLong("reopenAt", now - 60_000).apply()
        assertEquals(home, Updater.takeReopen(context))
        assertNull(Updater.takeReopen(context))
        // Set for some other build that never went in: not.
        prefs.edit().putInt("reopenFor", Updater.current + 1).putLong("reopenAt", now).apply()
        assertNull(Updater.takeReopen(context))
    }

    @Test fun woodruffOpensAgainToWhatItShowed() {
        val now = System.currentTimeMillis()
        val film = Restore.Film(listOf("drive:a", "drive:b"), listOf("A", "B"), 1, 2_400_000, under = Restore.App(com.woodruff.tvexplorer.ui.Screen.DRIVE, "tv"))
        prefs.edit().putInt("reopenFor", Updater.current).putLong("reopenAt", now - 60_000).putString("reopenRoute", Restore.encode(film)).apply()
        assertEquals(film, Updater.takeReopen(context))
        // Once.
        assertNull(Updater.takeReopen(context))
        assertFalse(prefs.contains("reopenRoute"))
        // A route left by an install that never went in opens nothing, and goes.
        prefs.edit().putInt("reopenFor", Updater.current + 1).putLong("reopenAt", now).putString("reopenRoute", Restore.encode(film)).apply()
        assertNull(Updater.takeReopen(context))
        assertFalse(prefs.contains("reopenRoute"))
        // One this version can't read: Home rather than nothing (Woodruff was on screen).
        prefs.edit().putInt("reopenFor", Updater.current).putLong("reopenAt", now).putString("reopenRoute", "{\"v\":7}").apply()
        assertEquals(Restore.App(com.woodruff.tvexplorer.ui.Screen.HOME), Updater.takeReopen(context))
    }

    @Test fun aRefusedInstallForgetsWhatToOpen() {
        prefs.edit().putInt("reopenFor", 170).putLong("reopenAt", System.currentTimeMillis()).putString("reopenRoute", Restore.encode(Restore.Slideshow(false))).apply()
        Updater.onResult(context, PackageInstaller.STATUS_FAILURE_STORAGE, null, null, how = How.BY_ITSELF, build = 170, answered = 80)
        assertFalse(prefs.contains("reopenRoute"))
        assertFalse(prefs.contains("reopenFor"))
    }

    @Test fun readsGitHubsListOfReleases() {
        val json = """
            [
              {"url": "https://api.github.com/repos/o/r/releases/3", "tag_name": "v2.0.165", "name": "Woodruff Family 2.0 (build 165)",
               "draft": false, "prerelease": false, "created_at": "2026-11-02T02:46:35Z", "published_at": "2026-11-02T02:53:10Z",
               "author": {"login": "github-actions[bot]", "id": 41898282, "site_admin": false},
               "assets": [
                 {"name": "WoodruffFamily.apk", "state": "uploaded", "size": 23750277, "download_count": 4,
                  "uploader": {"login": "github-actions[bot]"}, "browser_download_url": "https://github.com/o/r/releases/download/v2.0.165/WoodruffFamily.apk"},
                 {"name": "WoodruffFamily-api31.dm", "state": "uploaded", "size": 8089, "label": null,
                  "browser_download_url": "https://github.com/o/r/releases/download/v2.0.165/WoodruffFamily-api31.dm"}
               ],
               "body": "Install on Android TV: …", "reactions": {"+1": 0}},
              {"tag_name": "v2.0.166", "name": "Woodruff Family 2.0 (build 166)", "draft": false, "prerelease": false,
               "published_at": "2026-11-02T03:10:00Z",
               "assets": [{"name": "WoodruffFamily.apk", "state": "open", "size": 0, "browser_download_url": "https://github.com/o/r/x.apk"}]},
              {"tag_name": "v2.0.167", "name": "Woodruff Family 2.0 (build 167) · rollback", "draft": false, "prerelease": false,
               "published_at": "2026-11-02T04:00:00Z", "assets": []},
              {"tag_name": "v2.0.168", "name": null, "draft": false, "prerelease": true, "published_at": "2026-11-02T05:00:00Z", "assets": null},
              {"tag_name": "v2.0.169", "name": "draft", "draft": true, "prerelease": false, "published_at": null, "assets": []},
              {"tag_name": "nightly", "name": "Not a version", "draft": false, "prerelease": false, "published_at": "2026-11-02T06:00:00Z", "assets": []}
            ]
        """.trimIndent()
        val releases = Updater.parseReleases(StringReader(json))
        assertEquals(listOf(165, 166, 167, 168), releases.map { it.build })
        val r165 = releases[0]
        assertEquals(Updater.published("2026-11-02T02:53:10Z"), r165.published)
        assertEquals(23_750_277L, r165.apk?.size)
        assertEquals("https://github.com/o/r/releases/download/v2.0.165/WoodruffFamily.apk", r165.apk?.url)
        assertEquals(listOf("WoodruffFamily.apk", "WoodruffFamily-api31.dm"), r165.files.map { it.name })
        assertFalse(r165.rollback)
        // Still uploading: listed, but without its APK yet.
        assertNull(releases[1].apk)
        assertTrue(releases[2].rollback)
        assertTrue(releases[3].prerelease)
        assertEquals(emptyList<Updater.Release>(), Updater.parseReleases(StringReader("[]")))
    }
}
