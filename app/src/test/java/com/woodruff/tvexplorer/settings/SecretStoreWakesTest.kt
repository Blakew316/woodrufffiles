package com.woodruff.tvexplorer.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * The encrypted store looking for a key the TV's Keystore can't find just after it turned on: in the background,
 * every couple of seconds, so a key that turns up a moment after a reader's wait gave up is found then (not at the
 * next read half a minute later), and whatever waits on it is told once. That look never counts the key as gone, and
 * nothing of it happens on a normal start. The Keystore is a stand-in; time is a counter, and the background look runs
 * when the test runs it.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class SecretStoreWakesTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @After fun leaveTheStoreEmpty() = leaveNoSecrets(context)

    private class Vault : SecretStore.KeyVault {
        var key: SecretKey? = null
        @Volatile var missing = false
        override fun find(): SecretKey? = if (missing) null else key
        override fun create(): SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey().also { key = it }
    }

    /** The pause the background look stops at when told to. */
    private class Stop : RuntimeException()

    private var now = 60_000_000L
    /** The background look's pauses (a reader's wait pauses [SecretStore.LOOK_PAUSE_MS] instead). */
    private val relooks = mutableListOf<Long>()
    /** Called at each of the background look's pauses, with how many there have been. */
    private var atRelook: (Int) -> Unit = {}
    /** The background look the store asked to start (run by the test). */
    private var started: Runnable? = null
    private var told = 0
    private val meta get() = context.getSharedPreferences("secrets_meta", Context.MODE_PRIVATE)

    private fun run(vault: Vault, mainThread: Boolean = false) = SecretStore(
        context, vault, onMainThread = { mainThread }, clock = { now },
        pause = { ms ->
            now += ms
            if (ms != SecretStore.LOOK_PAUSE_MS) { relooks += ms; atRelook(relooks.size) }
        },
        background = { started = it }, onKeyBack = { told++ },
    )

    private fun saved(): Vault {
        val vault = Vault()
        run(vault).put("google.refresh", "r-token")
        started = null
        return vault
    }

    @Test fun aKeyThatTurnsUpAfterAWaitGaveUpIsFoundInTheBackgroundAndToldOnce() {
        val vault = saved()
        vault.missing = true
        val store = run(vault)
        // A reader's wait gives up: unset for now, and the look goes on in the background.
        assertNull(store.get("google.refresh"))
        assertTrue(store.isUnreadableNow("google.refresh"))
        assertNotNull("a background look is started", started)
        assertEquals(0, told)
        // The Keystore finds its key a moment later (at the background look's second pause)...
        atRelook = { n -> if (n == 2) vault.missing = false }
        started!!.run()
        // ...found every couple of seconds, not half a minute later at the next read, and told once.
        assertEquals(listOf(SecretStore.RELOOK_MS, SecretStore.RELOOK_MS), relooks)
        assertEquals(1, told)
        assertEquals("r-token", store.get("google.refresh"))
        assertEquals(1, told)
    }

    @Test fun theBackgroundLookNeverTakesTheKeyForGone() {
        val vault = saved()
        vault.missing = true
        val store = run(vault)
        assertNull(store.get("google.refresh"))
        assertEquals("a reader's wait counts once", 1, meta.getInt("keyMissingRuns", 0))
        // An hour of looking, the key never there: every 2 s for two minutes, then every 30 s.
        atRelook = { n -> if (n > 150) throw Stop() }
        try { started!!.run() } catch (e: Stop) { }
        assertEquals(List(60) { SecretStore.RELOOK_MS }, relooks.take(60))
        assertTrue(relooks.drop(60).all { it == SecretStore.LOOK_AGAIN_MS })
        assertEquals("the background look counts nothing", 1, meta.getInt("keyMissingRuns", 0))
        assertTrue("nothing dropped", context.getSharedPreferences("secrets", Context.MODE_PRIVATE).contains("google.refresh"))
        assertEquals(0, told)
        // It looks on again after a reader misses once more (it stopped here only because the test stopped it).
        started = null
        now += SecretStore.LOOK_AGAIN_MS
        assertNull(store.get("google.refresh"))
        assertNotNull(started)
    }

    @Test fun aKeyFoundByAReaderAfterAMissIsToldOnceToo() {
        val vault = saved()
        vault.missing = true
        // The screens' reads (the main thread) look once and never wait.
        val store = run(vault, mainThread = true)
        assertNull(store.get("google.refresh"))
        vault.missing = false
        assertEquals("r-token", store.get("google.refresh"))
        assertEquals(1, told)
        store.put("google.access", "a-token")
        assertEquals(1, told)
    }

    @Test fun aNormalStartLooksForNothingAndTellsNobody() {
        val vault = saved()
        val store = run(vault)
        assertEquals("r-token", store.get("google.refresh"))
        assertNull(started)
        assertEquals(0, told)
    }

    @Test fun theBackgroundLookSlowsDownAfterTwoMinutes() {
        assertEquals(2_000L, SecretStore.relookPause(0))
        assertEquals(2_000L, SecretStore.relookPause(119_999))
        assertEquals(SecretStore.LOOK_AGAIN_MS, SecretStore.relookPause(120_000))
        assertEquals(SecretStore.LOOK_AGAIN_MS, SecretStore.relookPause(3_600_000))
    }
}
