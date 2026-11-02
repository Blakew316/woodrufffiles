package com.woodruff.tvexplorer.tvs

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.woodruff.tvexplorer.settings.SecretStore
import com.woodruff.tvexplorer.settings.leaveNoSecrets
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.IOException
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * A TV's links just after a power cut, while its Keystore is still waking up and every saved secret reads as
 * unset for now: no new link key, TV id or name is made up in place of the saved one (each hub would say "Link
 * again", or see a TV it doesn't know), the linked TVs come back once the Keystore wakes, and nothing saved is
 * written over. A value no key can read any more (restored onto another TV) is made anew, rather than waited for
 * for ever. Over the real encrypted store and a stand-in Keystore holding a real AES key; time is a counter.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class TvLinksKeystoreTest {
    private val app: Application = ApplicationProvider.getApplicationContext()

    /** The TV's Keystore: has the key, but says it has none while [missing] (just after the TV turned on). */
    private class Vault : SecretStore.KeyVault {
        var key: SecretKey? = null
        @Volatile var missing = false
        override fun find(): SecretKey? = if (missing) null else key
        override fun create(): SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey().also { key = it }
    }

    private val vault = Vault()
    private var now = 80_000_000L
    private val tvs get() = app.getSharedPreferences("tvs", Context.MODE_PRIVATE)
    private val secrets get() = app.getSharedPreferences("secrets", Context.MODE_PRIVATE)

    /** A run of the app over the same saved values. */
    private fun run() = SecretStore(app, vault, onMainThread = { false }, clock = { now }, pause = {})

    private var swapped = false
    private var before: SecretStore? = null

    /** TvLinks reads [store] from now on, as a new run of the app (put back after each test). */
    private fun use(store: SecretStore): SecretStore {
        val was = SecretStore.swapForTest(store)
        if (!swapped) { before = was; swapped = true }
        TvLinks.forgetForTest()
        return store
    }

    /** The Keystore finds its key again (and the store looks for it again). */
    private fun wakes() {
        vault.missing = false
        now += SecretStore.LOOK_AGAIN_MS
    }

    @Before fun fresh() {
        leaveNoSecrets(app)
        tvs.edit().clear().commit()
        TvLinks.forgetForTest()
    }

    @After fun putBack() {
        if (swapped) SecretStore.swapForTest(before)
        leaveNoSecrets(app)
        tvs.edit().clear().commit()
        TvLinks.forgetForTest()
    }

    @Test fun aLinkKeyTheKeystoreCantReadYetIsWaitedForNeverReplaced() {
        run().put("tvs.linkkey", "link-key-1")
        val saved = secrets.getString("tvs.linkkey", null)
        vault.missing = true
        use(run())
        // A hub's request goes on to the pairing-key check (no error), and no new key is made: it would turn down
        // every hub's key for good.
        assertFalse(TvLinks.isLinkKey(app, "link-key-1"))
        val code = TvLinks.newCode()
        try {
            // The hub hears it didn't work; the code on screen still works once the key can be read.
            assertThrows(IOException::class.java) { TvLinks.redeem(app, code) }
        } finally {
            TvLinks.cancelCode()
        }
        assertEquals("nothing written over the saved key", saved, secrets.getString("tvs.linkkey", null))
        // The Keystore wakes: the very key every hub holds, in this run and the next.
        wakes()
        assertTrue(TvLinks.isLinkKey(app, "link-key-1"))
        use(run())
        assertTrue(TvLinks.isLinkKey(app, "link-key-1"))
    }

    @Test fun anIdAndNameTheKeystoreCantReadYetStayTheTvsOwn() {
        // Saved encrypted by a Woodruff from before they moved to plain settings.
        run().apply { put("tvs.id", "tv-id-1"); put("tvs.name", "Den") }
        vault.missing = true
        use(run())
        // An id for this run only, the same all run, never saved over the TV's own.
        val interim = TvLinks.id(app)
        assertNotEquals("tv-id-1", interim)
        assertEquals(interim, TvLinks.id(app))
        assertNull(tvs.getString("id", null))
        // No name is taken for "none" either: the model's name for now, nothing saved.
        assertNull(TvLinks.givenName(app))
        assertTrue(TvLinks.name(app).endsWith(" TV"))
        assertFalse(tvs.contains("name"))
        // The Keystore wakes: both are copied over and kept.
        wakes()
        assertEquals("tv-id-1", TvLinks.id(app))
        assertEquals("Den", TvLinks.givenName(app))
        assertEquals("tv-id-1", tvs.getString("id", null))
        assertEquals("Den", tvs.getString("name", null))
    }

    @Test fun aValueNoKeyCanReadAnyMoreIsMadeAnewNotWaitedForForEver() {
        // Saved under a key that's gone for good (data restored onto another TV): unset for good, so made anew.
        run().apply { put("tvs.linkkey", "link-key-1"); put("tvs.id", "tv-id-1") }
        vault.create()
        use(run())
        val id = TvLinks.id(app)
        assertNotEquals("tv-id-1", id)
        assertEquals("kept, not an id for this run", id, tvs.getString("id", null))
        val code = TvLinks.newCode()
        val answer = try { TvLinks.redeem(app, code) } finally { TvLinks.cancelCode() }
        assertNotNull("the code gave this TV's link key", answer)
        val key = answer!!.getString("key")
        assertNotEquals("link-key-1", key)
        assertTrue(TvLinks.isLinkKey(app, key))
    }

    @Test fun theLinkedTvsComeBackOnceTheKeystoreWakesAndAreNeverSavedOver() {
        val list = JSONArray()
            .put(JSONObject().put("id", "den-1").put("name", "Den").put("base", "http://den.local:8080").put("key", "den-key"))
            .put(JSONObject().put("id", "kitchen-1").put("name", "Kitchen").put("base", "http://kitchen.local:8080").put("key", "kitchen-key"))
        run().put("tvs.linked", list.toString())
        val saved = secrets.getString("tvs.linked", null)
        vault.missing = true
        use(run())
        // None for now (the doorbell, messages and keys skip them), and read again next time rather than kept as none.
        TvLinks.load(app)
        assertEquals(emptyList<LinkedTv>(), TvLinks.linked.value)
        // A change can't be saved over the list this run couldn't read: unlinking does nothing, linking says why
        // before the other TV uses up its code.
        TvLinks.remove(app, "den-1")
        try {
            runBlocking { TvLinks.link(app, "http://living-room.local:8080", "123456") }
            fail("linked over a list that couldn't be read")
        } catch (e: IOException) {
            assertEquals("This TV can’t read its list of linked TVs just now. Try again in a moment.", e.message)
        }
        assertEquals("nothing written over the saved list", saved, secrets.getString("tvs.linked", null))
        // The Keystore wakes: every linked TV is back.
        wakes()
        TvLinks.load(app)
        assertEquals(listOf("den-1", "kitchen-1"), TvLinks.linked.value.map { it.id })
        // And changes are kept again, in this run and the next.
        TvLinks.remove(app, "den-1")
        assertEquals(listOf("kitchen-1"), TvLinks.linked.value.map { it.id })
        use(run())
        TvLinks.load(app)
        assertEquals(listOf("kitchen-1"), TvLinks.linked.value.map { it.id })
    }
}
