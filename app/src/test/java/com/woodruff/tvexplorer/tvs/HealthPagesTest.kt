package com.woodruff.tvexplorer.tvs

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.woodruff.tvexplorer.tvs.HealthTest.DAY
import com.woodruff.tvexplorer.tvs.HealthTest.HOUR
import com.woodruff.tvexplorer.tvs.HealthTest.MIN
import com.woodruff.tvexplorer.tvs.HealthTest.clock
import com.woodruff.tvexplorer.tvs.HealthTest.facts
import com.woodruff.tvexplorer.tvs.HealthTest.now
import com.woodruff.tvexplorer.tvs.HealthTest.report
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The TV's health pages: Settings' first row, the TV list and a TV's report, word for word. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class HealthPagesTest {
    private val good = report(facts(name = "Living Room"))
    private val attention = report(facts(
        signIns = HealthGrant.entries.associateWith { SignInFact(SignInState.SIGNED_IN) } + (HealthGrant.DRIVE to SignInFact(SignInState.EXPIRED, now - DAY)),
        update = UpdateFact("ready", "2.0.172", message = "The package installer said no."),
    ))

    private fun view(name: String, kind: TvHealthKind, report: HealthReport? = null, isThis: Boolean = false, seen: Long? = null, version: String? = null) =
        TvHealthView(name, isThis, kind, report, report?.at, seen, version)

    @Test fun settingsFirstRowSummarisesEveryTv() {
        val me = view("Living Room", TvHealthKind.GOOD, good, isThis = true)
        assertEquals("All good", healthOverview(me, emptyList()))
        assertEquals("Needs attention", healthOverview(me.copy(kind = TvHealthKind.ATTENTION), emptyList()))
        assertEquals("Checking…", healthOverview(me.copy(kind = TvHealthKind.CHECKING), emptyList()))
        val den = view("Den", TvHealthKind.ATTENTION, attention)
        val kitchen = view("Kitchen", TvHealthKind.OFF, seen = now - 2 * HOUR)
        val bedroom = view("Bedroom", TvHealthKind.REFUSED)
        assertEquals("All good", healthOverview(me, listOf(kitchen)))   // a TV that's off doesn't need attention
        assertEquals("Den needs attention", healthOverview(me, listOf(den, kitchen)))
        assertEquals("2 TVs need attention", healthOverview(me, listOf(den, kitchen, bedroom)))
        assertEquals("Checking…", healthOverview(me, listOf(view("Den", TvHealthKind.CHECKING))))
        assertEquals("Den needs attention", healthOverview(me, listOf(den, view("Kitchen", TvHealthKind.CHECKING))))
        // Never a sentence cut in the middle.
        assertEquals("1 TV needs attention", healthOverview(me, listOf(den.copy(name = "Upstairs Guest Bedroom TV"))))
        assertEquals("Bedroom needs attention", healthOverview(me, listOf(bedroom)))
    }

    @Test fun aTvThatAsksBeforeEachUpdateSaysSoInsteadOfInstallingByItself() {
        fun versionAbout(v: TvHealthView) = reportRows(v, clock()).first { it.key == "version" }.about
        val asks = report(facts(name = "Living Room", update = UpdateFact("upToDate", "2.0.171", asks = true)))
        assertEquals("This TV asks before each update, so it’s offered when you open Woodruff.", versionAbout(view("Living Room", TvHealthKind.GOOD, asks, isThis = true)))
        // A linked TV's report says it by its name, also after the report's trip to the hub as JSON.
        val there = HealthReport.fromJson(report(facts(update = UpdateFact("upToDate", "2.0.171", asks = true))).toJson())!!
        assertTrue(there.updates.asks)
        assertEquals("Den asks before each update, so it’s offered when you open Woodruff there.", versionAbout(view("Den", TvHealthKind.GOOD, there)))
        // A TV that installs by itself, and a report from a Woodruff that doesn't say.
        assertEquals("Updates install by themselves when nobody is using Woodruff.", versionAbout(view("Den", TvHealthKind.GOOD, good)))
        val old = JSONObject(good.toJson().toString()).apply { getJSONObject("updates").remove("asks") }
        assertFalse(HealthReport.fromJson(old)!!.updates.asks)
    }

    @Test fun theTvListSaysHowEachIs() {
        val c = clock()
        assertEquals("All good", listStatus(view("Living Room", TvHealthKind.GOOD), c))
        assertEquals("Needs attention", listStatus(view("Den", TvHealthKind.ATTENTION), c))
        assertEquals("Off · seen 2 h ago", listStatus(view("Kitchen", TvHealthKind.OFF, seen = now - 2 * HOUR), c))
        assertEquals("Off · seen just now", listStatus(view("Kitchen", TvHealthKind.OFF, seen = now - 20_000), c))
        assertEquals("Off", listStatus(view("Kitchen", TvHealthKind.OFF), c))
        assertEquals("Link again", listStatus(view("Bedroom", TvHealthKind.REFUSED), c))
        assertEquals("Checking…", listStatus(view("Den", TvHealthKind.CHECKING), c))
        assertEquals("Didn’t answer", listStatus(view("Den", TvHealthKind.NO_ANSWER), c))
        assertEquals("Needs a newer Woodruff", listStatus(view("Den", TvHealthKind.OLD), c))
    }

    @Test fun aTvsAboutTextSaysWhyThereIsNoReport() {
        assertEquals("Everything’s working.", aboutTv(view("Den", TvHealthKind.GOOD, good)))
        assertEquals("Google Drive’s sign-in ended, and the last update didn’t install.", aboutTv(view("Den", TvHealthKind.ATTENTION, attention)))
        assertEquals("Off or asleep. These are the last things it reported.", aboutTv(view("Kitchen", TvHealthKind.OFF, good)))
        assertEquals("Kitchen hasn’t reported yet. Turn it on to see how it’s doing.", aboutTv(view("Kitchen", TvHealthKind.OFF)))
        assertEquals("Den is on the network but didn’t reply. Try Check every TV again.", aboutTv(view("Den", TvHealthKind.NO_ANSWER)))
        assertEquals("Health reports start with the next version. Den updates by itself when nobody’s using it.", aboutTv(view("Den", TvHealthKind.OLD)))
        assertTrue(aboutTv(view("Bedroom", TvHealthKind.REFUSED))!!.startsWith("Bedroom answered but turned down this TV’s link. That happens after Bedroom is reset or reinstalled"))
    }

    @Test fun theSummaryRowSaysWhereTheTvStands() {
        val c = clock()
        fun summary(v: TvHealthView) = summaryRow(v, c).let { Triple(it.title, it.value, it.icon) }
        assertEquals(Triple("All good", "Checked just now", RowIcon.CHECK_CIRCLE), summary(view("Den", TvHealthKind.GOOD, good)))
        assertEquals(Triple("2 things need attention", "Checked 3 min ago", RowIcon.ALERT_CIRCLE),
            summary(view("Den", TvHealthKind.ATTENTION, attention).copy(checkedAt = now - 3 * MIN)))
        assertEquals("1 thing needs attention", summaryRow(view("Den", TvHealthKind.ATTENTION, report(facts(installAllowed = false, sdk = 29))), c).title)
        assertEquals(Triple("Off", "Last seen 2 h ago", RowIcon.TV), summary(view("Kitchen", TvHealthKind.OFF, seen = now - 2 * HOUR)))
        assertEquals(Triple("Its link stopped working", "Link again", RowIcon.LINK_OFF), summary(view("Bedroom", TvHealthKind.REFUSED)))
        assertEquals(Triple("Checking Den…", null, RowIcon.TV), summary(view("Den", TvHealthKind.CHECKING)))
        assertEquals(Triple("Didn’t answer", null, RowIcon.TV), summary(view("Den", TvHealthKind.NO_ANSWER)))
        assertEquals(Triple("Needs a newer Woodruff", "2.0.160", RowIcon.UPDATE), summary(view("Den", TvHealthKind.OLD, version = "2.0.160")))
        assertTrue(summaryRow(view("Bedroom", TvHealthKind.REFUSED), c).attention)
    }

    @Test fun aReportPageHasEveryRowInOrder() {
        val rows = reportRows(view("Den", TvHealthKind.ATTENTION, attention), clock())
        assertEquals(
            listOf("summary", "signin.drive", "signin.photos", "signin.calendar", "signin.cameras", "doorbell", "version", "lastTry", "permission",
                "crash", "closed", "space", "keys", "check", "unlink"),
            rows.map { it.key },
        )
        val byKey = rows.associateBy { it.key }
        assertEquals("Sign-in ended", byKey.getValue("signin.drive").value)
        assertTrue(byKey.getValue("signin.drive").attention)
        assertEquals(RowAction.SIGN_IN, byKey.getValue("signin.drive").action)
        assertEquals("Google ended this sign-in. On Den: open Streaming and scan the code with your phone.", byKey.getValue("signin.drive").about)
        assertEquals("Version 2.0.171", byKey.getValue("version").title)
        assertEquals("Didn’t install", byKey.getValue("lastTry").value)
        assertEquals("Last update try", byKey.getValue("lastTry").title)
        assertEquals("None", byKey.getValue("crash").value)
        assertEquals("Unlink this TV", byKey.getValue("unlink").title)
        assertTrue(byKey.getValue("unlink").danger)
        // Groups start with a gap.
        assertEquals(listOf("signin.drive", "version", "crash", "space", "check"), rows.filter { it.spaced }.map { it.key })
    }

    @Test fun thisTvSignsInRightHereAndCantBeUnlinked() {
        val mine = report(facts(name = "Living Room", signIns = HealthGrant.entries.associateWith { SignInFact(SignInState.SIGNED_IN) } +
            (HealthGrant.PHOTOS to SignInFact(SignInState.EXPIRED))))
        val rows = reportRows(view("Living Room", TvHealthKind.ATTENTION, mine, isThis = true), clock())
        assertEquals("Google ended this sign-in. OK signs in again.", rows.first { it.key == "signin.photos" }.about)
        assertEquals(RowAction.VERSION, rows.first { it.key == "version" }.action)
        assertFalse(rows.any { it.key == "unlink" })
    }

    @Test fun aTvThatsOffShowsItsLastReportMuted() {
        val rows = reportRows(view("Kitchen", TvHealthKind.OFF, attention, seen = now - 2 * HOUR), clock())
        assertFalse(rows.drop(1).any { it.attention })
        assertTrue(rows.none { it.action == RowAction.SIGN_IN || it.action == RowAction.CHECK })
        assertEquals("unlink", rows.last().key)
        // Never reported: the summary and Unlink only.
        assertEquals(listOf("summary", "unlink"), reportRows(view("Kitchen", TvHealthKind.OFF), clock()).map { it.key })
        // Asked, nothing yet: the summary alone.
        assertEquals(listOf("summary"), reportRows(view("Den", TvHealthKind.CHECKING), clock()).map { it.key })
    }

    @Test fun aTvWhoseLinkStoppedWorkingOffersLinkAgain() {
        val rows = reportRows(view("Bedroom", TvHealthKind.REFUSED, good), clock())
        assertEquals(listOf("relink", "unlink"), rows.filter { it.action == RowAction.RELINK || it.action == RowAction.UNLINK }.map { it.key })
        assertEquals("Link again", rows.first { it.key == "relink" }.title)
    }

    @Test fun checkForUpdatesSaysCheckingThenHowItWent() {
        val v = view("Den", TvHealthKind.GOOD, good)
        assertNull(reportRows(v, clock()).first { it.key == "check" }.value)
        assertEquals("Checking…", reportRows(v, clock(), checking = true, asked = true).first { it.key == "check" }.value)
        assertEquals("Up to date", reportRows(v, clock(), checking = false, asked = true).first { it.key == "check" }.value)
    }

    @Test fun theAboutPanelListsWhatNeedsAttentionFirst() {
        val inside = insideRows(view("Den", TvHealthKind.ATTENTION, attention), clock())
        assertEquals(7, inside.size)
        assertEquals(listOf("signin.drive", "lastTry"), inside.take(2).map { it.key })
    }

    /** The TV's rows and the phone's JSON carry identical words: both come from one builder. */
    @Test fun theTvRowsAndTheJsonSayTheSame() {
        val r = report(facts(
            signIns = HealthGrant.entries.associateWith { SignInFact(SignInState.SIGNED_IN) } +
                (HealthGrant.DRIVE to SignInFact(SignInState.EXPIRED, now - DAY)) + (HealthGrant.CALENDAR to SignInFact(SignInState.NOT_SIGNED_IN)),
            update = UpdateFact("ready", "2.0.172", message = "The package installer said no."),
            crash = CrashFact(now - 2 * HOUR, HealthTest.CRASH),
            exits = listOf(AppExit(ExitKind.LOW_MEMORY, now - HOUR)),
            freeBytes = 900L * 1024 * 1024,
        ))
        // As a linked TV's report reaches the hub: through its JSON.
        val json = JSONObject(r.toJson().toString())
        val rows = reportRows(view("Den", TvHealthKind.ATTENTION, HealthReport.fromJson(json)), clock()).associateBy { it.key }
        val signIns = json.getJSONObject("signIns")
        for (g in HealthGrant.entries) assertEquals(signIns.getJSONObject(g.json).getString("text"), rows.getValue("signin.${g.json}").value)
        assertEquals(signIns.getJSONObject("doorbell").getString("text"), rows.getValue("doorbell").value)
        assertEquals(json.getJSONObject("updates").getString("text"), rows.getValue("version").value)
        assertEquals(json.getJSONObject("lastUpdateTry").getString("text"), rows.getValue("lastTry").value)
        assertEquals(json.getJSONObject("lastUpdateTry").getString("reason"), rows.getValue("lastTry").detail)
        assertEquals(json.getJSONObject("installPermission").getString("text"), rows.getValue("permission").value)
        assertEquals(json.getJSONObject("lastCrash").getString("text"), rows.getValue("crash").value)
        assertEquals(json.getJSONObject("closedByAndroid").getString("text"), rows.getValue("closed").value)
        assertEquals(json.getJSONObject("freeSpace").getString("text"), rows.getValue("space").value)
        assertEquals(json.getJSONObject("keys").getString("text"), rows.getValue("keys").value)
        assertEquals("Version " + json.getString("version"), rows.getValue("version").title)
        assertEquals(json.getString("sentence"), rows.getValue("summary").about)
        // The attention items: the TV's about sentence is the phone's rows, joined.
        val words = (0 until json.getJSONArray("attention").length()).map { json.getJSONArray("attention").getJSONObject(it).getString("words") }
        assertEquals(listOf("Google Drive’s sign-in ended", "The last update didn’t install", "Woodruff crashed today", "Only 900 MB is free"), words)
        assertEquals(sentenceOf(r.attention), json.getString("sentence"))
    }
}
