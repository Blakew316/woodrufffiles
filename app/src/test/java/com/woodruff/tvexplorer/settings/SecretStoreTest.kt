package com.woodruff.tvexplorer.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.security.KeyStoreException
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * The encrypted store when the TV's Keystore misbehaves: a key that's missing for a moment just
 * after the TV turns on, a Keystore that errors, a key that's really gone. Saved sign-ins must never
 * be lost to a hiccup, the screen must never wait, and saving must never fail. The Keystore here is
 * a stand-in (tests have none) holding a real AES key; time is a counter, so pauses take no time.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class SecretStoreTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private class Vault : SecretStore.KeyVault {
        var key: SecretKey? = null
        /** Says "no key" this many more times, though there is one (a Keystore not ready yet). */
        var missingFor = 0
        /** Throws instead of answering. */
        var failing = false
        var looks = 0

        override fun find(): SecretKey? {
            looks++
            if (failing) throw KeyStoreException("not ready")
            if (missingFor > 0) { missingFor--; return null }
            return key
        }

        override fun create(): SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey().also { key = it }
    }

    private var now = 50_000_000L
    private val pauses = mutableListOf<Long>()

    /** A run of the app: a fresh store (empty cache) over the same saved values. */
    private fun run(vault: Vault, mainThread: Boolean = false) =
        SecretStore(context, vault, onMainThread = { mainThread }, clock = { now }, pause = { pauses += it; now += it })

    private fun saved(vararg values: Pair<String, String>): Vault {
        val vault = Vault()
        val store = run(vault)
        values.forEach { (k, v) -> store.put(k, v) }
        vault.looks = 0
        return vault
    }

    private val meta get() = context.getSharedPreferences("secrets_meta", Context.MODE_PRIVATE)

    @Test fun valuesComeBackInTheNextRun() {
        val vault = saved("google.refresh" to "r-token", "tmdb.key" to "abc123")
        val next = run(vault)
        assertEquals("r-token", next.get("google.refresh"))
        assertEquals("abc123", next.get("tmdb.key"))
        assertNull(next.get("never.saved"))
        assertTrue(pauses.isEmpty())
        assertNull(next.lostAt())
    }

    @Test fun aKeyMissingForAMomentIsWaitedForInTheBackground() {
        val vault = saved("google.refresh" to "r-token")
        vault.missingFor = 2
        val next = run(vault)
        assertEquals("r-token", next.get("google.refresh"))
        assertEquals(listOf(SecretStore.LOOK_PAUSE_MS, SecretStore.LOOK_PAUSE_MS), pauses)
        // Found: nothing counted against it.
        assertTrue(!meta.contains("keyMissingRuns"))
    }

    @Test fun theMainThreadNeverWaits() {
        val vault = saved("google.refresh" to "r-token")
        vault.missingFor = 100
        val onScreen = run(vault, mainThread = true)
        assertNull(onScreen.get("google.refresh"))
        assertTrue("paused on the main thread: $pauses", pauses.isEmpty())
        assertEquals(1, vault.looks)
        // It draws no conclusions either: nothing counted, nothing lost.
        assertTrue(!meta.contains("keyMissingRuns"))
        // Saving from the main thread doesn't wait or fail.
        onScreen.put("tvs.name", "Den")
        assertTrue(pauses.isEmpty())
        assertEquals("Den", onScreen.get("tvs.name"))
    }

    @Test fun afterAWaitThatCameToNothingReadsDontWaitAgainForAWhile() {
        val vault = saved("a" to "1", "b" to "2", "c" to "3")
        vault.missingFor = 1_000
        val next = run(vault)
        assertNull(next.get("a"))
        assertEquals(SecretStore.LOOKS, vault.looks)
        assertEquals(SecretStore.LOOKS - 1, pauses.size)
        // The rest of the start-up reads answer at once, without asking the Keystore.
        next.warmAll()
        assertNull(next.get("b"))
        assertEquals(SecretStore.LOOKS, vault.looks)
        assertEquals(SecretStore.LOOKS - 1, pauses.size)
        // Then it looks again, and finds the key once the Keystore has it.
        now += SecretStore.LOOK_AGAIN_MS
        vault.missingFor = 0
        assertEquals("2", next.get("b"))
    }

    @Test fun savingWhileTheKeyIsMissingNeverFailsAndIsSavedOnceItsBack() {
        val vault = saved("google.refresh" to "r-token")
        vault.missingFor = 1_000
        val next = run(vault)
        next.put("google.access", "a-token") // a token refresh: must not throw
        assertEquals("a-token", next.get("google.access"))
        // Not on disk yet: another run can't read it.
        vault.missingFor = 0
        assertNull(run(vault).get("google.access"))
        // The key's back: this run saves it.
        now += SecretStore.LOOK_AGAIN_MS
        assertEquals("r-token", next.get("google.refresh"))
        assertEquals("a-token", run(vault).get("google.access"))
    }

    @Test fun aValueMadeBecauseTheSavedOneReadAsUnsetNeverReplacesIt() {
        val vault = saved("pair.token" to "the-real-pairing-key", "tmdb.key" to "old-key", "google.refresh" to "r-token")
        vault.missingFor = 1_000
        val next = run(vault)
        // A "get or make" (the phone pairing key): the saved one reads as unset, so a new one is made.
        val made = next.get("pair.token") ?: "made-up-key".also { next.put("pair.token", it) }
        assertEquals("made-up-key", made)
        // A deliberate change to a value nobody read meanwhile (a new TMDB key from the phone).
        next.put("tmdb.key", "new-key")
        // The key's back, found by the next read that needs it.
        now += SecretStore.LOOK_AGAIN_MS
        vault.missingFor = 0
        assertEquals("r-token", next.get("google.refresh"))
        // This run carries on with what it was given; the saved pairing key survives for every later run,
        // and the deliberate change is saved.
        assertEquals("made-up-key", next.get("pair.token"))
        val later = run(vault)
        assertEquals("the-real-pairing-key", later.get("pair.token"))
        assertEquals("new-key", later.get("tmdb.key"))
    }

    @Test fun aKeystoreThatErrorsIsNeverTakenForAMissingKey() {
        val vault = saved("google.refresh" to "r-token")
        vault.failing = true
        val next = run(vault)
        repeat(6) {
            assertNull(next.get("google.refresh"))
            now += SecretStore.MISS_SPACING_MS
        }
        // Three separate runs too.
        repeat(3) { assertNull(run(vault).get("google.refresh")); now += SecretStore.MISS_SPACING_MS }
        vault.failing = false
        now += SecretStore.LOOK_AGAIN_MS
        assertEquals("r-token", run(vault).get("google.refresh"))
        assertNull(next.lostAt())
    }

    @Test fun aKeyGoneForGoodStartsOverAfterThreeAbsencesSpacedApart() {
        val vault = saved("google.refresh" to "r-token", "tmdb.key" to "abc")
        vault.key = null
        vault.missingFor = 0
        val next = run(vault)
        assertNull(next.get("google.refresh")) // first absence
        next.put("tvs.name", "Living Room") // put meanwhile: kept through the start-over
        // Absences closer together than the spacing count once.
        now += SecretStore.LOOK_AGAIN_MS
        assertNull(next.get("google.refresh"))
        assertEquals(1, meta.getInt("keyMissingRuns", 0))
        now += SecretStore.MISS_SPACING_MS
        assertNull(next.get("google.refresh")) // second
        assertEquals(2, meta.getInt("keyMissingRuns", 0))
        assertNull(next.lostAt())
        now += SecretStore.MISS_SPACING_MS
        // Third: gone for good. A new key; what it encrypted can't be read by any key.
        assertNull(next.get("google.refresh"))
        assertNotNull(next.lostAt())
        assertNotNull(vault.key)
        assertNull(next.get("tmdb.key"))
        assertEquals("Living Room", next.get("tvs.name"))
        // Saved with the new key, and new sign-ins save normally.
        next.put("google.refresh", "new-token")
        val later = run(vault)
        assertEquals("Living Room", later.get("tvs.name"))
        assertEquals("new-token", later.get("google.refresh"))
        assertTrue(!meta.contains("keyMissingRuns"))
    }

    @Test fun separateRunsEachCountOnce() {
        val vault = saved("google.refresh" to "r-token")
        vault.key = null
        repeat(2) { i ->
            assertNull(run(vault).get("google.refresh"))
            assertEquals(i + 1, meta.getInt("keyMissingRuns", 0))
            now += SecretStore.LOOK_AGAIN_MS
        }
        val third = run(vault)
        assertNull(third.get("google.refresh"))
        assertNotNull(third.lostAt())
    }

    @Test fun findingTheKeyStartsTheCountAgain() {
        val vault = saved("google.refresh" to "r-token")
        vault.missingFor = 1_000
        assertNull(run(vault).get("google.refresh"))
        now += SecretStore.MISS_SPACING_MS
        assertNull(run(vault).get("google.refresh"))
        assertEquals(2, meta.getInt("keyMissingRuns", 0))
        // It turns up: the count starts again, and two more absences don't lose anything.
        vault.missingFor = 0
        assertEquals("r-token", run(vault).get("google.refresh"))
        assertTrue(!meta.contains("keyMissingRuns"))
        vault.missingFor = 1_000
        repeat(2) { assertNull(run(vault).get("google.refresh")); now += SecretStore.MISS_SPACING_MS }
        vault.missingFor = 0
        assertEquals("r-token", run(vault).get("google.refresh"))
    }

    @Test fun aValueTheKeyCantReachYetIsUnreadableNotUnset() {
        val vault = saved("pair.token" to "the-real-pairing-key")
        vault.missingFor = 1_000
        val next = run(vault)
        assertNull(next.get("pair.token"))
        // Saved, just not readable yet: whatever would "get or make" it waits instead.
        assertTrue(next.isUnreadableNow("pair.token"))
        // Never saved, or not asked about yet: plainly unset / unknown.
        assertTrue(!next.isUnreadableNow("never.saved"))
        // The key turns up for another read; until this value is asked for again it still counts as
        // unreadable, so nothing can be made up in between and replace it.
        vault.missingFor = 0
        now += SecretStore.LOOK_AGAIN_MS
        next.put("tmdb.key", "abc123")
        assertTrue(next.isUnreadableNow("pair.token"))
        assertEquals("the-real-pairing-key", next.get("pair.token"))
        assertTrue(!next.isUnreadableNow("pair.token"))
    }

    @Test fun aValueNoKeyCanDecryptIsUnsetNotUnreadable() {
        val vault = saved("pair.token" to "the-real-pairing-key")
        // A key that's there but isn't the one it was saved with (data restored onto another TV).
        vault.create()
        val next = run(vault)
        assertNull(next.get("pair.token"))
        assertTrue(!next.isUnreadableNow("pair.token"))
    }

    @Test fun aValueMissedThenUndecryptableIsNotUnreadableForever() {
        val vault = saved("pair.token" to "the-real-pairing-key")
        vault.missingFor = 1_000
        val next = run(vault)
        assertNull(next.get("pair.token"))
        assertTrue(next.isUnreadableNow("pair.token"))
        // The key that turns up can't decrypt it: unset for good, so a new one may be made.
        vault.missingFor = 0
        vault.create()
        now += SecretStore.LOOK_AGAIN_MS
        assertNull(next.get("pair.token"))
        assertTrue(!next.isUnreadableNow("pair.token"))
    }
}
