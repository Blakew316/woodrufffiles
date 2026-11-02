package com.woodruff.tvexplorer.update

import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.woodruff.tvexplorer.server.CompanionRoutes
import com.woodruff.tvexplorer.server.answerFor
import com.woodruff.tvexplorer.server.phoneRequest
import com.woodruff.tvexplorer.update.Updater.Release
import com.woodruff.tvexplorer.update.Updater.ReleaseFile
import com.woodruff.tvexplorer.update.Updater.Step
import fi.iki.elonen.NanoHTTPD.Method
import fi.iki.elonen.NanoHTTPD.Response.Status
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.IOException

/**
 * Check for Updates asked for away from the TV (the phone's TV page, or a hub TV's health page through
 * /api/update/check) looks at once but follows the test TV's day, as this TV's own checks do: a build out for an
 * hour is left to the test TV, and arrives here a day after it came out. Settings → Check for updates on the TV
 * itself still takes it at once. Releases and downloads are stand-ins: no network.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class RemoteUpdateCheckTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val prefs get() = app.getSharedPreferences("updates", Context.MODE_PRIVATE)
    private val routes = CompanionRoutes(app)
    private val hour = 3_600_000L
    private val next = Updater.current + 1
    @Volatile private var checks = 0
    @Volatile private var fetched = 0

    @Before fun aBuildOutForAnHour() {
        clean()
        released(ago = hour)
        Updater.fetchFile = { _, _, _, _ -> fetched++; throw IOException("no network in tests") }
    }

    @After fun clean() {
        Updater.resetForTest()
        prefs.edit().clear().commit()
    }

    private fun released(ago: Long) {
        Updater.listReleases = {
            checks++
            listOf(Release(next, System.currentTimeMillis() - ago, files = listOf(ReleaseFile("WoodruffFamily.apk", 4, "https://example.invalid/apk"))))
        }
    }

    private fun fromThePhone() =
        assertEquals(Status.OK, routes.answerFor(phoneRequest("/api/tvs/update/check", Method.POST, body = JSONObject().put("id", "this"))).status)

    private fun fromAHub() =
        assertEquals(Status.OK, routes.answerFor(phoneRequest("/api/update/check", Method.POST, body = JSONObject())).status)

    private fun waitUntil(what: String, check: () -> Boolean) {
        val until = System.currentTimeMillis() + 10_000
        while (!check()) {
            if (System.currentTimeMillis() > until) throw AssertionError("never: $what (${Updater.state.value}, $checks checks, $fetched fetched)")
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(20)
        }
    }

    @Test fun thePhonesCheckLeavesABuildTheTestTvHasntHadADayWith() {
        fromThePhone()
        waitUntil("it looked") { checks == 1 && Updater.state.value.step == Step.UP_TO_DATE }
        assertEquals(0, fetched)
        assertEquals(next, Updater.state.value.latest)
        assertTrue(Updater.state.value.message.orEmpty(), Updater.state.value.message.orEmpty().endsWith("(after the test TV)"))
    }

    @Test fun aHubTvsCheckDoesToo() {
        fromAHub()
        waitUntil("it looked") { checks == 1 && Updater.state.value.step == Step.UP_TO_DATE }
        assertEquals(0, fetched)
    }

    @Test fun settingsOnTheTvItselfStillTakesItAtOnce() {
        Updater.checkNow(app)
        waitUntil("it was fetched") { fetched == 1 && Updater.state.value.step == Step.ERROR }
        assertEquals(next, Updater.state.value.latest)
    }

    @Test fun fromThePhoneTheTestTvTakesItAtOnceAndEveryTvABuildThatStoodADay() {
        Updater.setTestTv(app, true)
        fromThePhone()
        waitUntil("the test TV fetched it") { fetched == 1 && Updater.state.value.step == Step.ERROR }
        Updater.setTestTv(app, false)
        released(ago = 25 * hour)
        fromThePhone()
        waitUntil("it was fetched") { fetched == 2 && checks == 2 && Updater.state.value.step == Step.ERROR }
    }
}
