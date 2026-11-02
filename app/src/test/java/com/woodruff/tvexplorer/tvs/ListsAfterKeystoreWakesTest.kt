package com.woodruff.tvexplorer.tvs

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.woodruff.tvexplorer.phones.PhoneInbox
import com.woodruff.tvexplorer.phones.Phones
import com.woodruff.tvexplorer.settings.SecretStore
import com.woodruff.tvexplorer.settings.leaveNoSecrets
import com.woodruff.tvexplorer.util.NetworkWatch
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * The moment a TV's Keystore wakes after a power cut, everything read as "not yet" catches up by itself: the linked
 * TVs and the paired phones are read (the screens showing them, the doorbell's relay and announcements have them at
 * once, without anything asking again), and whatever waits to try again on the network coming back (the libraries, the
 * calendar, the cameras, the doorbell) is woken. Until then the doorbell doesn't take the unread list for "no TVs".
 * Over the real encrypted store and a stand-in Keystore; its background look runs when the test runs it.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ListsAfterKeystoreWakesTest {
    private val app: Application = ApplicationProvider.getApplicationContext()

    private class Vault : SecretStore.KeyVault {
        var key: SecretKey? = null
        @Volatile var missing = false
        override fun find(): SecretKey? = if (missing) null else key
        override fun create(): SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey().also { key = it }
    }

    private val vault = Vault()
    private var now = 85_000_000L
    private var started: Runnable? = null
    private var swapped = false
    private var before: SecretStore? = null

    /** A run of the app; [waking]: its store looks for the key in the background ([started]) and, once found, tells the app. */
    private fun run(waking: Boolean = false) = SecretStore(
        app, vault, onMainThread = { false }, clock = { now },
        // The Keystore finds its key at the background look's second look.
        pause = { ms -> now += ms; if (ms == SecretStore.RELOOK_MS && ++relooks >= 2) vault.missing = false },
        background = if (waking) ({ r: Runnable -> started = r }) else null,
        onKeyBack = if (waking) SecretStore::keyBack else ({}),
    )
    private var relooks = 0

    private fun clean() {
        leaveNoSecrets(app)
        app.getSharedPreferences("tvs", Context.MODE_PRIVATE).edit().clear().commit()
        TvLinks.forgetForTest()
        Phones.forgetForTest()
        PhoneInbox.forgetLoaded()
        File(app.filesDir, "phone-inbox.json").delete()
    }

    @Before fun fresh() = clean()

    @After fun putBack() {
        if (swapped) SecretStore.swapForTest(before)
        clean()
    }

    @Test fun theListsAreReadAndRetriesWokenTheMomentTheKeystoreWakes() {
        run().apply {
            put("tvs.linked", JSONArray().put(JSONObject().put("id", "den-1").put("name", "Den").put("base", "http://den.local:8080").put("key", "den-key")).toString())
            put("phones", JSONArray().put(JSONObject().put("id", "phone-den-0001").put("name", "Den iPad").put("topic", "woodruff-den-topic")).toString())
        }
        vault.missing = true
        before = SecretStore.swapForTest(run(waking = true))
        swapped = true
        TvLinks.forgetForTest()
        Phones.forgetForTest()

        // Read while the Keystore wakes (Settings open, the doorbell's loop): nothing yet, and not read for good.
        TvLinks.load(app)
        Phones.load(app)
        assertTrue(TvLinks.linked.value.isEmpty())
        assertFalse("an unread list isn't \"no TVs\" for the doorbell", TvLinks.listRead)
        assertTrue(Phones.items.value.isEmpty())
        assertNotNull(started)

        // The Keystore wakes: the background look finds the key within a couple of seconds.
        val waiting = NetworkWatch.comebacks.value
        started!!.run()
        // Nothing asked again, yet both lists are there, and what waits to try again was woken.
        assertEquals(listOf("den-1"), TvLinks.linked.value.map { it.id })
        assertTrue(TvLinks.listRead)
        assertEquals(listOf("woodruff-den-topic"), Phones.items.value.map { it.topic })
        assertEquals(waiting + 1, NetworkWatch.comebacks.value)
    }

    @Test fun aListThatWasNeverSavedIsReadAtOnce() {
        before = SecretStore.swapForTest(run())
        swapped = true
        TvLinks.forgetForTest()
        TvLinks.load(app)
        assertTrue(TvLinks.listRead)
        assertTrue(TvLinks.linked.value.isEmpty())
    }
}
