package com.woodruff.tvexplorer.settings

import android.content.Context
import android.os.Looper
import android.os.SystemClock
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import com.woodruff.tvexplorer.util.NetworkWatch
import java.security.KeyStore
import java.security.KeyStoreException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
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
 *
 * Some TVs may not find the key for a moment just after they turn on. That never costs the saved
 * sign-ins, and never holds up the screen: see [key]. It's looked for in the background meanwhile,
 * and what waits on it is told the moment it turns up: see [lookOn].
 */
class SecretStore internal constructor(
    context: Context,
    private val vault: KeyVault = AndroidKeyVault,
    /** Whether the caller is the main thread, which never waits for the key. */
    private val onMainThread: () -> Boolean = { Looper.getMainLooper().isCurrentThread },
    /** Milliseconds that only go forward (elapsedRealtime), for spacing out looks for a missing key. */
    private val clock: () -> Long = SystemClock::elapsedRealtime,
    private val pause: (Long) -> Unit = { Thread.sleep(it) },
    /**
     * Starts the background look for a missing key ([lookOn]) on a thread of its own. Null: no such look (a test's
     * store, unless it asks for one); the app's own store ([get]) has it.
     */
    private val background: ((Runnable) -> Unit)? = null,
    /** Told once, when the key turns up after it was missing this run (the app's own store: [keyBack]). */
    private val onKeyBack: () -> Unit = {},
) {
    private val prefs = context.applicationContext.getSharedPreferences("secrets", Context.MODE_PRIVATE)
    /** What happened to the key, in plain prefs of their own (never read as a secret): see [lostAt]. */
    private val meta = context.applicationContext.getSharedPreferences("secrets_meta", Context.MODE_PRIVATE)

    /**
     * Decrypted values, so each secret goes through the Keystore once per app run. Keystore
     * operations take milliseconds each (tens on some TV chips), and tokens are read on every
     * Drive video request. The values are only ever used in this process anyway; disk stays encrypted.
     */
    private val cache = ConcurrentHashMap<String, String>()
    /** Values saved while the key couldn't be found: this run uses them (they're in [cache]); saved once it's back. */
    private val unsaved = ConcurrentHashMap<String, String>()
    /** Saved values this run read as unset because the key couldn't be found. */
    private val unreadable = ConcurrentHashMap.newKeySet<String>()
    @Volatile private var secretKey: SecretKey? = null
    /** Held only for one quick look in the Keystore at a time, never while waiting between looks. */
    private val lock = Any()
    /** After a wait for a missing key came to nothing: until then ([clock]), nobody looks again; reads just say unset. */
    @Volatile private var lookAgainAt = 0L
    /** When this run last counted the key as missing ([clock]); null while it hasn't. */
    @Volatile private var missCountedAt: Long? = null
    /** Whether a look for the key came to nothing this run, so its turning up is news ([onKeyBack]). */
    @Volatile private var missed = false
    /** Whether [onKeyBack] was told. */
    private val told = AtomicBoolean(false)
    /** Whether the background look for the key ([lookOn]) is under way. */
    private val lookingOn = AtomicBoolean(false)

    fun get(name: String): String? {
        cache[name]?.let { return it }
        val stored = prefs.getString(name, null) ?: return null
        val key = try { key() } catch (e: Exception) {
            // The key isn't there right now (see [key]): unset for now. Remembered, so that a value
            // "made" because this read as unset never replaces the saved one (see [saveUnsaved]).
            unreadable.add(name)
            return null
        }
        return try {
            val value = decrypt(stored, key)
            // A put() meanwhile wins over the value read from disk.
            cache.putIfAbsent(name, value) ?: value
        } catch (e: Exception) {
            // Saved with a key that's gone (data restored onto another device): unset, for good, so
            // not "unreadable for now" either (see [isUnreadableNow]).
            unreadable.remove(name)
            null
        }
    }

    /**
     * Whether [name] has a saved value this run hasn't been able to read yet, because the Keystore
     * hadn't found the key (see [key]): [get] said unset, but it isn't. Whatever would "get or make" a
     * value (the phone pairing key) should ask again later instead of making a new one, and a
     * sign-in that reads as missing for this reason hasn't ended. Stays true after the key turns up
     * until [get] is asked again (which then reads it), so a key found in between can never let a
     * made-up value replace the saved one.
     */
    fun isUnreadableNow(name: String): Boolean = name in unreadable && !cache.containsKey(name) && prefs.contains(name)

    /**
     * Decrypts every stored value into the cache. Run once in the background at app start, so the
     * screens' first reads (Settings, Drive, Mail, cameras) never wait on the Keystore.
     */
    fun warmAll() {
        prefs.all.keys.forEach { runCatching { get(it) } }
    }

    fun put(name: String, value: String?) {
        if (value == null) { remove(name); return }
        if (cache[name] == value && (prefs.contains(name) || unsaved.containsKey(name))) return // unchanged: skip the Keystore round trip
        val key = try { key() } catch (e: Exception) { null }
        if (key != null) { write(name, value, key); return }
        // The key can't be found right now (see [key]). Whatever is saving this (a token refresh, a
        // new pairing key) mustn't fail over it: this run uses the value, and it's saved once the key is back.
        cache[name] = value
        unsaved[name] = value
        // Found by another thread just now: save it at once rather than keep it waiting.
        secretKey?.let { saveUnsaved(it) }
    }

    fun remove(vararg names: String) {
        names.forEach { cache.remove(it); unsaved.remove(it) }
        val edit = prefs.edit()
        names.forEach { edit.remove(it) }
        edit.apply()
    }

    /** When the saved keys and sign-ins were last lost because the TV's Keystore lost their key; null if never. */
    fun lostAt(): Long? = meta.getLong(K_LOST_AT, 0L).takeIf { it > 0 }

    private fun write(name: String, value: String, key: SecretKey) {
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val out = cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        prefs.edit().putString(name, Base64.encodeToString(out, Base64.NO_WRAP)).apply()
        cache[name] = value
        unsaved.remove(name)
    }

    private fun decrypt(stored: String, key: SecretKey): String {
        val bytes = Base64.decode(stored, Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, bytes, 0, IV_LEN))
        return String(cipher.doFinal(bytes, IV_LEN, bytes.size - IV_LEN), Charsets.UTF_8)
    }

    /**
     * The key every secret is encrypted with. When secrets are saved but the key isn't there, the
     * Keystore may just not be ready (some TVs, just after turning on), and making a new key then
     * would leave every saved sign-in unreadable for good. So:
     * - A background caller looks again a few times over about a second ([LOOKS], [LOOK_PAUSE_MS]).
     *   The main thread looks once and never waits: it's drawing the screen.
     * - The lock is held only for each look, never during the pauses, so nobody waits behind a pause.
     * - After a wait that came to nothing, nobody looks again for [LOOK_AGAIN_MS]: reads say unset at
     *   once instead of each waiting again (a dozen secrets read at start-up would otherwise take a
     *   dozen seconds). The background look ([lookOn]) goes on meanwhile, so a key that turns up is
     *   read as soon as it's there.
     * - Only when the Keystore has said the key isn't there on [MISSES_BEFORE_NEW_KEY] occasions at
     *   least [MISS_SPACING_MS] apart (or on that many separate runs) is it taken as gone: the old
     *   secrets are dropped and a new key made. A Keystore that errors, rather than answering, is
     *   never taken as an answer. Finding the key starts the count again.
     */
    private fun key(): SecretKey {
        secretKey?.let { return it }
        val key = try {
            lookForKey()
        } catch (e: Exception) {
            missed = true
            lookOn()
            // Found by another thread meanwhile, before this one counted as having missed it: told all the same.
            if (secretKey != null) tellKeyBack()
            throw e
        }
        tellKeyBack()
        return key
    }

    /** [key]'s looks (see there); throws while the key can't be found. */
    private fun lookForKey(): SecretKey {
        secretKey?.let { return it }
        val mayWait = !onMainThread()
        var answeredMissing = false
        var error: Exception? = null
        for (look in 0 until if (mayWait) LOOKS else 1) {
            if (look > 0) pause(LOOK_PAUSE_MS)
            synchronized(lock) {
                secretKey?.let { return it }
                if (clock() < lookAgainAt) throw KeyStoreException("The secrets' key isn't available yet")
                try {
                    vault.find()?.let { return found(it) }
                    // Nothing saved yet, so nothing to lose: a key of its own (the first secret ever).
                    if (prefs.all.isEmpty()) return found(vault.create())
                    answeredMissing = true
                } catch (e: Exception) {
                    error = e
                }
            }
        }
        synchronized(lock) {
            secretKey?.let { return it }
            // The main thread draws no conclusions: a background read will look properly.
            if (!mayWait) throw KeyStoreException("The secrets' key isn't available yet", error)
            lookAgainAt = clock() + LOOK_AGAIN_MS
            if (!answeredMissing) throw KeyStoreException("The Keystore didn't answer", error)
            val misses = countMiss()
            if (misses < MISSES_BEFORE_NEW_KEY) throw KeyStoreException("The secrets' key isn't there (miss $misses)")
            return startOver()
        }
    }

    /**
     * Looks for a missing key in the background ([background]) until it turns up: every [RELOOK_MS] for the first
     * [RELOOK_FAST_FOR_MS], then every [LOOK_AGAIN_MS] (see [relookPause]). One quick look each time, which waits for
     * nothing and counts nothing as the key being gone (only a reader's wait does, see [key]). So a key the Keystore
     * finds a moment after a wait for it gave up is found within a couple of seconds, not at the next read after
     * [LOOK_AGAIN_MS], and whatever waits on it is told at once ([onKeyBack]), even when nothing reads a secret meanwhile.
     */
    private fun lookOn() {
        val start = background ?: return
        if (secretKey != null || !lookingOn.compareAndSet(false, true)) return
        val since = clock()
        start(Runnable {
            try {
                while (secretKey == null) {
                    pause(relookPause(clock() - since))
                    synchronized(lock) {
                        if (secretKey == null) runCatching { vault.find() }.getOrNull()?.let { found(it) }
                    }
                }
            } finally {
                lookingOn.set(false)
            }
            tellKeyBack()
        })
    }

    /** The key is here: when it was missing earlier this run, [onKeyBack] is told, once. Never with [lock] held. */
    private fun tellKeyBack() {
        if (missed && secretKey != null && told.compareAndSet(false, true)) runCatching { onKeyBack() }.onFailure { Log.w(TAG, "telling the key is back failed", it) }
    }

    /** Counts this absence of the key, at most once every [MISS_SPACING_MS] in a run; the count so far (kept across runs). */
    private fun countMiss(): Int {
        val misses = meta.getInt(K_MISSES, 0)
        val now = clock()
        missCountedAt?.let { if (now - it < MISS_SPACING_MS) return misses }
        missCountedAt = now
        return (misses + 1).also { meta.edit().putInt(K_MISSES, it).apply() }
    }

    /** Called with [lock] held. */
    private fun found(key: SecretKey): SecretKey {
        secretKey = key
        lookAgainAt = 0
        if (meta.contains(K_MISSES)) meta.edit().remove(K_MISSES).apply()
        saveUnsaved(key)
        return key
    }

    /**
     * Saves what was put while the key was missing. Not over a saved value this run read as unset:
     * whatever put the new one may have "made" it because the saved one seemed to be missing (a
     * pairing key, this TV's id). The saved one stays on disk; this run keeps using the value it was given.
     */
    private fun saveUnsaved(key: SecretKey) = synchronized(lock) {
        for ((name, value) in unsaved) {
            if (!prefs.contains(name) || name !in unreadable) {
                runCatching { write(name, value, key) }.onFailure { Log.w(TAG, "couldn't save a secret kept while the key was missing", it) }
            }
            unsaved.remove(name)
        }
    }

    /**
     * The key has been missing too long to be a hiccup (the TV's Keystore was reset): what it
     * encrypted can't be read by any key. Starts over with a new key, keeping what was put meanwhile.
     * Called with [lock] held.
     */
    private fun startOver(): SecretKey {
        Log.w(TAG, "the secrets' key is gone: starting over")
        prefs.edit().clear().apply()
        cache.keys.retainAll(unsaved.keys)
        unreadable.clear()
        meta.edit().remove(K_MISSES).putLong(K_LOST_AT, System.currentTimeMillis()).apply()
        missCountedAt = null
        return found(vault.create())
    }

    /** Where the secrets' key lives: the Android Keystore (a stand-in in tests, which have none). */
    internal interface KeyVault {
        /** The key; null when the Keystore says there's none. Throws when the Keystore can't be asked. */
        fun find(): SecretKey?
        fun create(): SecretKey
    }

    private object AndroidKeyVault : KeyVault {
        override fun find(): SecretKey? {
            val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            return ks.getKey(ALIAS, null) as? SecretKey
        }

        override fun create(): SecretKey {
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
    }

    companion object {
        private const val TAG = "SecretStore"
        private const val K_MISSES = "keyMissingRuns"
        private const val K_LOST_AT = "keyLostAt"
        private const val ALIAS = "woodruff_secrets"
        private const val TRANSFORM = "AES/GCM/NoPadding"
        private const val IV_LEN = 12
        /** Looks for a missing key per wait (a background caller's), and the pause between them. */
        internal const val LOOKS = 4
        internal const val LOOK_PAUSE_MS = 400L
        internal const val LOOK_AGAIN_MS = 30_000L
        internal const val MISS_SPACING_MS = 5 * 60_000L
        internal const val MISSES_BEFORE_NEW_KEY = 3
        /** How often the background look for a missing key looks ([lookOn]), at first, and for how long before it slows down. */
        internal const val RELOOK_MS = 2_000L
        internal const val RELOOK_FAST_FOR_MS = 2 * 60_000L

        /** The background look's pause before its next look, once it has looked for [lookedForMs]. */
        internal fun relookPause(lookedForMs: Long): Long = if (lookedForMs < RELOOK_FAST_FOR_MS) RELOOK_MS else LOOK_AGAIN_MS

        @Volatile private var instance: SecretStore? = null
        fun get(context: Context): SecretStore =
            instance ?: synchronized(this) {
                instance ?: SecretStore(context, background = ::onItsOwnThread, onKeyBack = { onItsOwnThread(::keyBack) }).also { instance = it }
            }

        private fun onItsOwnThread(r: Runnable) = Thread(r, "secrets-key").apply { isDaemon = true }.start()

        private val keyBackListeners = CopyOnWriteArrayList<() -> Unit>()

        /**
         * Runs [action] whenever the app's store finds its key after it was missing (at most once a run, on a
         * background thread): for what was read once a run as "not yet" while the Keystore was still waking
         * ([isUnreadableNow]), such as the linked TVs and the paired phones, to read again at once.
         */
        fun whenKeyBack(action: () -> Unit) { keyBackListeners += action }

        /**
         * The app's store found its key after it was missing: whatever waits to try again (on the network coming back:
         * the libraries, the calendar, the cameras, the doorbell) tries now, and what was read as "not yet" reads again.
         */
        internal fun keyBack() {
            NetworkWatch.tryAgainNow()
            keyBackListeners.forEach { action -> runCatching(action).onFailure { Log.w(TAG, "reading again with the key back failed", it) } }
        }

        /**
         * Tests: [get] hands out [store] (one over a stand-in Keystore) from now on. Returns the one it
         * replaces, to be put back the same way.
         */
        @androidx.annotation.VisibleForTesting
        internal fun swapForTest(store: SecretStore?): SecretStore? = synchronized(this) { instance.also { instance = store } }
    }
}
