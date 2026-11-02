package com.woodruff.tvexplorer.tvs

import com.woodruff.tvexplorer.cloud.GoogleAuth
import com.woodruff.tvexplorer.notify.boardCatchUp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** A TV reset or reinstalled is linked again as one TV, not a ghost and its twin (finding 55). */
class TvRelinkTest {
    private val den = LinkedTv("den-id", "Den", "http://den.local:8080", "key1")
    private val kitchen = LinkedTv("kitchen-id", "Kitchen", "http://kitchen.local:8080", "key2")
    private val bedroom = LinkedTv("bed-id", "Bedroom", "http://bedroom.local:8080", "key3")

    @Test fun aTvLinkedAgainReplacesItsOwnEntryInPlace() {
        val again = den.copy(base = "http://den-2.local:8080", key = "new")
        val (list, gone) = linkedAfter(listOf(den, kitchen), again, null, emptySet())
        assertEquals(listOf(again, kitchen), list)
        assertEquals(setOf("den-id"), gone)
    }

    @Test fun linkingAgainFromItsReportReplacesTheOldEntry() {
        // Reinstalled: a new id (and, until renamed, a new name), at its old address.
        val reset = LinkedTv("new-id", "BRAVIA 4K TV", den.base, "fresh")
        val (list, gone) = linkedAfter(listOf(kitchen, den, bedroom), reset, replacing = "den-id", refused = emptySet())
        assertEquals(listOf(kitchen, reset, bedroom), list)
        assertEquals(setOf("den-id"), gone)
    }

    @Test fun aRefusingEntryGivesWayToTheTvLinkedWithItsNameOrAddress() {
        val byName = LinkedTv("new-id", "Den (2)", "http://den-3.local:8080", "fresh")
        assertEquals(listOf(kitchen, byName), linkedAfter(listOf(kitchen, den), byName, null, refused = setOf("den-id")).first)
        val byAddress = LinkedTv("new-id", "BRAVIA 4K TV", den.base, "fresh")
        assertEquals(listOf(byAddress, kitchen), linkedAfter(listOf(den, kitchen), byAddress, null, refused = setOf("den-id")).first)
        // A TV that still takes its link never gives way to another by the same name.
        val twin = LinkedTv("other-id", "Den", "http://den-4.local:8080", "k")
        assertEquals(listOf(den, kitchen, twin), linkedAfter(listOf(den, kitchen), twin, null, refused = emptySet()).first)
    }

    @Test fun aRefusalCountsOnlyWhereAWoodruffTvIsAdvertised() {
        val suspects = mapOf("den-id" to den.base, "kitchen-id" to kitchen.base)
        // The Woodruff advertised at Den's address (whatever it's called now) turned the key down.
        assertEquals(listOf("den-id"), refusedHere(TvLinks.Found("BRAVIA 4K TV", den.base, "new-id"), setOf("den-id", "kitchen-id"), suspects))
        // Elsewhere, or a TV not being looked for: nothing.
        assertEquals(emptyList<String>(), refusedHere(TvLinks.Found("Den", "http://elsewhere.local:8080", "den-id"), setOf("den-id"), suspects))
        assertEquals(emptyList<String>(), refusedHere(TvLinks.Found("Den", den.base, "den-id"), setOf("kitchen-id"), suspects))
    }

    @Test fun aKeyTurnedDownIsToldApart() {
        assertTrue(failure(401, "Scan the QR code on the TV") is TvLinks.KeyRefused)
        assertEquals("HTTP 401", failure(401, null).message)
    }

    @Test fun twoTvsByOneNameMeanTheOneHeardFromLast() {
        val ghost = LinkedTv("ghost", "Den", "http://den.local:8080", "old")
        val fresh = LinkedTv("fresh", "Den", "http://den-2.local:8080", "new")
        val t = pickTargets(listOf("Den"), TvTargets.HERE, "me", "Living Room", listOf(ghost, fresh), seen = mapOf("ghost" to 1_000L, "fresh" to 9_000L))
        assertEquals(TvTargets(false, setOf("fresh")), t)
        // Nothing heard from either: the first, as before.
        assertEquals(TvTargets(false, setOf("ghost")), pickTargets(listOf("Den"), TvTargets.HERE, "me", "Living Room", listOf(ghost, fresh)))
        // By id it's always that one.
        assertEquals(TvTargets(false, setOf("ghost")), pickTargets(listOf("ghost"), TvTargets.HERE, "me", "Living Room", listOf(ghost, fresh), seen = mapOf("fresh" to 9_000L)))
    }

    @Test fun pinnedMessagesCatchUpTvsThatWereOff() {
        val pinned = 1_000_000L
        val statuses = listOf(
            TvStatus("this", "Living Room", true, true, null, board = pinned),
            TvStatus("den", "Den", true, false, null, board = 0L),           // was off: never had it
            TvStatus("kitchen", "Kitchen", true, false, null, board = pinned), // has it (or dismissed it)
            TvStatus("bed", "Bedroom", false, false, null, board = 0L),        // off now: next time
            TvStatus("old", "Garage", true, false, null, board = null),        // an older Woodruff: never again
            TvStatus("gone", "Attic", false, false, null, refused = true, board = 0L),
        )
        assertEquals(listOf("den"), boardCatchUp(pinned, pinned + 3_600_000L, statuses))
        // A newer pin there beats this one; after a week, nothing.
        assertEquals(emptyList<String>(), boardCatchUp(pinned, pinned + 3_600_000L, listOf(statuses[0], statuses[1].copy(board = pinned + 5))))
        assertEquals(emptyList<String>(), boardCatchUp(pinned, pinned + 7 * 24 * 3_600_000L, statuses))
    }

    @Test fun aSignInsStateFromHowItsTokenLastWent() {
        val now = 10_000_000L
        assertEquals(SignInState.SIGNED_IN, SignIns.connectedState(null, networkUp = true, now = now))
        assertEquals(SignInState.OFFLINE, SignIns.connectedState(null, networkUp = false, now = now))
        assertEquals(SignInState.UNREACHABLE, SignIns.connectedState(GoogleAuth.LastAsk(offline = true, at = now - 60_000, networkUp = true), true, now))
        // It failed while there was no network, which is back now: it will work by itself.
        assertEquals(SignInState.SIGNED_IN, SignIns.connectedState(GoogleAuth.LastAsk(true, now - 60_000, networkUp = false), true, now))
        // Long ago: the next try decides.
        assertEquals(SignInState.SIGNED_IN, SignIns.connectedState(GoogleAuth.LastAsk(true, now - 20 * 60_000, true), true, now))
        // A renewal that got through, then the network went: offline. (Keys Google turns down are kept with the
        // sign-in itself, so they outlast a restart: SignInRecordsTest.)
        assertEquals(SignInState.OFFLINE, SignIns.connectedState(GoogleAuth.LastAsk(false, now - 60_000, true), false, now))
    }
}
