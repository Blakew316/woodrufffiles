package com.woodruff.tvexplorer.server

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.woodruff.tvexplorer.settings.SecretStore
import com.woodruff.tvexplorer.settings.leaveNoSecrets
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.security.KeyStoreException
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * The phone pairing key just after the TV turns on, when some TVs' Keystore can't find its key for a
 * while: the phone connection must wait for the saved key, never open with a made-up one that turns
 * away every paired phone. The Keystore is a stand-in holding a real AES key; time is a counter.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class PairingKeyTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @After fun leaveTheStoreEmpty() = leaveNoSecrets(context)

    private class Vault : SecretStore.KeyVault {
        var key: SecretKey? = null
        /** Says "no key" this many more times, though there is one. */
        var missingFor = 0
        var failing = false

        override fun find(): SecretKey? {
            if (failing) throw KeyStoreException("not ready")
            if (missingFor > 0) { missingFor--; return null }
            return key
        }

        override fun create(): SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey().also { key = it }
    }

    private var now = 90_000_000L

    /** A run of the app: a fresh store over the same saved values. */
    private fun run(vault: Vault) = SecretStore(context, vault, onMainThread = { false }, clock = { now }, pause = { now += it })

    private fun notReady(store: SecretStore) {
        try {
            PairingKey.get(store)
            fail("a key was made up while the saved one couldn't be read")
        } catch (e: PairingKey.NotReadyYet) {
            // waits, as it should
        }
    }

    @Test fun theFirstKeyIsMadeOnceAndKept() {
        val vault = Vault()
        val first = PairingKey.get(run(vault))
        assertTrue(first.isNotBlank())
        assertEquals(first, PairingKey.get(run(vault)))
    }

    @Test fun aSavedKeyTheKeystoreCantFindYetIsWaitedFor() {
        val vault = Vault()
        val saved = PairingKey.get(run(vault))
        // The TV just turned on: its Keystore can't find the key for a while.
        vault.missingFor = 1_000
        val next = run(vault)
        notReady(next)
        // Asked again soon, as the phone connection does: still waiting, nothing made.
        now += 10_000
        notReady(next)
        // The key turns up: the very key the phones were paired with.
        vault.missingFor = 0
        now += SecretStore.LOOK_AGAIN_MS
        assertEquals(saved, PairingKey.get(next))
        assertEquals(saved, PairingKey.get(run(vault)))
    }

    @Test fun aKeystoreThatErrorsIsWaitedForToo() {
        val vault = Vault()
        val saved = PairingKey.get(run(vault))
        vault.failing = true
        val next = run(vault)
        notReady(next)
        vault.failing = false
        now += SecretStore.LOOK_AGAIN_MS
        assertEquals(saved, PairingKey.get(next))
    }

    @Test fun aKeyForThisRunNeverReplacesTheSavedOne() {
        val vault = Vault()
        val saved = PairingKey.get(run(vault))
        // A Keystore that keeps failing, long past any hiccup: the phone connection opens with a key
        // for this run rather than staying off.
        vault.failing = true
        val next = run(vault)
        notReady(next)
        val temporary = PairingKey.forThisRun(next)
        assertNotEquals(saved, temporary)
        // The rest of this run hands out the same one, so links from the phone app match the server.
        assertEquals(temporary, PairingKey.get(next))
        // The Keystore recovers and this run saves what it kept meanwhile, but never over the saved
        // key: the next run is back to the key every phone was paired with.
        vault.failing = false
        now += SecretStore.LOOK_AGAIN_MS
        next.put("tmdb.key", "abc123")
        assertEquals(temporary, PairingKey.get(next))
        assertEquals(saved, PairingKey.get(run(vault)))
    }

    @Test fun aKeyGoneForGoodGivesANewPairingKeyOnceTheStoreStartsOver() {
        val vault = Vault()
        val saved = PairingKey.get(run(vault))
        // The TV's Keystore was reset: the key never comes back.
        vault.missingFor = Int.MAX_VALUE
        val next = run(vault)
        // Looked for about every minute, as the phone connection does, for as long as the store
        // counts it as a hiccup; then it starts over, and phones need to scan the new code.
        var made: String? = null
        for (minute in 0..20) {
            made = try { PairingKey.get(next) } catch (e: PairingKey.NotReadyYet) { null }
            if (made != null) break
            now += 60_000
        }
        assertNotNull("never gave up on a key that's gone", made)
        assertNotEquals(saved, made)
        assertNotNull(next.lostAt())
    }
}
