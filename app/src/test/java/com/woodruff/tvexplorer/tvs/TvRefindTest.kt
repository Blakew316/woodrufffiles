package com.woodruff.tvexplorer.tvs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A linked TV whose address changed (a new router, a power cut) is found again on the network. */
class TvRefindTest {
    private val kids = LinkedTv("kids-id", "Kids Room", "http://192.168.1.21:8080", "key1")
    private val bedroom = LinkedTv("bed-id", "Bedroom", "http://192.168.1.22:8080", "key2")
    private val guestBedroom = LinkedTv("guest-id", "Bedroom", "http://192.168.1.23:8080", "key3")
    private val newAddress = "http://192.168.1.40:8090"

    private fun found(name: String, id: String? = null) = TvLinks.Found(name, newAddress, id)

    // ---- Which TV an advertisement is ----------------------------------------------------------

    @Test fun theIdComesFromTheTxtRecord() {
        assertEquals("kids-id", txtId(mapOf("id" to "kids-id".toByteArray())))
        // TXT keys are case-insensitive; stray spaces don't count.
        assertEquals("kids-id", txtId(mapOf("ID" to " kids-id ".toByteArray(), "other" to "x".toByteArray())))
    }

    @Test fun olderVersionsAdvertiseNoId() {
        assertNull(txtId(emptyMap()))
        assertNull(txtId(mapOf("id" to null)))
        assertNull(txtId(mapOf("id" to " ".toByteArray())))
    }

    @Test fun androidsRenameSuffixIsIgnored() {
        assertEquals("Living Room TV", withoutRenameSuffix("Living Room TV (2)"))
        assertEquals("Living Room TV", withoutRenameSuffix("Living Room TV (13)"))
        assertEquals("Living Room TV", withoutRenameSuffix("Living Room TV"))
        // Only a suffix like Android's, at the very end.
        assertEquals("Den (2) TV", withoutRenameSuffix("Den (2) TV"))
        assertEquals("TV(2)", withoutRenameSuffix("TV(2)"))
        assertEquals("Kids Room 2", withoutRenameSuffix("Kids Room 2"))
    }

    @Test fun namesMatchDespiteTheSuffixOrCase() {
        assertTrue(sameTvName("Kids Room", "Kids Room"))
        assertTrue(sameTvName("Kids Room (2)", "Kids Room"))
        assertTrue(sameTvName("kids room", "Kids Room"))
        // A TV whose own name ends in "(2)", renamed again by Android.
        assertTrue(sameTvName("Den (3)", "Den (2)"))
        assertFalse(sameTvName("Kids Room 2", "Kids Room"))
        assertFalse(sameTvName("Bedroom", "Kids Room"))
    }

    @Test fun anAdvertisedIdPicksThatTvWhateverItsName() {
        assertEquals(listOf(kids), candidatesFor(found("Playroom", "kids-id"), listOf(kids, bedroom)))
    }

    @Test fun anotherIdIsNeverTheTvEvenWithItsName() {
        assertEquals(emptyList<LinkedTv>(), candidatesFor(found("Kids Room", "other-id"), listOf(kids, bedroom)))
    }

    @Test fun withoutAnIdTheNameDecides() {
        assertEquals(listOf(kids), candidatesFor(found("Kids Room (2)"), listOf(kids, bedroom)))
        assertEquals(emptyList<LinkedTv>(), candidatesFor(found("Garage"), listOf(kids, bedroom)))
    }

    @Test fun twoTvsWithTheSameNameAreBothTried() {
        // The link key check (each TV's own) then tells them apart.
        assertEquals(listOf(bedroom, guestBedroom), candidatesFor(found("Bedroom (2)"), listOf(kids, bedroom, guestBedroom)))
    }

    @Test fun onlyTvsThatStoppedAnsweringAreCandidates() {
        assertEquals(emptyList<LinkedTv>(), candidatesFor(found("Kids Room", "kids-id"), listOf(bedroom)))
        assertEquals(emptyList<LinkedTv>(), candidatesFor(found("Kids Room"), emptyList()))
    }

    // ---- How often to look ---------------------------------------------------------------------

    @Test fun aTvIsLookedForAtMostEverySoOften() {
        assertTrue(refindDue(null, 1_000, 180_000))
        assertFalse(refindDue(1_000, 100_000, 180_000))
        assertTrue(refindDue(1_000, 181_000, 180_000))
        // The clock went back: don't wait for it to catch up.
        assertTrue(refindDue(500_000, 1_000, 180_000))
    }

    @Test fun oneSearchAtATimeCoversEveryTvThatStoppedAnswering() {
        val r = Refinds(180_000) { 0L }
        assertTrue(r.lost("kids-id", null))
        // Fails before the search starts: looked for in the same one.
        assertFalse(r.lost("bed-id", null))
        assertEquals(setOf("kids-id", "bed-id"), r.next())
        // Fails during it: in the next one.
        assertFalse(r.lost("guest-id", null))
        assertEquals(setOf("guest-id"), r.next())
        assertNull(r.next())
        // Searching stopped: the next TV that fails starts a search again.
        assertTrue(r.lost("attic-id", null))
    }

    @Test fun aTvAlreadyBeingLookedForIsNotLookedForTwice() {
        val r = Refinds(180_000) { 0L }
        assertTrue(r.lost("kids-id", null))
        assertFalse(r.lost("kids-id", null))
        assertEquals(setOf("kids-id"), r.next())
        assertFalse(r.lost("kids-id", null))
        assertNull(r.next())
    }

    @Test fun aTvIsLookedForAgainOnlyAfterAFewMinutes() {
        var now = 0L
        val r = Refinds(180_000) { now }
        assertTrue(r.lost("kids-id", null))
        r.next()
        assertNull(r.next())
        now = 60_000
        assertFalse(r.lost("kids-id", null))
        assertNull(r.next())
        now = 180_000
        assertTrue(r.lost("kids-id", null))
        assertEquals(setOf("kids-id"), r.next())
    }

    // ---- What's sent again ---------------------------------------------------------------------

    @Test fun whatFailedGoesOnceToTheNewAddress() {
        val r = Refinds(180_000) { 0L }
        val sent = mutableListOf<String>()
        r.lost("kids-id") { sent += "doorbell to ${it.base}" }
        // Failed again while waiting: goes too.
        r.lost("kids-id") { sent += "notice to ${it.base}" }
        // A key press: nothing to send again.
        r.lost("kids-id", null)
        r.next()
        r.found("kids-id").forEach { it(kids.copy(base = newAddress)) }
        assertEquals(listOf("doorbell to $newAddress", "notice to $newAddress"), sent)
        assertTrue(r.found("kids-id").isEmpty())
    }

    @Test fun aMessageFailingDuringTheSearchGoesTooIfTheTvTurnsUp() {
        val r = Refinds(180_000) { 0L }
        var sent = 0
        r.lost("kids-id", null)
        r.next()
        r.lost("kids-id") { sent++ }
        r.found("kids-id").forEach { it(kids) }
        assertEquals(1, sent)
    }

    @Test fun whatATvThatWasntFoundMissedIsDropped() {
        val r = Refinds(180_000) { 0L }
        var sent = 0
        r.lost("kids-id") { sent++ }
        assertEquals(setOf("kids-id"), r.next())
        // The search ended without finding it (off, or out of reach).
        assertNull(r.next())
        assertTrue(r.found("kids-id").isEmpty())
        assertEquals(0, sent)
    }

    @Test fun aTvThatWasntLookedForHasNothingToSend() {
        val r = Refinds(180_000) { 0L }
        r.lost("kids-id") { }
        r.next()
        assertTrue(r.found("bed-id").isEmpty())
    }
}
