package com.woodruff.tvexplorer.settings

import android.content.Context

/**
 * Empties the encrypted store's files after a test that filled them through a SecretStore of its own
 * (over a stand-in Keystore). The app's single SecretStore is made by the first test of a run that asks
 * for one, and keeps reading that test's files for the rest of the run: anything left in them that the
 * test JVM's Keystore can't decrypt counts as kept but unreadable just now, which is signed in (see
 * GoogleAuth's sign-in checks), in every later test.
 */
internal fun leaveNoSecrets(c: Context) {
    for (file in listOf("secrets", "secrets_meta")) c.getSharedPreferences(file, Context.MODE_PRIVATE).edit().clear().commit()
}
