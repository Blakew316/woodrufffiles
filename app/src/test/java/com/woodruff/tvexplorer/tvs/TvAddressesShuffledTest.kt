package com.woodruff.tvexplorer.tvs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * After a power cut the router may hand the TVs their addresses afresh, one TV's old address to another. A TV that
 * turns down a link key at an old address is then often just another linked TV that took it: the TV looked for is
 * fine at its new address, and must neither be called "Link again" nor be impossible to link again (finding 55's
 * refusal, told apart from a reshuffle).
 */
class TvAddressesShuffledTest {
    private val denOld = "http://address-1.local:8080"
    private val denNew = "http://address-2.local:8080"
    private val den = LinkedTv("den-id", "Den", denOld, "den-key")
    private val kitchen = LinkedTv("kitchen-id", "Kitchen", "http://address-3.local:8080", "kitchen-key")
    private val linked = listOf(den, kitchen)
    /** Den's status check got a 401 at its old address. */
    private val suspects = mapOf("den-id" to denOld)

    @Test fun anotherLinkedTvAtTheAddressIsntTheTvThatTurnsItsLinkDown() {
        // Kitchen took Den's old address: it turns Den's key down, but it's Kitchen.
        assertEquals(emptyList<String>(), refusedHere(TvLinks.Found("Kitchen", denOld, "kitchen-id"), setOf("den-id"), suspects, linked))
        // An older Woodruff advertises no id: its name says it's Kitchen.
        assertEquals(emptyList<String>(), refusedHere(TvLinks.Found("Kitchen (2)", denOld, null), setOf("den-id"), suspects, linked))
    }

    @Test fun denItselfOrATvResetThereStillTurnsTheLinkDown() {
        // Den, its link key reset ("Stop other TVs seeing this one"): its own id.
        assertEquals(listOf("den-id"), refusedHere(TvLinks.Found("Den", denOld, "den-id"), setOf("den-id"), suspects, linked))
        // Reset or reinstalled: a new id, and maybe the model's name.
        assertEquals(listOf("den-id"), refusedHere(TvLinks.Found("BRAVIA 4K TV", denOld, "new-id"), setOf("den-id"), suspects, linked))
        assertEquals(listOf("den-id"), refusedHere(TvLinks.Found("BRAVIA 4K TV", denOld, null), setOf("den-id"), suspects, linked))
        // An older Woodruff called Den: it may be Den, whichever other TV has that name too.
        val twin = LinkedTv("twin-id", "Den", "http://address-4.local:8080", "twin-key")
        assertEquals(listOf("den-id"), refusedHere(TvLinks.Found("Den", denOld, null), setOf("den-id"), suspects, linked + twin))
    }

    @Test fun aTvFoundAtItsNewAddressIsMovedNotRefused() {
        val search = LinkSearch(setOf("den-id"), suspects) { linked }
        val keyWorks = mapOf("kitchen-id" to denOld, "den-id" to denNew)
        val isTv = { tv: LinkedTv, base: String -> keyWorks[tv.id] == base }
        // Kitchen turns up first, at Den's old address: not Den, and not refusing.
        assertNull(search.seen(TvLinks.Found("Kitchen", denOld, "kitchen-id"), isTv))
        // Then Den, at its new address, taking its own key.
        assertEquals(den, search.seen(TvLinks.Found("Den", denNew, "den-id"), isTv))
        assertTrue(search.done)
        assertEquals(emptySet<String>(), search.refused())
    }

    @Test fun anOldRecordOfTheTvAtItsOldAddressDoesntOutweighItAnsweringAtItsNewOne() {
        val search = LinkSearch(setOf("den-id"), suspects) { linked }
        val isTv = { tv: LinkedTv, base: String -> tv.id == "den-id" && base == denNew }
        // The network still lists Den at its old address (where its key is turned down)...
        assertNull(search.seen(TvLinks.Found("Den", denOld, "den-id"), isTv))
        assertFalse(search.done)
        // ...and at its new one, where its key works.
        assertEquals(den, search.seen(TvLinks.Found("Den", denNew, "den-id"), isTv))
        assertEquals(emptySet<String>(), search.refused())
    }

    @Test fun aTvThatTurnsItsLinkDownAndTurnsUpNowhereElseIsRefusedOnceTheLookIsOver() {
        val search = LinkSearch(setOf("den-id", "kitchen-id"), suspects) { linked }
        val isTv = { tv: LinkedTv, base: String -> tv.id == "kitchen-id" && base == kitchen.base }
        assertNull(search.seen(TvLinks.Found("Den", denOld, "den-id"), isTv))
        assertEquals(kitchen, search.seen(TvLinks.Found("Kitchen", kitchen.base, "kitchen-id"), isTv))
        assertFalse(search.done)
        assertEquals(setOf("den-id"), search.refused())
        // A TV only off (no 401 came from it) is never refused.
        val off = LinkSearch(setOf("kitchen-id"), emptyMap()) { linked }
        assertNull(off.seen(TvLinks.Found("Kitchen", kitchen.base, "kitchen-id")) { _, _ -> false })
        assertEquals(emptySet<String>(), off.refused())
    }

    @Test fun linkingAgainTriesTheTvWhereItIsNow() {
        // The code turned down at the saved address: another TV may be there now. Where the network says the TV
        // is, unless that's the very same address (then the code was wrong).
        assertEquals(denNew, relinkAt(codeTurnedDown = true, saved = denOld, found = denNew))
        assertNull(relinkAt(codeTurnedDown = true, saved = denOld, found = denOld))
        assertNull(relinkAt(codeTurnedDown = true, saved = denOld, found = null))
        // Not reached there: wherever it is now, as before.
        assertEquals(denNew, relinkAt(codeTurnedDown = false, saved = denOld, found = denNew))
        assertEquals(denOld, relinkAt(codeTurnedDown = false, saved = denOld, found = denOld))
        assertNull(relinkAt(codeTurnedDown = false, saved = denOld, found = null))
    }
}
