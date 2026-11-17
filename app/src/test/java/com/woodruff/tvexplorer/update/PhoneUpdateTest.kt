package com.woodruff.tvexplorer.update

import android.app.Application
import android.content.Context
import android.content.pm.PackageInfo
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.woodruff.tvexplorer.notify.AppVisibility
import com.woodruff.tvexplorer.server.CompanionRoutes
import com.woodruff.tvexplorer.server.answerFor
import com.woodruff.tvexplorer.server.phoneRequest
import com.woodruff.tvexplorer.update.Updater.Release
import com.woodruff.tvexplorer.update.Updater.ReleaseFile
import com.woodruff.tvexplorer.update.Updater.Step
import com.woodruff.tvexplorer.util.EarlyCrashes
import com.woodruff.tvexplorer.util.Presence
import fi.iki.elonen.NanoHTTPD.Method
import fi.iki.elonen.NanoHTTPD.Response.Status
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Update pressed in the Woodruff app on an iPhone or Mac (POST /api/update/check {"now": true}): the newest build goes
 * in at once, also on a TV whose automatic updates are off (it used to be downloaded and left waiting there), and on
 * that TV, a film that held it back no longer does once it's over. Releases and downloads are stand-ins: no network.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class PhoneUpdateTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val prefs get() = app.getSharedPreferences("updates", Context.MODE_PRIVATE)
    private val routes = CompanionRoutes(app)
    private val hour = 3_600_000L
    private val next = Updater.current + 1
    /** Every step the updater takes, as it takes it (the stand-in installer answers at once, so INSTALLING is brief). */
    private val steps = CopyOnWriteArrayList<Step>()
    private var watch: Job? = null

    @Before fun aBuildOut() {
        clean()
        EarlyCrashes.lastExit = { _, _, _ -> null }
        Updater.listReleases = {
            listOf(Release(next, System.currentTimeMillis() - hour, files = listOf(ReleaseFile("WoodruffFamily.apk", 4, "https://example.invalid/apk"))))
        }
        // The build downloads and checks out as this app's next version (its install isn't really carried out).
        Updater.fetchFile = { _, dest, _, _ ->
            dest.writeBytes(byteArrayOf(1, 2, 3, 4))
            shadowOf(app.packageManager).setPackageArchiveInfo(dest.path, PackageInfo().apply {
                packageName = app.packageName
                @Suppress("DEPRECATION")
                versionCode = next
                longVersionCode = next.toLong()
            })
        }
        // Automatic updates off on this TV.
        Updater.setAuto(app, false)
        watch = CoroutineScope(Dispatchers.Unconfined).launch { Updater.state.collect { steps += it.step } }
    }

    @After fun clean() {
        // Whatever is still on its way (an installer's answer) arrives within this test.
        shadowOf(Looper.getMainLooper()).idle()
        watch?.cancel()
        steps.clear()
        EarlyCrashes.resetForTest()
        File(app.filesDir, "early_crashes.txt").delete()
        File(app.filesDir, "early_crash_report.txt").delete()
        Updater.resetForTest()
        Presence.resetForTest()
        AppVisibility.foreground = false
        prefs.edit().clear().commit()
    }

    private fun waitUntil(what: String, check: () -> Boolean) {
        val until = System.currentTimeMillis() + 10_000
        while (!check()) {
            if (System.currentTimeMillis() > until) throw AssertionError("never: $what (${Updater.state.value})")
            // The updater asks the screens what's up on the main thread.
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(20)
        }
    }

    private fun settled() { Thread.sleep(300); shadowOf(Looper.getMainLooper()).idle() }

    private fun updatePressed() =
        assertEquals(Status.OK, routes.answerFor(phoneRequest("/api/update/check", Method.POST, body = JSONObject().put("now", true))).status)

    @Test fun updateFromThePhoneGoesInWithAutomaticUpdatesOff() {
        updatePressed()
        waitUntil("it installs") { Step.INSTALLING in steps }
        // The stand-in installer answers at once: heard here, not in the next test.
        waitUntil("the installer answers") { Updater.state.value.step != Step.INSTALLING }
    }

    @Test fun andGoesInOnceWhatHeldItBackIsOver() {
        // A copy is running when Update is pressed: it waits, ready.
        val copy = Presence.begin()
        try {
            updatePressed()
            waitUntil("it's ready") { Updater.state.value.step == Step.READY }
            settled()
            assertEquals(Step.READY, Updater.state.value.step)
            assertFalse(Step.INSTALLING in steps)
        } finally {
            Presence.end(copy)
        }
        // The phone connection's next round: automatic updates are still off, but this one was asked for.
        Updater.maybeAuto(app)
        waitUntil("it installs") { Step.INSTALLING in steps }
        waitUntil("the installer answers") { Updater.state.value.step != Step.INSTALLING }
    }

    @Test fun withAutomaticUpdatesOffNothingElseGoesInByItself() {
        // Downloaded from Settings → Updates on the TV (not the phone's Update): the round leaves it for a press.
        Updater.checkNow(app)
        waitUntil("it's ready") { Updater.state.value.step == Step.READY }
        Updater.maybeAuto(app)
        settled()
        assertEquals(Step.READY, Updater.state.value.step)
        assertFalse(Step.INSTALLING in steps)
    }
}
