package com.woodruff.tvexplorer.server

import android.content.Context
import com.woodruff.tvexplorer.settings.SecretStore
import java.util.WeakHashMap

/**
 * The phone pairing key. Generated once and kept (encrypted), so the phone link and any bookmark
 * or iOS Shortcut keep working across restarts. "Reset pairing" in Settings replaces it, which
 * disconnects every phone.
 */
object PairingKey {
    private const val KEY = "pair.token"

    /**
     * The key for this run only ([forThisRun]) of each store (the app has one; a test makes one per run of the app),
     * while its saved key can't be read. Held here, never in the store: once the saved key reads again, that one is
     * handed out again. Guarded by this object's lock.
     */
    private val runOnly = WeakHashMap<SecretStore, String>()

    /**
     * The saved key can't be read yet: the TV's Keystore hasn't found its key, as happens on some TVs
     * just after they turn on (see [SecretStore]). Ask again in a little while.
     */
    class NotReadyYet : IllegalStateException("The pairing key can't be read yet")

    /**
     * The key, made the first time it's asked for. Throws [NotReadyYet] while the saved one can't be
     * read: a key made up then would turn away every phone, bookmark and Shortcut paired with the
     * saved one for as long as the phone connection ran with it. Once the phone connection opened with
     * a key for this run ([forThisRun]), that one meanwhile, so links handed out match it.
     */
    fun get(c: Context): String = get(SecretStore.get(c))

    internal fun get(st: SecretStore): String {
        st.get(KEY)?.let { saved -> synchronized(this) { runOnly.remove(st) }; return saved }
        synchronized(this) {
            val temporary = runOnly[st]
            if (st.isUnreadableNow(KEY)) return temporary ?: throw NotReadyYet()
            // None saved: the first key ever, or the Keystore lost its key for good and the store started over. A key
            // this run handed out meanwhile becomes the saved one, so phones that scanned it stay paired.
            runOnly.remove(st)
            return (temporary ?: TransferServer.randomToken()).also { st.put(KEY, it) }
        }
    }

    /**
     * A key for this run only, for when the saved one has stayed unreadable far longer than a hiccup
     * (a Keystore that keeps failing): phones scan the code again, rather than the phone connection
     * staying off. It's never saved over the saved key, and [get] hands it out only until the saved
     * key reads again (the phone connection then opens again with that one: [TransferService]).
     */
    fun forThisRun(c: Context): String = forThisRun(SecretStore.get(c))

    internal fun forThisRun(st: SecretStore): String =
        st.get(KEY) ?: synchronized(this) { runOnly.getOrPut(st) { TransferServer.randomToken() } }

    fun reset(c: Context): String = TransferServer.randomToken().also { SecretStore.get(c).put(KEY, it) }
}
