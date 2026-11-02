package com.woodruff.tvexplorer.server

import android.content.Context
import com.woodruff.tvexplorer.settings.SecretStore

/**
 * The phone pairing key. Generated once and kept (encrypted), so the phone link and any bookmark
 * or iOS Shortcut keep working across restarts. "Reset pairing" in Settings replaces it, which
 * disconnects every phone.
 */
object PairingKey {
    private const val KEY = "pair.token"

    /**
     * The saved key can't be read yet: the TV's Keystore hasn't found its key, as happens on some TVs
     * just after they turn on (see [SecretStore]). Ask again in a little while.
     */
    class NotReadyYet : IllegalStateException("The pairing key can't be read yet")

    /**
     * The key, made the first time it's asked for. Throws [NotReadyYet] while the saved one can't be
     * read: a key made up then would turn away every phone, bookmark and Shortcut paired with the
     * saved one for as long as the phone connection ran with it.
     */
    fun get(c: Context): String = get(SecretStore.get(c))

    internal fun get(st: SecretStore): String {
        st.get(KEY)?.let { return it }
        if (st.isUnreadableNow(KEY)) throw NotReadyYet()
        return TransferServer.randomToken().also { st.put(KEY, it) }
    }

    /**
     * A key for this run only, for when the saved one has stayed unreadable far longer than a hiccup
     * (a Keystore that keeps failing): phones scan the code again, rather than the phone connection
     * staying off. It's never saved over the saved key (see [SecretStore.put]), which comes back
     * with the Keystore. Every later [get] in this run returns it, so links handed out match.
     */
    fun forThisRun(c: Context): String = forThisRun(SecretStore.get(c))

    internal fun forThisRun(st: SecretStore): String = st.get(KEY) ?: TransferServer.randomToken().also { st.put(KEY, it) }

    fun reset(c: Context): String = TransferServer.randomToken().also { SecretStore.get(c).put(KEY, it) }
}
