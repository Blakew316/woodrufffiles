package com.woodruff.tvexplorer.util

import android.app.ApplicationExitInfo
import com.woodruff.tvexplorer.util.EarlyCrashes.Exit
import com.woodruff.tvexplorer.util.EarlyCrashes.Record
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The rules for counting closes while starting ([EarlyCrashes]), without Android: how each event
 * changes the record, and how it's written down.
 */
class EarlyCrashesTest {
    private val v = 172

    @Test fun eachCloseWhileStartingIsOneMoreInARow() {
        val one = EarlyCrashes.afterCrash(Record(), v)
        assertEquals(Record(v, 1), one)
        assertEquals(Record(v, 2), EarlyCrashes.afterCrash(one, v))
        assertEquals(2, EarlyCrashes.countFor(EarlyCrashes.afterCrash(one, v), v))
    }

    @Test fun aNewVersionStartsAfresh() {
        val old = Record(171, 5)
        assertEquals(0, EarlyCrashes.countFor(old, v))
        assertEquals(Record(v, 1), EarlyCrashes.afterCrash(old, v))
        assertEquals(Record(v, 0, 1_000, 42), EarlyCrashes.afterOpen(old, v, 1_000, 42))
    }

    @Test fun openingKeepsTheCountUntilTheScreenHasStayedUp() {
        val opened = EarlyCrashes.afterOpen(Record(v, 1), v, at = 1_000, pid = 42)
        assertEquals(Record(v, 1, 1_000, 42), opened)
        // 45 seconds up: Woodruff starts fine, so none in a row (and nothing left to ask Android about).
        assertEquals(Record(v), EarlyCrashes.afterSettle(opened, v))
    }

    @Test fun tryOpeningAgainLeavesOneSoTheNextCloseBringsTheScreenBack() {
        val after = EarlyCrashes.afterTryAgain(Record(v, 2), v)
        assertEquals(1, after.count)
        assertEquals(EarlyCrashes.RECOVER_AT, EarlyCrashes.afterCrash(after, v).count)
    }

    @Test fun aNativeCrashOrAFreezeBeforeTheScreenStayedUpCounts() {
        val pending = Record(v, 1, openedAt = 1_000, pid = 42)
        for (exit in listOf(Exit.CRASH, Exit.NATIVE_CRASH, Exit.FROZE)) {
            assertEquals(exit.name, Record(v, 2), EarlyCrashes.afterExit(pending, v, exit))
        }
    }

    @Test fun anyOtherEndIsntAClose() {
        // Android closing it for memory, the TV turning off, someone stopping it: not counted, not asked again.
        val pending = Record(v, 1, openedAt = 1_000, pid = 42)
        assertEquals(Record(v, 1), EarlyCrashes.afterExit(pending, v, Exit.OTHER))
        assertEquals(Record(v, 1), EarlyCrashes.afterExit(pending, v, null))
        // Nothing pending: nothing to ask.
        assertEquals(Record(v, 1), EarlyCrashes.afterExit(Record(v, 1), v, Exit.NATIVE_CRASH))
        // Another version's: this one starts afresh, whatever happened to that one.
        assertEquals(Record(v), EarlyCrashes.afterExit(Record(171, 1, 1_000, 42), v, Exit.NATIVE_CRASH))
    }

    @Test fun androidsReasonsAreReadAsClosesOnlyForCrashesAndFreezes() {
        assertEquals(Exit.CRASH, EarlyCrashes.exitOf(ApplicationExitInfo.REASON_CRASH))
        assertEquals(Exit.NATIVE_CRASH, EarlyCrashes.exitOf(ApplicationExitInfo.REASON_CRASH_NATIVE))
        assertEquals(Exit.FROZE, EarlyCrashes.exitOf(ApplicationExitInfo.REASON_ANR))
        for (other in listOf(ApplicationExitInfo.REASON_LOW_MEMORY, ApplicationExitInfo.REASON_USER_REQUESTED, ApplicationExitInfo.REASON_PACKAGE_UPDATED, ApplicationExitInfo.REASON_SIGNALED, ApplicationExitInfo.REASON_UNKNOWN)) {
            assertEquals(Exit.OTHER, EarlyCrashes.exitOf(other))
        }
    }

    @Test fun theFileKeepsOnlyWhatMatters() {
        val r = Record(v, 2, 1_762_000_000_000, 4242)
        assertEquals(r, EarlyCrashes.parse(EarlyCrashes.format(r)))
        // Nothing in a row and nothing pending: no file at all.
        assertNull(EarlyCrashes.format(Record(v)))
        assertNull(EarlyCrashes.format(Record()))
        // Damaged or from some other format: as if there were none.
        for (bad in listOf(null, "", "garbage", "2 172 1 0 0", "1 172 x 0 0", "1 172")) {
            assertEquals(bad.toString(), Record(), EarlyCrashes.parse(bad))
        }
    }
}
