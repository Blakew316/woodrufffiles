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
    /** What happened to the key, in plain prefs of their own (never read as a secret): see [lostAt]. */
    private val meta = context.applicationContext.getSharedPreferences("secrets_meta", Context.MODE_PRIVATE)
    /** This run already counted the key as missing (see [loadOrCreateKey]). */
    @Volatile private var missCounted = false

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

    /**
     * Decrypts every stored value into the cache. Run once in the background at app start, so the
     * screens' first reads (Settings, Drive, Mail, cameras) never wait on the Keystore.
     */
    fun warmAll() {
        prefs.all.keys.forEach { runCatching { get(it) } }
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

    /**
     * The key every secret is encrypted with. When it isn't there but secrets are saved, the Keystore
     * may just not be ready (some TVs, just after turning on): a new key then would make every saved
     * sign-in unreadable for good. So it asks again for a moment, and otherwise fails this time
     * (secrets read as unset until the next try); only once the key has been missing on
     * [RUNS_BEFORE_NEW_KEY] separate runs is it taken as gone, the old secrets dropped and a new key made.
     */
    private fun loadOrCreateKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { meta.edit().remove(K_MISSES).apply(); return it }
        if (prefs.all.isNotEmpty()) {
            repeat(3) {
                Thread.sleep(400)
                ks.load(null)
                (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
            }
            val misses = meta.getInt(K_MISSES, 0) + if (missCounted) 0 else 1
            if (!missCounted) { missCounted = true; meta.edit().putInt(K_MISSES, misses).apply() }
            if (misses < RUNS_BEFORE_NEW_KEY) throw java.security.KeyStoreException("The secrets' key isn't available yet (try $misses)")
            // Gone for good (the TV's Keystore was reset): what it encrypted can't be read by any key.
            android.util.Log.w("SecretStore", "the secrets' key is gone: starting over")
            prefs.edit().clear().apply()
            cache.clear()
            meta.edit().remove(K_MISSES).putLong(K_LOST_AT, System.currentTimeMillis()).apply()
        }
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

    /** When the saved keys and sign-ins were last lost because the TV's Keystore lost their key; null if never. */
    fun lostAt(): Long? = meta.getLong(K_LOST_AT, 0L).takeIf { it > 0 }

    companion object {
        private const val K_MISSES = "keyMissingRuns"
        private const val K_LOST_AT = "keyLostAt"
        private const val RUNS_BEFORE_NEW_KEY = 3
        private const val ALIAS = "woodruff_secrets"
        private const val TRANSFORM = "AES/GCM/NoPadding"
        private const val IV_LEN = 12

        @Volatile private var instance: SecretStore? = null
        fun get(context: Context): SecretStore =
            instance ?: synchronized(this) { instance ?: SecretStore(context).also { instance = it } }
    }
}
