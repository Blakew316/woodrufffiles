package com.woodruff.tvexplorer.tvs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A linked TV whose address changed (a new router, a power cut) is found again on the network. */
class TvRefindTest {
    private val family = LinkedTv("family-id", "Family Room", "http://192.168.1.21:8080", "key1")
    private val bedroom = LinkedTv("bed-id", "Bedroom", "http://192.168.1.22:8080", "key2")
    private val guestBedroom = LinkedTv("guest-id", "Bedroom", "http://192.168.1.23:8080", "key3")
    private val newAddress = "http://192.168.1.40:8090"

    private fun found(name: String, id: String? = null) = TvLinks.Found(name, newAddress, id)

    // ---- Which TV an advertisement is ----------------------------------------------------------

    @Test fun theIdComesFromTheTxtRecord() {
        assertEquals("family-id", txtId(mapOf("id" to "family-id".toByteArray())))
        // TXT keys are case-insensitive; stray spaces don't count.
        assertEquals("family-id", txtId(mapOf("ID" to " family-id ".toByteArray(), "other" to "x".toByteArray())))
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
        assertEquals("Family Room 2", withoutRenameSuffix("Family Room 2"))
    }

    @Test fun namesMatchDespiteTheSuffixOrCase() {
        assertTrue(sameTvName("Family Room", "Family Room"))
        assertTrue(sameTvName("Family Room (2)", "Family Room"))
        assertTrue(sameTvName("family room", "Family Room"))
        // A TV whose own name ends in "(2)", renamed again by Android.
        assertTrue(sameTvName("Den (3)", "Den (2)"))
        assertFalse(sameTvName("Family Room 2", "Family Room"))
        assertFalse(sameTvName("Bedroom", "Family Room"))
    }

    @Test fun anAdvertisedIdPicksThatTvWhateverItsName() {
        assertEquals(listOf(family), candidatesFor(found("Playroom", "family-id"), listOf(family, bedroom)))
    }

    @Test fun anotherIdIsNeverTheTvEvenWithItsName() {
        assertEquals(emptyList<LinkedTv>(), candidatesFor(found("Family Room", "other-id"), listOf(family, bedroom)))
    }

    @Test fun withoutAnIdTheNameDecides() {
        assertEquals(listOf(family), candidatesFor(found("Family Room (2)"), listOf(family, bedroom)))
        assertEquals(emptyList<LinkedTv>(), candidatesFor(found("Garage"), listOf(family, bedroom)))
    }

    @Test fun twoTvsWithTheSameNameAreBothTried() {
        // The link key check (each TV's own) then tells them apart.
        assertEquals(listOf(bedroom, guestBedroom), candidatesFor(found("Bedroom (2)"), listOf(family, bedroom, guestBedroom)))
    }

    @Test fun onlyTvsThatStoppedAnsweringAreCandidates() {
        assertEquals(emptyList<LinkedTv>(), candidatesFor(found("Family Room", "family-id"), listOf(bedroom)))
        assertEquals(emptyList<LinkedTv>(), candidatesFor(found("Family Room"), emptyList()))
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
        assertTrue(r.lost("family-id", null))
        // Fails before the search starts: looked for in the same one.
        assertFalse(r.lost("bed-id", null))
        assertEquals(setOf("family-id", "bed-id"), r.next())
        // Fails during it: in the next one.
        assertFalse(r.lost("guest-id", null))
        assertEquals(setOf("guest-id"), r.next())
        assertNull(r.next())
        // Searching stopped: the next TV that fails starts a search again.
        assertTrue(r.lost("attic-id", null))
    }

    @Test fun aTvAlreadyBeingLookedForIsNotLookedForTwice() {
        val r = Refinds(180_000) { 0L }
        assertTrue(r.lost("family-id", null))
        assertFalse(r.lost("family-id", null))
        assertEquals(setOf("family-id"), r.next())
        assertFalse(r.lost("family-id", null))
        assertNull(r.next())
    }

    @Test fun aTvIsLookedForAgainOnlyAfterAFewMinutes() {
        var now = 0L
        val r = Refinds(180_000) { now }
        assertTrue(r.lost("family-id", null))
        r.next()
        assertNull(r.next())
        now = 60_000
        assertFalse(r.lost("family-id", null))
        assertNull(r.next())
        now = 180_000
        assertTrue(r.lost("family-id", null))
        assertEquals(setOf("family-id"), r.next())
    }

    // ---- What's sent again ---------------------------------------------------------------------

    @Test fun whatFailedGoesOnceToTheNewAddress() {
        val r = Refinds(180_000) { 0L }
        val sent = mutableListOf<String>()
        r.lost("family-id") { sent += "doorbell to ${it.base}" }
        // Failed again while waiting: goes too.
        r.lost("family-id") { sent += "notice to ${it.base}" }
        // A key press: nothing to send again.
        r.lost("family-id", null)
        r.next()
        r.found("family-id").forEach { it(family.copy(base = newAddress)) }
        assertEquals(listOf("doorbell to $newAddress", "notice to $newAddress"), sent)
        assertTrue(r.found("family-id").isEmpty())
    }

    @Test fun aMessageFailingDuringTheSearchGoesTooIfTheTvTurnsUp() {
        val r = Refinds(180_000) { 0L }
        var sent = 0
        r.lost("family-id", null)
        r.next()
        r.lost("family-id") { sent++ }
        r.found("family-id").forEach { it(family) }
        assertEquals(1, sent)
    }

    @Test fun whatATvThatWasntFoundMissedIsDropped() {
        val r = Refinds(180_000) { 0L }
        var sent = 0
        r.lost("family-id") { sent++ }
        assertEquals(setOf("family-id"), r.next())
        // The search ended without finding it (off, or out of reach).
        assertNull(r.next())
        assertTrue(r.found("family-id").isEmpty())
        assertEquals(0, sent)
    }

    @Test fun aTvThatWasntLookedForHasNothingToSend() {
        val r = Refinds(180_000) { 0L }
        r.lost("family-id") { }
        r.next()
        assertTrue(r.found("bed-id").isEmpty())
    }
}
