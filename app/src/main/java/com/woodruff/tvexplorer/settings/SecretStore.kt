package com.woodruff.tvexplorer.settings

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypted key/value storage for API keys, OAuth secrets/tokens and mail passwords.
 *
 * Values are AES-256-GCM encrypted with a key that lives in the Android Keystore (hardware-backed
 * where available), so they persist across app restarts and updates but are useless if the
 * prefs file is copied off the device. Uninstalling the app deletes the key and with it the data.
 */
class SecretStore private constructor(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("secrets", Context.MODE_PRIVATE)

    /**
     * Decrypted values, so each secret goes through the Keystore once per app run. Keystore
     * operations take milliseconds each (tens on some TV chips), and tokens are read on every
     * Drive video request. The values are only ever used in this process anyway; disk stays encrypted.
     */
    private val cache = java.util.concurrent.ConcurrentHashMap<String, String>()
    @Volatile private var secretKey: SecretKey? = null

    fun get(name: String): String? {
        cache[name]?.let { return it }
        val stored = prefs.getString(name, null) ?: return null
        return try {
            val bytes = Base64.decode(stored, Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORM)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes, 0, IV_LEN))
            String(cipher.doFinal(bytes, IV_LEN, bytes.size - IV_LEN), Charsets.UTF_8).also { cache[name] = it }
        } catch (e: Exception) {
            // Key lost (e.g. data restored onto another device): treat as unset.
            null
        }
    }

    fun put(name: String, value: String?) {
        if (value == null) { remove(name); return }
        if (cache[name] == value && prefs.contains(name)) return // unchanged: skip the Keystore round trip
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val out = cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        prefs.edit().putString(name, Base64.encodeToString(out, Base64.NO_WRAP)).apply()
        cache[name] = value
    }

    fun remove(vararg names: String) {
        names.forEach { cache.remove(it) }
        val edit = prefs.edit()
        names.forEach { edit.remove(it) }
        edit.apply()
    }

    @Synchronized
    private fun key(): SecretKey {
        secretKey?.let { return it }
        return loadOrCreateKey().also { secretKey = it }
    }

    private fun loadOrCreateKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    companion object {
        private const val ALIAS = "woodruff_secrets"
        private const val TRANSFORM = "AES/GCM/NoPadding"
        private const val IV_LEN = 12

        @Volatile private var instance: SecretStore? = null
        fun get(context: Context): SecretStore =
            instance ?: synchronized(this) { instance ?: SecretStore(context).also { instance = it } }
    }
}
