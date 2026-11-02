package com.woodruff.tvexplorer.phones

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.woodruff.tvexplorer.settings.SecretStore
import com.woodruff.tvexplorer.settings.leaveNoSecrets
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
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
 * The paired phones just after a power cut, while the TV's Keystore is still waking up and every saved secret reads
 * as unset for now: the list isn't taken as empty for the whole run, and nothing is saved over it, so no phone loses
 * its place or its Lock Screen alerts' topic. Over the real encrypted store and a stand-in Keystore holding a real AES
 * key; time is a counter.
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
    private var now = 90_000_000L
    private val secrets get() = app.getSharedPreferences("secrets", android.content.Context.MODE_PRIVATE)

    /** A run of the app over the same saved values; [mainThread]: reads that never wait, as a screen's are. */
    private fun run(mainThread: Boolean = false) = SecretStore(app, vault, onMainThread = { mainThread }, clock = { now }, pause = {})

    private var swapped = false
    private var before: SecretStore? = null

    /** Phones reads [store] from now on, as a new run of the app (put back after each test). */
    private fun use(store: SecretStore): SecretStore {
        val was = SecretStore.swapForTest(store)
        if (!swapped) { before = was; swapped = true }
        Phones.forgetForTest()
        return store
    }

    /** The Keystore finds its key again (and the store looks for it again). */
    private fun wakes() {
        vault.missing = false
        now += SecretStore.LOOK_AGAIN_MS
    }

    private fun clean() {
        leaveNoSecrets(app)
        Phones.forgetForTest()
        PhoneInbox.forgetLoaded()
        File(app.filesDir, "phone-inbox.json").delete()
    }

    @Before fun fresh() = clean()

    @After fun putBack() {
        if (swapped) SecretStore.swapForTest(before)
        clean()
    }

    /** Two paired phones, each with its own Lock Screen alerts topic, saved by an earlier run. */
    private fun twoPhonesSaved(): String {
        val list = JSONArray()
            .put(JSONObject().put("id", "phone-den-0001").put("name", "Den iPad").put("topic", "woodruff-den-topic").put("seen", 1L))
            .put(JSONObject().put("id", "phone-kitchen-02").put("name", "Kitchen phone").put("topic", "woodruff-kitchen-topic").put("seen", 2L))
        run().put("phones", list.toString())
        return secrets.getString("phones", null)!!
    }

    @Test fun thePhonesComeBackOnceTheKeystoreWakesAndAreNeverSavedOver() {
        val saved = twoPhonesSaved()
        vault.missing = true
        use(run())
        // None for now (announcements reach no phone), and read again next time rather than kept as none.
        Phones.load(app)
        assertEquals(emptyList<Phone>(), Phones.items.value)
        assertEquals(0, Phones.announce(app, null, "Dinner’s ready", "", "TV"))
        // A phone opening the app says hello: it hears to try again, and nothing is saved over the list (every other
        // phone, and its alerts, would be lost). Forgetting a phone does nothing either.
        val hello = assertThrows(IOException::class.java) { Phones.hello(app, "phone-den-0001", "Den iPad", null) }
        assertEquals("This TV can’t read its list of phones just now. Try again in a moment.", hello.message)
        Phones.remove(app, "phone-kitchen-02")
        assertEquals("nothing written over the saved phones", saved, secrets.getString("phones", null))
        // The Keystore wakes: both phones are back, each with its topic.
        wakes()
        Phones.load(app)
        assertEquals(listOf("Den iPad", "Kitchen phone"), Phones.items.value.map { it.name })
        assertEquals(listOf("woodruff-den-topic", "woodruff-kitchen-topic"), Phones.items.value.map { it.topic })
        // Hello now keeps the phone's own topic and every other phone, in this run and the next.
        val den = Phones.hello(app, "phone-den-0001", "Den iPad", null)
        assertEquals("woodruff-den-topic", den.topic)
        assertEquals(2, Phones.items.value.size)
        use(run())
        Phones.load(app)
        assertEquals(listOf("woodruff-den-topic", "woodruff-kitchen-topic"), Phones.items.value.map { it.topic })
    }

    @Test fun aScreenReadingThePhonesWhileTheKeystoreWakesDoesntKeepThemFromComingBack() {
        twoPhonesSaved()
        vault.missing = true
        // Settings or the Announce dialog asks first, on the main thread, which never waits for the key.
        use(run(mainThread = true))
        Phones.load(app)
        assertTrue(Phones.items.value.isEmpty())
        wakes()
        Phones.load(app)
        assertEquals(2, Phones.items.value.size)
    }

    @Test fun aListThatWasNeverSavedIsEmptyAtOnceAndTakesTheFirstPhone() {
        use(run())
        Phones.load(app)
        assertTrue(Phones.items.value.isEmpty())
        val p = Phones.hello(app, "phone-den-0001", "Den iPad", true)
        assertTrue(p.topic!!.startsWith("woodruff-"))
        use(run())
        Phones.load(app)
        assertEquals(listOf("phone-den-0001"), Phones.items.value.map { it.id })
    }
}
