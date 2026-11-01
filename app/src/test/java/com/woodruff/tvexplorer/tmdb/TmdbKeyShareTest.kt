package com.woodruff.tvexplorer.tmdb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which TMDB key the TVs end up sharing, and that passing it along always stops. */
class TmdbKeyShareTest {
    private val k1 = fingerprint("aaaa1111bbbb2222cccc3333dddd4444")
    private val k2 = fingerprint("eyJhbGciOiJIUzI1NiJ9.read-access-token")
    private val none = KeyVersion("", 0)

    @Test fun fingerprintsCompareKeysWithoutGivingThemAway() {
        assertEquals("", fingerprint(null))
        assertEquals("", fingerprint("   "))
        assertEquals(16, k1.length)
        assertEquals(k1, fingerprint("  aaaa1111bbbb2222cccc3333dddd4444 "))
        assertNotEquals(k1, k2)
        assertFalse("aaaa1111" in k1)
    }

    @Test fun aKeyFromBeforeSharingReachesATvWithout() {
        // The hub had its key before this version (set at 0); the new TV has none.
        val hub = KeyVersion(k1, 0)
        assertEquals(-1, winner(hub, listOf(none)))
        assertTrue(needs(none, hub))
        assertTrue(accepts(none, hub))
        // ...and a TV without a key never wipes the hub's.
        assertFalse(accepts(hub, none))
        assertFalse(needs(hub, none))
    }

    @Test fun theHubFetchesAKeySetOnlyOnAnotherTv() {
        assertEquals(1, winner(none, listOf(none, KeyVersion(k1, 0), null)))
        assertEquals(0, winner(KeyVersion(k1, 100), listOf(KeyVersion(k2, 200))))
    }

    @Test fun theNewestKeyWinsWhereverItWasSet() {
        val older = KeyVersion(k1, 100)
        val newer = KeyVersion(k2, 200)
        assertTrue(accepts(older, newer))
        assertFalse(accepts(newer, older))
        assertTrue(needs(older, newer))
        assertFalse(needs(newer, older))
        assertEquals(1, winner(older, listOf(KeyVersion(k1, 150), newer, null)))
    }

    @Test fun theSameKeyIsLeftAlone() {
        // Even when the TVs got it at different times.
        assertFalse(accepts(KeyVersion(k1, 0), KeyVersion(k1, 300)))
        assertFalse(needs(KeyVersion(k1, 300), KeyVersion(k1, 0)))
        assertEquals(-1, winner(KeyVersion(k1, 0), listOf(KeyVersion(k1, 300))))
    }

    @Test fun removingTheKeyLaterRemovesItEverywhere() {
        val removed = KeyVersion("", 500)
        assertTrue(accepts(KeyVersion(k1, 100), removed))
        assertEquals(0, winner(KeyVersion(k1, 100), listOf(removed)))
        // But a key set after that comes back.
        assertTrue(accepts(removed, KeyVersion(k2, 600)))
    }

    @Test fun twoKeysFromBeforeSharingGoTheHubsWay() {
        // Both set at 0: the hub (the one checking) keeps its own and the other TV takes it.
        val hub = KeyVersion(k1, 0)
        val other = KeyVersion(k2, 0)
        assertEquals(-1, winner(hub, listOf(other)))
        assertTrue(needs(other, hub))
        assertTrue(accepts(other, hub))
    }

    @Test fun offTvsAreSkipped() {
        assertEquals(-1, winner(KeyVersion(k1, 100), listOf(null, null)))
    }

    // ---- A house of TVs, run with the same rules as the app ----------------------------------

    /** Each TV's key, and the TVs each one is linked to (it holds their link keys). */
    private class House(val keys: MutableMap<String, KeyVersion>, val links: Map<String, List<String>>) {
        var messages = 0

        /** A TV takes [v] if it's newer, then passes it to the TVs linked to it (TmdbKeyShare.receive). */
        fun deliver(to: String, v: KeyVersion) {
            val queue = ArrayDeque(listOf(to to v))
            while (queue.isNotEmpty()) {
                val (tv, version) = queue.removeFirst()
                messages++
                check(messages < 1_000) { "the key kept bouncing between TVs" }
                if (!accepts(keys.getValue(tv), version)) continue
                keys[tv] = version
                links[tv].orEmpty().forEach { queue.add(it to version) }
            }
        }

        /** Someone sets [v] on [tv] (TmdbKeyShare.set): saved there, sent to its linked TVs. */
        fun set(tv: String, v: KeyVersion) {
            keys[tv] = v
            links[tv].orEmpty().forEach { deliver(it, v) }
        }

        /** [hub]'s check of its linked TVs (TmdbKeyShare.sync); [off]: TVs that don't answer. */
        fun sync(hub: String, off: Set<String> = emptySet()) {
            val tvs = links[hub].orEmpty()
            val theirs = tvs.map { if (it in off) null else keys.getValue(it) }
            val w = winner(keys.getValue(hub), theirs)
            if (w >= 0 && accepts(keys.getValue(hub), theirs[w]!!)) keys[hub] = theirs[w]!!
            val mine = keys.getValue(hub)
            tvs.forEachIndexed { i, tv -> val v = theirs[i] ?: return@forEachIndexed; if (needs(v, mine)) deliver(tv, mine) }
        }

        fun fingerprints() = keys.values.map { it.fingerprint }.toSet()
    }

    @Test fun aKeySetOnTheHubReachesEveryTv() {
        val house = House(
            mutableMapOf("hub" to none, "sony" to none, "kids" to none),
            mapOf("hub" to listOf("sony", "kids")),
        )
        house.set("hub", KeyVersion(k1, 100))
        assertEquals(setOf(k1), house.fingerprints())
    }

    @Test fun aKeySetOnATvTheHubLinksToReachesTheRestAtTheHubsCheck() {
        // The Sony knows no other TV, so its key waits for the hub to ask.
        val house = House(
            mutableMapOf("hub" to KeyVersion(k1, 0), "sony" to none, "kids" to KeyVersion(k1, 0)),
            mapOf("hub" to listOf("sony", "kids")),
        )
        house.set("sony", KeyVersion(k2, 200))
        assertEquals(KeyVersion(k1, 0), house.keys["hub"])
        house.sync("hub")
        assertEquals(setOf(k2), house.fingerprints())
    }

    @Test fun aTvThatWasOffCatchesUpLater() {
        val house = House(
            mutableMapOf("hub" to KeyVersion(k1, 0), "sony" to none),
            mapOf("hub" to listOf("sony")),
        )
        house.sync("hub", off = setOf("sony"))
        assertEquals(none, house.keys["sony"])
        house.sync("hub")
        assertEquals(KeyVersion(k1, 0), house.keys["sony"])
    }

    @Test fun linksBothWaysAndChainsStopPassingItOn() {
        // A ↔ B, B → C, C → A: a loop, and links both ways.
        val house = House(
            mutableMapOf("a" to none, "b" to KeyVersion(k1, 50), "c" to none),
            mapOf("a" to listOf("b"), "b" to listOf("a", "c"), "c" to listOf("a")),
        )
        house.set("c", KeyVersion(k2, 300))
        assertEquals(setOf(k2), house.fingerprints())
        assertTrue(house.messages < 10)
        // Checks afterwards change nothing and send nothing.
        val before = house.messages
        listOf("a", "b", "c").forEach { house.sync(it) }
        assertEquals(before, house.messages)
    }
}
