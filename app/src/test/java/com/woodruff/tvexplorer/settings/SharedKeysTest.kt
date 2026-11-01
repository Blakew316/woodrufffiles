package com.woodruff.tvexplorer.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which keys the TVs end up sharing, and that passing them along always stops. */
class SharedKeysTest {
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

    @Test fun theGoogleKeysTravelAsOnePair() {
        val v = googleValue(" 123-abc.apps.googleusercontent.com ", "GOCSPX-secret ")
        assertEquals("123-abc.apps.googleusercontent.com\nGOCSPX-secret", v)
        assertEquals("123-abc.apps.googleusercontent.com" to "GOCSPX-secret", parseGoogle(v))
        // Half a pair is no pair: it's never shared, and never taken.
        assertNull(googleValue("123-abc.apps.googleusercontent.com", null))
        assertNull(googleValue("", "GOCSPX-secret"))
        assertNull(parseGoogle(null))
        assertNull(parseGoogle("123-abc.apps.googleusercontent.com"))
        assertNull(parseGoogle("a\nb\nc"))
        assertNull(parseGoogle("\nGOCSPX-secret"))
    }

    @Test fun keysAreKnownByTheirIds() {
        assertEquals(SharedKey.GOOGLE, SharedKey.of("google"))
        assertEquals(SharedKey.CAMERAS, SharedKey.of("cameras"))
        assertNull(SharedKey.of("mail"))
        assertNull(SharedKey.of(null))
        // Each keeps when it was set under its own name.
        assertEquals(SharedKey.entries.size, SharedKey.entries.map { it.atName }.toSet().size)
    }

    @Test fun aKeyFromBeforeSharingReachesATvWithout() {
        // The hub had its key before this version (set at 0); the new TV has none.
        val hub = KeyVersion(k1, 0)
        assertEquals(-1, winner(hub, listOf(none)))
        for (tie in listOf(true, false)) {
            assertTrue(needs(none, hub, tie))
            assertTrue(accepts(none, hub, tie))
            // ...and a TV without a key never wipes the hub's.
            assertFalse(accepts(hub, none, tie))
            assertFalse(needs(hub, none, tie))
        }
    }

    @Test fun theHubFetchesAKeySetOnlyOnAnotherTv() {
        assertEquals(1, winner(none, listOf(none, KeyVersion(k1, 0), null)))
        assertEquals(0, winner(KeyVersion(k1, 100), listOf(KeyVersion(k2, 200))))
    }

    @Test fun theNewestKeyWinsWhereverItWasSet() {
        val older = KeyVersion(k1, 100)
        val newer = KeyVersion(k2, 200)
        for (tie in listOf(true, false)) {
            assertTrue(accepts(older, newer, tie))
            assertFalse(accepts(newer, older, tie))
            assertTrue(needs(older, newer, tie))
            assertFalse(needs(newer, older, tie))
        }
        assertEquals(1, winner(older, listOf(KeyVersion(k1, 150), newer, null)))
    }

    @Test fun theSameKeyIsLeftAlone() {
        // Even when the TVs got it at different times.
        assertFalse(accepts(KeyVersion(k1, 0), KeyVersion(k1, 300)))
        assertFalse(needs(KeyVersion(k1, 300), KeyVersion(k1, 0)))
        assertEquals(-1, winner(KeyVersion(k1, 0), listOf(KeyVersion(k1, 300))))
    }

    @Test fun removingAKeyLaterRemovesItEverywhere() {
        val removed = KeyVersion("", 500)
        assertTrue(accepts(KeyVersion(k1, 100), removed, tieGoesToSender = false))
        assertEquals(0, winner(KeyVersion(k1, 100), listOf(removed)))
        // But a key set after that comes back.
        assertTrue(accepts(removed, KeyVersion(k2, 600)))
    }

    @Test fun twoTmdbKeysFromBeforeSharingGoTheHubsWay() {
        // Both set at 0: the hub (the one checking) keeps its own and the other TV takes it.
        val hub = KeyVersion(k1, 0)
        val other = KeyVersion(k2, 0)
        assertEquals(-1, winner(hub, listOf(other)))
        assertTrue(needs(other, hub, SharedKey.TMDB.tieGoesToSender))
        assertTrue(accepts(other, hub, SharedKey.TMDB.tieGoesToSender))
    }

    @Test fun twoGoogleKeysFromBeforeSharingAreBothKept() {
        // Replacing either would sign that TV out of a Drive that works: neither moves until
        // someone sets the keys again, which then goes everywhere.
        val hub = KeyVersion(k1, 0)
        val other = KeyVersion(k2, 0)
        for (key in listOf(SharedKey.GOOGLE, SharedKey.CAMERAS)) {
            assertFalse(key.tieGoesToSender)
            assertFalse(needs(other, hub, key.tieGoesToSender))
            assertFalse(accepts(other, hub, key.tieGoesToSender))
        }
        assertTrue(accepts(other, KeyVersion(k1, 700), SharedKey.GOOGLE.tieGoesToSender))
    }

    @Test fun offTvsAreSkipped() {
        assertEquals(-1, winner(KeyVersion(k1, 100), listOf(null, null)))
    }

    // ---- A house of TVs, run with the same rules as the app ----------------------------------

    /** Each TV's key, and the TVs each one is linked to (it holds their link keys). */
    private class House(val keys: MutableMap<String, KeyVersion>, val links: Map<String, List<String>>, val tie: Boolean = true) {
        var messages = 0

        /** A TV takes [v] if it's newer, then passes it to the TVs linked to it (SharedKeys.receive). */
        fun deliver(to: String, v: KeyVersion) {
            val queue = ArrayDeque(listOf(to to v))
            while (queue.isNotEmpty()) {
                val (tv, version) = queue.removeFirst()
                messages++
                check(messages < 1_000) { "the key kept bouncing between TVs" }
                if (!accepts(keys.getValue(tv), version, tie)) continue
                keys[tv] = version
                links[tv].orEmpty().forEach { queue.add(it to version) }
            }
        }

        /** Someone sets [v] on [tv] (SharedKeys.set): saved there, sent to its linked TVs. */
        fun set(tv: String, v: KeyVersion) {
            keys[tv] = v
            links[tv].orEmpty().forEach { deliver(it, v) }
        }

        /** [hub]'s check of its linked TVs (SharedKeys.sync); [off]: TVs that don't answer. */
        fun sync(hub: String, off: Set<String> = emptySet()) {
            val tvs = links[hub].orEmpty()
            val theirs = tvs.map { if (it in off) null else keys.getValue(it) }
            val w = winner(keys.getValue(hub), theirs)
            if (w >= 0 && accepts(keys.getValue(hub), theirs[w]!!, tie)) keys[hub] = theirs[w]!!
            val mine = keys.getValue(hub)
            tvs.forEachIndexed { i, tv -> val v = theirs[i] ?: return@forEachIndexed; if (needs(v, mine, tie)) deliver(tv, mine) }
        }

        fun fingerprints() = keys.values.map { it.fingerprint }.toSet()
    }

    @Test fun aKeySetOnTheHubReachesEveryTv() {
        for (tie in listOf(true, false)) {
            val house = House(
                mutableMapOf("hub" to none, "sony" to none, "kids" to none),
                mapOf("hub" to listOf("sony", "kids")), tie,
            )
            house.set("hub", KeyVersion(k1, 100))
            assertEquals(setOf(k1), house.fingerprints())
        }
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

    @Test fun aTvThatWasOffOrResetCatchesUpLater() {
        val house = House(
            mutableMapOf("hub" to KeyVersion(k1, 0), "sony" to none),
            mapOf("hub" to listOf("sony")), tie = false,
        )
        house.sync("hub", off = setOf("sony"))
        assertEquals(none, house.keys["sony"])
        house.sync("hub")
        assertEquals(KeyVersion(k1, 0), house.keys["sony"])
        // Reinstalled (its keys gone): the hub's next check puts them back.
        house.keys["sony"] = none
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
