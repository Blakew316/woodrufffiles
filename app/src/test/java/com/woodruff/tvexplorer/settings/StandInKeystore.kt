package com.woodruff.tvexplorer.settings

import android.content.Context
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * The TV's encrypted store over a stand-in Keystore holding a real AES key, for tests of sign-ins and links, among
 * them what happens while a TV's Keystore is still waking after a power cut ([missing]: it says it has no key,
 * though it has). Each [run] is a run of the app over the same saved values; [use] makes one the app's own store
 * (as [SecretStore.get] hands out) until [putBack]. Time is a counter.
 */
internal class StandInKeystore(private val app: Context) {
    private class Vault : SecretStore.KeyVault {
        var key: SecretKey? = null
        @Volatile var missing = false
        override fun find(): SecretKey? = if (missing) null else key
        override fun create(): SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey().also { key = it }
    }

    private val vault = Vault()
    var now = 70_000_000L

    /** The Keystore says it has no key, as some TVs' do for a while after they turn on. */
    var missing: Boolean
        get() = vault.missing
        set(value) { vault.missing = value }

    private var swapped = false
    private var before: SecretStore? = null

    /** A run of the app over the same saved values; [mainThread]: reads that never wait, as the screens' are. */
    fun run(mainThread: Boolean = false) = SecretStore(app, vault, onMainThread = { mainThread }, clock = { now }, pause = {})

    /** The app reads [store] from now on (a new run of it, unless one is given). */
    fun use(store: SecretStore = run()): SecretStore {
        val was = SecretStore.swapForTest(store)
        if (!swapped) { before = was; swapped = true }
        return store
    }

    /** The Keystore finds its key again, and the store looks for it again. */
    fun wakes() {
        vault.missing = false
        now += SecretStore.LOOK_AGAIN_MS
    }

    /** Puts the app's own store back and leaves the encrypted store's files empty ([leaveNoSecrets]). */
    fun putBack() {
        if (swapped) SecretStore.swapForTest(before)
        swapped = false
        leaveNoSecrets(app)
    }
}
