package com.woodruff.tvexplorer.phones

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.woodruff.tvexplorer.settings.SecretStore
import com.woodruff.tvexplorer.settings.leaveNoSecrets
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * A TV's paired phones just after a power cut, while its Keystore is still waking up and every saved secret reads as
 * unset for now: the list counts as not known yet rather than as empty, so it's read again once the Keystore wakes,
 * and a phone that checks in meanwhile can't save a list of only itself over it (every other phone's name and
 * lock-screen alerts would be gone for good). Over the real encrypted store and a stand-in Keystore holding a real
 * AES key; time is a counter.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class PhonesKeystoreTest {
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
    private val secrets get() = app.getSharedPreferences("secrets", Context.MODE_PRIVATE)

    /** A run of the app over the same saved values. */
    private fun run() = SecretStore(app, vault, onMainThread = { false }, clock = { now }, pause = {})

    private var swapped = false
    private var before: SecretStore? = null

    /** Phones reads [store] from now on, as a new run of the app (put back after each test). */
    private fun use(store: SecretStore) {
        val was = SecretStore.swapForTest(store)
        if (!swapped) { before = was; swapped = true }
        Phones.forgetForTest()
    }

    /** The Keystore finds its key again (and the store looks for it again). */
    private fun wakes() {
        vault.missing = false
        now += SecretStore.LOOK_AGAIN_MS
    }

    @Before fun fresh() {
        leaveNoSecrets(app)
        Phones.forgetForTest()
    }

    @After fun putBack() {
        if (swapped) SecretStore.swapForTest(before)
        leaveNoSecrets(app)
        Phones.forgetForTest()
        PhoneInbox.forgetLoaded()
        File(app.filesDir, "phone-inbox.json").delete()
        PhoneInbox.forgetLoaded()
    }

    private val two = JSONArray()
        .put(JSONObject().put("id", "phone-den-1").put("name", "Den iPad").put("topic", "woodruff-topic-den").put("seen", 1L))
        .put(JSONObject().put("id", "phone-kitchen-2").put("name", "Kitchen phone").put("topic", "woodruff-topic-kitchen").put("seen", 2L))

    @Test fun thePhonesComeBackOnceTheKeystoreWakesAndAreNeverSavedOver() {
        run().put("phones", two.toString())
        val saved = secrets.getString("phones", null)
        vault.missing = true
        use(run())
        // Settings opened in that moment: no phones for now, and they're read again next time rather than kept as none.
        Phones.load(app)
        assertEquals(emptyList<Phone>(), Phones.items.value)
        // An announcement reaches none of them for now (nothing is pushed or kept for a phone it doesn't know).
        assertEquals(0, Phones.announce(app, null, "Dinner’s ready", "", "Den"))
        // A phone checking in can't save a list of only itself over the real one: it hears to try again in a moment.
        val refused = assertThrows(IOException::class.java) { Phones.hello(app, "phone-living-3", "Living Room phone", null) }
        assertEquals(Phones.LIST_NOT_YET, refused.message)
        assertThrows(IOException::class.java) { Phones.hello(app, "phone-den-1", "Den iPad", null) }
        // Forgetting one changes nothing either.
        assertFalse(Phones.remove(app, "phone-kitchen-2"))
        assertEquals("nothing written over the saved list", saved, secrets.getString("phones", null))

        // The Keystore wakes: every phone is back, its lock-screen alerts too.
        wakes()
        Phones.load(app)
        assertEquals(listOf("phone-den-1", "phone-kitchen-2"), Phones.items.value.map { it.id })
        assertEquals(listOf("woodruff-topic-den", "woodruff-topic-kitchen"), Phones.items.value.map { it.topic })
        // The phone's next check-in keeps its alerts, and the other phone stays.
        val den = Phones.hello(app, "phone-den-1", "Den iPad", null)
        assertEquals("woodruff-topic-den", den.topic)
        assertEquals(listOf("phone-den-1", "phone-kitchen-2"), Phones.items.value.map { it.id })
        // Changes are kept again, in this run and the next.
        assertTrue(Phones.remove(app, "phone-kitchen-2"))
        use(run())
        Phones.load(app)
        assertEquals(listOf("phone-den-1"), Phones.items.value.map { it.id })
        assertEquals("woodruff-topic-den", Phones.items.value.single().topic)
    }

    @Test fun aTvWithNoPhonesSavedStartsItsListAtOnce() {
        // Nothing saved: not "can't read it yet", just none. The first phone to check in is the first on the list.
        use(run())
        Phones.load(app)
        assertEquals(emptyList<Phone>(), Phones.items.value)
        Phones.hello(app, "phone-den-1", "Den iPad", true)
        use(run())
        Phones.load(app)
        val p = Phones.items.value.single()
        assertEquals("Den iPad", p.name)
        assertTrue(p.topic!!.startsWith("woodruff-"))
    }

    @Test fun aListNoKeyCanReadAnyMoreCountsAsNone() {
        // Saved under a key that's gone for good (data restored onto another TV): unset for good, not waited for.
        run().put("phones", two.toString())
        vault.create()
        use(run())
        Phones.load(app)
        assertEquals(emptyList<Phone>(), Phones.items.value)
        // A phone can check in (and starts the list again) rather than hear "try again" for ever.
        assertEquals("Den iPad", Phones.hello(app, "phone-den-1", "Den iPad", null).name)
        assertEquals(listOf("phone-den-1"), Phones.items.value.map { it.id })
    }
}
