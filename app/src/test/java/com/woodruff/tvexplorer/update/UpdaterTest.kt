package com.woodruff.tvexplorer.update

import com.woodruff.tvexplorer.update.Updater.Release
import com.woodruff.tvexplorer.update.Updater.ReleaseFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdaterTest {
    @Test fun releaseTags() {
        assertEquals(41, Updater.buildNumber("v2.0.41"))
        assertEquals(7, Updater.buildNumber("2.0.7"))
        assertEquals(120, Updater.buildNumber(" v3.1.120 "))
    }

    @Test fun rejectsOtherTags() {
        assertNull(Updater.buildNumber("latest"))
        assertNull(Updater.buildNumber("v2.0"))
        assertNull(Updater.buildNumber("v2.0.41-beta"))
        assertNull(Updater.buildNumber(""))
    }

    @Test fun readsGitHubTimes() {
        assertEquals(1_760_479_584_000L, Updater.published("2025-10-14T22:06:24Z"))
        assertNull(Updater.published("yesterday"))
    }

    // ---- Which build a TV takes ----------------------------------------------------------------

    private val hour = 3_600_000L
    private val t0 = 1_800_000_000_000L
    private fun apk(build: Int) = listOf(ReleaseFile("WoodruffFamily.apk", 23_750_277, "https://example.invalid/$build/WoodruffFamily.apk"))
    private fun rel(build: Int, atHours: Double, rollback: Boolean = false, uploaded: Boolean = true, prerelease: Boolean = false) =
        Release(build, t0 + (atHours * hour).toLong(), rollback, if (uploaded) apk(build) else emptyList(), prerelease)
    private fun at(hours: Double) = t0 + (hours * hour).toLong()

    @Test fun otherTvsWaitUntilABuildHasBeenTheNewestForADay() {
        val releases = listOf(rel(100, 0.0), rel(101, 1.0))
        val waiting = Updater.choose(releases, installed = 99, now = at(3.0), eager = false)
        assertNull(waiting.build)
        assertEquals(101, waiting.waitingFor)
        assertEquals(22 * hour, waiting.arrivesInMs)
        assertEquals("2.0.101 arrives in about 22 h (after the test TV)", Updater.waitingMessage(101, waiting.arrivesInMs))
        // 100 was the newest for only an hour: never taken, even once it's old.
        assertEquals(101, Updater.choose(releases, installed = 99, now = at(25.0), eager = false).build)
        assertNull(Updater.choose(releases, installed = 99, now = at(24.5), eager = false).build)
    }

    @Test fun theTestTvAndCheckForUpdatesTakeTheNewestAtOnce() {
        val releases = listOf(rel(100, 0.0), rel(101, 1.0))
        assertEquals(101, Updater.choose(releases, installed = 99, now = at(1.1), eager = true).build)
        // Nothing newer than what's installed: up to date, knowing the newest.
        val upToDate = Updater.choose(releases, installed = 101, now = at(2.0), eager = true)
        assertNull(upToDate.build)
        assertNull(upToDate.waitingFor)
        assertEquals(101, upToDate.newest)
    }

    @Test fun aRollbackGoesToEveryTvAtOnceButANewerTriedBuildWins() {
        // A bad 101 is undone by a re-release (102, rollback): every TV takes it at once.
        val releases = listOf(rel(100, 0.0), rel(101, 30.0), rel(102, 31.0, rollback = true))
        assertEquals(102, Updater.choose(releases, installed = 101, now = at(31.2), eager = false).build)
        assertEquals(102, Updater.choose(releases, installed = 100, now = at(31.2), eager = false).build)
        // An ordinary 103 right after it waits for the test TV; the rollback still comes now.
        val more = releases + rel(103, 32.0)
        assertEquals(102, Updater.choose(more, installed = 100, now = at(32.5), eager = false).build)
        // Once 103 has been the newest for a day it's taken, rollback or not.
        assertEquals(103, Updater.choose(more, installed = 100, now = at(56.5), eager = false).build)
    }

    @Test fun aBurstOfReleasesNeverHandsOtherTvsABuildTheTestTvHasntHadADayWith() {
        // A release every 6 hours for four days. (The 48-hour limit this replaced installed whatever
        // was newest at that moment, however new.)
        val burst = (0 until 16).map { rel(200 + it, it * 6.0) }
        for (h in 0..96) {
            val listed = burst.filter { it.published <= at(h.toDouble()) }
            assertNull("hour $h", Updater.choose(listed, installed = 199, now = at(h.toDouble()), eager = false).build)
        }
        // A day after the burst ends, its last build is taken.
        assertEquals(215, Updater.choose(burst, installed = 199, now = at(15 * 6.0 + 24.1), eager = false).build)
    }

    @Test fun theNewestBuildThatStoodADayIsTakenWhileNewerOnesAreTried() {
        // 156 was the newest for 40 hours, then 157 to 159 came within half an hour.
        val releases = listOf(rel(155, 0.0), rel(156, 0.3), rel(157, 40.4), rel(158, 40.5), rel(159, 40.7))
        assertEquals(156, Updater.choose(releases, installed = 153, now = at(41.0), eager = false).build)
        assertNull(Updater.choose(releases, installed = 156, now = at(41.0), eager = false).build)
        assertEquals(159, Updater.choose(releases, installed = 156, now = at(41.0), eager = false).waitingFor)
    }

    @Test fun aBuildPublishedAfterAHigherNumberedOneIsLeftOut() {
        // 301 finished after 302 (builds finish out of order): it never became the latest.
        val releases = listOf(rel(300, 0.0), rel(302, 1.0), rel(301, 2.0))
        assertEquals(302, Updater.choose(releases, installed = 299, now = at(26.0), eager = false).build)
        assertEquals(302, Updater.choose(releases, installed = 299, now = at(2.5), eager = true).build)
        // And 300 stood only until 302 came out, not until 301 did.
        assertNull(Updater.choose(releases, installed = 299, now = at(24.5), eager = false).build)
    }

    @Test fun aDownloadWaitingForAQuietMomentStaysWhenNewerBuildsArrive() {
        // 156 was chosen and downloaded; then 157 came out. 156 stays ready (it used to be dropped).
        val releases = listOf(rel(155, 0.0), rel(156, 0.3), rel(157, 40.4))
        assertEquals(156, Updater.choose(releases, installed = 153, now = at(41.0), eager = false, ready = 156).build)
        // A download from Check for updates stays too, though the rule alone would take an older build.
        assertEquals(157, Updater.choose(releases, installed = 153, now = at(41.0), eager = false, ready = 157).build)
        // A later build that's been tried replaces it.
        val later = releases + rel(158, 41.0)
        assertEquals(158, Updater.choose(later, installed = 153, now = at(65.5), eager = false, ready = 156).build)
    }

    @Test fun aDownloadTakenDownSinceIsDropped() {
        val releases = listOf(rel(155, 0.0), rel(156, 0.3), rel(157, 40.4))
        // 157 was downloaded with Check for updates, then deleted from GitHub: dropped.
        val deleted = releases.filter { it.build != 157 }
        assertNull(Updater.choose(deleted, installed = 156, now = at(41.0), eager = false, ready = 157).build)
        // Or made a pre-release.
        val pulled = releases.map { if (it.build == 157) it.copy(prerelease = true) else it }
        assertNull(Updater.choose(pulled, installed = 156, now = at(41.0), eager = false, ready = 157).build)
        // Missing only because it's older than everything listed: still a good build, kept.
        val newer = (0 until 30).map { rel(400 + it, 50.0 + it) }
        assertEquals(156, Updater.choose(newer, installed = 153, now = at(90.0), eager = false, ready = 156).build)
    }

    @Test fun aBuildStillUploadingIsntInstalledYetButIsNewer() {
        // 501's APK is still uploading: the test TV waits for it rather than taking 500 now.
        val releases = listOf(rel(500, 0.0), rel(501, 30.0, uploaded = false))
        val testTv = Updater.choose(releases, installed = 499, now = at(30.01), eager = true)
        assertNull(testTv.build)
        assertEquals(501, testTv.waitingFor)
        assertEquals("2.0.501 is on its way", Updater.waitingMessage(501, testTv.arrivesInMs))
        // 500 stood 30 hours, until 501 came out (uploaded or not): taken by the other TVs.
        assertEquals(500, Updater.choose(releases, installed = 499, now = at(30.01), eager = false).build)
        // A build that stood less than a day doesn't look settled just because its successor is uploading.
        val young = listOf(rel(600, 0.0), rel(601, 23.99, uploaded = false))
        assertNull(Updater.choose(young, installed = 599, now = at(24.01), eager = false).build)
    }

    @Test fun preReleasesAreNeverInstalledNorCountedAsNewer() {
        val releases = listOf(rel(700, 0.0), rel(701, 1.0, prerelease = true))
        assertEquals(700, Updater.choose(releases, installed = 699, now = at(24.5), eager = false).build)
        assertEquals(700, Updater.choose(releases, installed = 699, now = at(1.5), eager = true).build)
    }

    @Test fun waitingMessagesRoundUpToWholeHours() {
        assertEquals("2.0.9 arrives in about 1 h (after the test TV)", Updater.waitingMessage(9, 60_000))
        assertEquals("2.0.9 arrives in about 9 h (after the test TV)", Updater.waitingMessage(9, 9 * hour))
        assertEquals("2.0.9 arrives in about 10 h (after the test TV)", Updater.waitingMessage(9, 9 * hour + 1))
    }

    /**
     * The real releases from Oct 14 to Nov 2 2026, replayed for a TV that checks every hour, sees the
     * newest 30 as GitHub lists them, and is never busy. The other TVs get the rollback (67) at once,
     * and otherwise only builds that were the newest for a day: during the Oct 28-29 burst (73 builds,
     * none standing a day) they stay on 79 until 153 has stood a day. The test TV gets each newest.
     */
    @Test fun theRealReleasesReplayed() {
        val all = REAL.map { line ->
            val (build, time) = line.split(' ')
            Release(build.toInt(), Updater.published(time)!!, rollback = line.endsWith("rollback"), files = apk(build.toInt()))
        }
        fun replay(start: Int, eager: Boolean): List<Pair<Int, Double>> {
            var installed = start
            val installs = mutableListOf<Pair<Int, Double>>()
            var now = all.first().published - hour
            val end = all.last().published + 2 * hour
            while (now <= end) {
                val page = all.filter { it.published <= now }.sortedByDescending { it.published }.take(30)
                val build = Updater.choose(page, installed, now, eager).build
                if (build != null && build > installed) {
                    val age = (now - all.first { it.build == build }.published).toDouble() / hour
                    installs += build to age
                    installed = build
                }
                now += hour
            }
            return installs
        }
        val others = replay(52, eager = false)
        assertEquals(listOf(67, 69, 75, 79, 153, 156), others.map { it.first })
        others.filter { it.first != 67 }.forEach { (build, age) -> assertTrue("2.0.$build at $age h", age >= 24.0) }
        // The test TV: every build that stood an hour or more reached it within the hour.
        val testTv = replay(52, eager = true).map { it.first }
        assertEquals(165, testTv.last())
        assertTrue(testTv.containsAll(listOf(67, 69, 75, 79, 118, 153, 156)))
    }

    @Test fun checkProblemsAreSaidInPlainWords() {
        assertEquals("Couldn't connect · trying again soon", Updater.plainCheckError(java.net.UnknownHostException("api.github.com")))
        assertEquals("Couldn't connect · trying again soon", Updater.plainCheckError(java.net.SocketTimeoutException()))
        assertEquals("Couldn't check · trying again soon", Updater.plainCheckError(IllegalStateException("odd JSON")))
    }

    private companion object {
        /** The project's releases from Oct 14 to Nov 2 2026: build, published, and whether it was a rollback. */
        val REAL = listOf(
            "53 2026-10-14T23:10:53Z", "54 2026-10-14T23:14:01Z", "55 2026-10-14T23:54:31Z", "58 2026-10-15T02:21:29Z",
            "59 2026-10-15T02:24:35Z", "60 2026-10-15T02:36:35Z", "61 2026-10-15T02:38:24Z", "62 2026-10-15T02:39:38Z",
            "63 2026-10-15T02:40:34Z", "64 2026-10-15T02:51:02Z", "65 2026-10-15T02:57:58Z", "66 2026-10-15T03:10:38Z",
            "67 2026-10-15T03:19:27Z rollback", "68 2026-10-15T22:03:05Z", "69 2026-10-15T22:18:42Z",
            "70 2026-10-19T03:35:30Z", "71 2026-10-19T03:46:22Z", "72 2026-10-19T04:04:58Z", "73 2026-10-19T12:35:27Z",
            "74 2026-10-19T12:42:38Z", "75 2026-10-19T12:44:12Z", "76 2026-10-25T20:01:10Z", "77 2026-10-25T20:03:57Z",
            "78 2026-10-25T20:22:04Z", "79 2026-10-25T20:31:16Z", "81 2026-10-28T02:21:32Z", "82 2026-10-28T02:25:18Z",
            "83 2026-10-28T02:28:43Z", "84 2026-10-28T02:34:57Z", "85 2026-10-28T02:38:17Z", "86 2026-10-28T02:45:55Z",
            "87 2026-10-28T02:46:30Z", "88 2026-10-28T02:47:07Z", "89 2026-10-28T02:53:38Z", "90 2026-10-28T02:57:13Z",
            "91 2026-10-28T03:30:54Z", "92 2026-10-28T03:38:58Z", "93 2026-10-28T04:04:44Z", "94 2026-10-28T04:21:19Z",
            "95 2026-10-28T04:22:49Z", "96 2026-10-28T04:22:59Z", "97 2026-10-28T04:26:08Z", "98 2026-10-28T04:31:17Z",
            "99 2026-10-28T04:42:58Z", "100 2026-10-28T04:52:11Z", "101 2026-10-28T04:53:55Z", "102 2026-10-28T05:12:38Z",
            "104 2026-10-28T05:20:55Z", "106 2026-10-28T05:32:37Z", "108 2026-10-28T05:40:56Z", "109 2026-10-28T05:42:26Z",
            "110 2026-10-28T05:51:26Z", "111 2026-10-28T05:55:36Z", "112 2026-10-28T06:07:43Z", "113 2026-10-28T07:26:48Z",
            "114 2026-10-28T07:41:00Z", "115 2026-10-28T07:42:15Z", "116 2026-10-28T07:44:45Z", "117 2026-10-28T07:52:15Z",
            "118 2026-10-28T07:55:57Z", "119 2026-10-28T18:14:19Z", "120 2026-10-28T18:31:21Z", "121 2026-10-28T18:56:32Z",
            "122 2026-10-28T19:01:27Z", "123 2026-10-29T00:12:18Z", "124 2026-10-29T00:40:20Z", "125 2026-10-29T00:44:46Z",
            "126 2026-10-29T00:54:37Z", "127 2026-10-29T00:56:25Z", "128 2026-10-29T01:15:31Z", "130 2026-10-29T01:38:26Z",
            "132 2026-10-29T01:44:10Z", "133 2026-10-29T01:51:54Z", "134 2026-10-29T03:12:58Z", "135 2026-10-29T04:33:15Z",
            "136 2026-10-29T04:48:20Z", "137 2026-10-29T05:02:39Z", "138 2026-10-29T05:46:46Z", "140 2026-10-29T06:41:32Z",
            "141 2026-10-29T07:19:45Z", "142 2026-10-29T07:23:20Z", "144 2026-10-29T08:49:39Z", "146 2026-10-29T09:23:38Z",
            "147 2026-10-29T10:15:22Z", "148 2026-10-29T10:18:56Z", "149 2026-10-29T10:55:11Z", "150 2026-10-29T11:08:56Z",
            "151 2026-10-29T16:22:46Z", "152 2026-10-29T16:31:36Z", "153 2026-10-29T17:34:20Z", "155 2026-10-31T05:02:28Z",
            "156 2026-10-31T05:18:49Z", "157 2026-11-01T21:27:02Z", "158 2026-11-01T21:35:15Z", "159 2026-11-01T21:49:23Z",
            "160 2026-11-02T00:16:17Z", "161 2026-11-02T00:28:45Z", "163 2026-11-02T02:27:39Z", "164 2026-11-02T02:32:10Z",
            "165 2026-11-02T02:53:10Z",
        )
    }
}
