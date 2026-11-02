package com.woodruff.tvexplorer.tvs

import com.woodruff.tvexplorer.tvs.HealthTest.DAY
import com.woodruff.tvexplorer.tvs.HealthTest.HOUR
import com.woodruff.tvexplorer.tvs.HealthTest.MIN
import com.woodruff.tvexplorer.tvs.HealthTest.at
import com.woodruff.tvexplorer.tvs.HealthTest.facts
import com.woodruff.tvexplorer.tvs.HealthTest.now
import com.woodruff.tvexplorer.tvs.HealthTest.report
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** "Needs attention" is decided by the seven rules of the TV health spec, and nothing else (org.json needs Robolectric on the JVM). */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class HealthReportTest {
    private fun ids(r: HealthReport) = r.attention.map { it.id }

    @Test fun aTvWhereEverythingWorksIsAllGood() {
        val r = report()
        assertTrue(r.good)
        assertEquals("Everything’s working.", r.sentence)
        assertEquals(HealthSummary("good", 0, null), r.summary)
        assertEquals("Signed in", r.signIns.getValue(HealthGrant.DRIVE).text)
        assertEquals("Connected", r.signIns.getValue(HealthGrant.PHOTOS).text)
        assertEquals("Up to date", r.updates.text)
        assertEquals("All saved", r.keys.text)
        assertEquals("Allowed", r.installPermission!!.text)
        assertEquals("Nothing unusual", r.closedByAndroid!!.text)
        assertNull(r.lastCrash)
        assertNull(r.lastUpdateTry)
        assertEquals("Watching", r.doorbell.text)
    }

    @Test fun offlineAndTheRestNeverNeedAttention() {
        for (state in listOf(SignInState.OFFLINE, SignInState.UNREACHABLE, SignInState.NOT_SIGNED_IN, SignInState.NOT_SET_UP)) {
            val r = report(facts(signIns = HealthGrant.entries.associateWith { SignInFact(state) }))
            assertTrue("$state", r.good)
        }
        // A key not set, a routine close, an off doorbell: information only.
        val r = report(facts(
            keys = KeysFact(googleClientId = false, googleClientSecret = false, camerasProject = false, tmdb = false),
            doorbell = doorbellFact(subscribed = false, status = null, networkUp = true),
        ))
        assertTrue(r.good)
        assertEquals("3 keys not set", r.keys.text)
        assertEquals("Off", r.doorbell.text)
    }

    @Test fun signInWordsFollowAccountsForEachGrant() {
        val words = SignInState.entries.associateWith { it.words(HealthGrant.DRIVE) to it.words(HealthGrant.CAMERAS) }
        assertEquals("Signed in" to "Connected", words[SignInState.SIGNED_IN])
        assertEquals("Offline" to "Offline", words[SignInState.OFFLINE])
        assertEquals("Can’t reach Google" to "Can’t reach Google", words[SignInState.UNREACHABLE])
        assertEquals("Sign-in ended" to "Sign-in ended", words[SignInState.EXPIRED])
        assertEquals("Keys not accepted" to "Keys not accepted", words[SignInState.KEYS_REJECTED])
        assertEquals("Not signed in" to "Not connected", words[SignInState.NOT_SIGNED_IN])
        assertEquals("Not set up" to "Not connected", words[SignInState.NOT_SET_UP])
    }

    @Test fun anEndedSignInNeedsAttentionWithWhereToFixIt() {
        val r = report(facts(signIns = HealthGrant.entries.associateWith { SignInFact(SignInState.SIGNED_IN) } +
            (HealthGrant.DRIVE to SignInFact(SignInState.EXPIRED, at(2026, 11, 1, 21, 0))) + (HealthGrant.CAMERAS to SignInFact(SignInState.EXPIRED))))
        assertEquals(listOf("signin.drive", "signin.cameras"), ids(r))
        assertEquals("Google Drive’s sign-in ended", r.attention[0].words)
        assertEquals("the cameras’ sign-in ended", r.attention[1].words)
        assertEquals("Google ended this sign-in. On Den: open Streaming and scan the code with your phone.", r.attention[0].detail)
        assertEquals("Google ended this sign-in. On Den: open the Command Center and scan the code with your phone.", r.attention[1].detail)
        assertEquals("signin", r.attention[0].fix)
        assertEquals("drive", r.attention[0].grant)
        assertEquals("Google Drive’s sign-in ended, and the cameras’ sign-in ended.", r.sentence)
        // The summary names what ended and since when (the hub tells its phones once per ending).
        assertEquals(HealthSummary("attention", 2, "Google Drive’s sign-in ended", mapOf("drive" to at(2026, 11, 1, 21, 0))), r.summary)
    }

    @Test fun keysGoogleTurnsDownAreOneItemWhateverHowManySignInsSayIt() {
        val r = report(facts(signIns = HealthGrant.entries.associateWith { SignInFact(SignInState.KEYS_REJECTED) }))
        assertEquals(listOf("signin.keys"), ids(r))
        assertEquals("Google doesn’t accept the saved keys", r.attention[0].words)
        assertEquals("keys", r.attention[0].fix)
    }

    @Test fun aFailedInstallIsTheLastUpdateDidntInstall() {
        val r = report(facts(update = UpdateFact("ready", "2.0.172", message = "INSTALL_FAILED_INSUFFICIENT_STORAGE: not enough room", readySince = now - 3 * DAY)))
        assertEquals(listOf("update.failed"), ids(r)) // not also "waiting": one problem, one item
        assertEquals("the last update didn’t install", r.attention[0].words)
        assertEquals("Didn’t install", r.lastUpdateTry!!.text)
        assertEquals("There wasn’t enough free space.", r.lastUpdateTry!!.reason)
        assertEquals("2.0.172 ready", r.updates.text)
    }

    @Test fun anUpdateWaitsForOnePressOnlyAfterADay() {
        assertTrue(report(facts(update = UpdateFact("ready", "2.0.172", readySince = now - 23 * HOUR))).good)
        val r = report(facts(update = UpdateFact("ready", "2.0.172", readySince = now - 25 * HOUR)))
        assertEquals(listOf("update.waiting"), ids(r))
        assertEquals("an update is waiting for one press", r.attention[0].words)
        assertEquals("install", r.attention[0].fix)
        assertTrue(r.updates.attention)
    }

    @Test fun installingAppsNotAllowedMattersWhereEveryUpdateNeedsIt() {
        // Android 8 to 11, automatic updates on.
        val r = report(facts(installAllowed = false, sdk = 29))
        assertEquals(listOf("update.notAllowed"), ids(r))
        assertEquals("Woodruff isn’t allowed to install updates", r.attention[0].words)
        assertEquals("Not allowed", r.installPermission!!.text)
        assertTrue(r.installPermission!!.attention)
        // Android 12+ installs Woodruff's own updates without it; automatic updates off: nothing to install.
        assertTrue(report(facts(installAllowed = false, sdk = 31)).good)
        assertTrue(report(facts(installAllowed = false, sdk = 29, update = UpdateFact("upToDate", auto = false))).good)
    }

    @Test fun updateWordsAreTheReportsOwn() {
        assertEquals("Not checked yet", updateWords(UpdateFact("idle")))
        assertEquals("Checking…", updateWords(UpdateFact("checking")))
        assertEquals("Downloading 40%", updateWords(UpdateFact("downloading", progress = 0.4f)))
        assertEquals("Installing…", updateWords(UpdateFact("installing")))
        assertEquals("Couldn’t check", updateWords(UpdateFact("error")))
        val r = report(facts(update = UpdateFact("error", message = "Unable to resolve host \"api.github.com\": No address associated with hostname")))
        assertEquals("The TV couldn’t reach GitHub. It tries again by itself.", r.updates.reason)
        assertTrue(r.good)
    }

    @Test fun oneCrashIsOneItemThoughBothHandlersSawIt() {
        val crashAt = at(2026, 11, 1, 21, 41)
        val r = report(facts(crash = CrashFact(crashAt, HealthTest.CRASH), exits = listOf(AppExit(ExitKind.CRASH, crashAt + 2_000))))
        assertEquals(listOf("stability.crash"), ids(r))
        assertEquals("Woodruff crashed yesterday", r.attention[0].words)
        assertEquals("crash", r.attention[0].fix)
        val row = r.lastCrash!!
        assertEquals("Yesterday, 9:41 PM", row.text)
        assertTrue(row.attention)
        assertEquals("IllegalStateException in the player", row.summary)
        assertEquals(3, row.lines.lines().size)
        assertEquals("Crashed · yesterday", r.closedByAndroid!!.text)
        assertTrue(r.closedByAndroid!!.attention)
    }

    @Test fun aCrashOnlyAndroidSawHasNoReportToCopy() {
        val r = report(facts(exits = listOf(AppExit(ExitKind.CRASH, at(2026, 10, 30, 8, 0)))))
        assertEquals("Woodruff crashed on Oct 30", r.attention.single().words)
        assertEquals("none", r.attention.single().fix)
    }

    @Test fun aClearedCrashNoLongerNeedsAttention() {
        val crashAt = now - 2 * HOUR
        val r = report(facts(crash = CrashFact(crashAt, HealthTest.CRASH), crashesClearedAt = now - HOUR, exits = listOf(AppExit(ExitKind.CRASH, crashAt))))
        assertTrue(r.good)
        assertNull(r.lastCrash)
        assertEquals("Nothing unusual", r.closedByAndroid!!.text)
    }

    @Test fun anOldCrashIsShownButNeedsNothing() {
        val r = report(facts(crash = CrashFact(at(2026, 10, 20, 9, 0), HealthTest.CRASH)))
        assertTrue(r.good)
        assertEquals("Oct 20, 9:00 AM", r.lastCrash!!.text)
        assertFalse(r.lastCrash!!.attention)
    }

    @Test fun androidsReasonsInTheLastWeek() {
        val r = report(facts(exits = listOf(
            AppExit(ExitKind.ANR, at(2026, 11, 1, 10, 0)),
            AppExit(ExitKind.NATIVE_CRASH, at(2026, 10, 30, 22, 0), inMediaCode = true),
            AppExit(ExitKind.INIT_FAILURE, at(2026, 10, 30, 7, 0)),
            AppExit(ExitKind.CRASH, at(2026, 10, 20, 7, 0)), // over a week ago: forgotten
        )))
        assertEquals(listOf("stability.anr", "stability.native", "stability.start"), ids(r))
        assertEquals("Woodruff stopped responding yesterday", r.attention[0].words)
        assertEquals("Woodruff crashed on Oct 30", r.attention[1].words)
        assertEquals("It crashed inside the camera or video code.", r.attention[1].detail)
        assertEquals("Woodruff couldn’t start on Oct 30", r.attention[2].words)
        assertEquals("Stopped responding · yesterday", r.closedByAndroid!!.text)
    }

    @Test fun lowMemoryNowAndThenIsNormal() {
        val twice = report(facts(exits = listOf(AppExit(ExitKind.LOW_MEMORY, now - 2 * HOUR), AppExit(ExitKind.LOW_MEMORY, now - 5 * HOUR))))
        assertTrue(twice.good)
        assertEquals("Low memory · 2 times today", twice.closedByAndroid!!.text)
        assertFalse(twice.closedByAndroid!!.attention)
        val once = report(facts(exits = listOf(AppExit(ExitKind.LOW_MEMORY, at(2026, 11, 2, 3, 10)))))
        assertEquals("Low memory · 3:10 AM", once.closedByAndroid!!.text)
        val four = report(facts(exits = (1..4).map { AppExit(ExitKind.LOW_MEMORY, now - it * HOUR) }))
        assertEquals(listOf("stability.memory"), ids(four))
        assertEquals("Android closed Woodruff for low memory 4 times today", four.attention[0].words)
        assertEquals("Low memory · 4 times today", four.closedByAndroid!!.text)
        assertTrue(four.closedByAndroid!!.attention)
    }

    @Test fun android9And10SayNothingOfWhyAndroidClosedIt() {
        assertNull(report(facts(exits = null, sdk = 29)).closedByAndroid)
    }

    @Test fun lowSpaceAndAFailingDoorbell() {
        val r = report(facts(
            freeBytes = 600L * 1024 * 1024,
            doorbell = doorbellFact(subscribed = true, status = "Google refused the doorbell subscription. Connect the cameras again (to allow Pub/Sub) and check the subscription name.", networkUp = true),
        ))
        assertEquals(listOf("space.low", "doorbell.failing"), ids(r))
        assertEquals("only 600 MB is free", r.attention[0].words)
        assertEquals("600 MB free", r.freeSpace!!.text)
        assertEquals("doorbell alerts aren’t working", r.attention[1].words)
        assertEquals("Google refused", r.doorbell.text)
        assertEquals("Only 600 MB is free, and doorbell alerts aren’t working.", r.sentence)
    }

    @Test fun itemsComeInTheSpecsOrder() {
        val r = report(facts(
            signIns = HealthGrant.entries.associateWith { SignInFact(SignInState.SIGNED_IN) } + (HealthGrant.PHOTOS to SignInFact(SignInState.EXPIRED)),
            update = UpdateFact("ready", "2.0.172", message = "The update didn't install"),
            exits = listOf(AppExit(ExitKind.ANR, now - HOUR)),
            freeBytes = 100L * 1024 * 1024,
        ))
        assertEquals(listOf("signin.photos", "update.failed", "stability.anr", "space.low"), ids(r))
        assertEquals("Google Photos’ sign-in ended, the last update didn’t install, Woodruff stopped responding today, and only 100 MB is free.", r.sentence)
    }

    @Test fun keysSayWhichIsMissingNeverWhatTheyAre() {
        fun keys(id: Boolean, secret: Boolean, cams: Boolean, tmdb: Boolean) = report(facts(keys = KeysFact(id, secret, cams, tmdb))).keys.text
        assertEquals("All saved", keys(true, true, true, true))
        assertEquals("TMDB key not set", keys(true, true, true, false))
        assertEquals("Google keys not set", keys(true, false, true, true))
        assertEquals("Cameras project not set", keys(true, true, false, true))
        assertEquals("2 keys not set", keys(false, false, false, true))
    }

    @Test fun theJsonCarriesTheTvsOwnWordsAndComesBackTheSame() {
        val r = report(facts(
            signIns = HealthGrant.entries.associateWith { SignInFact(SignInState.SIGNED_IN) } +
                (HealthGrant.DRIVE to SignInFact(SignInState.EXPIRED, now - DAY)) + (HealthGrant.CAMERAS to SignInFact(SignInState.UNREACHABLE)),
            update = UpdateFact("ready", "2.0.172", message = "The package installer said no."),
            crash = CrashFact(now - 3 * HOUR, HealthTest.CRASH),
            exits = listOf(AppExit(ExitKind.LOW_MEMORY, now - HOUR)),
        ))
        val json = r.toJson()
        val back = HealthReport.fromJson(JSONObject(json.toString()))!!
        assertEquals(r, back)
        // On the phone each item starts its row: capitalised there, the fragment again here.
        assertEquals("The last update didn’t install", json.getJSONArray("attention").getJSONObject(1).getString("words"))
        assertEquals("the last update didn’t install", back.attention[1].words)
        assertEquals(r.sentence, json.getString("sentence"))
        assertEquals("attention", json.getString("status"))
        assertEquals("expired", json.getJSONObject("signIns").getJSONObject("drive").getString("state"))
        assertEquals("Can’t reach Google", json.getJSONObject("signIns").getJSONObject("cameras").getString("text"))
    }

    @Test fun aReportNeverCarriesKeysTokensAddressesOrEmails() {
        val json = report(facts(crash = CrashFact(now - HOUR, HealthTest.CRASH))).toJson().toString()
        val keys = JSONObject(json).getJSONObject("keys")
        // Only whether each key is there.
        assertEquals(setOf("text", "googleClientId", "googleClientSecret", "camerasProject", "tmdb"), keys.keys().asSequence().toSet())
        listOf("googleClientId", "googleClientSecret", "camerasProject", "tmdb").forEach { assertTrue(keys.get(it) is Boolean) }
        assertFalse(Regex("""\b\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}\b""").containsMatchIn(json))
        assertFalse(Regex("""[\w.]+@[\w.]+\.\w+""").containsMatchIn(json))
        assertFalse("GOCSPX" in json || "apps.googleusercontent.com" in json || "ya29." in json)
    }

    @Test fun aCrashThatNamedAnAddressOrATokenLeavesTheTvWithoutThem() {
        // An exception's message can carry what it failed on: the TV's report (and the copy the phone makes) never does.
        val crash = HealthTest.CRASH + "Caused by: java.io.FileNotFoundException: https://api.themoviedb.org/3/search/movie?api_key=abc123secret&query=harbor\n" +
            "Caused by: java.net.ConnectException: failed to connect to /192.168.1.21 (port 8080) from /192.168.1.20 (port 41234)\n" +
            "Caused by: java.io.IOException: Unauthorized for someone@example.com, Authorization: Bearer ya29.a0AfH6SMexample\n"
        val r = report(facts(crash = CrashFact(now - HOUR, crash)))
        val json = r.toJson().toString()
        listOf("abc123secret", "192.168.1.21", "192.168.1.20", "someone@example.com", "ya29.a0AfH6SMexample").forEach {
            assertFalse("$it leaves the TV", it in json)
        }
        val text = r.lastCrash!!.report
        assertTrue("api_key=…&query=harbor" in text)
        assertTrue("failed to connect to /… (port 8080)" in text)
        // The trace itself stays as it was.
        assertTrue("\tat com.woodruff.tvexplorer.player.PlayerActivity.onStop(PlayerActivity.kt:410)" in text)
        assertEquals("IllegalStateException in the player", r.lastCrash!!.summary)
        // A report kept from a TV on an older Woodruff is cleaned as it passes through the hub.
        val old = JSONObject(json).apply { getJSONObject("lastCrash").put("report", crash).put("lines", "token=xyz789") }
        val back = HealthReport.fromJson(old)!!.lastCrash!!
        assertFalse("abc123secret" in back.report || "192.168.1.21" in back.report || "xyz789" in back.lines)
    }

    @Test fun aReportFromAnotherVersionStillReads() {
        assertNull(HealthReport.fromJson(JSONObject().put("id", "x")))
        val minimal = JSONObject().put("name", "Den").put("updates", JSONObject().put("text", "Up to date")).put("signIns", JSONObject())
        val r = HealthReport.fromJson(minimal)!!
        assertEquals("Not set up", r.signIns.getValue(HealthGrant.DRIVE).text)
        assertTrue(r.good)
    }

    @Test fun wordsReadInSummariesAsTheySay() {
        assertEquals(HealthSummary("attention", 2, "Den"), HealthSummary.fromJson(HealthSummary("attention", 2, "Den").toJson()))
        assertNull(HealthSummary.fromJson(JSONObject()))
        assertNotNull(HealthSummary.fromJson(HealthSummary("good", 0, null).toJson()))
        assertNull(HealthSummary.fromJson(HealthSummary("good", 0, null).toJson())!!.first)
    }
}
