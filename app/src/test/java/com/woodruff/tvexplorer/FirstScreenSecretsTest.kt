package com.woodruff.tvexplorer

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.woodruff.tvexplorer.cloud.DriveViewModel
import com.woodruff.tvexplorer.cloud.GoogleAuth
import com.woodruff.tvexplorer.cloud.SignInStep
import com.woodruff.tvexplorer.settings.SecretStore
import com.woodruff.tvexplorer.settings.leaveNoSecrets
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * What the first screen reads from the encrypted store on the main thread, as Woodruff opens, is what
 * the start-up warm-up decrypts first ([TvExplorerApp.FIRST_SCREEN_SECRETS]), so those reads find it
 * ready instead of decrypting in the middle of drawing. Traced, not listed by hand: every secret is
 * saved, then read in a run whose Keystore can't find its key, where each saved secret a read touches
 * is left "unreadable now" (SecretStore.isUnreadableNow).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class FirstScreenSecretsTest {
    private val app: Application = ApplicationProvider.getApplicationContext()

    private class Vault : SecretStore.KeyVault {
        var key: SecretKey? = null
        var missing = false
        override fun find(): SecretKey? = if (missing) null else key
        override fun create(): SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey().also { key = it }
    }

    private val vault = Vault()
    private var swapped = false
    private var before: SecretStore? = null

    @After fun putBack() {
        if (swapped) SecretStore.swapForTest(before)
        leaveNoSecrets(app)
    }

    /** Every secret Google's sign-ins keep, plus a few others the first screen has no business reading. */
    private val everything = listOf(GoogleAuth.K_CLIENT_ID, GoogleAuth.K_CLIENT_SECRET, GoogleAuth.K_NEST_PROJECT) +
        GoogleAuth.Grant.entries.flatMap { listOf(it.refreshKey, it.accessKey, it.expiresKey, it.scopesKey, it.endedKey, it.refusedKey) } +
        listOf("tmdb.key", "tvs.linked", "tvs.name", "pair.token", "mail.accounts")

    /** Saves [names] (with made-up values), then opens a run that can't read them, on the main thread. */
    private fun waking(names: List<String>): SecretStore {
        // Woodruff's own start-up reads its secrets on a thread of its own: done before this run is put in.
        Thread.getAllStackTraces().keys.filter { it.name == "startup-warmup" }.forEach { it.join(10_000) }
        val first = SecretStore(app, vault, onMainThread = { false }, clock = { 0L }, pause = {})
        names.forEach { first.put(it, "value of $it") }
        vault.missing = true
        val run = SecretStore(app, vault, onMainThread = { true }, clock = { 0L }, pause = {})
        before = SecretStore.swapForTest(run)
        swapped = true
        return run
    }

    private fun SecretStore.read() = everything.filter { isUnreadableNow(it) }

    @Test fun signedInDrivesStepIsWarmedFirst() {
        val store = waking(everything)
        assertEquals(SignInStep.CONNECTED, DriveViewModel(app).state.value.step)
        val read = store.read()
        assertTrue("read: $read", read.isNotEmpty() && TvExplorerApp.FIRST_SCREEN_SECRETS.containsAll(read))
    }

    @Test fun anEndedDriveSignInsStepIsWarmedFirstToo() {
        // Google ended the Drive sign-in: its tokens are gone, the keys and the "ended" mark are kept.
        val store = waking(everything - GoogleAuth.Grant.DRIVE.stored.toSet())
        val drive = DriveViewModel(app)
        assertEquals(SignInStep.SIGN_IN, drive.state.value.step)
        assertTrue(drive.state.value.ended)
        val read = store.read()
        assertTrue("read: $read", read.containsAll(listOf(GoogleAuth.K_CLIENT_ID, GoogleAuth.Grant.DRIVE.endedKey)))
        assertTrue("read: $read", TvExplorerApp.FIRST_SCREEN_SECRETS.containsAll(read))
    }
}
