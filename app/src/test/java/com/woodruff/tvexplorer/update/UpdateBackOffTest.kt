package com.woodruff.tvexplorer.update

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.woodruff.tvexplorer.notify.AppVisibility
import com.woodruff.tvexplorer.update.Updater.FirstLook
import com.woodruff.tvexplorer.update.Updater.Release
import com.woodruff.tvexplorer.update.Updater.ReleaseFile
import com.woodruff.tvexplorer.update.Updater.State
import com.woodruff.tvexplorer.update.Updater.Step
import com.woodruff.tvexplorer.util.EarlyCrashes
import com.woodruff.tvexplorer.util.Presence
import fi.iki.elonen.NanoHTTPD
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
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Update checks and GitHub's limit (60 requests an hour for the whole home, shared by every TV): a check GitHub
 * turns down waits as long as it says, kept through a restart, whoever asks (Check for updates and Check for a fix
 * too), and never comes back sooner than after a check that went through; the background routine's retries are
 * spaced from the try that failed, not from the last good check; and after an early close each look asks github.com
 * which build is the newest first, so the API is read only when there's something newer. A server of the test's own,
 * on this machine, stands in for GitHub; downloads are stand-ins.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class UpdateBackOffTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val prefs get() = app.getSharedPreferences("updates", Context.MODE_PRIVATE)
    private val minute = 60_000L
    private val hour = 60 * minute
    private val next = Updater.current + 1

    /** What the stand-in API answers: a status and its headers, or (0) the list with [page]. */
    private data class Answer(val code: Int, val headers: Map<String, String> = emptyMap())

    @Volatile private var answer = Answer(0)
    private val asked = CopyOnWriteArrayList<String>()
    private val page = """[{"tag_name":"v2.0.$next","name":"2.0.$next","published_at":"2026-11-01T10:00:00Z","draft":false,"prerelease":false,""" +
        """"assets":[{"name":"WoodruffFamily.apk","size":4,"browser_download_url":"https://example.invalid/apk","state":"uploaded"}]}]"""

    private val github = object : NanoHTTPD("127.0.0.1", 0) {
        override fun serve(session: IHTTPSession): Response {
            asked += "${session.method} ${session.uri}"
            val a = answer
            if (a.code == 0) return newFixedLengthResponse(Response.Status.OK, "application/json", page)
            val status = object : Response.IStatus {
                override fun getDescription() = "${a.code} Refused"
                override fun getRequestStatus() = a.code
            }
            return newFixedLengthResponse(status, "application/json", """{"message":"API rate limit exceeded"}""")
                .apply { a.headers.forEach { (k, v) -> addHeader(k, v) } }
        }
    }
    private val api get() = "http://127.0.0.1:${github.listeningPort}/releases"
    private val apiAsked get() = asked.count { it.endsWith("/releases") }
    @Volatile private var fetched = 0

    @Before fun open() {
        clean()
        github.start(5_000, true)
        EarlyCrashes.lastExit = { _, _, _ -> null }
        standIns()
    }

    /** GitHub's API is the test's own server; downloads fail (no network in tests). */
    private fun standIns() {
        Updater.listReleases = { Updater.fetchReleasesFrom(api) }
        Updater.fetchFile = { _, _, _, _ -> fetched++; throw IOException("no network in tests") }
    }

    @After fun clean() {
        shadowOf(Looper.getMainLooper()).idle()
        runCatching { github.stop() }
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
            if (System.currentTimeMillis() > until) throw AssertionError("never: $what (${Updater.state.value}, asked $asked)")
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(20)
        }
    }

    private fun settled() { Thread.sleep(300); shadowOf(Looper.getMainLooper()).idle() }

    private fun closedWhileStarting() {
        EarlyCrashes.opened(app)
        EarlyCrashes.crashed(app, null)
        EarlyCrashes.resetForTest()
        EarlyCrashes.lastExit = { _, _, _ -> null }
        assertEquals(1, EarlyCrashes.count(app))
    }

    // ---- What GitHub's answer says ------------------------------------------------------------------------------

    @Test fun aRefusalWaitsAsLongAsGitHubSays() {
        val now = 1_800_000_000_000L
        // Retry-After, in seconds.
        assertEquals(now + 10 * minute, Updater.askAgainAt(429, "600", null, null, 0, now))
        // None of the hour's requests left: until its count starts again, by GitHub's clock (this TV's is 5 min fast).
        val server = now - 5 * minute
        assertEquals(now + 20 * minute, Updater.askAgainAt(403, null, "0", ((server + 20 * minute) / 1000).toString(), server, now))
        // Saying nothing: a quarter of an hour. Saying "now": still a minute, as GitHub asks.
        assertEquals(now + 15 * minute, Updater.askAgainAt(403, null, null, null, 0, now))
        assertEquals(now + minute, Updater.askAgainAt(429, "0", null, null, 0, now))
        // A reset hours away (a clock far off) is never waited for longer than GitHub's hour and a moment.
        assertEquals(now + Updater.MAX_HOLD_MS, Updater.askAgainAt(403, null, "0", ((now + 5 * hour) / 1000).toString(), 0, now))
        // Requests left: not its hourly limit, so its reset says nothing; a quarter of an hour, as for any refusal that doesn't say.
        assertEquals(now + 15 * minute, Updater.askAgainAt(403, null, "12", ((now + 30 * minute) / 1000).toString(), now, now))
        // Any other answer holds nothing back.
        for (code in listOf(200, 304, 404, 500, 503)) assertNull(Updater.askAgainAt(code, "600", "0", "1", now, now))
    }

    @Test fun aTurnedDownCheckIsntAskedAgainUntilGitHubSaysWhoeverAsks() {
        answer = Answer(429, mapOf("Retry-After" to "600"))
        val before = System.currentTimeMillis()
        Updater.checkNow(app)
        waitUntil("GitHub turned it down") { Updater.state.value.step == Step.ERROR }
        val s = Updater.state.value
        assertNotNull(s.busyUntil)
        assertTrue(s.busyUntil!! in before + 10 * minute..System.currentTimeMillis() + 10 * minute)
        assertTrue(s.message!!, s.message!!.startsWith("GitHub is busy · checking again at "))
        assertEquals(1, apiAsked)
        // Check for updates again, the routine, the urgent look and Check for a fix: nothing more is asked of the API.
        answer = Answer(0)
        Updater.checkNow(app)
        settled()
        Updater.maybeAuto(app)
        settled()
        assertEquals(1, apiAsked)
        assertEquals(Step.ERROR, Updater.state.value.step)
        assertEquals(s.busyUntil, Updater.state.value.busyUntil)
        // Woodruff restarts (a TV turned off and on): the wait is kept.
        Updater.resetForTest()
        standIns()
        closedWhileStarting()
        Updater.latestBuild = { next }
        Updater.lookForFix(app, manual = true)
        waitUntil("Check for a fix looked") { Updater.state.value.step == Step.ERROR && Updater.state.value.busyUntil != null }
        Updater.atStart(app)
        settled()
        assertEquals(1, apiAsked)
        // The time comes: the next check asks again.
        prefs.edit().putLong("githubHeldUntil", System.currentTimeMillis() - 1).commit()
        Updater.checkNow(app)
        waitUntil("it asked again") { apiAsked == 2 }
    }

    @Test fun aWaitFurtherOffThanGitHubEverAsksIsTheClockGoneBackAndIsntKept() {
        val now = System.currentTimeMillis()
        prefs.edit().putLong("githubHeldUntil", now + 30 * minute).commit()
        assertEquals(now + 30 * minute, Updater.heldUntil(app, now))
        assertNull(Updater.heldUntil(app, now + 31 * minute))
        prefs.edit().putLong("githubHeldUntil", now + 5 * hour).commit()
        assertNull(Updater.heldUntil(app, now))
    }

    @Test fun anUrgentLookComesBackNoSoonerAfterGitHubSaidItsBusy() {
        closedWhileStarting()
        Updater.latestBuild = { next }
        answer = Answer(403, mapOf("X-RateLimit-Remaining" to "0", "X-RateLimit-Reset" to ((System.currentTimeMillis() + 40 * minute) / 1000).toString()))
        Updater.atStart(app)
        waitUntil("GitHub turned it down") { Updater.state.value.busyUntil != null }
        assertEquals(1, apiAsked)
        // A minute later (where a check that didn't go through comes back): not yet. Only the next look's turn, as
        // after one that went through, and that one asks github.com only.
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(Updater.URGENT_RETRY_MS + 1_000))
        Updater.maybeAuto(app)
        settled()
        assertEquals(listOf("GET /releases"), asked.toList())
        assertTrue(Updater.urgentCheckDue(10_000, 10_000 + Updater.URGENT_RETRY_MS, failed = true))
        assertFalse(Updater.urgentCheckDue(10_000, 10_000 + Updater.URGENT_RETRY_MS, failed = false))
    }

    // ---- The background routine ----------------------------------------------------------------------------------

    @Test fun afterACheckThatDidntGoThroughTheRoutineTriesAgainWhenItSaysNotOnTheNextPass() {
        val now = 1_800_000_000_000L
        val lastGood = now - 3 * hour
        // Went through: hourly, or every 10 minutes as Woodruff opens.
        assertFalse(Updater.routineCheckDue(now, now - 59 * minute, 0, hour))
        assertTrue(Updater.routineCheckDue(now, now - hour, 0, hour))
        assertTrue(Updater.routineCheckDue(now, now - 10 * minute, 0, 10 * minute))
        // Didn't, 5 minutes ago (its next try a quarter of an hour after it), with the last good check hours back:
        // not on this pass, nor the next, as Woodruff opens neither, but then.
        val retry = now - 5 * minute + 15 * minute
        assertFalse(Updater.routineCheckDue(now, lastGood, retry, hour))
        assertFalse(Updater.routineCheckDue(now + 9 * minute, lastGood, retry, 10 * minute))
        assertTrue(Updater.routineCheckDue(retry, lastGood, retry, hour))
        // GitHub turned it down, with a good check a moment before: as soon as it said (Settings says that time).
        assertTrue(Updater.routineCheckDue(now + 20 * minute, now - 5 * minute, now + 20 * minute, hour))
        // A next try hours away is this TV's clock having gone back: now.
        assertTrue(Updater.routineCheckDue(now, lastGood, now + 3 * hour, hour))
    }

    @Test fun theNextTryIsWhenGitHubSaidAfterItTurnedOneDown() {
        prefs.edit().putLong("lastCheck", System.currentTimeMillis() - 3 * hour).commit()
        answer = Answer(429, mapOf("Retry-After" to "120"))
        Updater.maybeAuto(app)
        waitUntil("GitHub turned it down") { Updater.state.value.busyUntil != null }
        val until = Updater.state.value.busyUntil!!
        assertFalse(Updater.routineCheckDue(until - 1, System.currentTimeMillis() - 3 * hour, until, hour))
        assertEquals(until, Updater.heldUntil(app, System.currentTimeMillis()))
    }

    @Test fun theRoutineDoesntAskAgainOnTheNextPassAfterACheckThatDidntGoThrough() {
        // The last good check was hours ago (GitHub answered errors since).
        prefs.edit().putLong("lastCheck", System.currentTimeMillis() - 3 * hour).commit()
        answer = Answer(500)
        Updater.maybeAuto(app)
        waitUntil("it checked") { Updater.state.value.step == Step.ERROR }
        assertEquals(1, apiAsked)
        // The phone connection's next pass, and Woodruff opening: not again so soon.
        Updater.maybeAuto(app)
        Updater.maybeAuto(app, opening = true)
        settled()
        assertEquals(1, apiAsked)
    }

    @Test fun aCheckWithNoNetworkAtAllGoesAgainOnTheNextPass() {
        prefs.edit().putLong("lastCheck", System.currentTimeMillis() - 3 * hour).commit()
        val cm = shadowOf(app.getSystemService(ConnectivityManager::class.java))
        cm.setActiveNetworkInfo(null)
        val tries = java.util.concurrent.atomic.AtomicInteger()
        Updater.listReleases = { tries.incrementAndGet(); throw java.net.UnknownHostException("api.github.com") }
        Updater.maybeAuto(app)
        waitUntil("it tried") { Updater.state.value.step == Step.ERROR && Updater.state.value.offline }
        // GitHub never heard it: the next pass may go at once.
        Updater.maybeAuto(app)
        waitUntil("it tried again") { tries.get() == 2 }
    }

    // ---- After an early close: github.com first ---------------------------------------------------------------------

    @Test fun anUrgentLookWithNothingNewerOutDoesntAskTheApi() {
        closedWhileStarting()
        Updater.latestBuild = { Updater.current }
        Updater.atStart(app)
        waitUntil("it looked") { Updater.state.value.step == Step.UP_TO_DATE }
        assertEquals(0, apiAsked)
        // Check for a fix, pressed: the same, at once.
        Updater.lookForFix(app, manual = true)
        settled()
        assertEquals(Step.UP_TO_DATE, Updater.state.value.step)
        assertEquals(0, apiAsked)
        // github.com couldn't say: the API, as before.
        Updater.latestBuild = { null }
        Updater.lookForFix(app, manual = true)
        waitUntil("it asked the API") { apiAsked == 1 }
    }

    @Test fun aNewerBuildIsReadFromTheApiOnceAndADownloadThatFailedTriesAgainWithoutAsking() {
        closedWhileStarting()
        Updater.latestBuild = { next }
        Updater.atStart(app)
        waitUntil("the fix is fetched") { fetched == 1 && Updater.state.value.step == Step.ERROR }
        assertEquals(1, apiAsked)
        // The download didn't go through: the next look (a minute on) fetches it again from the list it has.
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(Updater.URGENT_RETRY_MS + 1_000))
        Updater.maybeAuto(app)
        waitUntil("it fetched it again") { fetched == 2 && Updater.state.value.step == Step.ERROR }
        assertEquals(1, apiAsked)
    }

    @Test fun anUrgentLooksFirstStep() {
        val apk = listOf(ReleaseFile("WoodruffFamily.apk", 4, "x"))
        val known = listOf(Release(171, 1, files = apk), Release(172, 2, files = apk))
        val uploading = listOf(Release(171, 1, files = apk), Release(172, 2))
        // Nothing newer, nothing downloaded: no list needed.
        assertEquals(FirstLook.NOTHING_NEWER, Updater.firstLook(170, 170, null, null))
        assertEquals(FirstLook.NOTHING_NEWER, Updater.firstLook(169, 170, null, known))
        // The newest is the one downloaded already.
        assertEquals(FirstLook.NOTHING_NEWER, Updater.firstLook(172, 170, 172, null))
        // Newer: the list read a moment ago, if it has it with its APK; else the API.
        assertEquals(FirstLook.KNOWN, Updater.firstLook(172, 170, null, known))
        assertEquals(FirstLook.ASK, Updater.firstLook(172, 170, null, uploading))
        assertEquals(FirstLook.ASK, Updater.firstLook(173, 170, null, known))
        assertEquals(FirstLook.ASK, Updater.firstLook(172, 170, null, null))
        // Newer than the one downloaded: the same.
        assertEquals(FirstLook.KNOWN, Updater.firstLook(172, 170, 171, known))
        // Older than the one downloaded: it may have been taken down, which only the API's list says.
        assertEquals(FirstLook.ASK, Updater.firstLook(170, 170, 172, known))
        // github.com couldn't say.
        assertEquals(FirstLook.ASK, Updater.firstLook(null, 170, null, known))
    }

    @Test fun urgentLooksAreSpacedFourMinutesGiveOrTakeOne() {
        assertEquals(3 * minute, Updater.urgentSpacing(0.0))
        assertEquals(4 * minute, Updater.urgentSpacing(0.5))
        assertEquals(5 * minute, Updater.urgentSpacing(1.0))
        assertEquals(5 * minute, Updater.urgentSpacing(7.0))
        assertFalse(Updater.urgentCheckDue(10_000, 10_000 + 4 * minute, failed = false, every = 5 * minute))
        assertTrue(Updater.urgentCheckDue(10_000, 10_000 + 5 * minute, failed = false, every = 5 * minute))
    }

    @Test fun theNewestBuildIsWhereGitHubsLatestReleasePageSendsYou() {
        val pages = object : NanoHTTPD("127.0.0.1", 0) {
            override fun serve(session: IHTTPSession): Response {
                asked += "${session.method} ${session.uri}"
                val found = object : Response.IStatus {
                    override fun getDescription() = "302 Found"
                    override fun getRequestStatus() = 302
                }
                return when (session.uri) {
                    "/latest" -> newFixedLengthResponse(found, "text/html", "").apply { addHeader("Location", "https://github.com/owner/repo/releases/tag/v2.0.171") }
                    "/none" -> newFixedLengthResponse(found, "text/html", "").apply { addHeader("Location", "https://github.com/owner/repo/releases") }
                    else -> newFixedLengthResponse(Response.Status.OK, "text/html", "<html></html>")
                }
            }
        }
        pages.start(5_000, true)
        try {
            val base = "http://127.0.0.1:${pages.listeningPort}"
            assertEquals(171, Updater.latestBuildFrom("$base/latest"))
            // No release yet, or a page that no longer sends you on: it can't say.
            assertNull(Updater.latestBuildFrom("$base/none"))
            assertNull(Updater.latestBuildFrom("$base/page"))
            // Its headers only.
            assertEquals(listOf("HEAD /latest", "HEAD /none", "HEAD /page"), asked.filter { !it.endsWith("/releases") })
        } finally {
            pages.stop()
        }
    }

    // ---- What it says ----------------------------------------------------------------------------------------------

    @Test fun theRecoveryScreenSaysWhenItChecksAgain() {
        val l = RecoveryView.look(State(Step.ERROR, busyUntil = 1L, message = "GitHub is busy · checking again at 9:40 PM")) { "9:40 PM" }
        assertEquals("GitHub is busy. Woodruff checks again by itself at 9:40 PM.", l.status)
        assertEquals("Check for a fix", l.pill)
        assertFalse(l.busy)
        // Without a time from GitHub, the words for any other check that didn't go through.
        assertEquals("Couldn’t check for a fix. Try again in a moment.", RecoveryView.look(State(Step.ERROR, message = "Couldn’t check · trying again soon")).status)
    }
}
