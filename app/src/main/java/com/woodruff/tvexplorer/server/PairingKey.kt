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

    fun get(c: Context): String {
        val st = SecretStore.get(c)
        return st.get(KEY) ?: TransferServer.randomToken().also { st.put(KEY, it) }
    }

    fun reset(c: Context): String = TransferServer.randomToken().also { SecretStore.get(c).put(KEY, it) }
}
