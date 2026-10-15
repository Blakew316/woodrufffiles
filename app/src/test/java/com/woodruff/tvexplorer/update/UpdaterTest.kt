package com.woodruff.tvexplorer.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    private val hour = 3_600_000L

    @Test fun otherTvsWaitADayAfterARelease() {
        assertEquals(true, Updater.waits(manual = false, rollback = false, testTv = false, ageMs = 2 * hour))
        assertEquals(false, Updater.waits(manual = false, rollback = false, testTv = false, ageMs = 25 * hour))
    }

    @Test fun testTvRollbackAndManualCheckDontWait() {
        assertEquals(false, Updater.waits(manual = false, rollback = false, testTv = true, ageMs = 0))
        assertEquals(false, Updater.waits(manual = false, rollback = true, testTv = false, ageMs = 0))
        assertEquals(false, Updater.waits(manual = true, rollback = false, testTv = false, ageMs = 0))
        // Releases every few hours never get 24 h old: after 48 h behind, the TV takes the newest anyway.
        assertEquals(true, Updater.waits(manual = false, rollback = false, testTv = false, ageMs = 0, behindMs = 47 * hour))
        assertEquals(false, Updater.waits(manual = false, rollback = false, testTv = false, ageMs = 0, behindMs = 49 * hour))
    }

    @Test fun readsGitHubTimes() {
        assertEquals(1_760_479_584_000L, Updater.published("2025-10-14T22:06:24Z"))
        assertNull(Updater.published("yesterday"))
    }
}
